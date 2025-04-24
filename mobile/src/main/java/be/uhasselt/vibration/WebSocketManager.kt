package be.uhasselt.vibration

import android.content.Context
import android.util.Log
import okhttp3.*
import org.json.JSONObject

class WebSocketManager(
    private val context: Context,
    private val onVibrate: (Int, Double) -> Unit,
    private val onDirection: (Double, Double) -> Unit,
    private val onCancel: () -> Unit,
    private val onPayloadReceived: (String) -> Unit,
    private val onStatusChanged: (String, Boolean) -> Unit
) {
    private var webSocket: WebSocket? = null

    fun connect(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onStatusChanged("Connected", true)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onStatusChanged("Error: ${t.message}", false)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                onStatusChanged("Closed: $reason", false)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                onPayloadReceived(text)
                handleMessage(text)
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Manual disconnect")
        webSocket = null
        onStatusChanged("Disconnected", false)
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.getString("status")){
                "on" -> onVibrate(json.getInt("amplitude"), json.getDouble("vibration_ratio"))
                "off" -> onCancel()
            }

        } catch (e: Exception) {
            Log.e("WS", "Invalid JSON", e)
        }
    }
}
