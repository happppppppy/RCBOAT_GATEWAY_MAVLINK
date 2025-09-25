package com.rcboat.gateway.mavlink.domain.routing

import com.rcboat.gateway.mavlink.data.config.AppConfig
import com.rcboat.gateway.mavlink.data.config.TransportType
import com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame
import com.rcboat.gateway.mavlink.data.mavlink.MavlinkEndpoint
import com.rcboat.gateway.mavlink.data.transport.*
import com.rcboat.gateway.mavlink.domain.sensors.BatteryProvider
import com.rcboat.gateway.mavlink.domain.sensors.GpsProvider
import com.rcboat.gateway.mavlink.domain.sensors.ImuProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Router engine state.
 */
sealed class RouterState {
    data object Stopped : RouterState()
    data object Starting : RouterState()
    data object Running : RouterState()
    data class Error(val message: String) : RouterState()
}

/**
 * Connection statistics for monitoring.
 */
data class ConnectionStats(
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    val uplinkFrames: Long = 0,
    val downlinkFrames: Long = 0,
    val injectedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val lastActivity: Long = 0
)

/**
 * Main router engine for bidirectional MAVLink forwarding.
 * Orchestrates data flow between USB, cloud, UDP mirror, and sensor injection.
 */
@Singleton
class RouterEngine @Inject constructor(
    private val usbSerialManager: UsbSerialManager,
    private val tcpTlsLink: TcpTlsLink,
    private val webSocketLink: WebSocketLink,
    private val udpMirror: UdpMirror,
    private val gpsProvider: GpsProvider,
    private val imuProvider: ImuProvider,
    private val batteryProvider: BatteryProvider,
    private val backoffStrategy: BackoffStrategy
) {
    
    companion object {
        private const val FRAME_BUFFER_SIZE = 1000
        private const val STATS_UPDATE_INTERVAL_MS = 1000L
    }
    
    private val _state = MutableStateFlow<RouterState>(RouterState.Stopped)
    val state: StateFlow<RouterState> = _state.asStateFlow()
    
    private val _stats = MutableStateFlow(ConnectionStats())
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()
    
    private var routingJob: Job? = null
    private var statsJob: Job? = null
    private var isRunning = false
    private var currentConfig: AppConfig? = null
    
    // Statistics counters
    private val uplinkBytes = AtomicLong(0)
    private val downlinkBytes = AtomicLong(0)
    private val uplinkFrames = AtomicLong(0)
    private val downlinkFrames = AtomicLong(0)
    private val injectedFrames = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val lastActivity = AtomicLong(0)
    
    // Internal routing channels
    private val usbToCloudChannel = Channel<MavRawFrame>(FRAME_BUFFER_SIZE)
    private val cloudToUsbChannel = Channel<MavRawFrame>(FRAME_BUFFER_SIZE)
    private val injectionChannel = Channel<MavRawFrame>(FRAME_BUFFER_SIZE)
    
    /**
     * Starts the router engine with the given configuration.
     */
    suspend fun start(config: AppConfig) {
        try {
            if (isRunning) {
                Timber.w("Router engine is already running")
                return
            }
            
            currentConfig = config
            _state.value = RouterState.Starting
            
            // Start all components
            startUsb(config)
            startCloudLink(config)
            startUdpMirror(config)
            startSensorProviders(config)
            
            // Start routing coroutines
            startRouting()
            startStatsUpdater()
            
            isRunning = true
            _state.value = RouterState.Running
            
            Timber.i("Router engine started successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start router engine")
            _state.value = RouterState.Error("Failed to start: ${e.message}")
            stop()
        }
    }
    
    /**
     * Stops the router engine and all components.
     */
    suspend fun stop() {
        try {
            isRunning = false
            routingJob?.cancel()
            statsJob?.cancel()
            
            // Stop all components
            usbSerialManager.stop()
            tcpTlsLink.disconnect()
            webSocketLink.disconnect()
            udpMirror.stop()
            gpsProvider.stop()
            imuProvider.stop()
            batteryProvider.stop()
            
            _state.value = RouterState.Stopped
            Timber.i("Router engine stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping router engine")
        }
    }
    
    /**
     * Restarts the router engine with the current or new configuration.
     */
    suspend fun restart(config: AppConfig? = null) {
        stop()
        delay(1000) // Give components time to clean up
        start(config ?: currentConfig ?: AppConfig())
    }
    
    /**
     * Starts USB serial connection.
     */
    private suspend fun startUsb(config: AppConfig) {
        try {
            usbSerialManager.start(config.mavlinkBaud)
            Timber.i("USB serial started at ${config.mavlinkBaud} baud")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start USB")
            // Continue without USB for now
        }
    }
    
    /**
     * Starts cloud link connection.
     */
    private suspend fun startCloudLink(config: AppConfig) {
        try {
            val cloudLink = when (config.transportType) {
                TransportType.TCP_TLS -> tcpTlsLink
                TransportType.WEBSOCKET_TLS -> webSocketLink
            }
            
            // Start cloud connection with backoff
            startCloudWithBackoff(cloudLink, config)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start cloud link")
        }
    }
    
    /**
     * Starts cloud connection with exponential backoff.
     */
    private suspend fun startCloudWithBackoff(cloudLink: MavlinkEndpoint, config: AppConfig) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isRunning) {
                try {
                    when (cloudLink) {
                        is TcpTlsLink -> cloudLink.connect(config.cloudHost, config.cloudPort)
                        is WebSocketLink -> cloudLink.connect(config.cloudHost, config.cloudPort)
                    }
                    
                    if (cloudLink.isConnected()) {
                        Timber.i("Cloud connection established")
                        backoffStrategy.reset()
                        
                        // Wait for connection to fail
                        while (cloudLink.isConnected() && isRunning) {
                            delay(1000)
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Cloud connection failed, retrying...")
                }
                
                if (isRunning) {
                    val backoffDelay = backoffStrategy.getNextDelay()
                    Timber.i("Retrying cloud connection in ${backoffDelay}ms")
                    delay(backoffDelay)
                }
            }
        }
    }
    
    /**
     * Starts UDP mirror if enabled.
     */
    private suspend fun startUdpMirror(config: AppConfig) {
        if (config.secondaryUdpEnabled) {
            try {
                udpMirror.start(config.secondaryUdpHost, config.secondaryUdpPort)
                Timber.i("UDP mirror started to ${config.secondaryUdpHost}:${config.secondaryUdpPort}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start UDP mirror")
            }
        }
    }
    
    /**
     * Starts sensor providers if enabled.
     */
    private suspend fun startSensorProviders(config: AppConfig) {
        val frameCallback: (MavRawFrame) -> Unit = { frame ->
            injectionChannel.trySend(frame)
            injectedFrames.incrementAndGet()
        }
        
        if (config.gpsEnabled) {
            try {
                gpsProvider.start(config.sensorGpsRateHz, frameCallback)
                Timber.i("GPS provider started at ${config.sensorGpsRateHz} Hz")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start GPS provider")
            }
        }
        
        if (config.imuEnabled) {
            try {
                imuProvider.start(config.sensorImuRateHz, frameCallback)
                Timber.i("IMU provider started at ${config.sensorImuRateHz} Hz")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start IMU provider")
            }
        }
        
        if (config.batteryEnabled) {
            try {
                batteryProvider.start(config.sensorBatteryRateHz, frameCallback)
                Timber.i("Battery provider started at ${config.sensorBatteryRateHz} Hz")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start battery provider")
            }
        }
    }
    
    /**
     * Starts the main routing coroutines.
     */
    private fun startRouting() {
        routingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // USB to Cloud routing
            launch { routeUsbToCloud() }
            
            // Cloud to USB routing  
            launch { routeCloudToUsb() }
            
            // Sensor injection routing
            launch { routeSensorInjection() }
        }
    }
    
    /**
     * Routes frames from USB to cloud.
     */
    private suspend fun routeUsbToCloud() {
        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val frame = usbSerialManager.readFrame()
                if (frame != null) {
                    lastActivity.set(System.currentTimeMillis())
                    uplinkFrames.incrementAndGet()
                    uplinkBytes.addAndGet(frame.rawBytes.size.toLong())
                    
                    // Forward to cloud
                    forwardToCloud(frame)
                    
                    // Mirror to UDP
                    udpMirror.mirror(frame)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Timber.e(e, "Error in USB to cloud routing")
                }
            }
        }
    }
    
    /**
     * Routes frames from cloud to USB.
     */
    private suspend fun routeCloudToUsb() {
        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val cloudLink = getCurrentCloudLink()
                val frame = cloudLink?.readFrame()
                
                if (frame != null) {
                    lastActivity.set(System.currentTimeMillis())
                    downlinkFrames.incrementAndGet()
                    downlinkBytes.addAndGet(frame.rawBytes.size.toLong())
                    
                    // Forward to USB
                    usbSerialManager.sendFrame(frame)
                    
                    // Mirror to UDP
                    udpMirror.mirror(frame)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Timber.e(e, "Error in cloud to USB routing")
                }
            }
        }
    }
    
    /**
     * Routes sensor injection frames.
     */
    private suspend fun routeSensorInjection() {
        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val frame = injectionChannel.receive()
                
                // Send to both USB and cloud
                usbSerialManager.sendFrame(frame)
                forwardToCloud(frame)
                udpMirror.mirror(frame)
                
                lastActivity.set(System.currentTimeMillis())
            } catch (e: Exception) {
                if (isRunning) {
                    Timber.e(e, "Error in sensor injection routing")
                }
            }
        }
    }
    
    /**
     * Forwards a frame to the current cloud link.
     */
    private suspend fun forwardToCloud(frame: MavRawFrame) {
        try {
            getCurrentCloudLink()?.sendFrame(frame)
        } catch (e: Exception) {
            Timber.w(e, "Failed to forward frame to cloud")
        }
    }
    
    /**
     * Gets the current active cloud link.
     */
    private fun getCurrentCloudLink(): MavlinkEndpoint? {
        val config = currentConfig ?: return null
        
        return when (config.transportType) {
            TransportType.TCP_TLS -> if (tcpTlsLink.isConnected()) tcpTlsLink else null
            TransportType.WEBSOCKET_TLS -> if (webSocketLink.isConnected()) webSocketLink else null
        }
    }
    
    /**
     * Starts the statistics updater.
     */
    private fun startStatsUpdater() {
        statsJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning && currentCoroutineContext().isActive) {
                try {
                    _stats.value = ConnectionStats(
                        uplinkBytes = uplinkBytes.get(),
                        downlinkBytes = downlinkBytes.get(),
                        uplinkFrames = uplinkFrames.get(),
                        downlinkFrames = downlinkFrames.get(),
                        injectedFrames = injectedFrames.get(),
                        droppedFrames = droppedFrames.get(),
                        lastActivity = lastActivity.get()
                    )
                    
                    delay(STATS_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Timber.e(e, "Error updating stats")
                }
            }
        }
    }
}