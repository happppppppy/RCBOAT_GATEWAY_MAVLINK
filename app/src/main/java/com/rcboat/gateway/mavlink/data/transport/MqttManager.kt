package com.rcboat.gateway.mavlink.data.transport

import android.content.Context
import com.rcboat.gateway.mavlink.data.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MQTT connection state.
 */
sealed class MqttConnectionState {
    data object Disconnected : MqttConnectionState()
    data object Connecting : MqttConnectionState()
    data object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}

/**
 * MQTT manager for bidirectional MAVLink message forwarding.
 * Handles connection to MQTT broker and message pub/sub operations.
 */
@Singleton
class MqttManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val CLIENT_ID_PREFIX = "rcboat_gateway_"
        private const val CONNECTION_TIMEOUT = 30
        private const val KEEP_ALIVE_INTERVAL = 60
        private const val QOS = 1 // At least once delivery
    }
    
    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()
    
    private var mqttClient: MqttClient? = null
    private var currentConfig: AppConfig? = null
    
    // Channels for incoming MAVLink messages from MQTT
    private val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)
    
    /**
     * Connects to the MQTT broker with the given configuration.
     */
    suspend fun connect(config: AppConfig) {
        try {
            _connectionState.value = MqttConnectionState.Connecting
            currentConfig = config
            
            // Generate unique client ID
            val clientId = CLIENT_ID_PREFIX + System.currentTimeMillis()
            
            // Create MQTT client
            val brokerUrl = config.mqttBrokerAddress
            mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
            
            // Setup connection options
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = CONNECTION_TIMEOUT
                keepAliveInterval = KEEP_ALIVE_INTERVAL
                isAutomaticReconnect = config.autoReconnect
                
                // Set credentials if provided
                if (config.mqttUsername.isNotEmpty()) {
                    userName = config.mqttUsername
                    password = config.mqttPassword.toCharArray()
                }
                
                // Set Last Will and Testament
                setWill(
                    config.getStatusTopic(),
                    "offline".toByteArray(),
                    QOS,
                    false
                )
            }
            
            // Setup callbacks
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Timber.w(cause, "MQTT connection lost")
                    _connectionState.value = MqttConnectionState.Disconnected
                }
                
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let {
                        Timber.v("MQTT message received on topic: $topic, ${it.payload.size} bytes")
                        incomingMessages.trySend(it.payload)
                    }
                }
                
                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Timber.v("MQTT delivery complete")
                }
            })
            
            // Connect to broker
            mqttClient?.connect(options)
            
            // Subscribe to the "to_vehicle" topic
            val toVehicleTopic = config.getToVehicleTopic()
            mqttClient?.subscribe(toVehicleTopic, QOS)
            Timber.i("Subscribed to MQTT topic: $toVehicleTopic")
            
            // Publish online status
            publishStatus("online")
            
            _connectionState.value = MqttConnectionState.Connected
            Timber.i("Connected to MQTT broker: $brokerUrl")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to MQTT broker")
            _connectionState.value = MqttConnectionState.Error("Connection failed: ${e.message}")
            disconnect()
        }
    }
    
    /**
     * Disconnects from the MQTT broker.
     */
    suspend fun disconnect() {
        try {
            // Publish offline status
            currentConfig?.let { publishStatus("offline") }
            
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
            
            _connectionState.value = MqttConnectionState.Disconnected
            Timber.i("Disconnected from MQTT broker")
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting from MQTT broker")
        }
    }
    
    /**
     * Publishes a MAVLink message to the "from_vehicle" topic.
     */
    suspend fun publishFromVehicle(payload: ByteArray) {
        try {
            val config = currentConfig ?: return
            val topic = config.getFromVehicleTopic()
            
            val message = MqttMessage(payload).apply {
                qos = QOS
                isRetained = false
            }
            
            mqttClient?.publish(topic, message)
            Timber.v("Published MAVLink message to $topic: ${payload.size} bytes")
        } catch (e: Exception) {
            Timber.e(e, "Failed to publish MAVLink message")
        }
    }
    
    /**
     * Publishes a status message.
     */
    private fun publishStatus(status: String) {
        try {
            val config = currentConfig ?: return
            val topic = config.getStatusTopic()
            
            val message = MqttMessage(status.toByteArray()).apply {
                qos = QOS
                isRetained = true
            }
            
            mqttClient?.publish(topic, message)
            Timber.i("Published status to $topic: $status")
        } catch (e: Exception) {
            Timber.e(e, "Failed to publish status")
        }
    }
    
    /**
     * Receives incoming MAVLink messages from MQTT.
     * Returns null if no message is available.
     */
    suspend fun receiveToVehicle(): ByteArray? {
        return incomingMessages.tryReceive().getOrNull()
    }
    
    /**
     * Checks if connected to MQTT broker.
     */
    fun isConnected(): Boolean {
        return mqttClient?.isConnected == true
    }
}
