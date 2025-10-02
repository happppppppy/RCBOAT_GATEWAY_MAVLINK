package com.rcboat.gateway.mavlink.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rcboat.gateway.mavlink.R
import com.rcboat.gateway.mavlink.data.config.ConfigRepository
import com.rcboat.gateway.mavlink.data.transport.MqttManager
import com.rcboat.gateway.mavlink.data.transport.MqttConnectionState
import com.rcboat.gateway.mavlink.data.transport.UsbSerialManager
import com.rcboat.gateway.mavlink.data.transport.UsbConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for MAVLink-to-MQTT gateway operations.
 * Runs independently of the UI to maintain connections when app is backgrounded.
 */
@AndroidEntryPoint
class MqttGatewayService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "mqtt_gateway_channel"
        private const val ACTION_STOP_SERVICE = "com.rcboat.gateway.mavlink.STOP_SERVICE"
        
        /**
         * Starts the MQTT Gateway service.
         */
        fun start(context: Context) {
            val intent = Intent(context, MqttGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stops the MQTT Gateway service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, MqttGatewayService::class.java)
            context.stopService(intent)
        }
    }
    
    @Inject
    lateinit var configRepository: ConfigRepository
    
    @Inject
    lateinit var usbSerialManager: UsbSerialManager
    
    @Inject
    lateinit var mqttManager: MqttManager
    
    private var serviceScope: CoroutineScope? = null
    private var notificationManager: NotificationManager? = null
    
    // Statistics
    private var mavlinkPacketsSent = 0L
    private var mavlinkPacketsReceived = 0L
    private var mqttMessagesSent = 0L
    private var mqttMessagesReceived = 0L
    
    override fun onCreate() {
        super.onCreate()
        Timber.i("MQTT Gateway service created")
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        // Run gateway work off the main thread to avoid UI jank
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundService()
                startGateway()
            }
        }
        
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Timber.i("MQTT Gateway service destroyed")

        // Perform cleanup on a fresh IO scope so it's not skipped due to cancellation
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanupScope.launch {
            try {
                mqttManager.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Error disconnecting MQTT in onDestroy")
            }
            try {
                usbSerialManager.stop()
            } catch (e: Exception) {
                Timber.e(e, "Error stopping USB in onDestroy")
            }
        }

        // Now cancel serviceScope to stop ongoing work
        serviceScope?.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Starts the foreground service with notification.
     */
    private fun startForegroundService() {
        val notification = buildNotification("Starting gateway...", "Initializing connections")
        startForeground(NOTIFICATION_ID, notification)
    }
    
    /**
     * Starts the gateway operations.
     */
    private fun startGateway() {
        serviceScope?.launch {
            try {
                // Collect configuration
                configRepository.configFlow.collectLatest { config ->
                    Timber.i("Configuration updated, restarting gateway")

                    // Stop existing connections
                    mqttManager.disconnect()
                    usbSerialManager.stop()

                    // Reset statistics
                    mavlinkPacketsSent = 0
                    mavlinkPacketsReceived = 0
                    mqttMessagesSent = 0
                    mqttMessagesReceived = 0

                    // Start USB Serial connection
                    usbSerialManager.start(config.mavlinkBaud)

                    // Start MQTT connection with retry/backoff
                    connectMqttWithRetry(config)

                    // Start bidirectional forwarding
                    launch { forwardUsbToMqtt() }
                    launch { forwardMqttToUsb() }
                    launch { monitorConnections() }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in gateway operation")
                updateNotification("Gateway Error", e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun connectMqttWithRetry(config: com.rcboat.gateway.mavlink.data.config.AppConfig) {
        var attempt = 0
        var delayMs = 5_000L
        val maxDelayMs = 60_000L
        while (currentCoroutineContext().isActive) {
            try {
                mqttManager.connect(config)
                // If connect succeeds, break
                if (mqttManager.connectionState.value is MqttConnectionState.Connected) return
            } catch (e: Exception) {
                // connect already logs; fallthrough to retry
            }
            attempt++
            // Update notification to reflect retry schedule
            updateNotification(
                "MQTT: Retry #$attempt",
                "Reconnecting in ${delayMs / 1000}s"
            )
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
        }
    }

    /**
     * Forwards MAVLink packets from USB Serial to MQTT.
     */
    private suspend fun forwardUsbToMqtt() {
        while (currentCoroutineContext().isActive) {
            try {
                val frame = usbSerialManager.readFrame()
                if (frame != null) {
                    mavlinkPacketsReceived++
                    mqttManager.publishFromVehicle(frame.rawBytes)
                    mqttMessagesSent++
                    
                    Timber.v("Forwarded MAVLink packet from USB to MQTT: ${frame.rawBytes.size} bytes")
                } else {
                    delay(10) // Prevent tight loop if no data
                }
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Timber.e(e, "Error forwarding USB to MQTT")
                    delay(1000) // Back off on error
                }
            }
        }
    }
    
    /**
     * Forwards MAVLink packets from MQTT to USB Serial.
     */
    private suspend fun forwardMqttToUsb() {
        while (currentCoroutineContext().isActive) {
            try {
                val payload = mqttManager.receiveToVehicle()
                if (payload != null) {
                    mqttMessagesReceived++
                    
                    // Parse the payload as MAVLink frame
                    val frame = com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame(
                        rawBytes = payload
                    )
                    usbSerialManager.sendFrame(frame)
                    mavlinkPacketsSent++
                    
                    Timber.v("Forwarded MAVLink packet from MQTT to USB: ${payload.size} bytes")
                } else {
                    delay(10) // Prevent tight loop if no data
                }
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Timber.e(e, "Error forwarding MQTT to USB")
                    delay(1000) // Back off on error
                }
            }
        }
    }
    
    /**
     * Monitors connection states and updates notification.
     */
    private suspend fun monitorConnections() {
        while (currentCoroutineContext().isActive) {
            try {
                val usbState = usbSerialManager.connectionState.value
                val mqttState = mqttManager.connectionState.value
                
                val title = buildConnectionTitle(usbState, mqttState)
                val message = buildConnectionMessage(usbState, mqttState)
                
                updateNotification(title, message)
                
                delay(2000) // Update every 2 seconds
            } catch (e: Exception) {
                Timber.e(e, "Error monitoring connections")
            }
        }
    }
    
    /**
     * Builds connection title based on states.
     */
    private fun buildConnectionTitle(
        usbState: UsbConnectionState,
        mqttState: MqttConnectionState
    ): String {
        val usbConnected = usbState is UsbConnectionState.Connected
        val mqttConnected = mqttState is MqttConnectionState.Connected
        
        return when {
            usbConnected && mqttConnected -> "Connected"
            usbConnected || mqttConnected -> "Partially Connected"
            else -> "Disconnected"
        }
    }
    
    /**
     * Builds connection message based on states.
     */
    private fun buildConnectionMessage(
        usbState: UsbConnectionState,
        mqttState: MqttConnectionState
    ): String {
        val usbStatus = when (usbState) {
            is UsbConnectionState.Connected -> "USB: Connected"
            is UsbConnectionState.Connecting -> "USB: Connecting"
            is UsbConnectionState.WaitingForDevice -> "USB: Waiting for device"
            is UsbConnectionState.WaitingForPermission -> "USB: Waiting for permission"
            is UsbConnectionState.Error -> "USB: Error"
            else -> "USB: Disconnected"
        }
        
        val mqttStatus = when (mqttState) {
            is MqttConnectionState.Connected -> "MQTT: Connected"
            is MqttConnectionState.Connecting -> "MQTT: Connecting"
            is MqttConnectionState.Error -> "MQTT: Error"
            else -> "MQTT: Disconnected"
        }
        
        return "$usbStatus | $mqttStatus\n↑$mavlinkPacketsSent ↓$mavlinkPacketsReceived | ↑$mqttMessagesSent ↓$mqttMessagesReceived"
    }
    
    /**
     * Creates the notification channel for the service.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "MQTT Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MAVLink to MQTT Gateway Status"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * Builds a notification with the given title and message.
     */
    private fun buildNotification(title: String, message: String): Notification {
        val stopIntent = Intent(this, MqttGatewayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_router) // Using existing icon
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .build()
    }
    
    /**
     * Updates the notification with new title and message.
     */
    private fun updateNotification(title: String, message: String) {
        val notification = buildNotification(title, message)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}
