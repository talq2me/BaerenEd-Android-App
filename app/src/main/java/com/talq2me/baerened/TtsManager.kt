package com.talq2me.baerened

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Shared TTS instance so the engine is initialized once at app startup and reused.
 * Activities (e.g. Spelling OCR) can speak immediately without waiting for engine init.
 * Call [init] from Application.onCreate(); then use [speak] and [stop] from activities.
 */
object TtsManager {
    private const val TAG = "TtsManager"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private val onReadyListeners = mutableListOf<Runnable>()

    @Volatile
    private var utteranceProgressListener: UtteranceProgressListener? = null

    /**
     * Call from Application.onCreate() with application context.
     * Initializes TTS and pre-warms English and French so first speak is fast.
     */
    @Synchronized
    fun init(context: Context) {
        if (tts != null) return
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS initialization failed")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            // Prefer high-quality English voice
            if (TtsHelper.selectBestEnglishVoice(engine) == null) {
                engine.setLanguage(Locale.ENGLISH)
            }
            // Slightly slower than default (1.0) so speech is easier to follow
            engine.setSpeechRate(0.85f)
            ready = true
            utteranceProgressListener?.let { engine.setOnUtteranceProgressListener(it) }
            Log.d(TAG, "TTS initialized (English pre-warmed)")
            // Pre-warm French so Spelling OCR French is fast on first use
            try {
                val frResult = engine.setLanguage(Locale.FRENCH)
                if (frResult == TextToSpeech.LANG_AVAILABLE || frResult == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                    engine.speak(" ", TextToSpeech.QUEUE_FLUSH, null, "tts_prewarm_fr")
                    Log.d(TAG, "TTS French pre-warmed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "French pre-warm skipped: ${e.message}")
            }
            // Notify listeners (e.g. SpellingOCRActivity waiting to speak)
            synchronized(onReadyListeners) {
                onReadyListeners.forEach { it.run() }
                onReadyListeners.clear()
            }
        }
    }

    fun isReady(): Boolean = ready

    /**
     * Run [action] when TTS is ready (immediately if already ready).
     * Must be called from main thread; [action] will run on main thread.
     */
    fun whenReady(action: Runnable) {
        if (ready) {
            action.run()
            return
        }
        synchronized(onReadyListeners) {
            if (ready) {
                action.run()
                return
            }
            onReadyListeners.add(action)
        }
    }

    /**
     * Speak text with the given locale. Safe to call from main thread only.
     * If TTS is not ready yet, this no-ops (caller should use [whenReady] to speak once ready).
     */
    fun speak(text: String, locale: Locale, queueMode: Int = TextToSpeech.QUEUE_FLUSH, utteranceId: String? = null) {
        val engine = tts ?: return
        if (!ready) return
        try {
            val setResult = engine.setLanguage(locale)
            if (setResult == TextToSpeech.LANG_MISSING_DATA || setResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS locale not available: $locale")
            }
            engine.speak(text, queueMode, null, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error: ${e.message}", e)
        }
    }

    /** Stop current and queued speech. Does not shutdown the engine. */
    fun stop() {
        tts?.stop()
    }

    /**
     * Set utterance progress listener (e.g. for GameActivity to play audio after TTS).
     * Only one listener is active; set to null when activity is destroyed.
     */
    @Synchronized
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?) {
        utteranceProgressListener = listener
        tts?.setOnUtteranceProgressListener(listener)
    }

    /** Shutdown TTS (e.g. from Application). Normally not needed; engine is reused. */
    fun shutdown() {
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
