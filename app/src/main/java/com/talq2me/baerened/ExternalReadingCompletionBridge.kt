package com.talq2me.baerened

import android.content.Context

/**
 * Single bridge point for external reading app completion checks.
 * Keeps timing/session logic out of Activities.
 */
object ExternalReadingCompletionBridge {
    sealed class Outcome {
        data object None : Outcome()
        data class TooSoon(val elapsedMs: Long) : Outcome()
        data class NotEnoughTime(val elapsedSeconds: Long, val minSeconds: Long) : Outcome()
        data class Complete(
            val taskId: String,
            val taskTitle: String,
            val stars: Int,
            val sectionId: String?,
            val elapsedSeconds: Long
        ) : Outcome()
    }

    private data class Spec(
        val prefsName: String,
        val keyStartTime: String,
        val keyTaskId: String,
        val keyTaskTitle: String,
        val keyStars: String,
        val keySectionId: String,
        val minSeconds: Long
    )

    private val readAlongSpec = Spec(
        prefsName = "read_along_session",
        keyStartTime = "read_along_start_time",
        keyTaskId = "read_along_task_id",
        keyTaskTitle = "read_along_task_title",
        keyStars = "read_along_stars",
        keySectionId = "read_along_section_id",
        minSeconds = 30L
    )

    private val boukiliSpec = Spec(
        prefsName = "boukili_session",
        keyStartTime = "boukili_start_time",
        keyTaskId = "boukili_task_id",
        keyTaskTitle = "boukili_task_title",
        keyStars = "boukili_stars",
        keySectionId = "boukili_section_id",
        minSeconds = 30L
    )

    fun checkReadAlong(context: Context): Outcome = check(context, readAlongSpec)

    fun checkBoukili(context: Context): Outcome = check(context, boukiliSpec)

    fun clearReadAlong(context: Context) = clear(context, readAlongSpec)

    fun clearBoukili(context: Context) = clear(context, boukiliSpec)

    private fun check(context: Context, spec: Spec): Outcome {
        val prefs = context.getSharedPreferences(spec.prefsName, Context.MODE_PRIVATE)
        val startTimeMs = prefs.getLong(spec.keyStartTime, -1L)
        if (startTimeMs <= 0L) return Outcome.None

        val taskId = prefs.getString(spec.keyTaskId, null) ?: return Outcome.None
        val taskTitle = prefs.getString(spec.keyTaskTitle, taskId) ?: taskId
        val stars = prefs.getInt(spec.keyStars, 0)
        val sectionId = prefs.getString(spec.keySectionId, null)?.takeIf { it.isNotBlank() }

        val elapsedMs = System.currentTimeMillis() - startTimeMs
        if (elapsedMs < 2000L) return Outcome.TooSoon(elapsedMs)

        val elapsedSeconds = elapsedMs / 1000L
        if (elapsedSeconds < spec.minSeconds) {
            return Outcome.NotEnoughTime(elapsedSeconds = elapsedSeconds, minSeconds = spec.minSeconds)
        }

        return Outcome.Complete(
            taskId = taskId,
            taskTitle = taskTitle,
            stars = stars,
            sectionId = sectionId,
            elapsedSeconds = elapsedSeconds
        )
    }

    private fun clear(context: Context, spec: Spec) {
        context.getSharedPreferences(spec.prefsName, Context.MODE_PRIVATE)
            .edit()
            .remove(spec.keyStartTime)
            .remove(spec.keyTaskId)
            .remove(spec.keyTaskTitle)
            .remove(spec.keyStars)
            .remove(spec.keySectionId)
            .apply()
    }
}
