package com.rcboat.gateway.mavlink.data.mavlink

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MAVLink codec for parsing and handling MAVLink frames.
 * Simplified version for raw byte forwarding in MQTT gateway.
 */
@Singleton
class MavlinkCodec @Inject constructor() {
    
    companion object {
        private const val MAV_STX_V1 = 0xFE.toByte()
        private const val MAV_STX_V2 = 0xFD.toByte()
        
        // MAVLink v1 frame structure: STX(1) + LEN(1) + SEQ(1) + SYSID(1) + COMPID(1) + MSGID(1) + PAYLOAD + CRC(2)
        // MAVLink v2 frame structure: STX(1) + LEN(1) + INCOMP_FLAGS(1) + COMP_FLAGS(1) + SEQ(1) + SYSID(1) + COMPID(1) + MSGID(3) + PAYLOAD + CRC(2) + SIGNATURE(13)
    }
    
    /**
     * Decodes a raw byte array into a MavRawFrame with parsed metadata.
     * Handles both MAVLink v1 and v2 frames.
     */
    fun decode(rawBytes: ByteArray): MavRawFrame? {
        if (rawBytes.isEmpty()) return null
        
        return try {
            val systemId: Int?
            val componentId: Int?
            val messageId: Int?
            val sequence: Int?
            
            when (rawBytes[0]) {
                MAV_STX_V1 -> {
                    if (rawBytes.size < 8) return null
                    sequence = rawBytes[2].toInt() and 0xFF
                    systemId = rawBytes[3].toInt() and 0xFF
                    componentId = rawBytes[4].toInt() and 0xFF
                    messageId = rawBytes[5].toInt() and 0xFF
                }
                MAV_STX_V2 -> {
                    if (rawBytes.size < 12) return null
                    sequence = rawBytes[4].toInt() and 0xFF
                    systemId = rawBytes[5].toInt() and 0xFF
                    componentId = rawBytes[6].toInt() and 0xFF
                    // MAVLink v2 uses 3-byte message ID (little-endian)
                    messageId = (rawBytes[7].toInt() and 0xFF) or
                               ((rawBytes[8].toInt() and 0xFF) shl 8) or
                               ((rawBytes[9].toInt() and 0xFF) shl 16)
                }
                else -> {
                    Timber.w("Invalid MAVLink magic byte: 0x%02x", rawBytes[0])
                    return null
                }
            }
            
            MavRawFrame(
                rawBytes = rawBytes,
                systemId = systemId,
                componentId = componentId,
                messageId = messageId,
                sequence = sequence
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode MAVLink frame: %s", rawBytes.joinToString(" ") { "%02x".format(it) })
            null
        }
    }
    
    /**
     * Attempts to parse a complete MAVLink frame from a byte buffer.
     * Returns the frame and the number of bytes consumed, or null if incomplete.
     */
    fun parseFrame(buffer: ByteArray, offset: Int = 0): Pair<MavRawFrame, Int>? {
        if (buffer.size <= offset) return null
        
        return try {
            when (buffer[offset]) {
                MAV_STX_V1 -> parseV1Frame(buffer, offset)
                MAV_STX_V2 -> parseV2Frame(buffer, offset)
                else -> {
                    // Invalid magic byte, skip this byte and try next
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error parsing MAVLink frame at offset $offset")
            null
        }
    }
    
    private fun parseV1Frame(buffer: ByteArray, offset: Int): Pair<MavRawFrame, Int>? {
        if (buffer.size < offset + 8) return null // Minimum v1 frame size
        
        val payloadLength = buffer[offset + 1].toInt() and 0xFF
        val frameLength = 8 + payloadLength // STX + LEN + SEQ + SYSID + COMPID + MSGID + PAYLOAD + CRC(2)
        
        if (buffer.size < offset + frameLength) return null // Incomplete frame
        
        val frameBytes = buffer.copyOfRange(offset, offset + frameLength)
        val frame = decode(frameBytes)
        
        return if (frame != null) {
            Pair(frame, frameLength)
        } else {
            null
        }
    }
    
    private fun parseV2Frame(buffer: ByteArray, offset: Int): Pair<MavRawFrame, Int>? {
        if (buffer.size < offset + 12) return null // Minimum v2 frame size
        
        val payloadLength = buffer[offset + 1].toInt() and 0xFF
        val compatFlags = buffer[offset + 3].toInt() and 0xFF
        val hasSignature = (compatFlags and 0x01) != 0
        
        val baseLength = 12 + payloadLength // STX + LEN + INCOMP + COMP + SEQ + SYSID + COMPID + MSGID(3) + PAYLOAD + CRC(2)
        val frameLength = if (hasSignature) baseLength + 13 else baseLength
        
        if (buffer.size < offset + frameLength) return null // Incomplete frame
        
        val frameBytes = buffer.copyOfRange(offset, offset + frameLength)
        val frame = decode(frameBytes)
        
        return if (frame != null) {
            Pair(frame, frameLength)
        } else {
            null
        }
    }
}