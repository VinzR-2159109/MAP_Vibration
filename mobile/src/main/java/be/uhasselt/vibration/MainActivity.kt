package be.uhasselt.vibration

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.Wearable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var mqttClient: Mqtt3AsyncClient
    private val topic = "Output/Vibration"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mqttClient = MqttClient.builder()
            .useMqttVersion3()
            .serverHost("0f158df0574242429e54c7458f9f4a37.s1.eu.hivemq.cloud")
            .serverPort(8883)
            .sslWithDefaultConfig()
            .simpleAuth()
            .username("dwi_map")
            .password("wRYx&RK%l5vsflnN".toByteArray())
            .applySimpleAuth()
            .buildAsync()

        mqttClient.connect().whenComplete { _, throwable ->
            if (throwable == null) {
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
                                showSnackbar(payload)
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
            } else {
                showSnackbar("MQTT connectie mislukt")
                Log.e("MQTT", "Connectie mislukt", throwable)
            }
        }

        findViewById<Button>(R.id.vibrate_btn).setOnClickListener {
            sendVibrateToWatch(150, 70.0)
        }

        findViewById<Button>(R.id.cancel_btn).setOnClickListener {
            sendCancelToWatch()
        }
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

    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }
}
