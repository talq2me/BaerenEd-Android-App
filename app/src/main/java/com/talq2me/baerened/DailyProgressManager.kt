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
        val totalTimeMinutes: Int,
        val gamesPlayed: Int,
        val videosWatched: Int,
        val completedTasks: List<String>,
        val completedTaskNames: Map<String, String>, // taskId -> taskName mapping
        val gameSessions: List<TimeTracker.ActivitySession>,
        val videoSessions: List<TimeTracker.ActivitySession>,
        val completedGameSessions: List<TimeTracker.ActivitySession>,
        val averageGameTimeMinutes: Int,
        val averageVideoTimeMinutes: Int,
        val longestSessionMinutes: Int,
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
            .putInt("banked_reward_minutes", 0) // Reset reward bank for new day
            .apply()

        // Also reset video sequence progress for new day
        resetVideoSequenceProgress()

        Log.d("DailyProgressManager", "Progress reset completed for date: $currentDate")
    }

    /**
     * Clears the banked reward minutes.
     */
    fun clearBankedRewardMinutes() {
        prefs.edit().putInt("banked_reward_minutes", 0).apply()
        Log.d("DailyProgressManager", "Banked reward minutes cleared.")
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
        // Also reset reward bank
        setBankedRewardMinutes(0)
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
     * Gets the completion status map for today (public method for batch reads)
     */
    fun getCompletedTasksMap(): Map<String, Boolean> {
        return getCompletedTasks()
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

    private fun isTaskVisible(showdays: String?, hidedays: String?): Boolean {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val todayShort = when (today) {
            Calendar.MONDAY -> "mon"
            Calendar.TUESDAY -> "tue"
            Calendar.WEDNESDAY -> "wed"
            Calendar.THURSDAY -> "thu"
            Calendar.FRIDAY -> "fri"
            Calendar.SATURDAY -> "sat"
            Calendar.SUNDAY -> "sun"
            else -> ""
        }

        if (!hidedays.isNullOrEmpty()) {
            if (hidedays.split(",").contains(todayShort)) {
                return false // Hide if today is in hidedays
            }
        }

        if (!showdays.isNullOrEmpty()) {
            return showdays.split(",").contains(todayShort) // Show only if today is in showdays
        }

        return true // Visible by default if no restrictions
    }

    private fun filterVisibleContent(originalContent: MainContent): MainContent {
        val filteredSections = originalContent.sections?.map { section ->
            val filteredTasks = section.tasks?.filter { task ->
                isTaskVisible(task.showdays, task.hidedays)
            }
            val filteredItems = section.items?.filter { item ->
                isTaskVisible(item.showdays, item.hidedays)
            }
            section.copy(tasks = filteredTasks, items = filteredItems)
        }
        return originalContent.copy(sections = filteredSections)
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

        Log.d("DailyProgressManager", "markTaskCompletedWithName: taskId=$taskId, taskName=$taskName, stars=$stars, isRequiredTask=$isRequiredTask")
        Log.d("DailyProgressManager", "Current completed tasks: ${completedTasks.keys}")

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
        val completedTasks = getCompletedTasks()
        val isCompleted = completedTasks[taskId] == true
        Log.d("DailyProgressManager", "isTaskCompleted: taskId=$taskId, isCompleted=$isCompleted, all completed tasks: ${completedTasks.keys}")
        return isCompleted
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

        val visibleContent = filterVisibleContent(config) // Filter content for visible items

        visibleContent.sections?.forEach { section ->
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

        val visibleContent = filterVisibleContent(config) // Filter content for visible items

        visibleContent.sections?.forEach { section ->
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

        val visibleContent = filterVisibleContent(config) // Filter content for visible items

        visibleContent.sections?.forEach { section ->
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
        return SettingsManager.readProfile(context) ?: "A"
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
     * Converts earned stars to reward minutes based on the conversion rules:
     * 1 star = 1 minute, 2 stars = 3 minutes, 3 stars = 5 minutes
     * For 4+ stars, uses the 3-star multiplier (5 minutes per 3 stars)
     */
    fun convertStarsToMinutes(stars: Int): Int {
        if (stars <= 0) return 0

        return when (stars) {
            1 -> 1
            2 -> 3
            3 -> 5
            else -> {
                // For 4+ stars, calculate using 3-star = 5 minutes ratio
                val fullSetsOf3 = stars / 3
                val remainingStars = stars % 3
                (fullSetsOf3 * 5) + when (remainingStars) {
                    1 -> 1
                    2 -> 3
                    else -> 0
                }
            }
        }
    }

    /**
     * Gets the current banked reward minutes
     */
    fun getBankedRewardMinutes(): Int {
        return try {
            prefs.getInt("banked_reward_minutes", 0)
        } catch (e: ClassCastException) {
            // Handle case where it was previously stored as a String or Float
            try {
                val floatValue = prefs.getFloat("banked_reward_minutes", 0f)
                floatValue.toInt()
            } catch (e2: ClassCastException) {
                // Fallback for extremely old versions or incorrect storage type
                val stringValue = prefs.getString("banked_reward_minutes", "0")
                stringValue?.toFloatOrNull()?.toInt() ?: 0
            }
        }
    }

    /**
     * Sets the banked reward minutes
     */
    fun setBankedRewardMinutes(minutes: Int) {
        prefs.edit()
            .remove("banked_reward_minutes") // Explicitly remove to avoid type conflicts
            .putInt("banked_reward_minutes", minutes)
            .apply()
    }

    /**
     * Adds stars to the bank and converts to reward minutes
     */
    fun addStarsToRewardBank(stars: Int): Int {
        val currentMinutes = getBankedRewardMinutes()
        val newMinutes = currentMinutes + convertStarsToMinutes(stars)
        setBankedRewardMinutes(newMinutes)
        return newMinutes
    }

    /**
     * Adds minutes directly to the banked reward minutes
     */
    fun addRewardMinutes(minutes: Int): Int {
        val currentMinutes = getBankedRewardMinutes()
        val newMinutes = currentMinutes + minutes
        setBankedRewardMinutes(newMinutes)
        Log.d("DailyProgressManager", "Added $minutes minutes to reward bank. New total: $newMinutes minutes")
        return newMinutes
    }

    /**
     * Uses all banked reward minutes and resets them to 0
     * Returns the minutes that were used
     */
    fun useAllRewardMinutes(): Int {
        val currentMinutes = getBankedRewardMinutes()
        if (currentMinutes > 0) {
            setBankedRewardMinutes(0)
            return currentMinutes
        }
        return 0
    }

    /**
     * Gets reward data from shared file (for BaerenLock to read)
     * This method can be called from BaerenLock to check for pending reward time
     */
    fun getPendingRewardData(): Pair<Int, Long>? {
        return try {
            val sharedFile = getSharedRewardFile()
            if (!sharedFile.exists()) return null

            val content = sharedFile.readText()
            val lines = content.lines()
            if (lines.size >= 2) {
                val rewardMinutes = lines[0].toIntOrNull() ?: 0
                val timestamp = lines[1].toLongOrNull() ?: 0L

                if (rewardMinutes > 0 && timestamp > 0) {
                    // Check if data is not too old (more than 24 hours)
                    val currentTime = System.currentTimeMillis()
                    val oneDayInMillis = 24 * 60 * 60 * 1000L
                    if (currentTime - timestamp < oneDayInMillis) {
                        return Pair(rewardMinutes, timestamp)
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("DailyProgressManager", "Error reading shared reward file", e)
            null
        }
    }

    /**
     * Clears pending reward data from shared file (call this after consuming the reward)
     */
    fun clearPendingRewardData() {
        try {
            val sharedFile = getSharedRewardFile()
            if (sharedFile.exists()) {
                sharedFile.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("DailyProgressManager", "Error clearing shared reward file", e)
        }
    }

    /**
     * Gets the shared reward file that both apps can access
     */
    fun getSharedRewardFile(): java.io.File {
        // Use external files directory if available, otherwise use internal
        val externalDir = context.getExternalFilesDir(null)
        val sharedDir = if (externalDir != null) {
            java.io.File(externalDir, "shared")
        } else {
            java.io.File(context.filesDir, "shared")
        }

        if (!sharedDir.exists()) {
            sharedDir.mkdirs()
        }

        return java.io.File(sharedDir, "baeren_reward_data.txt")
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
            totalTimeMinutes = totalTimeMinutes.toInt(),
            gamesPlayed = gamesPlayed,
            videosWatched = videosWatched,
            completedTasks = completedTasks.filter { it.value }.keys.toList(),
            completedTaskNames = completedTaskNames,
            gameSessions = gameSessions,
            videoSessions = videoSessions,
            completedGameSessions = completedGameSessions,
            averageGameTimeMinutes = if (gameSessions.isNotEmpty()) gameSessions.map { it.durationMinutes }.average().toInt() else 0,
            averageVideoTimeMinutes = if (videoSessions.isNotEmpty()) videoSessions.map { it.durationMinutes }.average().toInt() else 0,
            longestSessionMinutes = todaySummary.sessions.maxOfOrNull { it.durationMinutes }?.toInt() ?: 0,
            mostPlayedGame = gameSessions.groupBy { it.activityName }.maxByOrNull { it.value.size }?.key,
            totalSessions = todaySummary.sessions.size
        )
    }

}
