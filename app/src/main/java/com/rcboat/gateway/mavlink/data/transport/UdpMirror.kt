package com.rcboat.gateway.mavlink.data.transport

import com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame
import com.rcboat.gateway.mavlink.data.mavlink.MavlinkCodec
import com.rcboat.gateway.mavlink.data.mavlink.MavlinkEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UDP mirror connection state.
 */
sealed class UdpMirrorState {
    data object Stopped : UdpMirrorState()
    data object Starting : UdpMirrorState()
    data object Running : UdpMirrorState()
    data class Error(val message: String) : UdpMirrorState()
}

/**
 * UDP mirror for local MAVLink debugging.
 * Sends copies of all MAVLink frames to a local UDP endpoint (typically QGroundControl).
 */
@Singleton
class UdpMirror @Inject constructor(
    private val mavlinkCodec: MavlinkCodec
) : MavlinkEndpoint {
    
    companion object {
        private const val SEND_BUFFER_SIZE = 1024
    }
    
    private val _state = MutableStateFlow<UdpMirrorState>(UdpMirrorState.Stopped)
    val state: StateFlow<UdpMirrorState> = _state.asStateFlow()
    
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0
    private var sendJob: Job? = null
    private var isRunning = false
    
    private val outgoingFrames = Channel<MavRawFrame>(Channel.UNLIMITED)
    
    /**
     * Starts the UDP mirror with the specified target.
     */
    suspend fun start(host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            _state.value = UdpMirrorState.Starting
            
            // Resolve target address
            targetAddress = InetAddress.getByName(host)
            targetPort = port
            
            // Create UDP socket
            socket = DatagramSocket()
            socket?.sendBufferSize = SEND_BUFFER_SIZE
            
            isRunning = true
            _state.value = UdpMirrorState.Running
            
            // Start send loop
            startSendJob()
            
            Timber.i("UDP mirror started, sending to $host:$port")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start UDP mirror")
            _state.value = UdpMirrorState.Error("Failed to start: ${e.message}")
            stop()
        }
    }
    
    /**
     * Stops the UDP mirror.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            isRunning = false
            sendJob?.cancel()
            socket?.close()
            
            _state.value = UdpMirrorState.Stopped
            Timber.i("UDP mirror stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping UDP mirror")
        } finally {
            socket = null
            targetAddress = null
        }
    }
    
    /**
     * Mirrors a MAVLink frame to the UDP target.
     */
    fun mirror(frame: MavRawFrame) {
        if (isRunning) {
            outgoingFrames.trySend(frame)
        }
    }
    
    /**
     * Starts the background send job.
     */
    private fun startSendJob() {
        sendJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            sendLoop()
        }
    }
    
    /**
     * Background send loop for UDP packets.
     */
    private suspend fun sendLoop() {
        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val frame = outgoingFrames.receive()
                val socketInstance = socket ?: break
                val address = targetAddress ?: break
                
                val packet = DatagramPacket(
                    frame.rawBytes,
                    frame.rawBytes.size,
                    address,
                    targetPort
                )
                
                socketInstance.send(packet)
                Timber.v("Mirrored frame via UDP: sys=${frame.systemId}, comp=${frame.componentId}, msg=${frame.messageId}")
                
            } catch (e: Exception) {
                if (isRunning) {
                    Timber.e(e, "UDP mirror send error")
                    _state.value = UdpMirrorState.Error("Send error: ${e.message}")
                }
                break
            }
        }
    }
    
    // MavlinkEndpoint implementation (UDP mirror only sends, doesn't receive)
    override suspend fun readFrame(): MavRawFrame? = null
    
    override suspend fun sendFrame(frame: MavRawFrame) {
        mirror(frame)
    }
    
    override suspend fun close() {
        stop()
    }
    
    override fun isConnected(): Boolean = isRunning
}