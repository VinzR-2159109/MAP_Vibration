package be.uhasselt.vibration.presentation

import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.MaterialTheme
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
    private val isActive = mutableStateOf(false)
    private val amplitudeState = mutableStateOf(0)
    private val ratioState = mutableStateOf(0.0)

    @Volatile private var latestAmplitude = 0
    @Volatile private var latestRatio = 0.0
    @Volatile private var isVibrating = false


    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "Vibration::MainActivity")


        setContent {
            val currentDirection by direction
            val status by isActive
            val amplitude by amplitudeState
            val ratio by ratioState

            VibrationTheme {
                DirectionIndicator(
                    x = currentDirection.first,
                    y = currentDirection.second,
                    status = status,
                    amplitude = amplitude,
                    ratio = ratio
                )
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
                handleVibration(amplitude)
            } catch (e: Exception) {
                Log.e("Wear", "Invalid vibration payload", e)
            }
        }

        else if (event.path == "/cancel") {
            stopVibration()
            isActive.value = false
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

    private fun handleVibration(amplitude: Int) {
        if (amplitude <= 0) return

        latestAmplitude = amplitude
        isVibrating = true

        amplitudeState.value = amplitude

        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return

        if (vibrationJob?.isActive == true) return

        vibrationJob = CoroutineScope(Dispatchers.Default).launch {
            val interval = 800L
            val duration = 60L

            while (isVibrating) {
                val coercedAmplitude = latestAmplitude.coerceIn(1, 255)

                vibrator.vibrate(VibrationEffect.createOneShot(duration, coercedAmplitude))
                delay(interval)
            }
        }
    }


    private fun stopVibration() {
        vibrationJob?.cancel()
        isActive.value = false
        isVibrating = false
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
fun DirectionIndicator(x: Double, y: Double, status: Boolean, amplitude: Int, ratio: Double){
    val animatedRadius = remember { Animatable(0f) }

    val coroutineScope = rememberCoroutineScope()
    var animationJob by remember { mutableStateOf<Job?>(null) }

    val currentAmplitude by rememberUpdatedState(amplitude)
    val currentRatio by rememberUpdatedState(ratio)

    LaunchedEffect(status) {
        if (!status || amplitude <= 0 || ratio <= 0.0) {
            animationJob?.cancel()
            animatedRadius.snapTo(0f)
        } else if (animationJob == null || animationJob?.isActive == false) {
            animationJob = coroutineScope.launch {
                while (status) {
                    val scaledAmplitude = currentAmplitude.coerceIn(1, 255) / 255f
                    val minRadius = 30f
                    val maxRadius = 180f
                    val radius = minRadius + (maxRadius - minRadius) * scaledAmplitude

                    val duration = ((1.0 - (currentRatio / 85.0)) * 1000).toInt().coerceAtLeast(300)

                    animatedRadius.snapTo(0f)
                    animatedRadius.animateTo(
                        targetValue = radius,
                        animationSpec = tween(
                            durationMillis = duration,
                            easing = LinearEasing
                        )
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)

            if (!status || (x == 0.0 && y == 0.0)) {
                drawCircle(
                    color = Color(0xFFFFA500), // Orange center dot when off
                    radius = 20f,
                    center = center
                )
                return@Canvas
            }

            // Animated pulsing circle
            drawCircle(
                color = Color.Green.copy(alpha = 0.2f),
                radius = animatedRadius.value,
                center = center
            )

            val angleRadians = atan2(y, -x) + Math.PI / 2
            val triangleLength = size.maxDimension
            val triangleAngleOffset = Math.toRadians(25.0)

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

            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(base1.x, base1.y)
                    lineTo(base2.x, base2.y)
                    close()
                },
                color = Color.Green.copy(alpha = 0.6f)
            )

            drawCircle(
                color = Color.Green.copy(alpha = 0.6f),
                radius = 20f,
                center = center
            )
        }
    }
}
