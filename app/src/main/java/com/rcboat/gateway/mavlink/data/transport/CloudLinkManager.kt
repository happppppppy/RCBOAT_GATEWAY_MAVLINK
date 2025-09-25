package com.rcboat.gateway.mavlink.data.transport

import com.rcboat.gateway.mavlink.data.config.TransportType
import com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame
import com.rcboat.gateway.mavlink.data.mavlink.MavlinkCodec
import com.rcboat.gateway.mavlink.data.mavlink.MavlinkEndpoint
import com.rcboat.gateway.mavlink.util.Result
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Connection state for cloud links.
 */
sealed class CloudConnectionState {
    data object Disconnected : CloudConnectionState()
    data object Connecting : CloudConnectionState()
    data object Connected : CloudConnectionState()
    data class Error(val message: String) : CloudConnectionState()
}

/**
 * Abstract base class for cloud link managers.
 */
abstract class CloudLinkManager(
    protected val mavlinkCodec: MavlinkCodec
) : MavlinkEndpoint {
    
    protected val _connectionState = MutableStateFlow<CloudConnectionState>(CloudConnectionState.Disconnected)
    val connectionState: StateFlow<CloudConnectionState> = _connectionState.asStateFlow()
    
    protected val incomingFrames = Channel<MavRawFrame>(Channel.UNLIMITED)
    protected val outgoingFrames = Channel<MavRawFrame>(Channel.UNLIMITED)
    
    protected var isConnected = false
    protected var readJob: Job? = null
    protected var writeJob: Job? = null
    
    /**
     * Connects to the cloud endpoint.
     */
    abstract suspend fun connect(host: String, port: Int): Result<Unit>
    
    /**
     * Tests connectivity to the cloud endpoint without maintaining connection.
     */
    abstract suspend fun testConnection(host: String, port: Int): Result<Unit>
    
    /**
     * Disconnects from the cloud endpoint.
     */
    suspend fun disconnect() {
        try {
            isConnected = false
            readJob?.cancel()
            writeJob?.cancel()
            
            closeConnection()
            _connectionState.value = CloudConnectionState.Disconnected
            
            Timber.i("Cloud connection disconnected")
        } catch (e: Exception) {
            Timber.e(e, "Error during disconnect")
        }
    }
    
    /**
     * Implementation-specific connection close logic.
     */
    protected abstract suspend fun closeConnection()
    
    /**
     * Implementation-specific read operation.
     */
    protected abstract suspend fun readBytes(): ByteArray?
    
    /**
     * Implementation-specific write operation.
     */
    protected abstract suspend fun writeBytes(data: ByteArray)
    
    /**
     * Starts background coroutines for reading and writing.
     */
    protected fun startReadWriteJobs() {
        readJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            readLoop()
        }
        
        writeJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            writeLoop()
        }
    }
    
    /**
     * Background read loop.
     */
    private suspend fun readLoop() {
        var readBuffer = ByteArray(0)
        
        while (isConnected && currentCoroutineContext().isActive) {
            try {
                val bytes = readBytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    readBuffer += bytes
                    
                    // Process complete frames
                    var offset = 0
                    while (offset < readBuffer.size) {
                        val parseResult = mavlinkCodec.parseFrame(readBuffer, offset)
                        
                        if (parseResult != null) {
                            val (frame, consumed) = parseResult
                            incomingFrames.trySend(frame)
                            offset += consumed
                            Timber.v("Received cloud frame: sys=${frame.systemId}, comp=${frame.componentId}, msg=${frame.messageId}")
                        } else {
                            offset++
                        }
                    }
                    
                    // Update buffer
                    readBuffer = if (offset >= readBuffer.size) {
                        ByteArray(0)
                    } else {
                        readBuffer.copyOfRange(offset, readBuffer.size)
                    }
                    
                    // Prevent buffer overflow
                    if (readBuffer.size > 2048) {
                        Timber.w("Cloud read buffer overflow, clearing")
                        readBuffer = ByteArray(0)
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Timber.e(e, "Cloud read error")
                    _connectionState.value = CloudConnectionState.Error("Read error: ${e.message}")
                }
                break
            }
        }
    }
    
    /**
     * Background write loop.
     */
    private suspend fun writeLoop() {
        while (isConnected && currentCoroutineContext().isActive) {
            try {
                val frame = outgoingFrames.receive()
                writeBytes(frame.rawBytes)
                Timber.v("Sent cloud frame: sys=${frame.systemId}, comp=${frame.componentId}, msg=${frame.messageId}")
            } catch (e: Exception) {
                if (isConnected) {
                    Timber.e(e, "Cloud write error")
                    _connectionState.value = CloudConnectionState.Error("Write error: ${e.message}")
                }
                break
            }
        }
    }
    
    // MavlinkEndpoint implementation
    override suspend fun readFrame(): MavRawFrame? {
        return if (isConnected) {
            incomingFrames.receiveCatching().getOrNull()
        } else {
            null
        }
    }
    
    override suspend fun sendFrame(frame: MavRawFrame) {
        if (isConnected) {
            outgoingFrames.trySend(frame)
        }
    }
    
    override suspend fun close() {
        disconnect()
    }
    
    override fun isConnected(): Boolean = isConnected
}