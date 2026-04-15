package com.gesturecomm.gestures

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.gesturecomm.output.WatchTts
import kotlinx.coroutines.*

/**
 * Foreground service that owns the SensorManager lifecycle.
 * Runs even when the watch screen is off.
 *
 * Broadcasts detected gestures via:
 *   1. Local broadcast → UI (MainActivity updates the display)
 *   2. WatchTts        → watch speaks committed output
 */
class GestureService : Service(), SensorEventListener {

    companion object {
        const val ACTION_GESTURE = "com.gesturecomm.GESTURE_DETECTED"
        const val ACTION_MANUAL_GESTURE = "com.gesturecomm.MANUAL_GESTURE"
        const val EXTRA_GESTURE  = "gesture_name"
        const val EXTRA_MANUAL_GESTURE = "manual_gesture_name"
        const val EXTRA_SEQUENCE = "pending_sequence"
        const val EXTRA_PHONEME  = "resolved_phoneme"
        const val EXTRA_PREDICTION = "predicted_word"
        const val EXTRA_CANDIDATES = "prediction_candidates"
        const val EXTRA_PHRASE   = "phrase"
        const val CHANNEL_ID     = "gesture_service"

        fun start(context: Context) {
            val intent = Intent(context, GestureService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureService::class.java))
        }

