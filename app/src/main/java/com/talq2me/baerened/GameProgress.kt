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
        val profile = SettingsManager.readProfile(context) ?: "AM"
        val progressManager = DailyProgressManager(context)
        val index = progressManager.getGameIndexFromCache(profile, launchId)
        android.util.Log.d("GameProgress", "getCurrentIndex for $launchId: $index (from DB-backed session)")
        return index
    }

    /** RPC + DB refetch — call only from a coroutine (never block the main thread). */
    suspend fun saveIndex(index: Int): Result<Unit> {
        val profile = SettingsManager.readProfile(context) ?: "AM"
        return DailyProgressManager(context).updateGameIndexInDbSync(profile, launchId, index)
    }

    suspend fun resetProgress() {
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
        val profile = SettingsManager.readProfile(context) ?: "AM"
        val index = DailyProgressManager(context).getGameIndexFromCache(profile, gameId)
        android.util.Log.d("WebGameProgress", "getCurrentIndex for $gameId: $index (from DB-backed session)")
        return index
    }

    suspend fun saveIndex(index: Int): Result<Unit> {
        val profile = SettingsManager.readProfile(context) ?: "AM"
        return DailyProgressManager(context).updateGameIndexInDbSync(profile, gameId, index)
    }

    fun getProgressData(): Map<String, Int> {
        val index = getCurrentIndex()
        return mapOf(gameId to index)
    }
}
