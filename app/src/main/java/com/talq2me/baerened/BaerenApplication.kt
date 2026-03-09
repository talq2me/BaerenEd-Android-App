package com.talq2me.baerened

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        // No full-upload: all writes go via RPCs (SingleItemUpdate). Kept for compatibility so callers need not change.
        DailyProgressManager.setGlobalUploadCallback { _, _ -> }
        DailyProgressManager.setGlobalSyncSingleItemCallback { _, update ->
            CoroutineScope(Dispatchers.IO).launch {
                val sync = CloudSyncService()
                when (update) {
                    is DailyProgressManager.SingleItemUpdate.RequiredTask ->
                        sync.invokeAfUpdateRequiredTask(
                            update.profile, update.taskTitle,
                            update.status, update.correct, update.incorrect, update.questions
                        )
                    is DailyProgressManager.SingleItemUpdate.PracticeTask ->
                        sync.invokeAfUpdatePracticeTask(
                            update.profile, update.taskTitle,
                            update.timesCompleted, update.stars,
                            update.correct, update.incorrect, update.questionsAnswered
                        )
                    is DailyProgressManager.SingleItemUpdate.BonusTask ->
                        sync.invokeAfUpdateBonusTask(
                            update.profile, update.taskTitle,
                            update.timesCompleted, update.stars,
                            update.correct, update.incorrect, update.questionsAnswered
                        )
                    is DailyProgressManager.SingleItemUpdate.ChecklistItem ->
                        sync.invokeAfUpdateChecklistItem(update.profile, update.itemLabel, update.done)
                    is DailyProgressManager.SingleItemUpdate.Chore ->
                        sync.invokeAfUpdateChore(update.profile, update.choreId, update.done)
                    is DailyProgressManager.SingleItemUpdate.GameIndex ->
                        sync.invokeAfUpdateGameIndex(update.profile, update.gameKey, update.index)
                    is DailyProgressManager.SingleItemUpdate.PokemonUnlocked ->
                        sync.invokeAfUpdatePokemonUnlocked(update.profile, update.pokemonUnlocked)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BaerenApplication"
    }
}
