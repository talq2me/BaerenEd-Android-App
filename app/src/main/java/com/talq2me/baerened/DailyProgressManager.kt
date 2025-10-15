package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages daily progress tracking for games and tasks.
 * Tracks completion status and star earnings on a per-day basis.
 */
class DailyProgressManager(private val context: Context) {

    companion object {
        private const val PREF_NAME = "daily_progress_prefs"
        private const val KEY_COMPLETED_TASKS = "completed_tasks"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"
        private const val KEY_TOTAL_POSSIBLE_STARS = "total_possible_stars"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Gets the current date as a string for daily reset tracking
     */
    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Checks if we need to reset progress for a new day
     */
    private fun shouldResetProgress(): Boolean {
        val lastResetDate = prefs.getString(KEY_LAST_RESET_DATE, "")
        val currentDate = getCurrentDateString()
        return lastResetDate != currentDate
    }

    /**
     * Resets progress for a new day
     */
    private fun resetProgressForNewDay() {
        prefs.edit()
            .putString(KEY_COMPLETED_TASKS, gson.toJson(emptyMap<String, Boolean>()))
            .putString(KEY_LAST_RESET_DATE, getCurrentDateString())
            .apply()
        Log.d("DailyProgressManager", "Reset progress for new day: ${getCurrentDateString()}")
    }

    /**
     * Gets the completion status map for today
     */
    private fun getCompletedTasks(): MutableMap<String, Boolean> {
        if (shouldResetProgress()) {
            resetProgressForNewDay()
        }

        val json = prefs.getString(KEY_COMPLETED_TASKS, "{}")
        val type = object : TypeToken<MutableMap<String, Boolean>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }

    /**
     * Saves the completion status map
     */
    private fun saveCompletedTasks(completedTasks: Map<String, Boolean>) {
        prefs.edit()
            .putString(KEY_COMPLETED_TASKS, gson.toJson(completedTasks))
            .apply()
    }

    /**
     * Marks a task as completed and returns the stars earned
     * For required/once-per-day tasks: only award once per day
     * For optional tasks: award each time completed
     */
    fun markTaskCompleted(taskId: String, stars: Int, isRequiredTask: Boolean = false): Int {
        val completedTasks = getCompletedTasks()

        if (isRequiredTask) {
            // Required tasks: only award once per day
            if (completedTasks[taskId] != true) {
                completedTasks[taskId] = true
                saveCompletedTasks(completedTasks)
                Log.d("DailyProgressManager", "Required task $taskId completed, earned $stars stars")
                return stars
            }
            Log.d("DailyProgressManager", "Required task $taskId already completed today")
            return 0
        } else {
            // Optional tasks: award each time completed, but still track for progress calculation
            // We track completion status for all tasks to enable proper progress calculation
            if (completedTasks[taskId] != true) {
                completedTasks[taskId] = true
                saveCompletedTasks(completedTasks)
                Log.d("DailyProgressManager", "Optional task $taskId completed, earned $stars stars")
                return stars
            } else {
                // For optional tasks that are already completed today, still award stars
                Log.d("DailyProgressManager", "Optional task $taskId already completed today, but awarding $stars stars again")
                return stars
            }
        }
    }

    /**
     * Checks if a task is completed today
     */
    fun isTaskCompleted(taskId: String): Boolean {
        return getCompletedTasks()[taskId] == true
    }

    /**
     * Gets current progress (earned stars / total possible stars)
     */
    fun getCurrentProgress(totalPossibleStars: Int): Pair<Int, Int> {
        val completedTasks = getCompletedTasks()
        val earnedStars = completedTasks.values.count { it } // Count completed tasks
        return Pair(earnedStars, totalPossibleStars)
    }

    /**
     * Gets current progress with actual star values from config
     */
    fun getCurrentProgressWithActualStars(config: MainContent): Pair<Int, Int> {
        val completedTasks = getCompletedTasks()
        var earnedStars = 0

        config.sections?.forEach { section ->
            section.tasks?.forEach { task ->
                val taskId = task.launch ?: "unknown_task"
                val stars = task.stars ?: 0

                // All tasks contribute to total stars in config
                // For progress tracking, count if completed today (required tasks only once, optional could be multiple but we track once per day for now)
                if (completedTasks[taskId] == true && stars > 0) {
                    earnedStars += stars
                }
            }

            section.items?.forEach { item ->
                val itemId = item.id ?: "checkbox_${item.label}"
                val stars = item.stars ?: 0

                // All checklist items contribute to total stars in config
                // For progress tracking, count if completed today
                if (completedTasks[itemId] == true && stars > 0) {
                    earnedStars += stars
                }
            }
        }

        val (totalCoins, totalStars) = calculateTotalsFromConfig(config)
        return Pair(earnedStars, totalStars)
    }

    /**
     * Calculates total possible coins and stars from config
     */
    fun calculateTotalsFromConfig(config: MainContent): Pair<Int, Int> {
        var totalCoins = 0
        var totalStars = 0

        config.sections?.forEach { section ->
            section.tasks?.forEach { task ->
                val stars = task.stars ?: 0

                // Required tasks award coins equal to their stars
                if (section.id == "required") {
                    totalCoins += stars
                }

                // All tasks with stars contribute to total stars
                if (stars > 0) {
                    totalStars += stars
                }
            }

            // Check for checklist items if they have stars
            section.items?.forEach { item ->
                val stars = item.stars ?: 0

                // ALL checklist items award coins equal to their stars (not just required ones)
                if (stars > 0) {
                    totalCoins += stars
                    totalStars += stars
                }
            }
        }

        // Cache the totals for performance
        prefs.edit()
            .putInt("total_coins", totalCoins)
            .putInt(KEY_TOTAL_POSSIBLE_STARS, totalStars)
            .apply()

        Log.d("DailyProgressManager", "Calculated totals from config - Coins: $totalCoins, Stars: $totalStars")
        return Pair(totalCoins, totalStars)
    }

    /**
     * Force recalculation of totals from config (for debugging)
     */
    fun recalculateTotalsFromConfig(config: MainContent) {
        Log.d("DailyProgressManager", "Force recalculating totals from config")
        calculateTotalsFromConfig(config)
    }

    /**
     * Gets current progress with both coins and stars
     */
    fun getCurrentProgress(totalCoins: Int, totalStars: Int): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val completedTasks = getCompletedTasks()
        var earnedCoins = 0
        var earnedStars = 0

        // We need the config to calculate actual earned amounts
        // This method will be called with the config available
        return Pair(Pair(earnedCoins, totalCoins), Pair(earnedStars, totalStars))
    }

