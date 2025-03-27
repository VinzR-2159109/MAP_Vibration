package be.uhasselt.vibration.presentation

import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private var vibrationJob: Job? = null
    private val direction = mutableStateOf(Pair(0.0, 0.0))

    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "Vibration::MainActivity")


        setContent {
            val currentDirection by direction
            VibrationTheme {
                DirectionIndicator(currentDirection.first, currentDirection.second)
            }
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

        else if (event.path == "/cancel") {
            stopVibration()
        }

        else if (event.path == "/direction") {
            val message = String(event.data)
            try {
                val jsonDirection = JSONObject(message)
                val x = jsonDirection.getDouble("x")
                val y = jsonDirection.getDouble("y")

                handleDirection(x, y)
            } catch (e: Exception) {
                Log.e("Wear", "Invalid vibration payload", e)
            }
        }
    }

    private fun handleVibration(amplitude: Int, ratio: Double) {
        vibrationJob?.cancel()

        if (amplitude <= 0 || ratio <= 0) return

        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return

        val coercedAmplitude = amplitude.coerceIn(1, 255)

        vibrationJob = CoroutineScope(Dispatchers.Default).launch {
            if (ratio >= 85.0) {
                while (isActive) {
                    val onTime = 500L
                    vibrator.vibrate(VibrationEffect.createOneShot(onTime, coercedAmplitude))
                    delay(onTime + 10)
                }
            } else {
                while (isActive) {
                    val basePulse = 50L
                    val interval = ((1.0 - (ratio / 85.0)) * 1000L).toLong().coerceAtLeast(basePulse)

                    vibrator.vibrate(VibrationEffect.createOneShot(basePulse, coercedAmplitude))

                    delay(interval)
                }
            }
        }
    }


    private fun stopVibration() {
        vibrationJob?.cancel()
    }

    private fun handleDirection(x : Double , y : Double) {
        direction.value = Pair(x, y)
    }


    override fun onResume() {
        super.onResume()
        if (!wakeLock.isHeld) {
            wakeLock.acquire(30*60*1000L /*30 minutes*/)
        }
    }

    override fun onPause() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        super.onPause()
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onDestroy()
    }
}

@Composable
fun DirectionIndicator(x: Double, y: Double) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        val angleRadians = atan2(y, -x) + Math.PI/2
        val angleDegrees = Math.toDegrees(angleRadians)

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (x == 0.0 && y == 0.0) return@Canvas

            val center = Offset(size.width / 2, size.height / 2)

            // Adjust angle: watch held upside down + x-axis flipped
            val angleRadians = atan2(y, -x) + Math.PI / 2

            // Triangle config
            val triangleLength = size.maxDimension // full size to extend beyond bezel
            val triangleAngleOffset = Math.toRadians(25.0) // spread

            // Base corners (outside screen)
            val baseAngle1 = angleRadians - triangleAngleOffset
            val baseAngle2 = angleRadians + triangleAngleOffset

            val base1 = Offset(
                x = center.x + cos(baseAngle1).toFloat() * triangleLength,
                y = center.y + sin(baseAngle1).toFloat() * triangleLength
            )

            val base2 = Offset(
                x = center.x + cos(baseAngle2).toFloat() * triangleLength,
                y = center.y + sin(baseAngle2).toFloat() * triangleLength
            )

            // Draw the triangle pointing out from center
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x, center.y) // tip
                    lineTo(base1.x, base1.y)
                    lineTo(base2.x, base2.y)
                    close()
                },
                color = Color.Green.copy(alpha = 0.6f)
            )

            // Draw the center dot
            drawCircle(
                color = Color.Blue,
                radius = 20f,
                center = center
            )
        }

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


