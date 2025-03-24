package be.uhasselt.vibration

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.Wearable
import androidx.lifecycle.lifecycleScope
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
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback { publish ->
                        val payload = publish.payload.orElse(null)?.let { String(it.array()) }
                        try {
                            val vibrateValue = JSONObject(payload).getInt("vibrate")
                            sendVibrateToWatch(vibrateValue)
                        } catch (e: Exception) {
                            Log.e("MQTT", "Invalid payload", e)
                        }
                    }
                    .send()
            } else {
                Log.e("MQTT", "Connectie mislukt", throwable)
            }
        }


        val vibrateButton: Button = findViewById(R.id.vibrate_btn)
        vibrateButton.setOnClickListener {
            sendVibrateToWatch(50)
        }
    }


    private fun sendVibrateToWatch(value: Int) {
        lifecycleScope.launch {
            val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
            for (node in nodes) {
                val payload = JSONObject().put("vibrate", value).toString().toByteArray()
                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(node.id, "/vibrate", payload)
                    .await()
            }
        }
    }


}
