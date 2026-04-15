package com.gesturecomm.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import com.gesturecomm.gestures.Gesture
import com.gesturecomm.gestures.GestureService
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var gestureReceiver: BroadcastReceiver
    private var onGestureDetected: ((GestureUiState) -> Unit)? = null

    data class GestureUiState(
        val gesture: Gesture?,
        val pendingSequence: String,
        val resolvedPhoneme: String?,
        val predictedWord: String?,
        val candidates: String,
        val committedPhrase: String?
    )

    // ── Permission launcher ────────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) GestureService.start(this)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gestureReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val name = intent.getStringExtra(GestureService.EXTRA_GESTURE)
                val gesture = name?.let { runCatching { Gesture.valueOf(it) }.getOrNull() }
                val state = GestureUiState(
                    gesture = gesture,
                    pendingSequence = intent.getStringExtra(GestureService.EXTRA_SEQUENCE).orEmpty(),
                    resolvedPhoneme = intent.getStringExtra(GestureService.EXTRA_PHONEME),
                    predictedWord = intent.getStringExtra(GestureService.EXTRA_PREDICTION),
                    candidates = intent.getStringExtra(GestureService.EXTRA_CANDIDATES).orEmpty(),
                    committedPhrase = intent.getStringExtra(GestureService.EXTRA_PHRASE)
                )
                onGestureDetected?.invoke(state)
            }
        }

        setContent {
            GestureCommTheme {
                GestureScreen { callback -> onGestureDetected = callback }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            gestureReceiver,
            IntentFilter(GestureService.ACTION_GESTURE),
            RECEIVER_NOT_EXPORTED
        )
        requestSensorPermissionAndStart()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gestureReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        GestureService.stop(this)
    }

    private fun requestSensorPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            GestureService.start(this)
        } else {
            permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
    }
}

// ── UI ─────────────────────────────────────────────────────────────────────────

@Composable
fun GestureCommTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary            = Color(0xFF4FC3F7),
            primaryVariant     = Color(0xFF0288D1),
            secondary          = Color(0xFF80CBC4),
            secondaryVariant   = Color(0xFF00897B),
            background         = Color(0xFF000000),
            surface            = Color(0xFF1A1A1A),
            error              = Color(0xFFCF6679),
            onPrimary          = Color.Black,
            onSecondary        = Color.Black,
            onBackground       = Color.White,
            onSurface          = Color.White,
            onError            = Color.Black
        ),
        content = content
    )
}

@Composable
fun GestureScreen(registerCallback: ((MainActivity.GestureUiState) -> Unit) -> Unit) {
    var uiState by remember { mutableStateOf<MainActivity.GestureUiState?>(null) }
    var showFlash      by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Register the callback with the Activity
    LaunchedEffect(Unit) {
        registerCallback { state ->
            uiState = state
            showFlash = true
        }
    }

    // Auto-clear flash after 3.5 s
    LaunchedEffect(showFlash) {
        if (showFlash) {
            delay(3500)
            showFlash = false
        }
    }

    val bgColor by animateColorAsState(
        targetValue = if (showFlash) Color(0xFF0D2D45) else Color.Black,
        animationSpec = tween(300),
        label = "bg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        GestureService.sendManualGesture(context, Gesture.DOUBLE_TAP)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (uiState != null && showFlash) {
            GestureResultCard(state = uiState!!)
        } else {
            IdleScreen()
        }
    }
}

@Composable
fun IdleScreen() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(12.dp)
    ) {
        Text(
            text  = "GestureComm",
            color = Color(0xFF4FC3F7),
            fontSize   = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign  = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text      = "Ready",
            color     = Color(0xFF80CBC4),
            fontSize  = 11.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        GestureHintRow(emoji = "👋", label = "Flick up")
        GestureHintRow(emoji = "🙏", label = "Flick down")
        GestureHintRow(emoji = "🔄", label = "Twist CW / CCW")
        GestureHintRow(emoji = "✋", label = "Shake")
        GestureHintRow(emoji = "👍", label = "Double tap")
    }
}

@Composable
fun GestureHintRow(emoji: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Text(text = emoji, fontSize = 10.sp)
        Spacer(Modifier.width(4.dp))
        Text(text = label, color = Color(0xFFB0BEC5), fontSize = 9.sp)
    }
}

@Composable
fun GestureResultCard(state: MainActivity.GestureUiState) {
    val gesture = state.gesture
    val scale by animateFloatAsState(
        targetValue    = 1f,
        animationSpec  = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label          = "scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .scale(scale)
            .padding(10.dp)
    ) {
        // Big emoji
        Text(
            text     = gesture?.emoji ?: "🧠",
            fontSize = 36.sp,
        )
        Spacer(Modifier.height(6.dp))

        // Gesture name chip
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E3A4A))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text       = gesture?.displayName ?: "Sequence",
                color      = Color(0xFF4FC3F7),
                fontSize   = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))

        val mainText = state.committedPhrase
            ?: state.predictedWord
            ?: state.resolvedPhoneme
            ?: "Listening..."

        // Main decoded output
        Text(
            text       = mainText,
            color      = Color.White,
            fontSize   = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign  = TextAlign.Center,
            lineHeight = 16.sp
        )

        if (state.pendingSequence.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Pending: ${state.pendingSequence}",
                color = Color(0xFFB0BEC5),
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        }

        if (!state.resolvedPhoneme.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Phoneme: ${state.resolvedPhoneme}",
                color = Color(0xFF80CBC4),
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        }

        if (state.candidates.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Candidates: ${state.candidates}",
                color = Color(0xFF90A4AE),
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                lineHeight = 10.sp
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text     = if (state.committedPhrase.isNullOrBlank()) "▶ Predicting" else "▶ Speaking on watch",
            color    = Color(0xFF80CBC4),
            fontSize = 9.sp
        )
    }
}
