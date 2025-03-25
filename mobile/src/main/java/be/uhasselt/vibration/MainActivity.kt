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
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var mqttClient: Mqtt5AsyncClient
    private lateinit var mqttStatusText: TextView
    private lateinit var mqttPayloadText: TextView

    private lateinit var wakeLock: PowerManager.WakeLock

    private val topic = "Output/Vibration"

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

        mqttPayloadText = findViewById(R.id.mqttPayload_txt)

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
            .topicFilter(topic)
            .qos(MqttQos.AT_MOST_ONCE)
            .callback { publish ->
                val payload = publish.payload.orElse(null)?.let {
                    val bytes = ByteArray(it.remaining())
                    it.get(bytes)
                    String(bytes)
                }

                if (payload != null) {
                    try {
                        mqttPayloadText.text = payload
                        val jsonPayload = JSONObject(payload)
                        val status = jsonPayload.getString("status")

                        if (status == "off") {
                            sendCancelToWatch()
                        } else if (status == "on") {
                            val amplitude = jsonPayload.getInt("amplitude")
                            val ratio = jsonPayload.getDouble("vibration_ratio")
                            sendVibrateToWatch(amplitude, ratio)
                        }

                    } catch (e: Exception) {
                        showSnackbar("JSON error: ${e.message}")
                        Log.e("MQTT", "Invalid payload", e)
                    }
                } else {
                    showSnackbar("Payload is null")
                    Log.e("MQTT", "Payload is null")
                }
            }
            .send()
    }


    private fun sendVibrateToWatch(amplitude: Int, ratio: Double) {
        showSnackbar("Sending Vibrate A: $amplitude & R: $ratio")
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
