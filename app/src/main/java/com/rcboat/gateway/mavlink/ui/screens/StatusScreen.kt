package com.rcboat.gateway.mavlink.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rcboat.gateway.mavlink.service.MavlinkService
import com.rcboat.gateway.mavlink.ui.components.ConnectionStatusCard
import com.rcboat.gateway.mavlink.ui.components.StatisticsCard
import com.rcboat.gateway.mavlink.ui.viewmodels.StatusViewModel

/**
 * Status screen showing connection status and statistics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RC Boat MAVLink Gateway") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                // Service control buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { MavlinkService.start(context) },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isServiceRunning
                    ) {
                        Text("Start Service")
                    }
                    
                    Button(
                        onClick = { MavlinkService.stop(context) },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.isServiceRunning
                    ) {
                        Text("Stop Service")
                    }
                }
            }
            
            item {
                // Connection status cards
                Text(
                    text = "Connection Status",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                ConnectionStatusCard(
                    title = "USB Serial",
                    state = uiState.usbState,
                    details = uiState.usbDetails
                )
            }
            
            item {
                ConnectionStatusCard(
                    title = "Cloud Link",
                    state = uiState.cloudState,
                    details = uiState.cloudDetails
                )
            }
            
            item {
                ConnectionStatusCard(
                    title = "UDP Mirror",
                    state = uiState.udpState,
                    details = uiState.udpDetails
                )
            }
            
            item {
                ConnectionStatusCard(
                    title = "Sensor Injection",
                    state = uiState.sensorState,
                    details = uiState.sensorDetails
                )
            }
            
            item {
                // Statistics
                Text(
                    text = "Statistics",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                StatisticsCard(
                    stats = uiState.connectionStats
                )
            }
        }
    }
}