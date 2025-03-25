package be.uhasselt.vibration.presentation

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import be.uhasselt.vibration.R
import be.uhasselt.vibration.presentation.theme.VibrationTheme
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import org.json.JSONObject

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private var vibrationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Wear App Ready")
        }

        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/vibrate") {
            val message = String(event.data)
            try {
                val json = JSONObject(message)
                val amplitude = json.getInt("amplitude")
                val ratio = json.getDouble("ratio")
                handleVibration(amplitude, ratio)
            } catch (e: Exception) {
                Log.e("Wear", "Invalid vibration payload", e)
            }
        }

        if (event.path == "/cancel") {
            stopVibration()
        }
    }

    private fun handleVibration(amplitude: Int, ratio: Double) {
        vibrationJob?.cancel()

        if (amplitude <= 0 || ratio <= 0) return

        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return

        val cycleDuration = 1000L
        val onTime = (cycleDuration * (ratio / 100.0)).toLong()
        val offTime = cycleDuration - onTime
        val coercedAmplitude = amplitude.coerceIn(1, 255)

        vibrationJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                if (vibrator.hasAmplitudeControl()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(onTime, coercedAmplitude))
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(onTime, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                delay(onTime + offTime)
            }
        }
    }

    private fun stopVibration() {
        vibrationJob?.cancel()
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onDestroy()
    }
}



@Composable
fun WearApp(greetingName: String) {
    VibrationTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Greeting(greetingName = greetingName)
        }
    }
}

@Composable
fun Greeting(greetingName: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = stringResource(R.string.hello_world, greetingName)
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp("Preview Android")
}