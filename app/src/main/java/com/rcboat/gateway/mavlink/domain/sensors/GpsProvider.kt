package com.rcboat.gateway.mavlink.domain.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import com.rcboat.gateway.mavlink.data.mavlink.MavRawFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import io.dronefleet.mavlink.common.GpsInput
import io.dronefleet.mavlink.common.MavGpsInputIgnoreFlags
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPS provider state.
 */
data class GpsProviderState(
    val isEnabled: Boolean = false,
    val hasPermission: Boolean = false,
    val hasLocation: Boolean = false,
    val lastLocation: Location? = null,
    val satelliteCount: Int = 0
)

/**
 * GPS sensor provider for MAVLink GPS_INPUT messages.
 * Uses Android location services to provide GPS data.
 */
@Singleton
class GpsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val MIN_TIME_MS = 100L // Minimum time between location updates
        private const val MIN_DISTANCE_M = 0.1f // Minimum distance between location updates
        private const val GPS_SYSTEM_ID = 1
        private const val GPS_COMPONENT_ID = 191 // MAV_COMP_ID_ONBOARD_COMPUTER
        private const val GPS_DEVICE_ID = 0
    }
    
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    private val _state = MutableStateFlow(GpsProviderState())
    val state: Flow<GpsProviderState> = _state.asStateFlow()
    
    private var isStarted = false
    private var lastLocation: Location? = null
    private var injectionJob: Job? = null
    private var frameCallback: ((MavRawFrame) -> Unit)? = null
    
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            updateState()
            Timber.v("GPS location updated: lat=${location.latitude}, lon=${location.longitude}, alt=${location.altitude}")
        }
        
        override fun onProviderEnabled(provider: String) {
            Timber.i("GPS provider enabled: $provider")
            updateState()
        }
        
        override fun onProviderDisabled(provider: String) {
            Timber.i("GPS provider disabled: $provider")
            updateState()
        }
    }
    
    /**
     * Starts GPS data collection and injection.
     */
    suspend fun start(rateHz: Float, callback: (MavRawFrame) -> Unit) = withContext(Dispatchers.Main) {
        try {
            frameCallback = callback
            
            if (!hasLocationPermission()) {
                Timber.w("GPS permission not granted")
                updateState()
                return@withContext
            }
            
            if (!isGpsEnabled()) {
                Timber.w("GPS is not enabled on device")
                updateState()
                return@withContext
            }
            
            // Start location updates
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener
            )
            
            // Get last known location
            lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            
            isStarted = true
            updateState()
            
            // Start injection coroutine
            startInjection(rateHz)
            
            Timber.i("GPS provider started at $rateHz Hz")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start GPS provider")
        }
    }
    
    /**
     * Stops GPS data collection.
     */
    suspend fun stop() = withContext(Dispatchers.Main) {
        try {
            isStarted = false
            injectionJob?.cancel()
            locationManager.removeUpdates(locationListener)
            frameCallback = null
            
            updateState()
            Timber.i("GPS provider stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping GPS provider")
        }
    }
    
    /**
     * Starts the GPS injection coroutine.
     */
    private fun startInjection(rateHz: Float) {
        val intervalMs = (1000.0 / rateHz).toLong()
        
        injectionJob = CoroutineScope(Dispatchers.IO).launch {
            while (isStarted && currentCoroutineContext().isActive) {
                try {
                    generateGpsFrame()?.let { frame ->
                        frameCallback?.invoke(frame)
                    }
                    delay(intervalMs)
                } catch (e: Exception) {
                    Timber.e(e, "Error in GPS injection loop")
                }
            }
        }
    }
    
    /**
     * Generates a GPS_INPUT MAVLink message.
     */
    private fun generateGpsFrame(): MavRawFrame? {
        val location = lastLocation ?: return null
        
        try {
            // Create GPS_INPUT message
            val gpsInput = GpsInput.builder()
                .timeUsec(System.currentTimeMillis() * 1000) // Convert to microseconds
                .gpsId(GPS_DEVICE_ID)
                .ignoreFlags(EnumSet.noneOf(MavGpsInputIgnoreFlags::class.java))
                .lat((location.latitude * 1e7).toInt()) // Convert to 1E7 degrees
                .lon((location.longitude * 1e7).toInt()) // Convert to 1E7 degrees
                .alt(if (location.hasAltitude()) location.altitude.toFloat() else 0.0f)
                .hdop(if (location.hasAccuracy()) location.accuracy else 99.99f)
                .vdop(if (location.hasAccuracy()) location.accuracy else 99.99f)
                .vn(if (location.hasSpeed()) location.speed else 0.0f) // North velocity
                .ve(if (location.hasSpeed()) 0.0f else 0.0f) // East velocity (approximation)
                .vd(0.0f) // Down velocity
                .speedAccuracy(if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else 1.0f)
                .horizAccuracy(if (location.hasAccuracy()) location.accuracy else 10.0f)
                .vertAccuracy(if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else 10.0f)
                .satellitesVisible(estimateSatelliteCount(location))
                .build()
            
            // TODO: Use MavlinkCodec to encode the message
            // This is a placeholder - the actual encoding would use the codec
            val rawBytes = ByteArray(0) // Placeholder
            
            return MavRawFrame(
                rawBytes = rawBytes,
                systemId = GPS_SYSTEM_ID,
                componentId = GPS_COMPONENT_ID,
                messageId = GpsInput.MESSAGE_ID,
                sequence = 0
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate GPS frame")
            return null
        }
    }
    
    /**
     * Estimates satellite count based on location accuracy.
     */
    private fun estimateSatelliteCount(location: Location): Int {
        return when {
            !location.hasAccuracy() -> 0
            location.accuracy <= 5.0 -> 12
            location.accuracy <= 10.0 -> 8
            location.accuracy <= 20.0 -> 6
            else -> 4
        }
    }
    
    /**
     * Checks if location permission is granted.
     */
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Checks if GPS is enabled on the device.
     */
    private fun isGpsEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
    
    /**
     * Updates the provider state.
     */
    private fun updateState() {
        _state.value = GpsProviderState(
            isEnabled = isStarted,
            hasPermission = hasLocationPermission(),
            hasLocation = lastLocation != null,
            lastLocation = lastLocation,
            satelliteCount = lastLocation?.let { estimateSatelliteCount(it) } ?: 0
        )
    }
}