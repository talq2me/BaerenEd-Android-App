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
        val index = prefs.getInt(key, 0)
        android.util.Log.d("GameProgress", "getCurrentIndex for $key: $index")
        return index
    }

    fun saveIndex(index: Int) {
        val key = getStorageKey()
        prefs.edit().putInt(key, index).apply()
        android.util.Log.d("GameProgress", "saveIndex for $key: $index")
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
        prefs.edit().putInt(key, index).apply()
        android.util.Log.d("WebGameProgress", "saveIndex for $key: $index")
    }

    fun getProgressData(): Map<String, Int> {
        val key = getStorageKey()
        val index = prefs.getInt(key, 0)
        return mapOf(gameId to index)
    }
}
