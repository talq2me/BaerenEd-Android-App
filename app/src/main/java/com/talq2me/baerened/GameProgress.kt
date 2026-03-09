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
        // ONLINE-ONLY: Read from DB cache (progressDataAfterFetch); no local storage for game_indices.
        val profile = SettingsManager.readProfile(context) ?: "AM"
        val progressManager = DailyProgressManager(context)
        val index = progressManager.getGameIndexFromCache(profile, launchId)
        android.util.Log.d("GameProgress", "getCurrentIndex for $launchId: $index (from DB cache)")
        return index
    }

    fun saveIndex(index: Int) {
        // ONLINE-ONLY: Update DB cache only; sync to DB is done by caller (e.g. updateLocalTimestampAndSyncToCloud).
        val profile = SettingsManager.readProfile(context) ?: "AM"
        DailyProgressManager(context).updateGameIndexInCache(profile, launchId, index)
        android.util.Log.d("GameProgress", "saveIndex for $launchId: $index (cache only, sync by caller)")
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
        // ONLINE-ONLY: Read from DB cache; no local storage for game_indices.
        val profile = SettingsManager.readProfile(context) ?: "AM"
        val index = DailyProgressManager(context).getGameIndexFromCache(profile, gameId)
        android.util.Log.d("WebGameProgress", "getCurrentIndex for $gameId: $index (from DB cache)")
        return index
    }

    fun saveIndex(index: Int) {
        // ONLINE-ONLY: Update DB cache only; sync to DB is done by caller when sync runs.
        val profile = SettingsManager.readProfile(context) ?: "AM"
        DailyProgressManager(context).updateGameIndexInCache(profile, gameId, index)
        android.util.Log.d("WebGameProgress", "saveIndex for $gameId: $index (cache only)")
    }

    fun getProgressData(): Map<String, Int> {
        val index = getCurrentIndex()
        return mapOf(gameId to index)
    }
}
