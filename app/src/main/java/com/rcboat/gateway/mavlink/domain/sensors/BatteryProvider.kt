package com.rcboat.gateway.mavlink.domain.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.MavBatteryFunction
import io.dronefleet.mavlink.common.MavBatteryType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery provider state.
 */
data class BatteryProviderState(
    val isEnabled: Boolean = false,
    val batteryLevel: Int = -1, // Percentage (0-100)
    val voltage: Float = -1.0f, // Volts
    val temperature: Int = -1, // Celsius
    val isCharging: Boolean = false
)

/**
 * Battery sensor provider for MAVLink BATTERY_STATUS messages.
 * Uses Android battery manager to provide phone battery data.
 */
@Singleton
class BatteryProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val BATTERY_SYSTEM_ID = 1
        private const val BATTERY_COMPONENT_ID = 191 // MAV_COMP_ID_ONBOARD_COMPUTER
        private const val BATTERY_ID = 0
        private const val CELL_COUNT = 1 // Phone battery treated as single cell
        private const val VOLTAGE_SCALE = 1000.0f // Convert volts to millivolts for MAVLink
    }
    
    private val _state = MutableStateFlow(BatteryProviderState())
    val state: Flow<BatteryProviderState> = _state.asStateFlow()
    
    private var isStarted = false
    private var injectionJob: Job? = null
    private var frameCallback: ((MavRawFrame) -> Unit)? = null
    
    /**
     * Starts battery data collection and injection.
     */
    suspend fun start(rateHz: Float, callback: (MavRawFrame) -> Unit) = withContext(Dispatchers.IO) {
        try {
            frameCallback = callback
            isStarted = true
            
            // Start injection coroutine
            startInjection(rateHz)
            
            Timber.i("Battery provider started at $rateHz Hz")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start battery provider")
        }
    }
    
    /**
     * Stops battery data collection.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            isStarted = false
            injectionJob?.cancel()
            frameCallback = null
            
            updateState()
            Timber.i("Battery provider stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping battery provider")
        }
    }
    
    /**
     * Starts the battery injection coroutine.
     */
    private fun startInjection(rateHz: Float) {
        val intervalMs = (1000.0 / rateHz).toLong()
        
        injectionJob = CoroutineScope(Dispatchers.IO).launch {
            while (isStarted && currentCoroutineContext().isActive) {
                try {
                    updateBatteryInfo()
                    generateBatteryFrame()?.let { frame ->
                        frameCallback?.invoke(frame)
                    }
                    delay(intervalMs)
                } catch (e: Exception) {
                    Timber.e(e, "Error in battery injection loop")
                }
            }
        }
    }
    
    /**
     * Updates battery information from Android system.
     */
    private fun updateBatteryInfo() {
        try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            
            val batteryPercentage = if (level != -1 && scale != -1) {
                (level * 100 / scale)
            } else {
                -1
            }
            
            val batteryVoltage = if (voltage != -1) {
                voltage / 1000.0f // Convert millivolts to volts
            } else {
                -1.0f
            }
            
            val batteryTemp = if (temperature != -1) {
                temperature / 10 // Convert tenths of degree Celsius to degrees
            } else {
                -1
            }
            
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                           status == BatteryManager.BATTERY_STATUS_FULL
            
            _state.value = BatteryProviderState(
                isEnabled = isStarted,
                batteryLevel = batteryPercentage,
                voltage = batteryVoltage,
                temperature = batteryTemp,
                isCharging = isCharging
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to update battery info")
        }
    }
    
    /**
     * Generates a BATTERY_STATUS MAVLink message.
     */
    private fun generateBatteryFrame(): MavRawFrame? {
        val currentState = _state.value
        
        if (currentState.batteryLevel < 0) {
            return null // No valid battery data
        }
        
        try {
            // Create cell voltages array (single cell for phone battery)
            val cellVoltages = IntArray(10) { -1 } // MAVLink supports up to 10 cells, -1 means not used
            if (currentState.voltage > 0) {
                cellVoltages[0] = (currentState.voltage * VOLTAGE_SCALE).toInt()
            }
            
            // Create BATTERY_STATUS message
            val batteryStatus = BatteryStatus.builder()
                .id(BATTERY_ID)
                .batteryFunction(MavBatteryFunction.MAV_BATTERY_FUNCTION_ALL)
                .type(MavBatteryType.MAV_BATTERY_TYPE_LIPO) // Generic battery type
                .temperature(if (currentState.temperature > 0) currentState.temperature else Short.MAX_VALUE)
                .voltages(cellVoltages)
                .currentBattery(-1) // Current draw not available from Android API
                .currentConsumed(-1) // Consumed current not available
                .energyConsumed(-1) // Energy consumed not available
                .batteryRemaining(if (currentState.batteryLevel >= 0) currentState.batteryLevel.toByte() else -1)
                .timeRemaining(0) // Time remaining not calculated
                .chargeState(if (currentState.isCharging) 1 else 0) // Charging state
                .voltagesExt(IntArray(4) { -1 }) // Extended voltages not used
                .mode(0) // Battery mode (not specified)
                .faultBitmask(0) // No faults detected
                .build()
            
            // TODO: Use MavlinkCodec to encode the message
            // This is a placeholder - the actual encoding would use the codec
            val rawBytes = ByteArray(0) // Placeholder
            
            return MavRawFrame(
                rawBytes = rawBytes,
                systemId = BATTERY_SYSTEM_ID,
                componentId = BATTERY_COMPONENT_ID,
                messageId = BatteryStatus.MESSAGE_ID,
                sequence = 0
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate battery frame")
            return null
        }
    }
    
    /**
     * Updates the provider state with disabled status.
     */
    private fun updateState() {
        _state.value = _state.value.copy(isEnabled = isStarted)
    }
}