package com.rcboat.gateway.mavlink.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rcboat.gateway.mavlink.data.config.AppConfig
import com.rcboat.gateway.mavlink.data.config.ConfigRepository
import com.rcboat.gateway.mavlink.data.config.TransportType
import com.rcboat.gateway.mavlink.data.transport.TcpTlsLink
import com.rcboat.gateway.mavlink.data.transport.WebSocketLink
import com.rcboat.gateway.mavlink.util.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    val config: AppConfig = AppConfig(),
    val originalConfig: AppConfig = AppConfig(),
    val hasUnsavedChanges: Boolean = false,
    val isSaving: Boolean = false,
    val isTestingConnection: Boolean = false,
    val saveResult: String? = null,
    val connectionTestResult: String? = null
)

/**
 * ViewModel for the settings screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val tcpTlsLink: TcpTlsLink,
    private val webSocketLink: WebSocketLink
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            configRepository.configFlow.collect { config ->
                _uiState.value = _uiState.value.copy(
                    config = config,
                    originalConfig = config,
                    hasUnsavedChanges = false
                )
            }
        }
    }
    
    /**
     * Updates cloud host configuration.
     */
    fun updateCloudHost(host: String) {
        updateConfig { it.copy(cloudHost = host) }
    }
    
    /**
     * Updates cloud port configuration.
     */
    fun updateCloudPort(port: Int) {
        updateConfig { it.copy(cloudPort = port) }
    }
    
    /**
     * Updates transport type configuration.
     */
    fun updateTransportType(transportType: TransportType) {
        updateConfig { it.copy(transportType = transportType) }
    }
    
    /**
     * Updates UDP enabled configuration.
     */
    fun updateUdpEnabled(enabled: Boolean) {
        updateConfig { it.copy(secondaryUdpEnabled = enabled) }
    }
    
    /**
     * Updates UDP host configuration.
     */
    fun updateUdpHost(host: String) {
        updateConfig { it.copy(secondaryUdpHost = host) }
    }
    
    /**
     * Updates UDP port configuration.
     */
    fun updateUdpPort(port: Int) {
        updateConfig { it.copy(secondaryUdpPort = port) }
    }
    
    /**
     * Updates signing enabled configuration.
     */
    fun updateSigningEnabled(enabled: Boolean) {
        updateConfig { it.copy(signingEnabled = enabled) }
    }
    
    /**
     * Updates signing key configuration.
     */
    fun updateSigningKey(key: String) {
        updateConfig { it.copy(signingKeyHex = key) }
    }
    
    /**
     * Updates sensor configuration.
     */
    fun updateSensorConfig(config: AppConfig) {
        updateConfig { config }
    }
    
    /**
     * Generic config update helper.
     */
    private fun updateConfig(update: (AppConfig) -> AppConfig) {
        val currentState = _uiState.value
        val newConfig = update(currentState.config)
        val hasChanges = newConfig != currentState.originalConfig
        
        _uiState.value = currentState.copy(
            config = newConfig,
            hasUnsavedChanges = hasChanges,
            saveResult = null,
            connectionTestResult = null
        )
    }
    
    /**
     * Saves the current configuration.
     */
    fun saveConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, saveResult = null)
            
            val config = _uiState.value.config
            val validationErrors = config.validate()
            
            if (validationErrors.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveResult = "Validation errors: ${validationErrors.joinToString(", ")}"
                )
                return@launch
            }
            
            when (val result = configRepository.updateConfig(config)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        originalConfig = config,
                        hasUnsavedChanges = false,
                        saveResult = "Settings saved successfully"
                    )
                    Timber.i("Configuration saved successfully")
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        saveResult = "Failed to save: ${result.exception.message}"
                    )
                    Timber.e(result.exception, "Failed to save configuration")
                }
                Result.Loading -> {
                    // Should not happen with DataStore
                }
            }
        }
    }
    
    /**
     * Tests the cloud connection with current settings.
     */
    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isTestingConnection = true,
                connectionTestResult = null
            )
            
            val config = _uiState.value.config
            
            try {
                val result = when (config.transportType) {
                    TransportType.TCP_TLS -> tcpTlsLink.testConnection(config.cloudHost, config.cloudPort)
                    TransportType.WEBSOCKET_TLS -> webSocketLink.testConnection(config.cloudHost, config.cloudPort)
                }
                
                when (result) {
                    is Result.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isTestingConnection = false,
                            connectionTestResult = "Connection test successful"
                        )
                    }
                    is Result.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isTestingConnection = false,
                            connectionTestResult = "Connection test failed: ${result.exception.message}"
                        )
                    }
                    Result.Loading -> {
                        // Should not happen
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTestingConnection = false,
                    connectionTestResult = "Connection test failed: ${e.message}"
                )
                Timber.e(e, "Connection test failed")
            }
        }
    }
}