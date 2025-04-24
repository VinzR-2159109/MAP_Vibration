package be.uhasselt.vibration

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.Wearable
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var mqttManager: MQTTManager
    private lateinit var wsManager: WebSocketManager

    private lateinit var mqttStatusText : TextView
    private lateinit var wsStatusText : TextView

    private lateinit var mqttPayloadText: TextView
    private lateinit var wsPayloadText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mqttStatusText = findViewById(R.id.mqttStatus_txt)
        wsStatusText = findViewById(R.id.wsStatus_txt)

        mqttPayloadText = findViewById(R.id.mqttPayload_txt)
        wsPayloadText = findViewById(R.id.wsPayload_txt)

        mqttManager = MQTTManager(
            context = this,
            onWebSocketCommand = { action, data ->
                when (action) {
                    "connect" -> data?.let { wsManager.connect(it) }
                    "disconnect" -> wsManager.disconnect()
                }
            },
            onPayloadReceived = { raw ->
                runOnUiThread { mqttPayloadText.text = raw }
            },
            onStatusChanged = { status, isConnected ->
                runOnUiThread {
                    mqttStatusText.text = "MQTT: $status"
                    mqttStatusText.setTextColor(
                        getColor(if (isConnected) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
                    )
                }
            }
        )

        wsManager = WebSocketManager(
            context = this,
            onVibrate = { amp, ratio -> sendVibrateToWatch(amp, ratio) },
            onDirection = { x, y -> sendDirectionToWatch(x, y) },
            onCancel = { sendCancelToWatch() },
            onPayloadReceived = { raw ->
                runOnUiThread { wsPayloadText.text = raw }
            },
            onStatusChanged = { status, isConnected ->
                runOnUiThread {
                    wsStatusText.text = "WebSocket: $status"
                    wsStatusText.setTextColor(
                        getColor(if (isConnected) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
                    )
                }
            }
        )

        mqttManager.connect()
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

    private fun sendDirectionToWatch(x: Double, y: Double) {
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

    private fun sendCancelToWatch() {
        Snackbar.make(findViewById(android.R.id.content), "Sending Cancel", Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
            for (node in nodes) {
                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(node.id, "/cancel", null)
                    .await()
            }
        }
    }

}
