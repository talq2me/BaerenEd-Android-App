package com.talq2me.baerened

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Centralized task completion handler
 * Handles marking tasks as completed and syncing to cloud
 */
class TaskCompletionHandler(
    private val context: Context,
    private val progressManager: DailyProgressManager,
    private val cloudStorageManager: CloudStorageManager? = null
) {
    companion object {
        private const val TAG = "TaskCompletionHandler"
    }

    /**
     * Result of task completion
     */
    data class CompletionResult(
        val earnedStars: Int,
        val wasAlreadyCompleted: Boolean
    )

    /**
     * Handles task completion
     */
    fun handleCompletion(
        taskId: String,
        taskTitle: String,
        stars: Int,
        sectionId: String?,
        config: MainContent? = null
    ): CompletionResult {
        val isRequiredTask = sectionId == "required"
        
        val earnedStars = progressManager.markTaskCompletedWithName(
            taskId,
            taskTitle,
            stars,
            isRequiredTask,
            config,
            sectionId
        )
        
        // Sync to cloud if enabled
        cloudStorageManager?.let { manager ->
            val profile = SettingsManager.readProfile(context) ?: "AM"
            CoroutineScope(Dispatchers.IO).launch {
                manager.saveIfEnabled(profile)
            }
        }
        
        return CompletionResult(
            earnedStars = earnedStars,
            wasAlreadyCompleted = earnedStars == 0 && stars > 0
        )
    }

    /**
     * Handles task completion with berries (for battle hub)
     */
    fun handleCompletionWithBerries(
        taskId: String,
        taskTitle: String,
        stars: Int,
        sectionId: String?,
        config: MainContent? = null
    ): CompletionResult {
        val result = handleCompletion(taskId, taskTitle, stars, sectionId, config)
        
        // Add berries for required/optional tasks
        if (sectionId == "required" || sectionId == "optional") {
            if (result.earnedStars > 0) {
                progressManager.addEarnedBerries(result.earnedStars)
                Log.d(TAG, "Added ${result.earnedStars} berries from task completion")
            }
        }
        
        return result
    }
}
