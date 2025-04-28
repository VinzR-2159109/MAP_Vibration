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
import androidx.compose.animation.core.EaseInOutCubic
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

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private var vibrationJob: Job? = null
    private val isActive = mutableStateOf(false)
    private val amplitudeState = mutableStateOf(0)

    @Volatile private var latestAmplitude = 0
    @Volatile private var isVibrating = false

    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.FULL_WAKE_LOCK, "Vibration::MainActivity")

        setContent {
            val status by isActive
            val amplitude by amplitudeState

            VibrationTheme {
                DirectionIndicator(status = status, amplitude = amplitude)
            }
        }

        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/vibrate" -> handleVibrateMessage(String(event.data))
            "/cancel" -> stopVibration()
        }
    }

    private fun handleVibrateMessage(message: String) {
        try {
            val json = JSONObject(message)
            val amplitude = json.getInt("amplitude")
            handleVibration(amplitude)
        } catch (e: Exception) {
            Log.e("Wear", "Invalid vibration payload", e)
        }
    }

    private fun handleVibration(amplitude: Int) {
        if (amplitude <= 0) return

        latestAmplitude = amplitude
        isVibrating = true
        isActive.value = true
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

    override fun onResume() {
        super.onResume()
        if (!wakeLock.isHeld) {
            wakeLock.acquire(30 * 60 * 1000L) // 30 minutes
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
fun DirectionIndicator(status: Boolean, amplitude: Int) {
    val animatedRadius = remember { Animatable(0f) }
    val currentAmplitude by rememberUpdatedState(amplitude)

    LaunchedEffect(status, amplitude) {
        if (!status || amplitude <= 0) {
            animatedRadius.animateTo(
                targetValue = 20f,
                animationSpec = tween(durationMillis = 300, easing = EaseInOutCubic)
            )
        } else {
            val scaledAmplitude = currentAmplitude.coerceIn(1, 255) / 255f
            val minRadius = 20f
            val maxRadius = 160f
            val targetRadius = minRadius + (maxRadius - minRadius) * scaledAmplitude

            animatedRadius.animateTo(
                targetValue = targetRadius,
                animationSpec = tween(durationMillis = 300, easing = EaseInOutCubic)
            )
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

            drawCircle(
                color = if (!status || amplitude <= 0) Color(0xFFFFA500) else Color.Green.copy(alpha = 0.4f),
                radius = animatedRadius.value,
                center = center
            )
        }
    }
}



