package com.rcboat.gateway.mavlink.domain.routing

import com.rcboat.gateway.mavlink.data.config.AppConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff strategy for connection retries.
 * Implements exponential backoff with jitter to prevent thundering herd problems.
 */
@Singleton
class BackoffStrategy @Inject constructor() {
    
    private var currentAttempt = 0
    private var baseDelayMs: Long = 1000
    private var maxDelayMs: Long = 30000
    
    /**
     * Updates backoff configuration from app config.
     */
    fun updateConfig(config: AppConfig) {
        baseDelayMs = config.reconnectBaseMs
        maxDelayMs = config.reconnectMaxMs
    }
    
    /**
     * Gets the next backoff delay in milliseconds.
     * Uses exponential backoff: delay = base * 2^attempt, capped at max.
     * Adds ±10% jitter to prevent synchronized retries.
     */
    fun getNextDelay(): Long {
        val exponentialDelay = baseDelayMs * (1L shl currentAttempt)
        val cappedDelay = min(exponentialDelay, maxDelayMs)
        
        // Add ±10% jitter
        val jitterRange = (cappedDelay * 0.1).toLong()
        val jitter = Random.nextLong(-jitterRange, jitterRange + 1)
        val finalDelay = maxOf(cappedDelay + jitter, baseDelayMs)
        
        currentAttempt++
        
        Timber.d("Backoff delay: ${finalDelay}ms (attempt $currentAttempt)")
        return finalDelay
    }
    
    /**
     * Resets the backoff strategy to initial state.
     * Call this when a connection is successfully established.
     */
    fun reset() {
        currentAttempt = 0
        Timber.d("Backoff strategy reset")
    }
    
    /**
     * Gets the current attempt number.
     */
    fun getCurrentAttempt(): Int = currentAttempt
}