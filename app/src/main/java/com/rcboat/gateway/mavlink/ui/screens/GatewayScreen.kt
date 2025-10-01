package com.rcboat.gateway.mavlink.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rcboat.gateway.mavlink.data.transport.MqttConnectionState
import com.rcboat.gateway.mavlink.data.transport.UsbConnectionState
import com.rcboat.gateway.mavlink.service.MqttGatewayService
import com.rcboat.gateway.mavlink.ui.viewmodels.GatewayViewModel

/**
 * Main gateway screen with configuration and status display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayScreen(
    viewModel: GatewayViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MAVLink-MQTT Gateway") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Configuration Section
            ConfigurationSection(
                config = uiState.config,
                onMqttBrokerChange = viewModel::updateMqttBrokerAddress,
                onUsernameChange = viewModel::updateMqttUsername,
                onPasswordChange = viewModel::updateMqttPassword,
                onBoatIdChange = viewModel::updateBoatId,
                onSaveClick = viewModel::saveConfiguration
            )
            
            // Validation Errors
            if (uiState.validationErrors.isNotEmpty()) {
                ValidationErrorsCard(
                    errors = uiState.validationErrors,
                    onDismiss = viewModel::clearValidationErrors
                )
            }
            
            // Status Section
            StatusSection(
                usbState = uiState.usbConnectionState,
                mqttState = uiState.mqttConnectionState,
                isServiceRunning = uiState.isServiceRunning
            )
            
            // Service Control Section
            ServiceControlSection(
                context = context,
                isServiceRunning = uiState.isServiceRunning,
                onServiceStateChange = viewModel::setServiceRunning
            )
        }
    }
}

/**
 * Configuration section with input fields.
 */
@Composable
fun ConfigurationSection(
    config: com.rcboat.gateway.mavlink.data.config.AppConfig,
    onMqttBrokerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onBoatIdChange: (String) -> Unit,
    onSaveClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Configuration",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            OutlinedTextField(
                value = config.mqttBrokerAddress,
                onValueChange = onMqttBrokerChange,
                label = { Text("MQTT Broker Address") },
                placeholder = { Text("tcp://broker.hivemq.com:1883") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = config.mqttUsername,
                onValueChange = onUsernameChange,
                label = { Text("MQTT Username (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = config.mqttPassword,
                onValueChange = onPasswordChange,
                label = { Text("MQTT Password (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = config.boatId,
                onValueChange = onBoatIdChange,
                label = { Text("Boat ID") },
                placeholder = { Text("sea_serpent_01") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Text(
                text = "MAVLink Baud Rate: ${config.mavlinkBaud}",
                style = MaterialTheme.typography.bodyMedium
            )
            
            Button(
                onClick = onSaveClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Configuration")
            }
            
            // Display topic names
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "MQTT Topics:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "From Vehicle: ${config.getFromVehicleTopic()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "To Vehicle: ${config.getToVehicleTopic()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Status: ${config.getStatusTopic()}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Validation errors card.
 */
@Composable
fun ValidationErrorsCard(
    errors: List<String>,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Validation Errors",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
            
            errors.forEach { error ->
                Text(
                    text = "• $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Status section showing connection states.
 */
@Composable
fun StatusSection(
    usbState: UsbConnectionState,
    mqttState: MqttConnectionState,
    isServiceRunning: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            // Service Status
            StatusRow(
                label = "Service",
                status = if (isServiceRunning) "Running" else "Stopped",
                color = if (isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            
            // USB Status
            StatusRow(
                label = "USB Serial",
                status = getUsbStatusText(usbState),
                color = getUsbStatusColor(usbState)
            )
            
            // MQTT Status
            StatusRow(
                label = "MQTT Broker",
                status = getMqttStatusText(mqttState),
                color = getMqttStatusColor(mqttState)
            )
        }
    }
}

/**
 * Status row component.
 */
@Composable
fun StatusRow(
    label: String,
    status: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Service control section.
 */
@Composable
fun ServiceControlSection(
    context: Context,
    isServiceRunning: Boolean,
    onServiceStateChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Service Control",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            if (isServiceRunning) {
                Button(
                    onClick = {
                        MqttGatewayService.stop(context)
                        onServiceStateChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Stop Service")
                }
            } else {
                Button(
                    onClick = {
                        MqttGatewayService.start(context)
                        onServiceStateChange(true)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Service")
                }
            }
            
            Text(
                text = if (isServiceRunning) {
                    "Service is running in the background and will maintain connections even when the app is closed."
                } else {
                    "Start the service to begin forwarding MAVLink messages between USB and MQTT."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Helper functions for status display
private fun getUsbStatusText(state: UsbConnectionState): String = when (state) {
    is UsbConnectionState.Connected -> "Connected"
    is UsbConnectionState.Connecting -> "Connecting"
    is UsbConnectionState.WaitingForDevice -> "Waiting for Device"
    is UsbConnectionState.WaitingForPermission -> "Waiting for Permission"
    is UsbConnectionState.Error -> "Error: ${state.message}"
    else -> "Disconnected"
}

@Composable
private fun getUsbStatusColor(state: UsbConnectionState): androidx.compose.ui.graphics.Color = when (state) {
    is UsbConnectionState.Connected -> MaterialTheme.colorScheme.primary
    is UsbConnectionState.Connecting, 
    is UsbConnectionState.WaitingForDevice,
    is UsbConnectionState.WaitingForPermission -> MaterialTheme.colorScheme.tertiary
    is UsbConnectionState.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun getMqttStatusText(state: MqttConnectionState): String = when (state) {
    is MqttConnectionState.Connected -> "Connected"
    is MqttConnectionState.Connecting -> "Connecting"
    is MqttConnectionState.Error -> "Error: ${state.message}"
    else -> "Disconnected"
}

@Composable
private fun getMqttStatusColor(state: MqttConnectionState): androidx.compose.ui.graphics.Color = when (state) {
    is MqttConnectionState.Connected -> MaterialTheme.colorScheme.primary
    is MqttConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
    is MqttConnectionState.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
