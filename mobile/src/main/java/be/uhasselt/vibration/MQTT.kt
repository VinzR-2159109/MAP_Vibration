package be.uhasselt.vibration

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import org.json.JSONObject
import java.nio.ByteBuffer

class MQTTManager(private val context: Context,
                  private val onPayloadReceived: (String) -> Unit,
                  private val onWebSocketCommand: (action: String, data: String?) -> Unit,
                  private val onStatusChanged: (String, Boolean) -> Unit
) {
    private lateinit var mqttClient: Mqtt5AsyncClient

    private val MQTT_HOST = "0f158df0574242429e54c7458f9f4a37.s1.eu.hivemq.cloud"
    private val USERNAME = "dwi_map"
    private val PASSWORD = "wRYx&RK%l5vsflnN"
    private val TOPIC = "Command/Haptic"

    fun connect() {
        mqttClient = MqttClient.builder()
            .useMqttVersion5()
            .serverHost(MQTT_HOST)
            .serverPort(8883)
            .sslWithDefaultConfig()
            .simpleAuth()
            .username(USERNAME)
            .password(PASSWORD.toByteArray())
            .applySimpleAuth()
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                onStatusChanged("Connected", true)
            }
            .addDisconnectedListener {
                onStatusChanged("Disconnected", false)
            }
            .buildAsync()

        mqttClient.connect().whenComplete { _, throwable ->
            if (throwable == null) {
                subscribe()
                onStatusChanged("Connected", true)
            } else {
                onStatusChanged("Failed: ${throwable.message}", false)
            }
        }
    }

    private fun subscribe() {
        mqttClient.subscribeWith()
            .topicFilter(TOPIC)
            .qos(MqttQos.AT_MOST_ONCE)
            .callback { msg -> handleMessage(msg.payload.orElse(null)) }
            .send()
    }

    private fun handleMessage(rawPayload: ByteBuffer?) {
        val payload = rawPayload?.let {
            val bytes = ByteArray(it.remaining())
            it.get(bytes)
            String(bytes)
        } ?: return

        onPayloadReceived(payload)

        try {
            val json = JSONObject(payload)
            if (json.optString("key") == "websocket") {
                val action = json.getString("action")
                val data = if (json.has("data")) json.getString("data") else null
                onWebSocketCommand(action, data)
            }
        } catch (e: Exception) {
            Log.e("MQTT", "Invalid JSON", e)
        }
    }
}
