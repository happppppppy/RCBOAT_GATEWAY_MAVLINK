package com.rcboat.gateway.mavlink.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rcboat.gateway.mavlink.R
import com.rcboat.gateway.mavlink.data.config.ConfigRepository
import com.rcboat.gateway.mavlink.domain.routing.RouterEngine
import com.rcboat.gateway.mavlink.domain.routing.RouterState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for MAVLink routing operations.
 * Runs independently of the UI to maintain connections when app is backgrounded.
 */
@AndroidEntryPoint
class MavlinkService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "mavlink_service_channel"
        private const val ACTION_STOP_SERVICE = "com.rcboat.gateway.mavlink.STOP_SERVICE"
        private const val ACTION_RESTART_SERVICE = "com.rcboat.gateway.mavlink.RESTART_SERVICE"
        
        /**
         * Starts the MAVLink service.
         */
        fun start(context: Context) {
            val intent = Intent(context, MavlinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stops the MAVLink service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, MavlinkService::class.java)
            context.stopService(intent)
        }
    }
    
    @Inject
    lateinit var configRepository: ConfigRepository
    
    @Inject
    lateinit var routerEngine: RouterEngine
    
    private var serviceScope: CoroutineScope? = null
    private var notificationManager: NotificationManager? = null
    
    override fun onCreate() {
        super.onCreate()
        Timber.i("MAVLink service created")
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART_SERVICE -> {
                restartRouting()
            }
            else -> {
                startForegroundService()
                startRouting()
            }
        }
        
        return START_STICKY // Restart if killed
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Timber.i("MAVLink service destroyed")
        
        serviceScope?.cancel()
        
        // Stop router engine
        serviceScope?.launch {
            try {
                routerEngine.stop()
            } catch (e: Exception) {
                Timber.e(e, "Error stopping router engine")
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Starts the foreground service with notification.
     */
    private fun startForegroundService() {
        val notification = createNotification("Starting...", false)
        startForeground(NOTIFICATION_ID, notification)
        
        Timber.i("MAVLink service started in foreground")
    }
    
    /**
     * Starts the routing engine and monitors its state.
     */
    private fun startRouting() {
        serviceScope?.launch {
            try {
                // Collect configuration changes and restart router
                configRepository.configFlow.collectLatest { config ->
                    Timber.i("Configuration updated, restarting router")
                    routerEngine.restart(config)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in configuration flow")
            }
        }
        
        // Monitor router state and update notification
        serviceScope?.launch {
            routerEngine.state.collectLatest { state ->
                updateNotificationForState(state)
            }
        }
    }
    
    /**
     * Restarts the routing engine.
     */
    private fun restartRouting() {
        serviceScope?.launch {
            try {
                routerEngine.restart()
                Timber.i("Router engine restarted")
            } catch (e: Exception) {
                Timber.e(e, "Error restarting router engine")
            }
        }
    }
    
    /**
     * Creates the notification channel for the service.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.service_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_notification_channel_description)
                setShowBadge(false)
            }
            
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * Creates a notification for the service.
     */
    private fun createNotification(status: String, isConnected: Boolean): Notification {
        val stopIntent = Intent(this, MavlinkService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val restartIntent = Intent(this, MavlinkService::class.java).apply {
            action = ACTION_RESTART_SERVICE
        }
        val restartPendingIntent = PendingIntent.getService(
            this,
            1,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_router) // TODO: Create this icon
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.action_stop_service),
                stopPendingIntent
            )
            .addAction(
                R.drawable.ic_restart,
                getString(R.string.action_restart_service),
                restartPendingIntent
            )
        
        // Set notification color based on connection status
        if (isConnected) {
            builder.color = getColor(android.R.color.holo_green_dark)
        } else {
            builder.color = getColor(android.R.color.holo_orange_dark)
        }
        
        return builder.build()
    }
    
    /**
     * Updates notification based on router state.
     */
    private fun updateNotificationForState(state: RouterState) {
        val (status, isConnected) = when (state) {
            RouterState.Stopped -> getString(R.string.status_disconnected) to false
            RouterState.Starting -> getString(R.string.status_connecting) to false
            RouterState.Running -> getString(R.string.status_connected) to true
            is RouterState.Error -> "${getString(R.string.status_error)}: ${state.message}" to false
        }
        
        val notification = createNotification(status, isConnected)
        notificationManager?.notify(NOTIFICATION_ID, notification)
        
        Timber.d("Notification updated: $status")
    }
}