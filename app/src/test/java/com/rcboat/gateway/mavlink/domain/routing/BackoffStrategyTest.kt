package com.rcboat.gateway.mavlink.domain.routing

import com.rcboat.gateway.mavlink.data.config.AppConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BackoffStrategy.
 */
class BackoffStrategyTest {
    
    private lateinit var backoffStrategy: BackoffStrategy
    
    @Before
    fun setup() {
        backoffStrategy = BackoffStrategy()
    }
    
    @Test
    fun `initial attempt returns base delay`() {
        val config = AppConfig(reconnectBaseMs = 1000, reconnectMaxMs = 30000)
        backoffStrategy.updateConfig(config)
        
        val delay = backoffStrategy.getNextDelay()
        
        // Should be base delay with some jitter (900-1100ms)
        assertTrue("Expected delay around base value", delay in 900..1100)
        assertEquals("Expected attempt count 1", 1, backoffStrategy.getCurrentAttempt())
    }
    
    @Test
    fun `exponential backoff increases delay`() {
        val config = AppConfig(reconnectBaseMs = 1000, reconnectMaxMs = 30000)
        backoffStrategy.updateConfig(config)
        
        val delay1 = backoffStrategy.getNextDelay()
        val delay2 = backoffStrategy.getNextDelay()
        val delay3 = backoffStrategy.getNextDelay()
        
        // Each delay should be roughly double the previous (with jitter)
        assertTrue("Expected delay2 > delay1", delay2 > delay1)
        assertTrue("Expected delay3 > delay2", delay3 > delay2)
        assertEquals("Expected attempt count 3", 3, backoffStrategy.getCurrentAttempt())
    }
    
    @Test
    fun `delay caps at maximum value`() {
        val config = AppConfig(reconnectBaseMs = 1000, reconnectMaxMs = 5000)
        backoffStrategy.updateConfig(config)
        
        // Generate many delays to exceed maximum
        repeat(10) {
            backoffStrategy.getNextDelay()
        }
        
        val finalDelay = backoffStrategy.getNextDelay()
        
        // Should be capped at max value with jitter (4500-5500ms)
        assertTrue("Expected delay capped at max", finalDelay <= 5500)
    }
    
    @Test
    fun `reset clears attempt count`() {
        val config = AppConfig(reconnectBaseMs = 1000, reconnectMaxMs = 30000)
        backoffStrategy.updateConfig(config)
        
        // Generate a few attempts
        backoffStrategy.getNextDelay()
        backoffStrategy.getNextDelay()
        assertEquals("Expected attempt count 2", 2, backoffStrategy.getCurrentAttempt())
        
        // Reset and check
        backoffStrategy.reset()
        assertEquals("Expected attempt count 0 after reset", 0, backoffStrategy.getCurrentAttempt())
        
        // Next delay should be back to base level
        val delay = backoffStrategy.getNextDelay()
        assertTrue("Expected delay back to base level", delay in 900..1100)
    }
    
    @Test
    fun `jitter prevents exact values`() {
        val config = AppConfig(reconnectBaseMs = 1000, reconnectMaxMs = 30000)
        backoffStrategy.updateConfig(config)
        
        val delays = mutableSetOf<Long>()
        
        // Generate multiple delays and check for variation
        repeat(10) {
            backoffStrategy.reset()
            delays.add(backoffStrategy.getNextDelay())
        }
        
        // Should have some variation due to jitter
        assertTrue("Expected jitter to create variation", delays.size > 1)
    }
}