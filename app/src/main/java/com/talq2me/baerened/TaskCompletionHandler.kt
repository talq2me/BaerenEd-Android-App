package com.talq2me.baerened

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Centralized task completion handler.
 * Writes go to the DB via RPCs in [DailyProgressManager], then session is refreshed from DB.
 */
class TaskCompletionHandler(
    private val context: Context,
    private val progressManager: DailyProgressManager
) {
    data class CompletionResult(
        val earnedStars: Int,
        val wasAlreadyCompleted: Boolean
    )

    /**
     * [Result.failure] if RPC or DB refetch failed — caller should surface the error.
     */
    fun handleCompletion(
        taskId: String,
        taskTitle: String,
        stars: Int,
        sectionId: String?
    ): Result<CompletionResult> {
        return runBlocking(Dispatchers.IO) {
            progressManager.markTaskCompletedWithName(
                taskId,
                taskTitle,
                stars,
                sectionId
            ).map { earnedStars ->
                CompletionResult(
                    earnedStars = earnedStars,
                    wasAlreadyCompleted = earnedStars == 0 && stars > 0
                )
            }
        }
    }

    fun handleCompletionWithBerries(
        taskId: String,
        taskTitle: String,
        stars: Int,
        sectionId: String?
    ): Result<CompletionResult> {
        return handleCompletion(taskId, taskTitle, stars, sectionId)
    }
}
