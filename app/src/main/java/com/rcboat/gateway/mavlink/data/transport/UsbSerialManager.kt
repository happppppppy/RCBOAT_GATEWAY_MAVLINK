package com.rcboat.gateway.mavlink.data.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame
import com.rcboat.gateway.mavlink.data.mavlink.MavlinkCodec
import com.rcboat.gateway.mavlink.data.mavlink.MavlinkEndpoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state for USB serial connection.
 */
sealed class UsbConnectionState {
    data object Disconnected : UsbConnectionState()
    data object WaitingForDevice : UsbConnectionState()
    data object WaitingForPermission : UsbConnectionState()
    data object Connecting : UsbConnectionState()
    data object Connected : UsbConnectionState()
    data class Error(val message: String) : UsbConnectionState()
}

/**
 * USB serial manager for MAVLink communication with STM32 devices.
 * Handles device detection, permission requests, and data transfer.
 */
@Singleton
class UsbSerialManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mavlinkCodec: MavlinkCodec
) : MavlinkEndpoint {

    companion object {
        private const val USB_PERMISSION_ACTION = "com.rcboat.gateway.mavlink.USB_PERMISSION"
        private const val READ_BUFFER_SIZE = 1024
        private const val WRITE_TIMEOUT_MS = 1000
        private const val READ_TIMEOUT_MS = 100
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()
    
    private var currentDriver: UsbSerialDriver? = null
    private var currentPort: UsbSerialPort? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var isConnected = false
    
    // Channels for frame communication
    private val incomingFrames = Channel<MavRawFrame>(Channel.UNLIMITED)
    private val outgoingFrames = Channel<MavRawFrame>(Channel.UNLIMITED)
    
    // Buffer for partial frame assembly
    private var readBuffer = ByteArray(0)
    
    /**
     * Starts the USB connection process.
     * Attempts to find and connect to a compatible USB device.
     */
    suspend fun start(baudRate: Int) = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = UsbConnectionState.WaitingForDevice
            
            val device = findUsbDevice()
            if (device == null) {
                _connectionState.value = UsbConnectionState.Error("No compatible USB device found")
                return@withContext
            }
            
            if (!usbManager.hasPermission(device)) {
                requestUsbPermission(device)
                return@withContext
            }
            
            connectToDevice(device, baudRate)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start USB connection")
            _connectionState.value = UsbConnectionState.Error("Failed to start: ${e.message}")
        }
    }
    
    /**
     * Stops the USB connection and releases resources.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            isConnected = false
            readJob?.cancel()
            writeJob?.cancel()
            
            currentPort?.close()
            currentPort = null
            currentDriver = null
            
            _connectionState.value = UsbConnectionState.Disconnected
            Timber.i("USB connection stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping USB connection")
        }
    }
    
    /**
     * Finds a compatible USB device (CDC ACM or specific vendor/product IDs).
     */
    private fun findUsbDevice(): UsbDevice? {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        if (availableDrivers.isEmpty()) {
            Timber.w("No USB serial drivers found")
            return null
        }
        
        // Prefer first available driver
        val driver = availableDrivers.first()
        currentDriver = driver
        
        Timber.i("Found USB device: ${driver.device.deviceName}")
        return driver.device
    }
    
    /**
     * Requests USB permission from the user.
     */
    private fun requestUsbPermission(device: UsbDevice) {
        _connectionState.value = UsbConnectionState.WaitingForPermission
        
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(USB_PERMISSION_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        usbManager.requestPermission(device, permissionIntent)
        Timber.i("Requested USB permission for device: ${device.deviceName}")
    }
    
    /**
     * Connects to the USB device with the specified baud rate.
     */
    private suspend fun connectToDevice(device: UsbDevice, baudRate: Int) = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = UsbConnectionState.Connecting
            
            val driver = currentDriver ?: throw IllegalStateException("No driver available")
            val connection = usbManager.openDevice(driver.device)
                ?: throw IllegalStateException("Cannot open USB device")
            
            val port = driver.ports.firstOrNull()
                ?: throw IllegalStateException("No ports available")
            
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = false
            
            currentPort = port
            isConnected = true
            _connectionState.value = UsbConnectionState.Connected
            
            // Start read/write coroutines
            startReadWriteJobs()
            
            Timber.i("USB connected successfully at $baudRate baud")
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to USB device")
            _connectionState.value = UsbConnectionState.Error("Connection failed: ${e.message}")
            currentPort = null
        }
    }
    
    /**
     * Starts background coroutines for reading and writing data.
     */
    private fun startReadWriteJobs() {
        readJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            readLoop()
        }
        
        writeJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            writeLoop()
        }
    }
    
    /**
     * Continuous read loop for incoming USB data.
     */
    private suspend fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        
        while (isConnected && currentCoroutineContext().isActive) {
            try {
                val port = currentPort ?: break
                val bytesRead = port.read(buffer, READ_TIMEOUT_MS)
                
                if (bytesRead > 0) {
                    processIncomingBytes(buffer, bytesRead)
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Timber.e(e, "USB read error")
                    _connectionState.value = UsbConnectionState.Error("Read error: ${e.message}")
                }
                break
            }
        }
    }
    
    /**
     * Processes incoming bytes and assembles complete MAVLink frames.
     */
    private suspend fun processIncomingBytes(buffer: ByteArray, length: Int) {
        // Append new bytes to read buffer
        readBuffer += buffer.copyOf(length)
        
        var offset = 0
        while (offset < readBuffer.size) {
            val parseResult = mavlinkCodec.parseFrame(readBuffer, offset)
            
            if (parseResult != null) {
                val (frame, consumed) = parseResult
                incomingFrames.trySend(frame)
                offset += consumed
                Timber.v("Received MAVLink frame: sys=${frame.systemId}, comp=${frame.componentId}, msg=${frame.messageId}")
            } else {
                // No complete frame found, try next byte
                offset++
            }
        }
        
        // Remove processed bytes from buffer
        if (offset > 0) {
            readBuffer = if (offset >= readBuffer.size) {
                ByteArray(0)
            } else {
                readBuffer.copyOfRange(offset, readBuffer.size)
            }
        }
        
        // Prevent buffer from growing too large
        if (readBuffer.size > READ_BUFFER_SIZE * 2) {
            Timber.w("Read buffer overflow, clearing")
            readBuffer = ByteArray(0)
        }
    }
    
    /**
     * Continuous write loop for outgoing frames.
     */
    private suspend fun writeLoop() {
        while (isConnected && currentCoroutineContext().isActive) {
            try {
                val frame = outgoingFrames.receive()
                val port = currentPort ?: break
                
                port.write(frame.rawBytes, WRITE_TIMEOUT_MS)
                Timber.v("Sent MAVLink frame: sys=${frame.systemId}, comp=${frame.componentId}, msg=${frame.messageId}")
            } catch (e: Exception) {
                if (isConnected) {
                    Timber.e(e, "USB write error")
                    _connectionState.value = UsbConnectionState.Error("Write error: ${e.message}")
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
        stop()
    }
    
    override fun isConnected(): Boolean = isConnected
}