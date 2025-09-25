package com.rcboat.gateway.mavlink.domain.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import io.dronefleet.mavlink.common.HighresImu
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMU provider state.
 */
data class ImuProviderState(
    val isEnabled: Boolean = false,
    val hasAccelerometer: Boolean = false,
    val hasGyroscope: Boolean = false,
    val hasMagnetometer: Boolean = false,
    val accelerometer: FloatArray = floatArrayOf(0f, 0f, 0f),
    val gyroscope: FloatArray = floatArrayOf(0f, 0f, 0f),
    val magnetometer: FloatArray = floatArrayOf(0f, 0f, 0f)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImuProviderState

        if (isEnabled != other.isEnabled) return false
        if (hasAccelerometer != other.hasAccelerometer) return false
        if (hasGyroscope != other.hasGyroscope) return false
        if (hasMagnetometer != other.hasMagnetometer) return false
        if (!accelerometer.contentEquals(other.accelerometer)) return false
        if (!gyroscope.contentEquals(other.gyroscope)) return false
        if (!magnetometer.contentEquals(other.magnetometer)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isEnabled.hashCode()
        result = 31 * result + hasAccelerometer.hashCode()
        result = 31 * result + hasGyroscope.hashCode()
        result = 31 * result + hasMagnetometer.hashCode()
        result = 31 * result + accelerometer.contentHashCode()
        result = 31 * result + gyroscope.contentHashCode()
        result = 31 * result + magnetometer.contentHashCode()
        return result
    }
}

/**
 * IMU sensor provider for MAVLink HIGHRES_IMU messages.
 * Uses Android sensor framework to provide accelerometer, gyroscope, and magnetometer data.
 */
@Singleton
class ImuProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val IMU_SYSTEM_ID = 1
        private const val IMU_COMPONENT_ID = 191 // MAV_COMP_ID_ONBOARD_COMPUTER
        private const val SENSOR_DELAY_US = 20000 // 50 Hz maximum
    }
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val _state = MutableStateFlow(ImuProviderState())
    val state: Flow<ImuProviderState> = _state.asStateFlow()
    
    private var isStarted = false
    private var injectionJob: Job? = null
    private var frameCallback: ((MavRawFrame) -> Unit)? = null
    
    // Sensor data storage
    private var accelData = floatArrayOf(0f, 0f, 0f)
    private var gyroData = floatArrayOf(0f, 0f, 0f)
    private var magData = floatArrayOf(0f, 0f, 0f)
    private var accelValid = false
    private var gyroValid = false
    private var magValid = false
    
    // Available sensors
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, accelData, 0, 3)
                    accelValid = true
                }
                Sensor.TYPE_GYROSCOPE -> {
                    System.arraycopy(event.values, 0, gyroData, 0, 3)
                    gyroValid = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, magData, 0, 3)
                    magValid = true
                }
            }
            updateState()
        }
        
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // Handle accuracy changes if needed
            Timber.v("Sensor accuracy changed: ${sensor.name} = $accuracy")
        }
    }
    
    /**
     * Starts IMU data collection and injection.
     */
    suspend fun start(rateHz: Float, callback: (MavRawFrame) -> Unit) = withContext(Dispatchers.Main) {
        try {
            frameCallback = callback
            
            // Register sensor listeners
            accelerometer?.let { sensor ->
                sensorManager.registerListener(sensorListener, sensor, SENSOR_DELAY_US)
            }
            
            gyroscope?.let { sensor ->
                sensorManager.registerListener(sensorListener, sensor, SENSOR_DELAY_US)
            }
            
            magnetometer?.let { sensor ->
                sensorManager.registerListener(sensorListener, sensor, SENSOR_DELAY_US)
            }
            
            isStarted = true
            updateState()
            
            // Start injection coroutine
            startInjection(rateHz)
            
            Timber.i("IMU provider started at $rateHz Hz")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start IMU provider")
        }
    }
    
    /**
     * Stops IMU data collection.
     */
    suspend fun stop() = withContext(Dispatchers.Main) {
        try {
            isStarted = false
            injectionJob?.cancel()
            sensorManager.unregisterListener(sensorListener)
            frameCallback = null
            
            updateState()
            Timber.i("IMU provider stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping IMU provider")
        }
    }
    
    /**
     * Starts the IMU injection coroutine.
     */
    private fun startInjection(rateHz: Float) {
        val intervalMs = (1000.0 / rateHz).toLong()
        
        injectionJob = CoroutineScope(Dispatchers.IO).launch {
            while (isStarted && currentCoroutineContext().isActive) {
                try {
                    generateImuFrame()?.let { frame ->
                        frameCallback?.invoke(frame)
                    }
                    delay(intervalMs)
                } catch (e: Exception) {
                    Timber.e(e, "Error in IMU injection loop")
                }
            }
        }
    }
    
    /**
     * Generates a HIGHRES_IMU MAVLink message.
     */
    private fun generateImuFrame(): MavRawFrame? {
        try {
            // Create HIGHRES_IMU message
            val highresImu = HighresImu.builder()
                .timeUsec(System.currentTimeMillis() * 1000) // Convert to microseconds
                .xacc(if (accelValid) accelData[0] else 0.0f)
                .yacc(if (accelValid) accelData[1] else 0.0f)
                .zacc(if (accelValid) accelData[2] else 0.0f)
                .xgyro(if (gyroValid) gyroData[0] else 0.0f)
                .ygyro(if (gyroValid) gyroData[1] else 0.0f)
                .zgyro(if (gyroValid) gyroData[2] else 0.0f)
                .xmag(if (magValid) magData[0] else 0.0f)
                .ymag(if (magValid) magData[1] else 0.0f)
                .zmag(if (magValid) magData[2] else 0.0f)
                .absPress(0.0f) // Not available from phone sensors
                .diffPress(0.0f) // Not available from phone sensors
                .pressureAlt(0.0f) // Not available from phone sensors
                .temperature(0.0f) // Could be added from ambient temperature sensor
                .fieldsUpdated(calculateFieldsUpdated())
                .build()
            
            // TODO: Use MavlinkCodec to encode the message
            // This is a placeholder - the actual encoding would use the codec
            val rawBytes = ByteArray(0) // Placeholder
            
            return MavRawFrame(
                rawBytes = rawBytes,
                systemId = IMU_SYSTEM_ID,
                componentId = IMU_COMPONENT_ID,
                messageId = HighresImu.MESSAGE_ID,
                sequence = 0
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate IMU frame")
            return null
        }
    }
    
    /**
     * Calculates the fields_updated bitmask based on available sensor data.
     */
    private fun calculateFieldsUpdated(): Int {
        var fieldsUpdated = 0
        
        // Bits according to MAVLink HIGHRES_IMU message definition
        if (accelValid) fieldsUpdated = fieldsUpdated or 0x0E // bits 1,2,3 for x,y,z accel
        if (gyroValid) fieldsUpdated = fieldsUpdated or 0x70 // bits 4,5,6 for x,y,z gyro
        if (magValid) fieldsUpdated = fieldsUpdated or 0x380 // bits 7,8,9 for x,y,z mag
        
        return fieldsUpdated
    }
    
    /**
     * Updates the provider state.
     */
    private fun updateState() {
        _state.value = ImuProviderState(
            isEnabled = isStarted,
            hasAccelerometer = accelerometer != null,
            hasGyroscope = gyroscope != null,
            hasMagnetometer = magnetometer != null,
            accelerometer = accelData.copyOf(),
            gyroscope = gyroData.copyOf(),
            magnetometer = magData.copyOf()
        )
    }
}