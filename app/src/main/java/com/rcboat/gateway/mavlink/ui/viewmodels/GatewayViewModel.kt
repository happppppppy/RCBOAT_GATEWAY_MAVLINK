package com.rcboat.gateway.mavlink.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rcboat.gateway.mavlink.data.config.AppConfig
import com.rcboat.gateway.mavlink.data.config.ConfigRepository
import com.rcboat.gateway.mavlink.data.transport.MqttConnectionState
import com.rcboat.gateway.mavlink.data.transport.MqttManager
import com.rcboat.gateway.mavlink.data.transport.UsbConnectionState
import com.rcboat.gateway.mavlink.data.transport.UsbSerialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * UI state for the gateway screen.
 */
data class GatewayUiState(
    val config: AppConfig = AppConfig(),
    val usbConnectionState: UsbConnectionState = UsbConnectionState.Disconnected,
    val mqttConnectionState: MqttConnectionState = MqttConnectionState.Disconnected,
    val isServiceRunning: Boolean = false,
    val validationErrors: List<String> = emptyList()
)

/**
 * ViewModel for the MQTT Gateway screen.
 * Manages configuration and connection state.
 */
@HiltViewModel
class GatewayViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val usbSerialManager: UsbSerialManager,
    private val mqttManager: MqttManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(GatewayUiState())
    val uiState: StateFlow<GatewayUiState> = _uiState.asStateFlow()
    
    init {
        // Collect configuration changes
        viewModelScope.launch {
            configRepository.configFlow.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
        
        // Collect USB connection state
        viewModelScope.launch {
            usbSerialManager.connectionState.collect { state ->
                _uiState.update { it.copy(usbConnectionState = state) }
            }
        }
        
        // Collect MQTT connection state
        viewModelScope.launch {
            mqttManager.connectionState.collect { state ->
                _uiState.update { it.copy(mqttConnectionState = state) }
            }
        }
    }
    
    /**
     * Updates the MQTT broker address.
     */
    fun updateMqttBrokerAddress(address: String) {
        viewModelScope.launch {
            val currentConfig = _uiState.value.config
            val newConfig = currentConfig.copy(mqttBrokerAddress = address)
            updateConfig(newConfig)
        }
    }
    
    /**
     * Updates the MQTT username.
     */
    fun updateMqttUsername(username: String) {
        viewModelScope.launch {
            val currentConfig = _uiState.value.config
            val newConfig = currentConfig.copy(mqttUsername = username)
            updateConfig(newConfig)
        }
    }
    
    /**
     * Updates the MQTT password.
     */
    fun updateMqttPassword(password: String) {
        viewModelScope.launch {
            val currentConfig = _uiState.value.config
            val newConfig = currentConfig.copy(mqttPassword = password)
            updateConfig(newConfig)
        }
    }
    
    /**
     * Updates the boat ID.
     */
    fun updateBoatId(boatId: String) {
        viewModelScope.launch {
            val currentConfig = _uiState.value.config
            val newConfig = currentConfig.copy(boatId = boatId)
            updateConfig(newConfig)
        }
    }
    
    /**
     * Updates the MAVLink baud rate.
     */
    fun updateMavlinkBaud(baud: Int) {
        viewModelScope.launch {
            val currentConfig = _uiState.value.config
            val newConfig = currentConfig.copy(mavlinkBaud = baud)
            updateConfig(newConfig)
        }
    }
    
    /**
     * Saves the current configuration.
     */
    fun saveConfiguration() {
        viewModelScope.launch {
            val config = _uiState.value.config
            
            // Validate configuration
            val errors = config.validate()
            if (errors.isNotEmpty()) {
                _uiState.update { it.copy(validationErrors = errors) }
                Timber.w("Configuration validation failed: $errors")
                return@launch
            }
            
            // Clear validation errors
            _uiState.update { it.copy(validationErrors = emptyList()) }
            
            // Save configuration
            when (val result = configRepository.updateConfig(config)) {
                is com.rcboat.gateway.mavlink.util.Result.Success -> {
                    Timber.i("Configuration saved successfully")
                }
                is com.rcboat.gateway.mavlink.util.Result.Error -> {
                    Timber.e(result.exception, "Failed to save configuration")
                    _uiState.update { 
                        it.copy(validationErrors = listOf("Failed to save: ${result.exception.message}"))
                    }
                }
                is com.rcboat.gateway.mavlink.util.Result.Loading -> {
                    Timber.d("Saving configuration: loading")
                }
            }
        }
    }
    
    /**
     * Clears validation errors.
     */
    fun clearValidationErrors() {
        _uiState.update { it.copy(validationErrors = emptyList()) }
    }
    
    /**
     * Updates the service running state.
     */
    fun setServiceRunning(isRunning: Boolean) {
        _uiState.update { it.copy(isServiceRunning = isRunning) }
    }
    
    /**
     * Helper to update configuration.
     */
    private suspend fun updateConfig(config: AppConfig) {
        _uiState.update { it.copy(config = config) }
    }
}
