package com.rcboat.gateway.mavlink.data.config

import kotlinx.serialization.Serializable

/**
 * Transport type enumeration for cloud connections.
 */
@Serializable
enum class TransportType {
    TCP_TLS,
    WEBSOCKET_TLS
}

/**
 * Application configuration data class.
 * All fields have sensible defaults for immediate operation.
 */
@Serializable
data class AppConfig(
    // Connectivity
    val cloudHost: String = "mavlink.example.com",
    val cloudPort: Int = 5760,
    val transportType: TransportType = TransportType.TCP_TLS,
    
    // Secondary UDP mirror
    val secondaryUdpEnabled: Boolean = false,
    val secondaryUdpHost: String = "192.168.1.100",
    val secondaryUdpPort: Int = 14550,
    
    // MAVLink settings
    val mavlinkBaud: Int = 57600,
    
    // Security
    val signingEnabled: Boolean = false,
    val signingKeyHex: String = "", // 32 bytes hex encoded
    
    // Sensor injection rates (Hz)
    val sensorGpsRateHz: Float = 1.0f,
    val sensorImuRateHz: Float = 10.0f,
    val sensorBatteryRateHz: Float = 0.5f,
    
    // Sensor enable flags
    val gpsEnabled: Boolean = true,
    val imuEnabled: Boolean = true,
    val batteryEnabled: Boolean = true,
    
    // Reconnection settings
    val reconnectBaseMs: Long = 1000,
    val reconnectMaxMs: Long = 30000,
    
    // Logging
    val logLevel: String = "INFO" // DEBUG, INFO, WARN, ERROR
) {
    
    /**
     * Validates the configuration and returns a list of validation errors.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (cloudHost.isBlank()) {
            errors.add("Cloud host cannot be empty")
        }
        
        if (cloudPort !in 1..65535) {
            errors.add("Cloud port must be between 1 and 65535")
        }
        
        if (secondaryUdpPort !in 1..65535) {
            errors.add("UDP port must be between 1 and 65535")
        }
        
        if (mavlinkBaud !in listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)) {
            errors.add("Invalid MAVLink baud rate")
        }
        
        if (signingEnabled && signingKeyHex.length != 64) {
            errors.add("Signing key must be exactly 32 bytes (64 hex characters)")
        }
        
        if (sensorGpsRateHz !in 0.1f..5.0f) {
            errors.add("GPS rate must be between 0.1 and 5.0 Hz")
        }
        
        if (sensorImuRateHz !in 1.0f..50.0f) {
            errors.add("IMU rate must be between 1.0 and 50.0 Hz")
        }
        
        if (sensorBatteryRateHz !in 0.1f..2.0f) {
            errors.add("Battery rate must be between 0.1 and 2.0 Hz")
        }
        
        if (reconnectBaseMs < 100 || reconnectBaseMs > 10000) {
            errors.add("Reconnect base delay must be between 100ms and 10s")
        }
        
        if (reconnectMaxMs < 1000 || reconnectMaxMs > 300000) {
            errors.add("Reconnect max delay must be between 1s and 5 minutes")
        }
        
        return errors
    }
    
    /**
     * Returns the signing key as a byte array, or null if invalid.
     */
    fun getSigningKey(): ByteArray? {
        if (!signingEnabled || signingKeyHex.length != 64) return null
        
        return try {
            signingKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            null
        }
    }
}