package com.rcboat.gateway.mavlink.data.transport

import com.rcboat.gateway.mavlink.data.mavlink.MavlinkCodec
import com.rcboat.gateway.mavlink.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket with TLS connection manager for cloud MAVLink communication.
 * Provides secure WebSocket connection to MAVLink cloud services.
 */
@Singleton
class WebSocketLink @Inject constructor(
    mavlinkCodec: MavlinkCodec
) : CloudLinkManager(mavlinkCodec) {
    
    companion object {
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 30L
        private const val WRITE_TIMEOUT_S = 10L
    }
    
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    
    override suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = CloudConnectionState.Connecting
            
            // Create OkHttp client with timeouts
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
            
            // Create WebSocket request
            val url = "wss://$host:$port/mavlink" // Standard MAVLink WebSocket path
            val request = Request.Builder()
                .url(url)
                .build()
            
            // WebSocket listener for handling events
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.i("WebSocket connection opened to $host:$port")
                    isConnected = true
                    _connectionState.value = CloudConnectionState.Connected
                    startReadWriteJobs()
                }
                
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (isConnected) {
                        processIncomingBytes(bytes.toByteArray())
                    }
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // MAVLink should be binary, but handle text messages gracefully
                    Timber.w("Received unexpected text message: $text")
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.i("WebSocket closing: code=$code, reason=$reason")
                    isConnected = false
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.i("WebSocket closed: code=$code, reason=$reason")
                    isConnected = false
                    _connectionState.value = CloudConnectionState.Disconnected
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "WebSocket failure: ${response?.message}")
                    isConnected = false
                    _connectionState.value = CloudConnectionState.Error("WebSocket error: ${t.message}")
                }
            }
            
            // Start WebSocket connection
            webSocket = okHttpClient!!.newWebSocket(request, listener)
            
            // Wait for connection to establish
            var attempts = 0
            while (!isConnected && attempts < 100) { // 10 seconds max
                kotlinx.coroutines.delay(100)
                attempts++
            }
            
            if (!isConnected) {
                throw IllegalStateException("WebSocket connection timeout")
            }
            
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect via WebSocket to $host:$port")
            _connectionState.value = CloudConnectionState.Error("Connection failed: ${e.message}")
            closeConnection()
            Result.Error(e)
        }
    }
    
    override suspend fun testConnection(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val testClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
            
            val url = "wss://$host:$port/mavlink"
            val request = Request.Builder().url(url).build()
            
            var testResult: Result<Unit>? = null
            
            val testListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.i("WebSocket test connection successful to $host:$port")
                    testResult = Result.Success(Unit)
                    webSocket.close(1000, "Test complete")
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.w(t, "WebSocket test connection failed to $host:$port")
                    testResult = Result.Error(Exception(t.message ?: "WebSocket test failed"))
                }
            }
            
            val testSocket = testClient.newWebSocket(request, testListener)
            
            // Wait for test result
            var attempts = 0
            while (testResult == null && attempts < 50) { // 5 seconds max
                kotlinx.coroutines.delay(100)
                attempts++
            }
            
            testSocket.close(1000, "Test complete")
            testClient.dispatcher.executorService.shutdown()
            
            testResult ?: Result.Error(Exception("WebSocket test timeout"))
        } catch (e: Exception) {
            Timber.w(e, "WebSocket test connection error")
            Result.Error(e)
        }
    }
    
    override suspend fun closeConnection() {
        try {
            webSocket?.close(1000, "Closing connection")
            okHttpClient?.dispatcher?.executorService?.shutdown()
        } catch (e: Exception) {
            Timber.w(e, "Error closing WebSocket connection")
        } finally {
            webSocket = null
            okHttpClient = null
        }
    }
    
    override suspend fun readBytes(): ByteArray? {
        // WebSocket reading is handled by the onMessage callback
        // This method is not used in WebSocket implementation
        return null
    }
    
    override suspend fun writeBytes(data: ByteArray) {
        val ws = webSocket
        if (ws != null && isConnected) {
            val byteString = ByteString.of(*data)
            if (!ws.send(byteString)) {
                throw Exception("Failed to send WebSocket message")
            }
        } else {
            throw Exception("WebSocket not connected")
        }
    }
    
    /**
     * Processes incoming WebSocket binary messages.
     */
    private fun processIncomingBytes(bytes: ByteArray) {
        try {
            var offset = 0
            while (offset < bytes.size) {
                val parseResult = mavlinkCodec.parseFrame(bytes, offset)
                
                if (parseResult != null) {
                    val (frame, consumed) = parseResult
                    incomingFrames.trySend(frame)
                    offset += consumed
                    Timber.v("Received WebSocket frame: sys=${frame.systemId}, comp=${frame.componentId}, msg=${frame.messageId}")
                } else {
                    offset++
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing WebSocket message")
        }
    }
}