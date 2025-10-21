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

    /**
     * Comprehensive progress report including time tracking and detailed analytics
     */
    data class ComprehensiveProgressReport(
        val date: String,
        val earnedCoins: Int,
        val totalCoins: Int,
        val earnedStars: Int,
        val totalStars: Int,
        val completionRate: Double,
        val totalTimeMinutes: Double,
        val gamesPlayed: Int,
        val videosWatched: Int,
        val completedTasks: List<String>,
        val completedTaskNames: Map<String, String>, // taskId -> taskName mapping
        val gameSessions: List<TimeTracker.ActivitySession>,
        val videoSessions: List<TimeTracker.ActivitySession>,
        val completedGameSessions: List<TimeTracker.ActivitySession>,
        val averageGameTimeMinutes: Double,
        val averageVideoTimeMinutes: Double,
        val longestSessionMinutes: Double,
        val mostPlayedGame: String?,
        val totalSessions: Int
    )

    companion object {
        private const val PREF_NAME = "daily_progress_prefs"
        private const val KEY_COMPLETED_TASKS = "completed_tasks"
        private const val KEY_COMPLETED_TASK_NAMES = "completed_task_names"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"
        private const val KEY_TOTAL_POSSIBLE_STARS = "total_possible_stars"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
        private const val KEY_LAST_POKEMON_UNLOCK_DATE = "last_pokemon_unlock_date"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val DEFAULT_ADMIN_PIN = "1981" // Default PIN for first-time setup
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Gets the current date as a string for daily reset tracking
     */
    private fun getCurrentDateString(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        Log.d("DailyProgressManager", "Current date string: $date")
        return date
    }

    /**
     * Checks if we need to reset progress for a new day
     */
    private fun shouldResetProgress(): Boolean {
        val lastResetDate = prefs.getString(KEY_LAST_RESET_DATE, "")
        val currentDate = getCurrentDateString()
        val shouldReset = lastResetDate != currentDate
        Log.d("DailyProgressManager", "Reset check - Last: '$lastResetDate', Current: '$currentDate', Should reset: $shouldReset")
        return shouldReset
    }

    /**
     * Resets progress for a new day
     */
    private fun resetProgressForNewDay() {
        val currentDate = getCurrentDateString()
        Log.d("DailyProgressManager", "RESETTING progress for new day: $currentDate")
        prefs.edit()
            .putString(KEY_COMPLETED_TASKS, gson.toJson(emptyMap<String, Boolean>()))
            .putString(KEY_COMPLETED_TASK_NAMES, gson.toJson(emptyMap<String, String>()))
            .putString(KEY_LAST_RESET_DATE, currentDate)
            .apply()

        // Also reset video sequence progress for new day
        resetVideoSequenceProgress()

        Log.d("DailyProgressManager", "Progress reset completed for date: $currentDate")
    }

    /**
     * Resets video sequence progress for all video files
     */
    private fun resetVideoSequenceProgress() {
        val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
        videoPrefs.edit().clear().apply()
        Log.d("DailyProgressManager", "Reset video sequence progress for new day")
    }

    /**
     * Manually reset video sequence progress (for debugging)
     */
    fun resetVideoProgress() {
        resetVideoSequenceProgress()
    }

    /**
     * Manually reset all progress (for debugging or forced reset)
     */
    fun resetAllProgress() {
        resetProgressForNewDay()
    }

    /**
     * Gets the completion status map for today
     */
    private fun getCompletedTasks(): MutableMap<String, Boolean> {
        // Always check if we need to reset before returning data
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
     * Gets the completed task names map
     */
    private fun getCompletedTaskNames(): MutableMap<String, String> {
        // Always check if we need to reset before returning data
        if (shouldResetProgress()) {
            resetProgressForNewDay()
        }

        val json = prefs.getString(KEY_COMPLETED_TASK_NAMES, "{}")
        val type = object : TypeToken<MutableMap<String, String>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }

    /**
     * Saves a completed task name
     */
    private fun saveCompletedTaskName(taskId: String, taskName: String) {
        val completedTaskNames = getCompletedTaskNames()
        completedTaskNames[taskId] = taskName
        prefs.edit()
            .putString(KEY_COMPLETED_TASK_NAMES, gson.toJson(completedTaskNames))
            .apply()
    }

    /**
     * Marks a task as completed and returns the stars earned
     * For required/once-per-day tasks: only award once per day
     * For optional tasks: award each time completed
     */
    fun markTaskCompleted(taskId: String, stars: Int, isRequiredTask: Boolean = false): Int {
        return markTaskCompletedWithName(taskId, taskId, stars, isRequiredTask)
    }

    /**
     * Marks a task as completed with a display name and returns the stars earned
     */
    fun markTaskCompletedWithName(taskId: String, taskName: String, stars: Int, isRequiredTask: Boolean = false): Int {
        val completedTasks = getCompletedTasks()

        if (isRequiredTask) {
            // Required tasks: only award once per day
            if (completedTasks[taskId] != true) {
                completedTasks[taskId] = true
                saveCompletedTasks(completedTasks)
                saveCompletedTaskName(taskId, taskName)
                Log.d("DailyProgressManager", "Required task $taskId ($taskName) completed, earned $stars stars")
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
                saveCompletedTaskName(taskId, taskName)
                Log.d("DailyProgressManager", "Optional task $taskId ($taskName) completed, earned $stars stars")
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

    /**
     * Pokemon Collection Management
     */

    /**
     * Gets the current number of unlocked Pokemon for the current kid
     */
    fun getUnlockedPokemonCount(): Int {
        val kid = getCurrentKid()
        return prefs.getInt("${kid}_$KEY_POKEMON_UNLOCKED", 0)
    }

    /**
     * Sets the number of unlocked Pokemon for the current kid
     */
    fun setUnlockedPokemonCount(count: Int) {
        val kid = getCurrentKid()
        prefs.edit().putInt("${kid}_$KEY_POKEMON_UNLOCKED", count).apply()
    }

    /**
     * Gets the current kid identifier (A or B)
     */
    fun getCurrentKid(): String {
        return context.getSharedPreferences("child_profile", Context.MODE_PRIVATE)
            .getString("profile", "A") ?: "A"
    }

    /**
     * Checks if all coins have been earned today (for showing Pokemon button)
     */
    fun hasAllCoinsBeenEarned(config: MainContent): Boolean {
        val (earnedCoins, totalCoins) = getCurrentProgressWithCoinsAndStars(config).first
        return earnedCoins >= totalCoins && totalCoins > 0
    }

    /**
     * Gets the current admin PIN (creates default if none exists)
     */
    fun getAdminPin(): String {
        return prefs.getString(KEY_ADMIN_PIN, DEFAULT_ADMIN_PIN) ?: DEFAULT_ADMIN_PIN
    }

    /**
     * Sets a new admin PIN
     */
    fun setAdminPin(pin: String) {
        prefs.edit().putString(KEY_ADMIN_PIN, pin).apply()
    }

    /**
     * Validates admin PIN for Pokemon management
     */
    fun validateAdminPin(pin: String): Boolean {
        return pin == getAdminPin()
    }

    /**
     * Unlocks additional Pokemon (admin function)
     */
    fun unlockPokemon(count: Int): Boolean {
        val currentCount = getUnlockedPokemonCount()
        setUnlockedPokemonCount(currentCount + count)
        return true
    }

    /**
     * Gets the last unlocked Pokemon (for display)
     */
    fun getLastUnlockedPokemon(): String? {
        // This would need to be implemented based on how Pokemon are organized
        // For now, return null - will be implemented when we have Pokemon data structure
        return null
    }

    /**
     * Checks if a Pokemon was already unlocked today
     */
    fun wasPokemonUnlockedToday(): Boolean {
        val lastUnlockDate = prefs.getString(KEY_LAST_POKEMON_UNLOCK_DATE, "")
        val currentDate = getCurrentDateString()
        return lastUnlockDate == currentDate
    }

    /**
     * Records that a Pokemon was unlocked today
     */
    fun recordPokemonUnlockToday() {
        prefs.edit()
            .putString(KEY_LAST_POKEMON_UNLOCK_DATE, getCurrentDateString())
            .apply()
    }

    /**
     * Checks if all coins have been earned AND no Pokemon was unlocked today
     */
    fun shouldShowPokemonUnlockButton(config: MainContent): Boolean {
        return hasAllCoinsBeenEarned(config) && !wasPokemonUnlockedToday()
    }

    /**
     * Gets comprehensive progress report including time tracking
     */
    fun getComprehensiveProgressReport(config: MainContent, timeTracker: TimeTracker): ComprehensiveProgressReport {
        val progressData = getCurrentProgressWithCoinsAndStars(config)
        val (earnedCoins, totalCoins) = progressData.first
        val (earnedStars, totalStars) = progressData.second
        val completedTasks = getCompletedTasks()

        // Get the stored completed task names
        val completedTaskNames = getCompletedTaskNames()

        // Get time tracking data
        val todaySummary = timeTracker.getTodaySummary()
        val totalTimeMinutes = todaySummary.totalTimeMinutes
        val gamesPlayed = todaySummary.gamesPlayed
        val videosWatched = todaySummary.videosWatched

        // Calculate completion rates
        val completionRate = if (totalStars > 0) (earnedStars.toDouble() / totalStars.toDouble()) * 100 else 0.0

        // Get detailed session information
        val gameSessions = todaySummary.gameSessions
        val videoSessions = todaySummary.sessions.filter { it.activityType in listOf("video", "youtube") }
        val completedGameSessions = todaySummary.completedGameSessions

        return ComprehensiveProgressReport(
            date = getCurrentDateString(),
            earnedCoins = earnedCoins,
            totalCoins = totalCoins,
            earnedStars = earnedStars,
            totalStars = totalStars,
            completionRate = completionRate,
            totalTimeMinutes = totalTimeMinutes,
            gamesPlayed = gamesPlayed,
            videosWatched = videosWatched,
            completedTasks = completedTasks.filter { it.value }.keys.toList(),
            completedTaskNames = completedTaskNames,
            gameSessions = gameSessions,
            videoSessions = videoSessions,
            completedGameSessions = completedGameSessions,
            averageGameTimeMinutes = if (gameSessions.isNotEmpty()) gameSessions.map { it.durationMinutes }.average() else 0.0,
            averageVideoTimeMinutes = if (videoSessions.isNotEmpty()) videoSessions.map { it.durationMinutes }.average() else 0.0,
            longestSessionMinutes = todaySummary.sessions.maxOfOrNull { it.durationMinutes } ?: 0.0,
            mostPlayedGame = gameSessions.groupBy { it.activityName }.maxByOrNull { it.value.size }?.key,
            totalSessions = todaySummary.sessions.size
        )
    }

}
