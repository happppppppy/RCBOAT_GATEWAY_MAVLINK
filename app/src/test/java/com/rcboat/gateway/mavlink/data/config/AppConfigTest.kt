package com.rcboat.gateway.mavlink.data.config

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AppConfig validation and utility functions.
 */
class AppConfigTest {

    @Test
    fun `validate returns empty list for valid config`() {
        val config = AppConfig(
            cloudHost = "test.example.com",
            cloudPort = 5760,
            secondaryUdpPort = 14550,
            mavlinkBaud = 57600,
            signingKeyHex = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
        )
        
        val errors = config.validate()
        assertTrue("Expected no validation errors", errors.isEmpty())
    }
    
    @Test
    fun `validate detects empty cloud host`() {
        val config = AppConfig(cloudHost = "")
        
        val errors = config.validate()
        assertTrue("Expected cloud host error", errors.any { it.contains("Cloud host") })
    }
    
    @Test
    fun `validate detects invalid port ranges`() {
        val config = AppConfig(
            cloudPort = 0,
            secondaryUdpPort = 65536
        )
        
        val errors = config.validate()
        assertTrue("Expected cloud port error", errors.any { it.contains("Cloud port") })
        assertTrue("Expected UDP port error", errors.any { it.contains("UDP port") })
    }
    
    @Test
    fun `validate detects invalid baud rate`() {
        val config = AppConfig(mavlinkBaud = 12345)
        
        val errors = config.validate()
        assertTrue("Expected baud rate error", errors.any { it.contains("baud rate") })
    }
    
    @Test
    fun `validate detects invalid signing key length`() {
        val config = AppConfig(
            signingEnabled = true,
            signingKeyHex = "INVALID"
        )
        
        val errors = config.validate()
        assertTrue("Expected signing key error", errors.any { it.contains("Signing key") })
    }
    
    @Test
    fun `getSigningKey returns null when disabled`() {
        val config = AppConfig(signingEnabled = false)
        
        assertNull("Expected null signing key when disabled", config.getSigningKey())
    }
    
    @Test
    fun `getSigningKey returns null for invalid hex`() {
        val config = AppConfig(
            signingEnabled = true,
            signingKeyHex = "INVALID_HEX"
        )
        
        assertNull("Expected null signing key for invalid hex", config.getSigningKey())
    }
    
    @Test
    fun `getSigningKey returns correct bytes for valid hex`() {
        val hexKey = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
        val config = AppConfig(
            signingEnabled = true,
            signingKeyHex = hexKey
        )
        
        val key = config.getSigningKey()
        assertNotNull("Expected valid signing key", key)
        assertEquals("Expected 32 bytes", 32, key!!.size)
        assertEquals("Expected correct first byte", 0x01, key[0])
        assertEquals("Expected correct second byte", 0x23, key[1])
    }
}