package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Handles one-time migration from old format (task IDs → Boolean) 
 * to new format (task names → TaskProgress)
 * 
 * Old format:
 * - Key: "{profile}_completed_tasks" → Map<String, Boolean> (task IDs)
 * - Key: "{profile}_completed_task_names" → Map<String, String> (task ID → name)
 * 
 * New format:
 * - Key: "{profile}_required_tasks" → Map<String, TaskProgress> (task names)
 * - No separate names map needed (names are keys)
 */
object TaskProgressMigration {
    private const val TAG = "TaskProgressMigration"
    private const val MIGRATION_FLAG_KEY = "format_unified_to_cloud_format"
    private val gson = Gson()

    /**
     * Checks if migration has been completed for a profile
     */
    fun isMigrated(context: Context, profile: String): Boolean {
        val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("${profile}_$MIGRATION_FLAG_KEY", false)
    }

    /**
     * Performs migration from old format to new format
     * Converts: Map<taskId, Boolean> + Map<taskId, taskName> → Map<taskName, TaskProgress>
     */
    fun migrateIfNeeded(context: Context, profile: String): Boolean {
        if (isMigrated(context, profile)) {
            Log.d(TAG, "Migration already completed for profile: $profile")
            return true
        }

        Log.d(TAG, "Starting migration for profile: $profile")
        val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)

        try {
            // Read old format
            val oldCompletedTasksKey = "${profile}_completed_tasks"
            val oldTaskNamesKey = "${profile}_completed_task_names"
            
            val oldCompletedTasksJson = prefs.getString(oldCompletedTasksKey, "{}") ?: "{}"
            val oldTaskNamesJson = prefs.getString(oldTaskNamesKey, "{}") ?: "{}"

            val oldCompletedTasks: Map<String, Boolean> = gson.fromJson(
                oldCompletedTasksJson,
                object : TypeToken<Map<String, Boolean>>() {}.type
            ) ?: emptyMap()

            val oldTaskNames: Map<String, String> = gson.fromJson(
                oldTaskNamesJson,
                object : TypeToken<Map<String, String>>() {}.type
            ) ?: emptyMap()

            Log.d(TAG, "Old format - completed tasks: ${oldCompletedTasks.size}, task names: ${oldTaskNames.size}")

            if (oldCompletedTasks.isEmpty() && oldTaskNames.isEmpty()) {
                // No old data to migrate - just mark as migrated
                Log.d(TAG, "No old data to migrate, marking as migrated")
                prefs.edit()
                    .putBoolean("${profile}_$MIGRATION_FLAG_KEY", true)
                    .apply()
                return true
            }

            // Load config to map task IDs to task names
            val contentUpdateService = ContentUpdateService()
            val configJson = contentUpdateService.getCachedMainContent(context)
            val config = if (!configJson.isNullOrEmpty()) {
                gson.fromJson(configJson, MainContent::class.java)
            } else {
                Log.w(TAG, "No config available for migration - will use existing task names map")
                null
            }

            // Convert old format to new format
            val newRequiredTasks = mutableMapOf<String, TaskProgress>()

            oldCompletedTasks.forEach { (taskId, isCompleted) ->
                // Find task name
                val taskName = oldTaskNames[taskId] 
                    ?: findTaskNameFromConfig(taskId, config)
                    ?: taskId // Fallback to taskId if name not found

                // Get task from config to get stars value
                val task = findTaskFromConfig(taskId, config)
                // Create TaskProgress from Boolean
                val taskProgress = TaskProgress(
                    status = if (isCompleted) "complete" else "incomplete",
                    // We don't have answer data in old format, so leave null
                    correct = null,
                    incorrect = null,
                    questions = null,
                    stars = task?.stars, // Get stars from config if available
                    // We don't have visibility data in old format
                    showdays = null,
                    hidedays = null,
                    displayDays = null,
                    disable = null
                )

                newRequiredTasks[taskName] = taskProgress
                Log.d(TAG, "Migrated task: $taskId ($taskName) -> status=${taskProgress.status}")
            }

            // Save new format
            val newRequiredTasksKey = "${profile}_required_tasks"
            prefs.edit()
                .putString(newRequiredTasksKey, gson.toJson(newRequiredTasks))
                .putBoolean("${profile}_$MIGRATION_FLAG_KEY", true)
                .apply()

            Log.d(TAG, "Migration completed for profile: $profile - migrated ${newRequiredTasks.size} tasks")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error during migration for profile: $profile", e)
            return false
        }
    }

    /**
     * Finds task name from config by task ID (task.launch)
     */
    private fun findTaskNameFromConfig(taskId: String, config: MainContent?): String? {
        if (config == null) return null

        return config.sections?.flatMap { it.tasks?.filterNotNull() ?: emptyList() }
            ?.find { (it.launch ?: "") == taskId }
            ?.title
    }

    /**
     * Finds task from config by task ID (task.launch) to get star value
     */
    private fun findTaskFromConfig(taskId: String, config: MainContent?): Task? {
        if (config == null) return null

        return config.sections?.flatMap { it.tasks?.filterNotNull() ?: emptyList() }
            ?.find { (it.launch ?: "") == taskId }
    }
}
