package com.rcboat.gateway.mavlink.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.rcboat.gateway.mavlink.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
        // DataStore keys for MQTT configuration
        private val MQTT_BROKER_ADDRESS = stringPreferencesKey("mqtt_broker_address")
        private val MQTT_USERNAME = stringPreferencesKey("mqtt_username")
        private val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
        private val BOAT_ID = stringPreferencesKey("boat_id")
        private val MAVLINK_BAUD = intPreferencesKey("mavlink_baud")
        private val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val RECONNECT_DELAY_MS = longPreferencesKey("reconnect_delay_ms")
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
                mqttBrokerAddress = preferences[MQTT_BROKER_ADDRESS] ?: AppConfig().mqttBrokerAddress,
                mqttUsername = preferences[MQTT_USERNAME] ?: AppConfig().mqttUsername,
                mqttPassword = preferences[MQTT_PASSWORD] ?: AppConfig().mqttPassword,
                boatId = preferences[BOAT_ID] ?: AppConfig().boatId,
                mavlinkBaud = preferences[MAVLINK_BAUD] ?: AppConfig().mavlinkBaud,
                autoReconnect = preferences[AUTO_RECONNECT] ?: AppConfig().autoReconnect,
                reconnectDelayMs = preferences[RECONNECT_DELAY_MS] ?: AppConfig().reconnectDelayMs,
                logLevel = preferences[LOG_LEVEL] ?: AppConfig().logLevel
            )
        }
    
    /**
     * Updates the configuration in the data store.
     */
    suspend fun updateConfig(config: AppConfig): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                preferences[MQTT_BROKER_ADDRESS] = config.mqttBrokerAddress
                preferences[MQTT_USERNAME] = config.mqttUsername
                preferences[MQTT_PASSWORD] = config.mqttPassword
                preferences[BOAT_ID] = config.boatId
                preferences[MAVLINK_BAUD] = config.mavlinkBaud
                preferences[AUTO_RECONNECT] = config.autoReconnect
                preferences[RECONNECT_DELAY_MS] = config.reconnectDelayMs
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
            val config = configFlow.first()
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