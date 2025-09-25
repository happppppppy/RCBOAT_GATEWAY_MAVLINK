package com.rcboat.gateway.mavlink.data.mavlink

import kotlinx.serialization.Serializable

/**
 * Raw MAVLink frame container.
 * Contains the original byte data plus parsed metadata for routing decisions.
 */
@Serializable
data class MavRawFrame(
    val rawBytes: ByteArray,
    val systemId: Int? = null,
    val componentId: Int? = null,
    val messageId: Int? = null,
    val sequence: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MavRawFrame

        if (!rawBytes.contentEquals(other.rawBytes)) return false
        if (systemId != other.systemId) return false
        if (componentId != other.componentId) return false
        if (messageId != other.messageId) return false
        if (sequence != other.sequence) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rawBytes.contentHashCode()
        result = 31 * result + (systemId ?: 0)
        result = 31 * result + (componentId ?: 0)
        result = 31 * result + (messageId ?: 0)
        result = 31 * result + (sequence ?: 0)
        return result
    }
    
    /**
     * Returns a hex string representation of the raw bytes for debugging.
     */
    fun toHexString(): String = rawBytes.joinToString(" ") { "%02x".format(it) }
    
    /**
     * Returns true if this is a heartbeat message.
     */
    fun isHeartbeat(): Boolean = messageId == 0
    
    /**
     * Returns true if this is a high-frequency message that might be dropped under backpressure.
     */
    fun isDroppable(): Boolean = when (messageId) {
        // STATUSTEXT, NAMED_VALUE_FLOAT, DEBUG_VECT, etc.
        253, 251, 250 -> true
        else -> false
    }
}

/**
 * MAVLink endpoint interface for reading and writing frames.
 */
interface MavlinkEndpoint {
    /**
     * Reads the next MAVLink frame. Returns null when the endpoint is closed.
     */
    suspend fun readFrame(): MavRawFrame?
    
    /**
     * Sends a MAVLink frame to this endpoint.
     */
    suspend fun sendFrame(frame: MavRawFrame)
    
    /**
     * Closes the endpoint and releases resources.
     */
    suspend fun close()
    
    /**
     * Returns true if the endpoint is currently connected.
     */
    fun isConnected(): Boolean
}