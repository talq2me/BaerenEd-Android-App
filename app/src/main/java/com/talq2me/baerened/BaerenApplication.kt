package com.talq2me.baerened

import android.app.Application
import android.util.Log

/**
 * Application entry point. Pre-warms TTS at startup so games (e.g. Spelling OCR)
 * can speak immediately without a long cold start.
 * Registers global single-item sync callback so progress updates go to DB via RPCs (no full JSON upload).
 */
class BaerenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BaerenApplication onCreate - pre-warming TTS")
        TtsManager.init(this)
        val appContext = applicationContext
        // No full-upload: all writes go via RPCs inside [DailyProgressManager.applyRpcChainThenRefetch], then DB refetch.
        DailyProgressManager.setGlobalUploadCallback { _, _ -> }
        DailyProgressManager.setGlobalSyncSingleItemCallback { _, _ -> }
    }

    companion object {
        private const val TAG = "BaerenApplication"
    }
}
