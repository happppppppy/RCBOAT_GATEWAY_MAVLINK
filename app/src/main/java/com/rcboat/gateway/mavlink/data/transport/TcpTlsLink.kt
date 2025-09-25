package com.rcboat.gateway.mavlink.data.transport

import com.rcboat.gateway.mavlink.data.mavlink.MavlinkCodec
import com.rcboat.gateway.mavlink.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.*

/**
 * TCP with TLS connection manager for cloud MAVLink communication.
 * Provides secure TCP connection to MAVLink cloud services.
 */
@Singleton
class TcpTlsLink @Inject constructor(
    mavlinkCodec: MavlinkCodec
) : CloudLinkManager(mavlinkCodec) {
    
    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 5000
    }
    
    private var socket: SSLSocket? = null
    private var source: BufferedSource? = null
    private var sink: BufferedSink? = null
    
    override suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = CloudConnectionState.Connecting
            
            // Create SSL context with permissive trust manager for development
            // TODO: In production, use proper certificate validation
            val sslContext = SSLContext.getInstance("TLS")
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            
            sslContext.init(null, arrayOf(trustManager), null)
            
            // Create and configure SSL socket
            val socketFactory = sslContext.socketFactory
            val plainSocket = java.net.Socket()
            plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plainSocket.soTimeout = READ_TIMEOUT_MS
            
            socket = socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket
            socket?.startHandshake()
            
            // Setup streams
            val socketInstance = socket ?: throw IOException("Socket creation failed")
            source = socketInstance.inputStream.source().buffer()
            sink = socketInstance.outputStream.sink().buffer()
            
            isConnected = true
            _connectionState.value = CloudConnectionState.Connected
            
            // Start background read/write loops
            startReadWriteJobs()
            
            Timber.i("TCP TLS connection established to $host:$port")
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect via TCP TLS to $host:$port")
            _connectionState.value = CloudConnectionState.Error("Connection failed: ${e.message}")
            closeConnection()
            Result.Error(e)
        }
    }
    
    override suspend fun testConnection(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            
            sslContext.init(null, arrayOf(trustManager), null)
            
            val socketFactory = sslContext.socketFactory
            val testSocket = socketFactory.createSocket(host, port) as SSLSocket
            testSocket.soTimeout = 5000
            testSocket.startHandshake()
            testSocket.close()
            
            Timber.i("TCP TLS test connection successful to $host:$port")
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.w(e, "TCP TLS test connection failed to $host:$port")
            Result.Error(e)
        }
    }
    
    override suspend fun closeConnection() = withContext(Dispatchers.IO) {
        try {
            sink?.close()
            source?.close()
            socket?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing TCP TLS connection")
        } finally {
            sink = null
            source = null
            socket = null
        }
    }
    
    override suspend fun readBytes(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val sourceInstance = source ?: return@withContext null
            
            if (!sourceInstance.exhausted()) {
                val available = sourceInstance.buffer.size
                if (available > 0) {
                    sourceInstance.readByteArray()
                } else {
                    // Try to read at least one byte (blocking)
                    val byte = sourceInstance.readByte()
                    val buffer = ByteArray(1)
                    buffer[0] = byte
                    
                    // Read any additional available bytes
                    val additionalBytes = if (!sourceInstance.exhausted()) {
                        sourceInstance.readByteArray()
                    } else {
                        ByteArray(0)
                    }
                    
                    buffer + additionalBytes
                }
            } else {
                null
            }
        } catch (e: Exception) {
            if (isConnected) {
                Timber.e(e, "TCP TLS read error")
            }
            null
        }
    }
    
    override suspend fun writeBytes(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val sinkInstance = sink ?: throw IOException("Connection not established")
            sinkInstance.write(data)
            sinkInstance.flush()
        } catch (e: Exception) {
            if (isConnected) {
                Timber.e(e, "TCP TLS write error")
            }
            throw e
        }
    }
}