    /**
     * Gets current progress with both coins and stars using actual values
     */
    fun getCurrentProgressWithCoinsAndStars(config: MainContent): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val completedTasks = getCompletedTasks()
        var earnedCoins = 0
        var earnedStars = 0

        config.sections?.forEach { section ->
            section.tasks?.forEach { task ->
                val taskId = task.launch ?: "unknown_task"
                val stars = task.stars ?: 0

                if (completedTasks[taskId] == true && stars > 0) {
                    earnedStars += stars

                    // Required tasks award coins equal to their stars
                    if (section.id == "required") {
                        earnedCoins += stars
                    }
                }
            }

            section.items?.forEach { item ->
                val itemId = item.id ?: "checkbox_${item.label}"
                val stars = item.stars ?: 0

                if (completedTasks[itemId] == true && stars > 0) {
                    earnedStars += stars
                    earnedCoins += stars  // ALL checklist items award coins
                }
            }
        }

        val (totalCoins, totalStars) = calculateTotalsFromConfig(config)
        return Pair(Pair(earnedCoins, totalCoins), Pair(earnedStars, totalStars))
    }

    /**
     * Gets current progress with totals for display (fallback when config not available)
     */
    fun getCurrentProgressWithTotals(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val totalCoins = prefs.getInt("total_coins", 0)
        val totalStars = prefs.getInt(KEY_TOTAL_POSSIBLE_STARS, 0)

        // For fallback, we need to calculate earned amounts based on actual star values
        // Since we don't have config, we'll use a simple approximation
        val completedTasks = getCompletedTasks()
        var earnedCoins = 0
        var earnedStars = 0

        // This is approximate - in a real implementation, we'd need to track star values per task
        // For now, just count completed tasks as 1 star each
        completedTasks.values.forEach { if (it) earnedStars += 1 }

        return Pair(Pair(earnedCoins, totalCoins), Pair(earnedStars, totalStars))
    }

    /**
     * Gets current progress with totals for display (with config for accurate calculation)
     */
    fun getCurrentProgressWithTotals(config: MainContent): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        return getCurrentProgressWithCoinsAndStars(config)
    }

    /**
     * Gets cached total possible stars (for when config isn't available)
     */
    fun getCachedTotalPossibleStars(): Int {
        return prefs.getInt(KEY_TOTAL_POSSIBLE_STARS, 10) // Default to 10 if not calculated
    }

    /**
     * Gets the date of the last reset for debugging
     */
    fun getLastResetDate(): String {
        return prefs.getString(KEY_LAST_RESET_DATE, "Never") ?: "Never"
    }
}
