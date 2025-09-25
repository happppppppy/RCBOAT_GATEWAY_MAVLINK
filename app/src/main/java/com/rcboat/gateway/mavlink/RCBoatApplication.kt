package com.rcboat.gateway.mavlink

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Main application class for RC Boat MAVLink Gateway.
 * Initializes Hilt dependency injection and Timber logging.
 */
@HiltAndroidApp
class RCBoatApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // In production, you might want a custom tree that sends logs to crash reporting
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    // TODO: Implement crash reporting integration
                }
            })
        }
        
        Timber.i("RC Boat MAVLink Gateway application started")
    }
}