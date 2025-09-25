package com.rcboat.gateway.mavlink.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rcboat.gateway.mavlink.ui.screens.SettingsScreen
import com.rcboat.gateway.mavlink.ui.screens.StatusScreen

/**
 * Main navigation component for the application.
 */
@Composable
fun RCBoatNavigation() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = "status"
    ) {
        composable("status") {
            StatusScreen(
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        
        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}