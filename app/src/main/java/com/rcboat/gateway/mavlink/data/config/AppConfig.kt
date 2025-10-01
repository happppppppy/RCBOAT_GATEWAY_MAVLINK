package com.rcboat.gateway.mavlink.data.config

import kotlinx.serialization.Serializable

/**
 * Application configuration data class for MQTT-based MAVLink gateway.
 * All fields have sensible defaults for immediate operation.
 */
@Serializable
data class AppConfig(
    // MQTT Broker Configuration
    val mqttBrokerAddress: String = "tcp://broker.hivemq.com:1883",
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val boatId: String = "sea_serpent_01",
    
    // MAVLink USB Serial Settings
    val mavlinkBaud: Int = 57600,
    
    // Connection settings
    val autoReconnect: Boolean = true,
    val reconnectDelayMs: Long = 5000,
    
    // Logging
    val logLevel: String = "INFO" // DEBUG, INFO, WARN, ERROR
) {
    
    /**
     * Validates the configuration and returns a list of validation errors.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (mqttBrokerAddress.isBlank()) {
            errors.add("MQTT broker address cannot be empty")
        }
        
        if (!mqttBrokerAddress.startsWith("tcp://") && !mqttBrokerAddress.startsWith("ssl://")) {
            errors.add("MQTT broker address must start with tcp:// or ssl://")
        }
        
        if (boatId.isBlank()) {
            errors.add("Boat ID cannot be empty")
        }
        
        if (!boatId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            errors.add("Boat ID can only contain letters, numbers, underscores, and hyphens")
        }
        
        if (mavlinkBaud !in listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)) {
            errors.add("Invalid MAVLink baud rate")
        }
        
        if (reconnectDelayMs < 1000 || reconnectDelayMs > 60000) {
            errors.add("Reconnect delay must be between 1 and 60 seconds")
        }
        
        return errors
    }
    
    /**
     * Returns the MQTT topic for messages from the vehicle to the cloud.
     */
    fun getFromVehicleTopic(): String = "boats/$boatId/from_vehicle"
    
    /**
     * Returns the MQTT topic for messages to the vehicle from the cloud.
     */
    fun getToVehicleTopic(): String = "boats/$boatId/to_vehicle"
    
    /**
     * Returns the MQTT status topic.
     */
    fun getStatusTopic(): String = "boats/$boatId/status"
}