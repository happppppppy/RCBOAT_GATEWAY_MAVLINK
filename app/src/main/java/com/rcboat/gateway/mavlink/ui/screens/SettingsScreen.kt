package com.rcboat.gateway.mavlink.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rcboat.gateway.mavlink.data.config.TransportType
import com.rcboat.gateway.mavlink.ui.viewmodels.SettingsViewModel

/**
 * Settings screen for configuring the MAVLink gateway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(
                    title = "Connectivity",
                    content = {
                        OutlinedTextField(
                            value = uiState.config.cloudHost,
                            onValueChange = { viewModel.updateCloudHost(it) },
                            label = { Text("Cloud Host") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = uiState.config.cloudPort.toString(),
                            onValueChange = { 
                                it.toIntOrNull()?.let { port -> viewModel.updateCloudPort(port) }
                            },
                            label = { Text("Cloud Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TransportTypeSelector(
                            selectedType = uiState.config.transportType,
                            onTypeSelected = { viewModel.updateTransportType(it) }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = { viewModel.testConnection() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isTestingConnection
                        ) {
                            if (uiState.isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing...")
                            } else {
                                Text("Test Connection")
                            }
                        }
                        
                        uiState.connectionTestResult?.let { result ->
                            Text(
                                text = result,
                                color = if (result.contains("Success")) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                )
            }
            
            item {
                SettingsSection(
                    title = "UDP Mirror",
                    content = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = uiState.config.secondaryUdpEnabled,
                                onCheckedChange = { viewModel.updateUdpEnabled(it) }
                            )
                            Text("Enable UDP Mirror")
                        }
                        
                        if (uiState.config.secondaryUdpEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = uiState.config.secondaryUdpHost,
                                onValueChange = { viewModel.updateUdpHost(it) },
                                label = { Text("UDP Host") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = uiState.config.secondaryUdpPort.toString(),
                                onValueChange = { 
                                    it.toIntOrNull()?.let { port -> viewModel.updateUdpPort(port) }
                                },
                                label = { Text("UDP Port") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                )
            }
            
            item {
                SettingsSection(
                    title = "Sensors",
                    content = {
                        SensorSettings(
                            config = uiState.config,
                            onConfigChange = viewModel::updateSensorConfig
                        )
                    }
                )
            }
            
            item {
                SettingsSection(
                    title = "Security",
                    content = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = uiState.config.signingEnabled,
                                onCheckedChange = { viewModel.updateSigningEnabled(it) }
                            )
                            Text("Enable MAVLink Signing")
                        }
                        
                        if (uiState.config.signingEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = uiState.config.signingKeyHex,
                                onValueChange = { viewModel.updateSigningKey(it) },
                                label = { Text("Signing Key (32 bytes hex)") },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("0123456789ABCDEF...") }
                            )
                        }
                    }
                )
            }
            
            item {
                // Save button
                Button(
                    onClick = { viewModel.saveConfig() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.hasUnsavedChanges && !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Saving...")
                    } else {
                        Text("Save Settings")
                    }
                }
                
                uiState.saveResult?.let { result ->
                    Text(
                        text = result,
                        color = if (result.contains("Success")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            content()
        }
    }
}

@Composable
private fun TransportTypeSelector(
    selectedType: TransportType,
    onTypeSelected: (TransportType) -> Unit
) {
    Column {
        Text("Transport Type", style = MaterialTheme.typography.labelMedium)
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TransportType.values().forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedType == type,
                        onClick = { onTypeSelected(type) }
                    )
                    Text(type.name.replace("_", " "))
                }
            }
        }
    }
}

@Composable
private fun SensorSettings(
    config: com.rcboat.gateway.mavlink.data.config.AppConfig,
    onConfigChange: (com.rcboat.gateway.mavlink.data.config.AppConfig) -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = config.gpsEnabled,
                onCheckedChange = { onConfigChange(config.copy(gpsEnabled = it)) }
            )
            Text("GPS Sensor")
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = config.imuEnabled,
                onCheckedChange = { onConfigChange(config.copy(imuEnabled = it)) }
            )
            Text("IMU Sensor")
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = config.batteryEnabled,
                onCheckedChange = { onConfigChange(config.copy(batteryEnabled = it)) }
            )
            Text("Battery Sensor")
        }
    }
}