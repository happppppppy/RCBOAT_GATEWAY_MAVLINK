package com.rcboat.gateway.mavlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rcboat.gateway.mavlink.domain.routing.ConnectionStats

/**
 * Connection status indicator.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Card showing connection status for a specific component.
 */
@Composable
fun ConnectionStatusCard(
    title: String,
    state: ConnectionState,
    details: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            ConnectionState.CONNECTED -> Color.Green
                            ConnectionState.CONNECTING -> Color.Yellow
                            ConnectionState.ERROR -> Color.Red
                            ConnectionState.DISCONNECTED -> Color.Gray
                        }
                    )
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Card showing connection statistics.
 */
@Composable
fun StatisticsCard(
    stats: ConnectionStats,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Data Transfer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatisticItem(
                    label = "Uplink",
                    value = "${formatBytes(stats.uplinkBytes)} (${stats.uplinkFrames} frames)"
                )
                
                StatisticItem(
                    label = "Downlink",
                    value = "${formatBytes(stats.downlinkBytes)} (${stats.downlinkFrames} frames)"
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatisticItem(
                    label = "Injected",
                    value = "${stats.injectedFrames} frames"
                )
                
                StatisticItem(
                    label = "Dropped",
                    value = "${stats.droppedFrames} frames"
                )
            }
            
            if (stats.lastActivity > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Last activity: ${formatTimeAgo(stats.lastActivity)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatisticItem(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Formats bytes into human readable format.
 */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * Formats time difference into human readable format.
 */
private fun formatTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 1000 -> "just now"
        diff < 60 * 1000 -> "${diff / 1000}s ago"
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m ago"
        else -> "${diff / (60 * 60 * 1000)}h ago"
    }
}