        fun sendManualGesture(context: Context, gesture: Gesture) {
            val intent = Intent(context, GestureService::class.java).apply {
                action = ACTION_MANUAL_GESTURE
                putExtra(EXTRA_MANUAL_GESTURE, gesture.name)
            }
            context.startForegroundService(intent)
        }
    }

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor:  Sensor? = null
    private lateinit var vibrator: Vibrator
    private val classifier = GestureClassifier()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Sequence + decoding state
    private var pendingGesture: Gesture? = null
    private var pendingGestureTs: Long = 0L
    private var sequenceTimeoutJob: Job? = null
    private var wordCommitJob: Job? = null
    private val tokenBuffer = mutableListOf<String>()

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        vibrator      = getSystemService(VIBRATOR_SERVICE) as Vibrator
        WatchTts.init(this)

        // Register at SENSOR_DELAY_GAME (~50 Hz) — good balance of responsiveness and battery
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        sequenceTimeoutJob?.cancel()
        wordCommitJob?.cancel()
        WatchTts.shutdown()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MANUAL_GESTURE) {
            val gestureName = intent.getStringExtra(EXTRA_MANUAL_GESTURE)
            val gesture = gestureName?.let { runCatching { Gesture.valueOf(it) }.getOrNull() }
            if (gesture != null) {
                onGestureDetected(gesture)
            }
        }
        return START_STICKY
    }

    // ── SensorEventListener ────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        val ts = System.currentTimeMillis()
        val gesture = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> classifier.onAccel(event.values[0], event.values[1], event.values[2], ts)
            Sensor.TYPE_GYROSCOPE     -> classifier.onGyro (event.values[0], event.values[1], event.values[2], ts)
            else -> null
        } ?: return

        onGestureDetected(gesture)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Gesture handling ───────────────────────────────────────────────────────

    private fun onGestureDetected(gesture: Gesture) {
        // 1. Haptic feedback on the watch (double pulse = confirmation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 80, 60, 80), -1)
        }

        // 2. Feed into sequence resolver + phonetic decoder
        processGesture(gesture)
    }

    private fun processGesture(gesture: Gesture) {
        val now = System.currentTimeMillis()
        val pending = pendingGesture

        if (pending == null) {
            pendingGesture = gesture
            pendingGestureTs = now
            scheduleSequenceTimeout()
            broadcastUi(
                lastGesture = gesture,
                pending = listOf(gesture),
                resolvedPhoneme = null,
                prediction = PhoneticPredictor.predict(tokenBuffer),
                committedPhrase = null
            )
            return
        }

        val elapsed = now - pendingGestureTs
        sequenceTimeoutJob?.cancel()

        if (elapsed > PhoneticLexicon.SEQUENCE_TIMEOUT_MS) {
            resolveSingleGestureToken(pending)
            pendingGesture = gesture
            pendingGestureTs = now
            scheduleSequenceTimeout()
            broadcastUi(
                lastGesture = gesture,
                pending = listOf(gesture),
                resolvedPhoneme = null,
                prediction = PhoneticPredictor.predict(tokenBuffer),
                committedPhrase = null
            )
            return
        }

        val pair = listOf(pending, gesture)
        val pairToken = PhoneticLexicon.resolveToken(pair)
        if (pairToken != null) {
            pendingGesture = null
            emitToken(pairToken, pair)
            return
        }

        // Pair is unknown: commit the first as a single token and keep the second pending.
        resolveSingleGestureToken(pending)
        pendingGesture = gesture
        pendingGestureTs = now
        scheduleSequenceTimeout()
        broadcastUi(
            lastGesture = gesture,
            pending = listOf(gesture),
            resolvedPhoneme = null,
            prediction = PhoneticPredictor.predict(tokenBuffer),
            committedPhrase = null
        )
    }

    private fun scheduleSequenceTimeout() {
        sequenceTimeoutJob?.cancel()
        sequenceTimeoutJob = scope.launch {
            delay(PhoneticLexicon.SEQUENCE_TIMEOUT_MS)
            val pending = pendingGesture ?: return@launch
            pendingGesture = null
            resolveSingleGestureToken(pending)
        }
    }

    private fun resolveSingleGestureToken(gesture: Gesture) {
        val token = PhoneticLexicon.resolveToken(listOf(gesture)) ?: return
        emitToken(token, listOf(gesture))
    }

    private fun emitToken(token: String, sourceGestures: List<Gesture>) {
        tokenBuffer += token
        val prediction = PhoneticPredictor.predict(tokenBuffer)
        val lastGesture = sourceGestures.lastOrNull()

        broadcastUi(
            lastGesture = lastGesture,
            pending = emptyList(),
            resolvedPhoneme = token,
            prediction = prediction,
            committedPhrase = null
        )

        scheduleWordCommit()
    }

    private fun scheduleWordCommit() {
        wordCommitJob?.cancel()
        wordCommitJob = scope.launch {
            delay(PhoneticLexicon.WORD_COMMIT_TIMEOUT_MS)
            commitCurrentWord()
        }
    }

    private fun commitCurrentWord() {
        if (tokenBuffer.isEmpty()) return
        val prediction = PhoneticPredictor.predict(tokenBuffer)
        val committed = prediction.topWord ?: tokenBuffer.joinToString("-")

        tokenBuffer.clear()
        pendingGesture = null

        broadcastUi(
            lastGesture = null,
            pending = emptyList(),
            resolvedPhoneme = null,
            prediction = prediction,
            committedPhrase = committed
        )

        WatchTts.speak(committed)
    }

    private fun broadcastUi(
        lastGesture: Gesture?,
        pending: List<Gesture>,
        resolvedPhoneme: String?,
        prediction: PredictionResult,
        committedPhrase: String?
    ) {
        val uiIntent = Intent(ACTION_GESTURE).apply {
            if (lastGesture != null) putExtra(EXTRA_GESTURE, lastGesture.name)
            putExtra(EXTRA_SEQUENCE, pending.joinToString(" + ") { it.displayName })
            putExtra(EXTRA_PHONEME, resolvedPhoneme)
            putExtra(EXTRA_PREDICTION, prediction.topWord)
            putExtra(EXTRA_CANDIDATES, prediction.candidates.joinToString(", "))
            putExtra(EXTRA_PHRASE, committedPhrase)
            `package` = packageName
        }
        sendBroadcast(uiIntent)
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gesture Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Running gesture recognition in background" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GestureComm active")
            .setContentText("Listening for gestures…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
