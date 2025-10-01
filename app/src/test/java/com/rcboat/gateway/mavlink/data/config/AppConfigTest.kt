package com.rcboat.gateway.mavlink.data.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AppConfig validation and utility functions.
 */
class AppConfigTest {

    @Test
    fun `validate returns empty list for valid config`() {
        val config = AppConfig(
            mqttBrokerAddress = "tcp://broker.hivemq.com:1883",
            boatId = "sea_serpent_01",
            mavlinkBaud = 57600,
            reconnectDelayMs = 5000
        )
        
        val errors = config.validate()
        assertTrue("Expected no validation errors", errors.isEmpty())
    }
    
    @Test
    fun `validate detects empty MQTT broker address`() {
        val config = AppConfig(mqttBrokerAddress = "")
        
        val errors = config.validate()
        assertTrue("Expected MQTT broker error", errors.any { it.contains("MQTT broker") })
    }
    
    @Test
    fun `validate detects invalid MQTT broker protocol`() {
        val config = AppConfig(mqttBrokerAddress = "http://broker.example.com:1883")
        
        val errors = config.validate()
        assertTrue("Expected protocol error", errors.any { it.contains("tcp://") || it.contains("ssl://") })
    }
    
    @Test
    fun `validate detects empty boat ID`() {
        val config = AppConfig(boatId = "")
        
        val errors = config.validate()
        assertTrue("Expected boat ID error", errors.any { it.contains("Boat ID") })
    }
    
    @Test
    fun `validate detects invalid boat ID characters`() {
        val config = AppConfig(boatId = "invalid@boat#id")
        
        val errors = config.validate()
        assertTrue("Expected boat ID character error", errors.any { it.contains("Boat ID") })
    }
    
    @Test
    fun `validate accepts valid boat ID with underscores and hyphens`() {
        val config = AppConfig(boatId = "sea_serpent-01")
        
        val errors = config.validate()
        assertFalse("Expected no boat ID error", errors.any { it.contains("Boat ID") })
    }
    
    @Test
    fun `validate detects invalid baud rate`() {
        val config = AppConfig(mavlinkBaud = 12345)
        
        val errors = config.validate()
        assertTrue("Expected baud rate error", errors.any { it.contains("baud rate") })
    }
    
    @Test
    fun `validate accepts valid baud rates`() {
        val validBaudRates = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
        
        validBaudRates.forEach { baudRate ->
            val config = AppConfig(mavlinkBaud = baudRate)
            val errors = config.validate()
            assertFalse("Expected no error for baud rate $baudRate", 
                       errors.any { it.contains("baud rate") })
        }
    }
    
    @Test
    fun `validate detects invalid reconnect delay`() {
        val config = AppConfig(reconnectDelayMs = 500) // Too short
        
        val errors = config.validate()
        assertTrue("Expected reconnect delay error", errors.any { it.contains("Reconnect delay") })
    }
    
    @Test
    fun `validate detects excessive reconnect delay`() {
        val config = AppConfig(reconnectDelayMs = 65000) // Too long
        
        val errors = config.validate()
        assertTrue("Expected reconnect delay error", errors.any { it.contains("Reconnect delay") })
    }
    
    @Test
    fun `getFromVehicleTopic returns correct topic`() {
        val config = AppConfig(boatId = "test_boat_123")
        
        val topic = config.getFromVehicleTopic()
        assertEquals("boats/test_boat_123/from_vehicle", topic)
    }
    
    @Test
    fun `getToVehicleTopic returns correct topic`() {
        val config = AppConfig(boatId = "test_boat_123")
        
        val topic = config.getToVehicleTopic()
        assertEquals("boats/test_boat_123/to_vehicle", topic)
    }
    
    @Test
    fun `getStatusTopic returns correct topic`() {
        val config = AppConfig(boatId = "test_boat_123")
        
        val topic = config.getStatusTopic()
        assertEquals("boats/test_boat_123/status", topic)
    }
    
    @Test
    fun `ssl protocol is accepted`() {
        val config = AppConfig(mqttBrokerAddress = "ssl://secure-broker.example.com:8883")
        
        val errors = config.validate()
        assertFalse("Expected no protocol error for ssl://", 
                   errors.any { it.contains("tcp://") || it.contains("ssl://") })
    }
    
    @Test
    fun `tcp protocol is accepted`() {
        val config = AppConfig(mqttBrokerAddress = "tcp://broker.example.com:1883")
        
        val errors = config.validate()
        assertFalse("Expected no protocol error for tcp://", 
                   errors.any { it.contains("tcp://") || it.contains("ssl://") })
    }
}