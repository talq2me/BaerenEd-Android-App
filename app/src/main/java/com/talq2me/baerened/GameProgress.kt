package com.talq2me.baerened

import android.content.Context

// GameProgress.kt
class GameProgress(private val context: Context, private val launchId: String) {
    private val prefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)

    fun getCurrentIndex(): Int = prefs.getInt("progress_$launchId", 0)

    fun saveIndex(index: Int) {
        prefs.edit().putInt("progress_$launchId", index).apply()
    }

    fun resetProgress() {
        saveIndex(0)
    }
}
