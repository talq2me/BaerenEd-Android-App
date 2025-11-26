package com.talq2me.baerened

import android.content.Context

// GameProgress.kt
class GameProgress(private val context: Context, private val launchId: String) {
    private val prefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)

    private fun getStorageKey(): String {
        val currentKid = SettingsManager.readProfile(context) ?: "A"
        return "${currentKid}_progress_$launchId"
    }

    fun getCurrentIndex(): Int = prefs.getInt(getStorageKey(), 0)

    fun saveIndex(index: Int) {
        prefs.edit().putInt(getStorageKey(), index).apply()
    }

    fun resetProgress() {
        saveIndex(0)
    }
}
