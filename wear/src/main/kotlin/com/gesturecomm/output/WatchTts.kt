package com.gesturecomm.output

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks phrases directly on the watch using the built-in TTS engine.
 * No phone connection required.
 */
object WatchTts {

    private const val TAG = "WatchTts"
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                ready = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (ready) {
                    tts?.setSpeechRate(0.9f)
                    tts?.setPitch(1.0f)
                    Log.d(TAG, "Watch TTS ready")
                } else {
                    Log.e(TAG, "TTS language not supported on this watch")
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speak(phrase: String) {
        if (!ready || tts == null) {
            Log.w(TAG, "TTS not ready — retrying in 600ms")
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ speak(phrase) }, 600)
            return
        }
        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "gc_${System.currentTimeMillis()}")
        Log.d(TAG, "Speaking: $phrase")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
