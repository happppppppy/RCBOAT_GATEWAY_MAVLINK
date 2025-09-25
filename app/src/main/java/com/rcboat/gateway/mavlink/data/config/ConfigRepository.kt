package com.rcboat.gateway.mavlink.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.rcboat.gateway.mavlink.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing application configuration using DataStore.
 * Provides type-safe access to configuration with persistence.
 */
@Singleton
class ConfigRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    
    companion object {
        // DataStore keys
        private val CLOUD_HOST = stringPreferencesKey("cloud_host")
        private val CLOUD_PORT = intPreferencesKey("cloud_port")
        private val TRANSPORT_TYPE = stringPreferencesKey("transport_type")
        private val SECONDARY_UDP_ENABLED = booleanPreferencesKey("secondary_udp_enabled")
        private val SECONDARY_UDP_HOST = stringPreferencesKey("secondary_udp_host")
        private val SECONDARY_UDP_PORT = intPreferencesKey("secondary_udp_port")
        private val MAVLINK_BAUD = intPreferencesKey("mavlink_baud")
        private val SIGNING_ENABLED = booleanPreferencesKey("signing_enabled")
        private val SIGNING_KEY_HEX = stringPreferencesKey("signing_key_hex")
        private val SENSOR_GPS_RATE_HZ = floatPreferencesKey("sensor_gps_rate_hz")
        private val SENSOR_IMU_RATE_HZ = floatPreferencesKey("sensor_imu_rate_hz")
        private val SENSOR_BATTERY_RATE_HZ = floatPreferencesKey("sensor_battery_rate_hz")
        private val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
        private val IMU_ENABLED = booleanPreferencesKey("imu_enabled")
        private val BATTERY_ENABLED = booleanPreferencesKey("battery_enabled")
        private val RECONNECT_BASE_MS = longPreferencesKey("reconnect_base_ms")
        private val RECONNECT_MAX_MS = longPreferencesKey("reconnect_max_ms")
        private val LOG_LEVEL = stringPreferencesKey("log_level")
    }
    
    /**
     * Observable flow of the current configuration.
     */
    val configFlow: Flow<AppConfig> = dataStore.data
        .catch { exception ->
            Timber.e(exception, "Error reading config from DataStore")
            emit(emptyPreferences())
        }
        .map { preferences ->
            AppConfig(
                cloudHost = preferences[CLOUD_HOST] ?: AppConfig().cloudHost,
                cloudPort = preferences[CLOUD_PORT] ?: AppConfig().cloudPort,
                transportType = preferences[TRANSPORT_TYPE]?.let { 
                    TransportType.valueOf(it) 
                } ?: AppConfig().transportType,
                secondaryUdpEnabled = preferences[SECONDARY_UDP_ENABLED] ?: AppConfig().secondaryUdpEnabled,
                secondaryUdpHost = preferences[SECONDARY_UDP_HOST] ?: AppConfig().secondaryUdpHost,
                secondaryUdpPort = preferences[SECONDARY_UDP_PORT] ?: AppConfig().secondaryUdpPort,
                mavlinkBaud = preferences[MAVLINK_BAUD] ?: AppConfig().mavlinkBaud,
                signingEnabled = preferences[SIGNING_ENABLED] ?: AppConfig().signingEnabled,
                signingKeyHex = preferences[SIGNING_KEY_HEX] ?: AppConfig().signingKeyHex,
                sensorGpsRateHz = preferences[SENSOR_GPS_RATE_HZ] ?: AppConfig().sensorGpsRateHz,
                sensorImuRateHz = preferences[SENSOR_IMU_RATE_HZ] ?: AppConfig().sensorImuRateHz,
                sensorBatteryRateHz = preferences[SENSOR_BATTERY_RATE_HZ] ?: AppConfig().sensorBatteryRateHz,
                gpsEnabled = preferences[GPS_ENABLED] ?: AppConfig().gpsEnabled,
                imuEnabled = preferences[IMU_ENABLED] ?: AppConfig().imuEnabled,
                batteryEnabled = preferences[BATTERY_ENABLED] ?: AppConfig().batteryEnabled,
                reconnectBaseMs = preferences[RECONNECT_BASE_MS] ?: AppConfig().reconnectBaseMs,
                reconnectMaxMs = preferences[RECONNECT_MAX_MS] ?: AppConfig().reconnectMaxMs,
                logLevel = preferences[LOG_LEVEL] ?: AppConfig().logLevel
            )
        }
    
    /**
     * Updates the configuration in the data store.
     */
    suspend fun updateConfig(config: AppConfig): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                preferences[CLOUD_HOST] = config.cloudHost
                preferences[CLOUD_PORT] = config.cloudPort
                preferences[TRANSPORT_TYPE] = config.transportType.name
                preferences[SECONDARY_UDP_ENABLED] = config.secondaryUdpEnabled
                preferences[SECONDARY_UDP_HOST] = config.secondaryUdpHost
                preferences[SECONDARY_UDP_PORT] = config.secondaryUdpPort
                preferences[MAVLINK_BAUD] = config.mavlinkBaud
                preferences[SIGNING_ENABLED] = config.signingEnabled
                preferences[SIGNING_KEY_HEX] = config.signingKeyHex
                preferences[SENSOR_GPS_RATE_HZ] = config.sensorGpsRateHz
                preferences[SENSOR_IMU_RATE_HZ] = config.sensorImuRateHz
                preferences[SENSOR_BATTERY_RATE_HZ] = config.sensorBatteryRateHz
                preferences[GPS_ENABLED] = config.gpsEnabled
                preferences[IMU_ENABLED] = config.imuEnabled
                preferences[BATTERY_ENABLED] = config.batteryEnabled
                preferences[RECONNECT_BASE_MS] = config.reconnectBaseMs
                preferences[RECONNECT_MAX_MS] = config.reconnectMaxMs
                preferences[LOG_LEVEL] = config.logLevel
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update configuration")
            Result.Error(e)
        }
    }
    
    /**
     * Exports configuration as JSON string.
     */
    suspend fun exportConfig(): Result<String> {
        return try {
            val config = dataStore.data.map { preferences ->
                AppConfig(
                    cloudHost = preferences[CLOUD_HOST] ?: AppConfig().cloudHost,
                    cloudPort = preferences[CLOUD_PORT] ?: AppConfig().cloudPort,
                    transportType = preferences[TRANSPORT_TYPE]?.let { 
                        TransportType.valueOf(it) 
                    } ?: AppConfig().transportType,
                    secondaryUdpEnabled = preferences[SECONDARY_UDP_ENABLED] ?: AppConfig().secondaryUdpEnabled,
                    secondaryUdpHost = preferences[SECONDARY_UDP_HOST] ?: AppConfig().secondaryUdpHost,
                    secondaryUdpPort = preferences[SECONDARY_UDP_PORT] ?: AppConfig().secondaryUdpPort,
                    mavlinkBaud = preferences[MAVLINK_BAUD] ?: AppConfig().mavlinkBaud,
                    signingEnabled = preferences[SIGNING_ENABLED] ?: AppConfig().signingEnabled,
                    signingKeyHex = preferences[SIGNING_KEY_HEX] ?: AppConfig().signingKeyHex,
                    sensorGpsRateHz = preferences[SENSOR_GPS_RATE_HZ] ?: AppConfig().sensorGpsRateHz,
                    sensorImuRateHz = preferences[SENSOR_IMU_RATE_HZ] ?: AppConfig().sensorImuRateHz,
                    sensorBatteryRateHz = preferences[SENSOR_BATTERY_RATE_HZ] ?: AppConfig().sensorBatteryRateHz,
                    gpsEnabled = preferences[GPS_ENABLED] ?: AppConfig().gpsEnabled,
                    imuEnabled = preferences[IMU_ENABLED] ?: AppConfig().imuEnabled,
                    batteryEnabled = preferences[BATTERY_ENABLED] ?: AppConfig().batteryEnabled,
                    reconnectBaseMs = preferences[RECONNECT_BASE_MS] ?: AppConfig().reconnectBaseMs,
                    reconnectMaxMs = preferences[RECONNECT_MAX_MS] ?: AppConfig().reconnectMaxMs,
                    logLevel = preferences[LOG_LEVEL] ?: AppConfig().logLevel
                )
            }
            
            val json = Json.encodeToString(config)
            Result.Success(json)
        } catch (e: Exception) {
            Timber.e(e, "Failed to export configuration")
            Result.Error(e)
        }
    }
    
    /**
     * Imports configuration from JSON string.
     */
    suspend fun importConfig(json: String): Result<Unit> {
        return try {
            val config = Json.decodeFromString<AppConfig>(json)
            updateConfig(config)
        } catch (e: Exception) {
            Timber.e(e, "Failed to import configuration")
            Result.Error(e)
        }
    }
}