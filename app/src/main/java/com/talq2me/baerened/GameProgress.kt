package com.talq2me.baerened

import android.content.Context

// GameProgress.kt
class GameProgress(private val context: Context, private val launchId: String) {
    private val prefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)

    private fun getStorageKey(): String {
        val currentKid = SettingsManager.readProfile(context) ?: "AM"
        return "${currentKid}_progress_$launchId"
    }

    fun getCurrentIndex(): Int {
        val key = getStorageKey()
        // NOTE: Cloud game_indices are applied to local storage by CloudDataApplier during sync.
        // We load from local storage here. Cloud sync should happen before game launch (in onResume).
        // If cloud has newer data, it will be applied to local storage first.
        val index = prefs.getInt(key, 0)
        android.util.Log.d("GameProgress", "getCurrentIndex for $key: $index (loaded from local, which should be synced from cloud)")
        return index
    }

    fun saveIndex(index: Int) {
        val key = getStorageKey()
        // CRITICAL: Use commit() for synchronous write to prevent race conditions
        val success = prefs.edit().putInt(key, index).commit()
        if (!success) {
            android.util.Log.e("GameProgress", "CRITICAL ERROR: Failed to save game index!")
        }
        android.util.Log.d("GameProgress", "saveIndex for $key: $index (saved synchronously)")
    }

    fun resetProgress() {
        saveIndex(0)
    }
}

// WebGameProgress.kt - For web-based games that need progress tracking
class WebGameProgress(private val context: Context, private val gameId: String) {
    private val prefs = context.getSharedPreferences("web_game_progress", Context.MODE_PRIVATE)

    private fun getStorageKey(): String {
        val currentKid = SettingsManager.readProfile(context) ?: "AM"
        return "${currentKid}_web_progress_$gameId"
    }

    fun getCurrentIndex(): Int {
        val key = getStorageKey()
        val index = prefs.getInt(key, 0)
        android.util.Log.d("WebGameProgress", "getCurrentIndex for $key: $index")
        return index
    }

    fun saveIndex(index: Int) {
        val key = getStorageKey()
        // Use commit() so index is persisted before any finish() - matches GameProgress and ensures cloud sync gets it
        val ok = prefs.edit().putInt(key, index).commit()
        if (!ok) {
            android.util.Log.e("WebGameProgress", "saveIndex failed for $key")
        } else {
            android.util.Log.d("WebGameProgress", "saveIndex for $key: $index")
        }
    }

    fun getProgressData(): Map<String, Int> {
        val key = getStorageKey()
        val index = prefs.getInt(key, 0)
        return mapOf(gameId to index)
    }
}
