package com.gesturecomm.gestures

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Threshold-based gesture classifier.
 *
 * Works on a rolling window of raw sensor events.
 * No ML model required — ships and runs immediately.
 *
 * Tuning constants are at the top of each detector function.
 * If gestures are misfiring on your specific wrist/watch mount,
 * increase the corresponding threshold constant.
 */
class GestureClassifier {

    // ── Internal window ────────────────────────────────────────────────────────
    private val WINDOW_SIZE = 40           // ~800 ms at 50 Hz
    private val accelWindow = ArrayDeque<FloatArray>(WINDOW_SIZE)
    private val gyroWindow  = ArrayDeque<FloatArray>(WINDOW_SIZE)

    // Cooldown short enough to allow deliberate 2-step gesture sequences
    private var lastGestureTs = 0L
    private val COOLDOWN_MS   = 250L

    // Double-tap state
    private var lastTapTs = 0L
    private val DOUBLE_TAP_WINDOW_MS = 500L

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Feed a new accelerometer reading. Returns a detected [Gesture] or null. */
    fun onAccel(x: Float, y: Float, z: Float, ts: Long): Gesture? {
        accelWindow.addLast(floatArrayOf(x, y, z))
        if (accelWindow.size > WINDOW_SIZE) accelWindow.removeFirst()

        if (accelWindow.size < 10) return null
        if (ts - lastGestureTs < COOLDOWN_MS) return null

        return (detectFlickUp() ?: detectFlickDown() ?: detectShake() ?: detectDoubleTap(z, ts))
            ?.also { lastGestureTs = ts }
    }

    /** Feed a new gyroscope reading. Returns a detected [Gesture] or null. */
    fun onGyro(x: Float, y: Float, z: Float, ts: Long): Gesture? {
        gyroWindow.addLast(floatArrayOf(x, y, z))
        if (gyroWindow.size > WINDOW_SIZE) gyroWindow.removeFirst()

        if (gyroWindow.size < 10) return null
        if (ts - lastGestureTs < COOLDOWN_MS) return null

        return (detectTwistCW() ?: detectTwistCCW())
            ?.also { lastGestureTs = ts }
    }

    // ── Gesture detectors ──────────────────────────────────────────────────────

    /**
     * FLICK_UP — sharp positive Y acceleration spike.
     * The watch's Y axis points toward the hand when worn normally.
     * Threshold: peak Y > 18 m/s² within the last 10 samples.
     */
    private fun detectFlickUp(): Gesture? {
        val THRESHOLD = 18f
        val recent = accelWindow.takeLast(10)
        val peak = recent.maxOf { it[1] }         // Y axis
        return if (peak > THRESHOLD) Gesture.FLICK_UP else null
    }

    /**
     * FLICK_DOWN — sharp negative Y acceleration spike.
     * Threshold: min Y < -18 m/s².
     */
    private fun detectFlickDown(): Gesture? {
        val THRESHOLD = -18f
        val recent = accelWindow.takeLast(10)
        val trough = recent.minOf { it[1] }
        return if (trough < THRESHOLD) Gesture.FLICK_DOWN else null
    }

    /**
     * SHAKE — oscillating X acceleration: multiple zero-crossings, high variance.
     * Detects left-right wrist shake.
     * Thresholds: ≥3 zero-crossings AND variance > 25 m²/s⁴.
     */
    private fun detectShake(): Gesture? {
        val CROSSING_MIN = 3
        val VARIANCE_MIN = 25f
        val xs = accelWindow.takeLast(20).map { it[0] }
        val crossings = xs.zipWithNext().count { (a, b) -> a * b < 0 }
        val mean = xs.average().toFloat()
        val variance = xs.map { (it - mean) * (it - mean) }.average().toFloat()
        return if (crossings >= CROSSING_MIN && variance > VARIANCE_MIN) Gesture.SHAKE else null
    }

    /**
     * DOUBLE_TAP — two sharp Z-axis impacts within DOUBLE_TAP_WINDOW_MS.
     * This is a physical watch-face tap gesture, not a touch UI input.
     * Threshold: |Z| > 22 m/s².
     */
    private fun detectDoubleTap(z: Float, ts: Long): Gesture? {
        val THRESHOLD = 22f
        if (abs(z) > THRESHOLD) {
            val dt = ts - lastTapTs
            lastTapTs = ts
            if (dt in 80L..DOUBLE_TAP_WINDOW_MS) return Gesture.DOUBLE_TAP
        }
        return null
    }

    /**
     * TWIST_CW — sustained positive gyroscope Z rotation.
     * Rotating the forearm clockwise (palm facing down → palm facing up).
     * Threshold: mean gyro-Z > 2.5 rad/s over last 15 samples.
     */
    private fun detectTwistCW(): Gesture? {
        val THRESHOLD = 2.5f
        val recent = gyroWindow.takeLast(15)
        val mean = recent.map { it[2] }.average().toFloat()  // Z axis = forearm roll
        return if (mean > THRESHOLD) Gesture.TWIST_CW else null
    }

    /**
     * TWIST_CCW — sustained negative gyroscope Z rotation.
     * Threshold: mean gyro-Z < -2.5 rad/s.
     */
    private fun detectTwistCCW(): Gesture? {
        val THRESHOLD = -2.5f
        val recent = gyroWindow.takeLast(15)
        val mean = recent.map { it[2] }.average().toFloat()
        return if (mean < THRESHOLD) Gesture.TWIST_CCW else null
    }

    /** Magnitude helper. */
    @Suppress("unused")
    private fun magnitude(x: Float, y: Float, z: Float) = sqrt(x*x + y*y + z*z)
}
