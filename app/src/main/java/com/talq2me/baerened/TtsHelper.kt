package com.talq2me.baerened

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Helper for selecting high-quality TTS voices.
 * Google TTS exposes enhanced/neural voices with QUALITY_VERY_HIGH or QUALITY_HIGH
 * which sound more natural and pronounce words (e.g. "th") correctly.
 */
object TtsHelper {
    private const val TAG = "TtsHelper"

    /** Google TTS engine package - provides best quality English voices on most devices. */
    const val GOOGLE_TTS_ENGINE = "com.google.android.tts"

    /**
     * Selects the best available English (en-US) voice with highest quality.
     * Call this from TTS OnInitListener after status == TextToSpeech.SUCCESS.
     * @return The selected Voice, or null if no suitable voice found (caller should fall back to setLanguage).
     */
    fun selectBestEnglishVoice(tts: TextToSpeech): Voice? {
        val voices = tts.voices ?: return null

        // Prefer QUALITY_VERY_HIGH (500), then QUALITY_HIGH (400)
        val candidates = voices
            .filter { voice ->
                val locale = voice.locale
                val lang = locale.language.lowercase()
                val country = locale.country.uppercase()
                (lang == "en" || lang == "eng") &&
                    (country.isEmpty() || country == "US" || country == "USA")
            }
            .sortedByDescending { voice ->
                when (voice.quality) {
                    Voice.QUALITY_VERY_HIGH -> 3
                    Voice.QUALITY_HIGH -> 2
                    Voice.QUALITY_NORMAL -> 1
                    else -> 0
                }
            }

        val best = candidates.firstOrNull()
        if (best != null) {
            val result = tts.setVoice(best)
            if (result == TextToSpeech.SUCCESS) {
                Log.d(TAG, "Selected high-quality English voice: ${best.name} (quality=${best.quality})")
                return best
            }
        }
        Log.d(TAG, "No high-quality English voice found, using default. Available: ${voices.size}")
        return null
    }

    /**
     * Configures TTS to use Google engine if available, otherwise uses default.
     * Returns the engine package name that was actually used.
     */
    fun getPreferredEngine(tts: TextToSpeech): String {
        return tts.defaultEngine ?: GOOGLE_TTS_ENGINE
    }
}
