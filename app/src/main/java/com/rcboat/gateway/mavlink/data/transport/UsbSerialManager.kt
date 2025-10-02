package com.rcboat.gateway.mavlink.data.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
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

    // Dedicated scope for all IO operations and receiver callbacks
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Channels for frame communication
    private val incomingFrames = Channel<MavRawFrame>(Channel.UNLIMITED)
    private val outgoingFrames = Channel<MavRawFrame>(Channel.UNLIMITED)

    // Buffer for partial frame assembly
    private var readBuffer = ByteArray(0)

    // Remember last requested baud and pending device for permission callbacks
    private var pendingBaudRate: Int = 57600
    private var pendingDevice: UsbDevice? = null

    // Receiver for USB permission and attach/detach events
    private val usbReceiver = object : BroadcastReceiver() {
        private fun Intent.getUsbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                USB_PERMISSION_ACTION -> {
                    val device = intent.getUsbDevice()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        if (granted) {
                            Timber.i("USB permission granted for ${device.deviceName}")
                            ioScope.launch {
                                connectToDevice(device, pendingBaudRate)
                            }
                        } else {
                            Timber.w("USB permission denied for ${device.deviceName}")
                            _connectionState.value = UsbConnectionState.Error("Permission denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getUsbDevice()
                    Timber.i("USB device attached: ${device?.deviceName}")
                    ioScope.launch {
                        start(pendingBaudRate)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getUsbDevice()
                    Timber.i("USB device detached: ${device?.deviceName}")
                    ioScope.launch {
                        stop()
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Register receiver compatibly across Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbReceiver, filter)
        }
    }

    /**
     * Starts the USB connection process.
     * Attempts to find and connect to a compatible USB device.
     */
    suspend fun start(baudRate: Int) = withContext(Dispatchers.IO) {
        try {
            pendingBaudRate = baudRate
            _connectionState.value = UsbConnectionState.WaitingForDevice
            
            val device = findUsbDevice()
            if (device == null) {
                // No device yet; wait for attach broadcast
                Timber.w("No compatible USB device found; waiting for attachment")
                return@withContext
            }
            pendingDevice = device

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
        // Diagnostics: list all connected USB devices
        try {
            val devices = usbManager.deviceList.values
            if (devices.isEmpty()) {
                Timber.i("USB device list is empty")
            } else {
                devices.forEach { d ->
                    Timber.i(
                        "USB device present: name=%s vid=0x%04x pid=0x%04x class=%d",
                        d.deviceName, d.vendorId, d.productId, d.deviceClass
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to enumerate USB devices")
        }
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
            
            // Ensure we have a driver for the specific device
            val driver = currentDriver?.takeIf { it.device.deviceId == device.deviceId }
                ?: UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: throw IllegalStateException("No driver available for device")
            currentDriver = driver

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
        readJob?.cancel()
        writeJob?.cancel()
        readJob = ioScope.launch { readLoop() }
        writeJob = ioScope.launch { writeLoop() }
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
        val incoming = buffer.copyOf(length)
        if (readBuffer.isEmpty()) {
            readBuffer = incoming
        } else {
            val newBuf = ByteArray(readBuffer.size + incoming.size)
            System.arraycopy(readBuffer, 0, newBuf, 0, readBuffer.size)
            System.arraycopy(incoming, 0, newBuf, readBuffer.size, incoming.size)
            readBuffer = newBuf
        }
        
        var index = 0
        val size = readBuffer.size
        while (index < size) {
            val b = readBuffer[index]
            if (b != 0xFE.toByte() && b != 0xFD.toByte()) {
                index++
                continue
            }
            // Try to parse a frame from this index
            val result = mavlinkCodec.parseFrame(readBuffer, index)
            if (result != null) {
                val (frame, consumed) = result
                incomingFrames.trySend(frame)
                index += consumed
                Timber.v("Received MAVLink frame: sys=${'$'}{frame.systemId}, comp=${'$'}{frame.componentId}, msg=${'$'}{frame.messageId}")
                continue
            } else {
                // Likely incomplete frame starting at index; keep remainder for next read
                break
            }
        }

        // Drop processed bytes and retain remainder (including potential partial frame)
        if (index > 0) {
            readBuffer = if (index >= size) ByteArray(0) else readBuffer.copyOfRange(index, size)
        }

        // Prevent buffer from growing too large
        if (readBuffer.size > READ_BUFFER_SIZE * 4) {
            Timber.w("Read buffer overflow (${readBuffer.size} bytes), clearing")
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
                Timber.v("Sent MAVLink frame: sys=${'$'}{frame.systemId}, comp=${'$'}{frame.componentId}, msg=${'$'}{frame.messageId}")
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