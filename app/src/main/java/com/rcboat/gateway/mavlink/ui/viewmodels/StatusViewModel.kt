package com.rcboat.gateway.mavlink.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rcboat.gateway.mavlink.domain.routing.ConnectionStats
import com.rcboat.gateway.mavlink.domain.routing.RouterEngine
import com.rcboat.gateway.mavlink.domain.routing.RouterState
import com.rcboat.gateway.mavlink.ui.components.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the status screen.
 */
data class StatusUiState(
    val isServiceRunning: Boolean = false,
    val routerState: RouterState = RouterState.Stopped,
    val usbState: ConnectionState = ConnectionState.DISCONNECTED,
    val usbDetails: String = "Not connected",
    val cloudState: ConnectionState = ConnectionState.DISCONNECTED,
    val cloudDetails: String = "Not connected",
    val udpState: ConnectionState = ConnectionState.DISCONNECTED,
    val udpDetails: String = "Not enabled",
    val sensorState: ConnectionState = ConnectionState.DISCONNECTED,
    val sensorDetails: String = "No sensors active",
    val connectionStats: ConnectionStats = ConnectionStats()
)

/**
 * ViewModel for the status screen.
 */
@HiltViewModel
class StatusViewModel @Inject constructor(
    private val routerEngine: RouterEngine
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            // Collect router state
            routerEngine.state.collect { routerState ->
                updateRouterState(routerState)
            }
        }
        
        viewModelScope.launch {
            // Collect connection stats
            routerEngine.stats.collect { stats ->
                _uiState.value = _uiState.value.copy(connectionStats = stats)
            }
        }
    }
    
    /**
     * Updates UI state based on router state.
     */
    private fun updateRouterState(routerState: RouterState) {
        val isServiceRunning = routerState != RouterState.Stopped
        
        val (usbState, usbDetails) = when (routerState) {
            RouterState.Stopped -> ConnectionState.DISCONNECTED to "Service stopped"
            RouterState.Starting -> ConnectionState.CONNECTING to "Starting..."
            RouterState.Running -> ConnectionState.CONNECTED to "Connected"
            is RouterState.Error -> ConnectionState.ERROR to routerState.message
        }
        
        // TODO: Get actual connection states from individual managers
        val cloudState = when (routerState) {
            RouterState.Stopped -> ConnectionState.DISCONNECTED
            RouterState.Starting -> ConnectionState.CONNECTING
            RouterState.Running -> ConnectionState.CONNECTED
            is RouterState.Error -> ConnectionState.ERROR
        }
        
        val cloudDetails = when (routerState) {
            RouterState.Stopped -> "Service stopped"
            RouterState.Starting -> "Connecting to cloud..."
            RouterState.Running -> "Connected to cloud"
            is RouterState.Error -> "Connection failed"
        }
        
        _uiState.value = _uiState.value.copy(
            isServiceRunning = isServiceRunning,
            routerState = routerState,
            usbState = usbState,
            usbDetails = usbDetails,
            cloudState = cloudState,
            cloudDetails = cloudDetails,
            udpState = if (routerState == RouterState.Running) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
            udpDetails = if (routerState == RouterState.Running) "Mirroring to UDP" else "Not active",
            sensorState = if (routerState == RouterState.Running) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
            sensorDetails = if (routerState == RouterState.Running) "GPS, IMU, Battery active" else "Sensors inactive"
        )
    }
}