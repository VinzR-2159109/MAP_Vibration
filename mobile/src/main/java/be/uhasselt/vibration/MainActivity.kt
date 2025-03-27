package be.uhasselt.vibration

import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.Wearable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {
    private lateinit var mqttClient: Mqtt5AsyncClient
    private lateinit var mqttStatusText: TextView

    private lateinit var vibrationPayloadTxt: TextView
    private lateinit var directionPayloadTxt: TextView

    private lateinit var wakeLock: PowerManager.WakeLock

    private val VIBRATION_TOPIC = "Output/Vibration"
    private val DIRECTION_TOPIC = "Output/Direction"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "vibrationApp::mqttWakeLock"
        )
        wakeLock.acquire(20*60*1000L /*20 minutes*/)

        mqttClient = MqttClient.builder()
            .useMqttVersion5()
            .serverHost("0f158df0574242429e54c7458f9f4a37.s1.eu.hivemq.cloud")
            .serverPort(8883)
            .sslWithDefaultConfig()
            .simpleAuth()
                .username("dwi_map")
                .password("wRYx&RK%l5vsflnN".toByteArray())
                .applySimpleAuth()
            .automaticReconnectWithDefaultConfig()
            .addDisconnectedListener { context ->
                runOnUiThread {
                    updateMqttStatus("Disconnected", false)
                    showSnackbar("MQTT disconnected. Reconnecting...")
                }
            }
            .addConnectedListener {
                runOnUiThread {
                    updateMqttStatus("Connected", true)
                    showSnackbar("MQTT connected!")
                }
            }
            .buildAsync()

        connectToMqtt()

        mqttStatusText = findViewById(R.id.mqttStatus_txt)
        mqttStatusText.text = "MQTT: Connecting..."

        vibrationPayloadTxt = findViewById(R.id.vibrationPayload_txt)
        directionPayloadTxt = findViewById(R.id.directionPayload_txt)

        findViewById<Button>(R.id.vibrate_btn).setOnClickListener {
            sendVibrateToWatch(150, 70.0)
        }

        findViewById<Button>(R.id.cancel_btn).setOnClickListener {
            sendCancelToWatch()
        }

    }

    private fun connectToMqtt() {
        mqttClient.connect().whenComplete { _, throwable ->
            if (throwable == null) {
                showSnackbar("MQTT connected")
                updateMqttStatus("Connected", true)
                subscribeToTopic()
            } else {
                updateMqttStatus("Failed: $throwable", false)
                showSnackbar("MQTT connectie mislukt: retrying...")
                Log.e("MQTT", "Connectie mislukt", throwable)

                lifecycleScope.launch {
                    delay(3000)
                    connectToMqtt()
                }
            }
        }
    }

    private fun subscribeToTopic() {
        mqttClient.subscribeWith()
            .topicFilter(VIBRATION_TOPIC)
            .qos(MqttQos.AT_MOST_ONCE)
            .callback { publish -> handleMqttMessage(publish.topic.toString(), publish.payload.orElse(null)) }
            .send()

        mqttClient.subscribeWith()
            .topicFilter(DIRECTION_TOPIC)
            .qos(MqttQos.AT_MOST_ONCE)
            .callback { publish -> handleMqttMessage(publish.topic.toString(), publish.payload.orElse(null)) }
            .send()

    }

    private fun handleMqttMessage(topic: String, rawPayload: ByteBuffer?) {
        val payload = rawPayload?.let {
            val bytes = ByteArray(it.remaining())
            it.get(bytes)
            String(bytes)
        } ?: return

        runOnUiThread {
            when (topic){
                VIBRATION_TOPIC -> {
                    vibrationPayloadTxt.text = payload
                }

                DIRECTION_TOPIC -> {
                    directionPayloadTxt.text = payload
                }
            }
        }

        try {
            val jsonPayload = JSONObject(payload)
            when (topic) {
                VIBRATION_TOPIC -> {
                    when (jsonPayload.optString("status")) {
                        "off" -> sendCancelToWatch()
                        "on" -> {
                            val amplitude = jsonPayload.getInt("amplitude")
                            val ratio = jsonPayload.getDouble("vibration_ratio")
                            sendVibrateToWatch(amplitude, ratio)
                        }
                    }
                }

                DIRECTION_TOPIC -> {
                    when (jsonPayload.optString("status")) {
                        "UNKNOWN" -> {
                            sendCancelToWatch()
                        }

                        "KNOWN" -> {
                            val x = jsonPayload.getDouble("x")
                            val y = jsonPayload.getDouble("y")
                            sendDirectionToWatch(x, y)
                        }
                    }

                }
            }
        } catch (e: Exception) {
            Log.e("MQTT", "Invalid payload", e)
            showSnackbar("JSON error: ${e.message}")
        }
    }



    private fun sendVibrateToWatch(amplitude: Int, ratio: Double) {
        lifecycleScope.launch {
            val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
            for (node in nodes) {
                val payload = JSONObject()
                    .put("amplitude", amplitude)
                    .put("ratio", ratio)
                    .toString()
                    .toByteArray()

                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(node.id, "/vibrate", payload)
                    .await()
            }
        }
    }

    private fun sendCancelToWatch() {
        showSnackbar("Sending Cancel")
        lifecycleScope.launch {
            val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
            for (node in nodes) {
                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(node.id, "/cancel", null)
                    .await()
            }
        }
    }

    private fun sendDirectionToWatch(x : Double, y : Double){
        lifecycleScope.launch {
            val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
            for (node in nodes) {
                val payload = JSONObject()
                    .put("x", x)
                    .put("y", y)
                    .toString()
                    .toByteArray()

                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(node.id, "/direction", payload)
                    .await()
            }
        }
    }

    private fun updateMqttStatus(status: String, isConnected: Boolean) {
        mqttStatusText.text = "MQTT: $status"
        mqttStatusText.setTextColor(
            if (isConnected) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
