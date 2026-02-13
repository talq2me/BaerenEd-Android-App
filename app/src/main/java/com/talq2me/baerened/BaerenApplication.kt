package com.talq2me.baerened

import android.app.Application
import android.util.Log

/**
 * Application entry point. Pre-warms TTS at startup so games (e.g. Spelling OCR)
 * can speak immediately without a long cold start.
 */
class BaerenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BaerenApplication onCreate - pre-warming TTS")
        TtsManager.init(this)
    }

    companion object {
        private const val TAG = "BaerenApplication"
    }
}
