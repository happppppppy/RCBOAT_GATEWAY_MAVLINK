package com.rcboat.gateway.mavlink.security

import com.rcboat.gateway.mavlink.data.config.AppConfig
import com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MAVLink 2 signing manager.
 * Handles signature generation and validation for secure MAVLink communication.
 */
@Singleton
class SigningManager @Inject constructor() {
    
    companion object {
        private const val SIGNATURE_LENGTH = 13
        private const val LINK_ID = 1.toByte() // Fixed link ID for this application
    }
    
    private val _isSigningEnabled = MutableStateFlow(false)
    val isSigningEnabled: Flow<Boolean> = _isSigningEnabled.asStateFlow()
    
    private var signingKey: ByteArray? = null
    private var timestamp: Long = 0L
    
    /**
     * Updates signing configuration from app config.
     */
    fun updateConfig(config: AppConfig) {
        _isSigningEnabled.value = config.signingEnabled
        signingKey = config.getSigningKey()
        
        if (config.signingEnabled && signingKey == null) {
            Timber.w("MAVLink signing enabled but invalid signing key provided")
            _isSigningEnabled.value = false
        }
        
        Timber.i("MAVLink signing ${if (_isSigningEnabled.value) "enabled" else "disabled"}")
    }
    
    /**
     * Returns true if signing is currently enabled and configured.
     */
    fun isSigningEnabled(): Boolean = _isSigningEnabled.value && signingKey != null
    
    /**
     * Generates a signature for a MAVLink frame.
     * Returns the signature bytes or null if signing is disabled.
     */
    fun generateSignature(frame: MavRawFrame): ByteArray? {
        if (!isSigningEnabled()) return null
        
        val key = signingKey ?: return null
        val rawBytes = frame.rawBytes
        
        // MAVLink v2 signature format:
        // - Link ID (1 byte)
        // - Timestamp (6 bytes, little-endian microseconds)
        // - Hash (6 bytes of SHA-256)
        
        try {
            val currentTimestamp = getCurrentTimestamp()
            val timestampBytes = timestampToBytes(currentTimestamp)
            
            // Create message to hash: key + header + payload + CRC + link_id + timestamp
            val messageDigest = MessageDigest.getInstance("SHA-256")
            messageDigest.update(key)
            
            // Add frame bytes up to (but not including) the signature
            val frameWithoutSignature = if (rawBytes.size > SIGNATURE_LENGTH) {
                rawBytes.copyOfRange(0, rawBytes.size - SIGNATURE_LENGTH)
            } else {
                rawBytes
            }
            messageDigest.update(frameWithoutSignature)
            messageDigest.update(LINK_ID)
            messageDigest.update(timestampBytes)
            
            val hash = messageDigest.digest()
            
            // Construct signature: link_id + timestamp + hash[0:6]
            val signature = ByteArray(SIGNATURE_LENGTH)
            signature[0] = LINK_ID
            System.arraycopy(timestampBytes, 0, signature, 1, 6)
            System.arraycopy(hash, 0, signature, 7, 6)
            
            return signature
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate MAVLink signature")
            return null
        }
    }
    
    /**
     * Validates a MAVLink frame's signature.
     * Returns true if the signature is valid or if signing is disabled.
     */
    fun validateSignature(frame: MavRawFrame): Boolean {
        if (!isSigningEnabled()) return true
        
        val key = signingKey ?: return true
        val rawBytes = frame.rawBytes
        
        if (rawBytes.size < SIGNATURE_LENGTH) return false
        
        try {
            // Extract signature from frame
            val signature = rawBytes.copyOfRange(rawBytes.size - SIGNATURE_LENGTH, rawBytes.size)
            val frameWithoutSignature = rawBytes.copyOfRange(0, rawBytes.size - SIGNATURE_LENGTH)
            
            val linkId = signature[0]
            val timestampBytes = signature.copyOfRange(1, 7)
            val receivedHash = signature.copyOfRange(7, 13)
            
            // Recreate hash
            val messageDigest = MessageDigest.getInstance("SHA-256")
            messageDigest.update(key)
            messageDigest.update(frameWithoutSignature)
            messageDigest.update(linkId)
            messageDigest.update(timestampBytes)
            
            val computedHash = messageDigest.digest()
            val expectedHash = computedHash.copyOfRange(0, 6)
            
            val isValid = receivedHash.contentEquals(expectedHash)
            
            if (!isValid) {
                Timber.w("MAVLink signature validation failed for frame: sys=${frame.systemId}, msg=${frame.messageId}")
            }
            
            return isValid
        } catch (e: Exception) {
            Timber.e(e, "Failed to validate MAVLink signature")
            return false
        }
    }
    
    /**
     * Gets current timestamp in microseconds for signing.
     */
    private fun getCurrentTimestamp(): Long {
        val currentTime = System.currentTimeMillis() * 1000 // Convert to microseconds
        // Ensure timestamp is always increasing
        timestamp = maxOf(timestamp + 1, currentTime)
        return timestamp
    }
    
    /**
     * Converts timestamp to 6-byte little-endian representation.
     */
    private fun timestampToBytes(timestamp: Long): ByteArray {
        val bytes = ByteArray(6)
        bytes[0] = (timestamp and 0xFF).toByte()
        bytes[1] = ((timestamp shr 8) and 0xFF).toByte()
        bytes[2] = ((timestamp shr 16) and 0xFF).toByte()
        bytes[3] = ((timestamp shr 24) and 0xFF).toByte()
        bytes[4] = ((timestamp shr 32) and 0xFF).toByte()
        bytes[5] = ((timestamp shr 40) and 0xFF).toByte()
        return bytes
    }
}