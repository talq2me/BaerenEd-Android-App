package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
        val webGameSessions: List<TimeTracker.ActivitySession>,
        val chromePageSessions: List<TimeTracker.ActivitySession>,
        val completedGameSessions: List<TimeTracker.ActivitySession>,
        val averageGameTimeMinutes: Int,
        val averageVideoTimeMinutes: Int,
        val longestSessionMinutes: Int,
        val mostPlayedGame: String?,
        val totalSessions: Int,
        val totalCorrectAnswers: Int,
        val totalIncorrectAnswers: Int,
        val config: MainContent? = null // Config for task matching
    )

    companion object {
        private const val PREF_NAME = "daily_progress_prefs"
        // Note: These keys are now profile-prefixed at runtime (e.g., "${profile}_required_tasks")
        private const val KEY_REQUIRED_TASKS = "required_tasks" // NEW: Uses cloud format (task names → TaskProgress)
        private const val KEY_PRACTICE_TASKS = "practice_tasks" // Practice tasks stored separately (task names → TaskProgress)
        private const val KEY_COMPLETED_TASKS = "completed_tasks" // OLD: Deprecated, kept for migration
        private const val KEY_COMPLETED_TASK_NAMES = "completed_task_names" // OLD: Deprecated, kept for migration
        private const val KEY_LAST_RESET_DATE = "last_reset_date"
        private const val KEY_PROFILE_LAST_RESET = "profile_last_reset" // Profile-specific reset timestamp (format: 2026-01-13 13:13:05.332)
        private const val KEY_TOTAL_POSSIBLE_STARS = "total_possible_stars"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
        private const val KEY_LAST_POKEMON_UNLOCK_DATE = "last_pokemon_unlock_date"
        private const val KEY_EARNED_STARS_AT_BATTLE_END = "earned_stars_at_battle_end" // Track earned stars when battle ended (for reset detection)
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        // Run migration asynchronously to avoid blocking the main thread during onCreate
        // Migration is only needed once per profile, so we can safely defer it
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val profile = getCurrentKid()
                TaskProgressMigration.migrateIfNeeded(context, profile)
            } catch (e: Exception) {
                Log.e("DailyProgressManager", "Error during async migration", e)
            }
        }
    }

    /**
     * Gets the current date as a string for daily reset tracking
     */
    private fun getCurrentDateString(): String {
        val date = SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault()).format(Date())
        Log.d("DailyProgressManager", "Current date string: $date")
        return date
    }

    /**
     * Checks if we need to reset progress for a new day
     * CRITICAL: Checks the cloud's last_reset timestamp if cloud is enabled
     */
    private fun shouldResetProgress(): Boolean {
        val profile = getCurrentKid()
        val currentDate = getCurrentDateString()
        val currentDatePart = currentDate.split(" ")[0] // Get "dd-MM-yyyy" part
        
        // ALWAYS try to get last_reset from cloud first if cloud is enabled (this is the source of truth)
        // Only fall back to local if cloud check fails or cloud not enabled
        var lastResetDate: String? = null
        var lastResetSource = "local"
        
        val isMainThread = android.os.Looper.getMainLooper().thread == Thread.currentThread()
        
        // Try to check cloud first (if not on main thread to avoid NetworkOnMainThreadException)
        if (!isMainThread) {
            try {
                val cloudStorageManager = CloudStorageManager(context)
                if (cloudStorageManager.isCloudStorageEnabled()) {
                    // Fetch only last_reset from cloud (more efficient than downloading all data)
                    // Use Dispatchers.IO to run on background thread
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        val syncService = CloudSyncService()
                        if (syncService.isConfigured()) {
                            try {
                                val url = "${syncService.getSupabaseUrl()}/rest/v1/user_data?profile=eq.$profile&select=last_reset"
                                val client = syncService.getClient()
                                val supabaseKey = try {
                                    syncService.getSupabaseKey()
                                } catch (e: Exception) {
                                    Log.e("DailyProgressManager", "Error reading Supabase key", e)
                                    ""
                                }
                                val request = okhttp3.Request.Builder()
                                    .url(url)
                                    .get()
                                    .addHeader("apikey", supabaseKey)
                                    .addHeader("Authorization", "Bearer $supabaseKey")
                                    .build()
                                
                                val response = client.newCall(request).execute()
                                if (response.isSuccessful) {
                                    val responseBody = response.body?.string() ?: "[]"
                                    val dataList: List<Map<String, String?>> = gson.fromJson(
                                        responseBody,
                                        object : TypeToken<List<Map<String, String?>>>() {}.type
                                    )
                                    
                                    val cloudLastReset = dataList.firstOrNull()?.get("last_reset")
                                    response.close()
                                    
                                    if (!cloudLastReset.isNullOrEmpty()) {
                                        // Convert cloud's ISO 8601 timestamp to local format
                                        try {
                                            val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
                                            
                                            // Strip timezone offset and milliseconds if present
                                            // Handle formats like: "2026-01-12T07:40:28", "2026-01-12T07:40:28.123", "2026-01-12T07:40:28-05:00", "2026-01-12T07:40:28Z"
                                            var timestampToParse = cloudLastReset
                                            
                                            // Remove timezone offset (at the end: +HH:MM, -HH:MM, or Z)
                                            if (timestampToParse.endsWith("Z")) {
                                                timestampToParse = timestampToParse.substringBeforeLast('Z')
                                            } else if (timestampToParse.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))) {
                                                // Find the last occurrence of + or - followed by digits (timezone offset)
                                                val lastPlus = timestampToParse.lastIndexOf('+')
                                                val lastMinus = timestampToParse.lastIndexOf('-')
                                                val offsetStart = if (lastPlus > lastMinus) lastPlus else lastMinus
                                                if (offsetStart > 10) { // Must be after the date part (YYYY-MM-DD is 10 chars)
                                                    timestampToParse = timestampToParse.substring(0, offsetStart)
                                                }
                                            }
                                            
                                            // Remove milliseconds if present
                                            if (timestampToParse.contains('.')) {
                                                timestampToParse = timestampToParse.substringBefore('.')
                                            }
                                            
                                            // Parse the timestamp (format: yyyy-MM-ddTHH:mm:ss)
                                            val parseFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                                            parseFormat.timeZone = estTimeZone
                                            val parsedDate = parseFormat.parse(timestampToParse)
                                            if (parsedDate != null) {
                                                val localFormat = java.text.SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", java.util.Locale.getDefault())
                                                localFormat.timeZone = estTimeZone
                                                lastResetDate = localFormat.format(parsedDate)
                                                lastResetSource = "cloud"
                                                Log.d("DailyProgressManager", "Fetched last_reset from cloud: $lastResetDate (cloud timestamp: $cloudLastReset)")
                                            }
                                        } catch (e: Exception) {
                                            Log.e("DailyProgressManager", "Error parsing cloud last_reset: $cloudLastReset", e)
                                        }
                                    }
                                } else {
                                    response.close()
                                }
                            } catch (e: Exception) {
                                Log.e("DailyProgressManager", "Error fetching last_reset from cloud: ${e.javaClass.simpleName} - ${e.message}", e)
                                // Log more details about network errors
                                if (e is java.net.UnknownHostException) {
                                    Log.e("DailyProgressManager", "  DNS resolution failed - cannot resolve Supabase hostname")
                                } else if (e is java.net.SocketTimeoutException) {
                                    Log.e("DailyProgressManager", "  Connection timeout - Supabase server not responding")
                                } else if (e is java.net.ConnectException) {
                                    Log.e("DailyProgressManager", "  Connection refused - cannot connect to Supabase server")
                                } else if (e is java.io.IOException) {
                                    Log.e("DailyProgressManager", "  Network I/O error: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DailyProgressManager", "Error checking cloud for last_reset", e)
            }
        }
        
        // Fall back to local last_reset_date if cloud fetch failed, cloud not enabled, or on main thread
        if (lastResetDate == null) {
            lastResetDate = prefs.getString(KEY_LAST_RESET_DATE, "") ?: ""
        }

        // If no last reset date found anywhere, trigger reset (no reset has happened yet)
        if (lastResetDate.isEmpty()) {
            Log.d("DailyProgressManager", "shouldResetProgress - No last reset date found ($lastResetSource), triggering reset")
            return true
        }

        // Compare only the date part (dd-MM-yyyy), not the time
        val lastDatePart = lastResetDate.split(" ")[0] // Get "dd-MM-yyyy" part

        val shouldReset = lastDatePart != currentDatePart
        Log.d("DailyProgressManager", "shouldResetProgress called - Last: '$lastResetDate' (date: $lastDatePart, source: $lastResetSource), Current: '$currentDate' (date: $currentDatePart), Should reset: $shouldReset")
        if (shouldReset) {
            Log.d("DailyProgressManager", "RESET TRIGGERED: Progress will be reset due to date mismatch")
        }
        return shouldReset
    }

    /**
     * Resets progress for a new day
     * CRITICAL: Resets required_tasks (new format), berries, banked_mins, and syncs to cloud
     */
    private fun resetProgressForNewDay() {
        val currentDate = getCurrentDateString()
        val profile = getCurrentKid()
        val bankedMinsKey = "${profile}_banked_reward_minutes"
        val completedTasksKey = "${profile}_$KEY_COMPLETED_TASKS"
        val completedTaskNamesKey = "${profile}_$KEY_COMPLETED_TASK_NAMES"
        val totalStarsKey = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        val requiredTasksKey = "${profile}_$KEY_REQUIRED_TASKS"
        
        Log.d("DailyProgressManager", "RESETTING progress for new day: $currentDate for profile: $profile")
        
        // Reset local progress (shared implementation)
        resetLocalProgressOnly(profile)

        // Sync reset to cloud (this will update last_reset, last_updated, berries_earned, banked_mins, required_tasks, practice_tasks)
        // Generate timestamp before reset to use for local update
        val syncService = CloudSyncService()
        val resetTimestamp = syncService.generateESTTimestamp()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cloudStorageManager = CloudStorageManager(context)
                if (cloudStorageManager.isCloudStorageEnabled()) {
                    val resetResult = cloudStorageManager.resetProgressInCloud(profile)
                    if (resetResult.isSuccess) {
                        // Update local timestamp to match cloud after successful reset
                        // Use the timestamp that was generated (same one sent to database)
                        setLocalLastUpdatedTimestamp(profile, resetTimestamp)
                        Log.d("DailyProgressManager", "Successfully synced daily reset to cloud for profile: $profile, timestamp: $resetTimestamp")
                    } else {
                        Log.e("DailyProgressManager", "Failed to sync daily reset to cloud: ${resetResult.exceptionOrNull()?.message}")
                    }
                } else {
                    // Cloud not enabled, just update local timestamp
                    setLocalLastUpdatedTimestamp(profile, resetTimestamp)
                    Log.d("DailyProgressManager", "Cloud not enabled, updated local timestamp only: $resetTimestamp")
                }
            } catch (e: Exception) {
                Log.e("DailyProgressManager", "Error syncing daily reset to cloud", e)
            }
        }

        Log.d("DailyProgressManager", "Progress reset completed for date: $currentDate for profile: $profile")
    }
    
    /**
     * Helper function to set local last updated timestamp (used after cloud reset)
     */
    private fun setLocalLastUpdatedTimestamp(profile: String, timestamp: String) {
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        val storedTimestampKey = "${profile}_last_updated_timestamp"
        val success = progressPrefs.edit()
            .putString(storedTimestampKey, timestamp)
            .commit() // Use commit() for synchronous write to prevent race conditions
        if (!success) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save last_updated_timestamp in setLocalLastUpdatedTimestamp!")
        }
    }
    
    /**
     * Checks if profile_last_reset exists and is today's date
     * Format: yyyy-MM-dd HH:mm:ss.SSS (e.g., "2026-01-13 13:13:05.332")
     * Returns true if reset is needed (profile_last_reset doesn't exist or is not today)
     */
    suspend fun checkIfResetNeeded(profile: String): Boolean = withContext(Dispatchers.IO) {
        val profileLastResetKey = "${profile}_$KEY_PROFILE_LAST_RESET"
        val profileLastReset = prefs.getString(profileLastResetKey, null)
        
        if (profileLastReset == null) {
            Log.d("DailyProgressManager", "checkIfResetNeeded: profile_last_reset not found for $profile - reset needed")
            return@withContext true
        }
        
        // Parse the timestamp and check if it's today
        try {
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            timestampFormat.timeZone = TimeZone.getTimeZone("America/New_York")
            val resetDate = timestampFormat.parse(profileLastReset)
            
            if (resetDate == null) {
                Log.w("DailyProgressManager", "checkIfResetNeeded: Failed to parse profile_last_reset: $profileLastReset - reset needed")
                return@withContext true
            }
            
            // Get today's date in EST
            val today = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            val resetCalendar = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            resetCalendar.time = resetDate
            
            val isToday = today.get(Calendar.YEAR) == resetCalendar.get(Calendar.YEAR) &&
                         today.get(Calendar.DAY_OF_YEAR) == resetCalendar.get(Calendar.DAY_OF_YEAR)
            
            Log.d("DailyProgressManager", "checkIfResetNeeded: profile_last_reset=$profileLastReset, isToday=$isToday")
            return@withContext !isToday
        } catch (e: Exception) {
            Log.e("DailyProgressManager", "checkIfResetNeeded: Error parsing profile_last_reset: $profileLastReset", e)
            return@withContext true
        }
    }
    
    /**
     * Performs daily reset: resets local progress, syncs to cloud, gets timestamp back, and stores it
     * Returns the timestamp that was stored (format: yyyy-MM-dd HH:mm:ss.SSS)
     */
    suspend fun performDailyResetAndSync(profile: String): String? = withContext(Dispatchers.IO) {
        Log.d("DailyProgressManager", "performDailyResetAndSync: Starting reset for profile: $profile")
        
        // Reset local progress first
        resetProgressForNewDaySync(profile)
        
        // Sync reset to cloud and get the timestamp back
        val cloudStorageManager = CloudStorageManager(context)
        val syncService = CloudSyncService()
        
        if (cloudStorageManager.isCloudStorageEnabled()) {
            try {
                // Perform reset in cloud
                val resetResult = cloudStorageManager.resetProgressInCloud(profile)
                if (resetResult.isSuccess) {
                    // Query cloud to get the last_reset timestamp that was set
                    val url = "${syncService.getSupabaseUrl()}/rest/v1/user_data?profile=eq.$profile&select=last_reset"
                    val client = syncService.getClient()
                    val supabaseKey = syncService.getSupabaseKey()
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("apikey", supabaseKey)
                        .addHeader("Authorization", "Bearer $supabaseKey")
                        .build()
                    
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: "[]"
                        val dataList: List<Map<String, String?>> = gson.fromJson(
                            responseBody,
                            object : TypeToken<List<Map<String, String?>>>() {}.type
                        )
                        
                        val cloudLastReset = dataList.firstOrNull()?.get("last_reset")
                        response.close()
                        
                        if (!cloudLastReset.isNullOrEmpty()) {
                            // Convert cloud's ISO 8601 timestamp to our format (yyyy-MM-dd HH:mm:ss.SSS)
                            try {
                                val estTimeZone = TimeZone.getTimeZone("America/New_York")
                                
                                // Strip timezone offset and parse
                                var timestampToParse = cloudLastReset
                                if (timestampToParse.endsWith("Z")) {
                                    timestampToParse = timestampToParse.substringBeforeLast('Z')
                                } else if (timestampToParse.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))) {
                                    val lastPlus = timestampToParse.lastIndexOf('+')
                                    val lastMinus = timestampToParse.lastIndexOf('-')
                                    val offsetStart = if (lastPlus > lastMinus) lastPlus else lastMinus
                                    if (offsetStart > 10) {
                                        timestampToParse = timestampToParse.substring(0, offsetStart)
                                    }
                                }
                                
                                // Parse ISO format and convert to our format
                                // Handle milliseconds - preserve them if present
                                val hasMillis = timestampToParse.contains('.')
                                val millisPart = if (hasMillis) {
                                    val millisStr = timestampToParse.substringAfter('.')
                                    // Get up to 3 digits of milliseconds
                                    if (millisStr.length >= 3) {
                                        millisStr.substring(0, 3)
                                    } else {
                                        millisStr.padEnd(3, '0')
                                    }
                                } else {
                                    "000"
                                }
                                
                                val timestampWithoutMillis = if (hasMillis) {
                                    timestampToParse.substringBefore('.')
                                } else {
                                    timestampToParse
                                }
                                
                                val parseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                                parseFormat.timeZone = estTimeZone
                                val parsedDate = parseFormat.parse(timestampWithoutMillis)
                                
                                if (parsedDate != null) {
                                    // Format to yyyy-MM-dd HH:mm:ss.SSS (preserve milliseconds from cloud)
                                    val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                    outputFormat.timeZone = estTimeZone
                                    val formattedTimestamp = "${outputFormat.format(parsedDate)}.$millisPart"
                                    
                                    // Store as profile_last_reset
                                    val profileLastResetKey = "${profile}_$KEY_PROFILE_LAST_RESET"
                                    val success = prefs.edit()
                                        .putString(profileLastResetKey, formattedTimestamp)
                                        .commit() // Use commit() for synchronous write
                                    if (!success) {
                                        Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save profile_last_reset!")
                                    }
                                    
                                    Log.d("DailyProgressManager", "performDailyResetAndSync: Stored profile_last_reset=$formattedTimestamp (from cloud: $cloudLastReset)")
                                    return@withContext formattedTimestamp
                                }
                            } catch (e: Exception) {
                                Log.e("DailyProgressManager", "performDailyResetAndSync: Error parsing cloud timestamp: $cloudLastReset", e)
                            }
                        }
                    } else {
                        response.close()
                    }
                } else {
                    Log.e("DailyProgressManager", "performDailyResetAndSync: Failed to reset in cloud: ${resetResult.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e("DailyProgressManager", "performDailyResetAndSync: Error during cloud reset/sync", e)
            }
        } else {
            // Cloud not enabled - generate timestamp locally
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            timestampFormat.timeZone = TimeZone.getTimeZone("America/New_York")
            val formattedTimestamp = timestampFormat.format(Date())
            
            val profileLastResetKey = "${profile}_$KEY_PROFILE_LAST_RESET"
            val success = prefs.edit()
                .putString(profileLastResetKey, formattedTimestamp)
                .commit() // Use commit() for synchronous write
            if (!success) {
                Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save profile_last_reset!")
            }
            
            Log.d("DailyProgressManager", "performDailyResetAndSync: Cloud not enabled, stored local timestamp: $formattedTimestamp")
            return@withContext formattedTimestamp
        }
        
        return@withContext null
    }
    
    /**
     * Internal function to reset local progress only (no cloud sync)
     * Used by performDailyResetAndSync to reset before syncing to cloud
     */
    private fun resetProgressForNewDaySync(profile: String) {
        resetLocalProgressOnly(profile)
    }
    
    /**
     * Internal helper to reset local progress only (shared by resetProgressForNewDay and resetProgressForNewDaySync)
     * 
     * WHAT GETS RESET:
     * - required_tasks: status → "incomplete", correct/incorrect/questions → 0 (preserves visibility fields: showdays, hidedays, displayDays, disable, stars)
     * - completed_tasks (old format): cleared
     * - completed_task_names (old format): cleared
     * - banked_reward_minutes: → 0
     * - total_possible_stars: → 0
     * - earned_stars_at_battle_end: → 0
     * - earned_berries: → 0 (via resetEarnedBerries())
     * - TimeTracker sessions: cleared (this resets practice_tasks locally)
     * - last_reset_date: updated to current date/time
     * 
     * WHAT DOES NOT GET RESET (preserved):
     * - Video sequence progress (persistent across days)
     * - Pokemon unlocks
     * - Other persistent data
     * 
     * @param profile The profile to reset
     */
    private fun resetLocalProgressOnly(profile: String) {
        val currentDate = getCurrentDateString()
        val bankedMinsKey = "${profile}_banked_reward_minutes"
        val completedTasksKey = "${profile}_$KEY_COMPLETED_TASKS"
        val completedTaskNamesKey = "${profile}_$KEY_COMPLETED_TASK_NAMES"
        val totalStarsKey = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        val requiredTasksKey = "${profile}_$KEY_REQUIRED_TASKS"
        val practiceTasksKey = "${profile}_$KEY_PRACTICE_TASKS"
        
        Log.d("DailyProgressManager", "resetLocalProgressOnly: Resetting local progress for profile: $profile")
        
        // Get current required_tasks directly from SharedPreferences
        val requiredTasksJson = prefs.getString(requiredTasksKey, "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, TaskProgress>>() {}.type
        val currentRequiredTasks: Map<String, TaskProgress> = gson.fromJson(requiredTasksJson, type) ?: emptyMap()
        
        // Reset required_tasks: preserve visibility fields but reset status and progress
        val resetRequiredTasks = currentRequiredTasks.mapValues { (_, taskProgress) ->
            TaskProgress(
                status = "incomplete",
                correct = 0,
                incorrect = 0,
                questions = 0,
                stars = taskProgress.stars,
                showdays = taskProgress.showdays,
                hidedays = taskProgress.hidedays,
                displayDays = taskProgress.displayDays,
                disable = taskProgress.disable
            )
        }
        
        // Get current practice_tasks and reset them
        val practiceTasksJson = prefs.getString(practiceTasksKey, "{}") ?: "{}"
        val currentPracticeTasks: Map<String, TaskProgress> = gson.fromJson(practiceTasksJson, type) ?: emptyMap()
        
        // Reset practice_tasks: preserve visibility fields but reset status and progress
        val resetPracticeTasks = currentPracticeTasks.mapValues { (_, taskProgress) ->
            TaskProgress(
                status = "incomplete",
                correct = 0,
                incorrect = 0,
                questions = 0,
                stars = taskProgress.stars,
                showdays = taskProgress.showdays,
                hidedays = taskProgress.hidedays,
                displayDays = taskProgress.displayDays,
                disable = taskProgress.disable
            )
        }
        
        // Clear practice_tasks_cumulative_times so times_completed in cloud reflects today only
        val cumulativeTimesKey = "${profile}_practice_tasks_cumulative_times"

        // Reset local storage
        val success = prefs.edit()
            .putString(completedTasksKey, gson.toJson(emptyMap<String, Boolean>()))
            .putString(completedTaskNamesKey, gson.toJson(emptyMap<String, String>()))
            .putString(requiredTasksKey, gson.toJson(resetRequiredTasks))
            .putString(practiceTasksKey, gson.toJson(resetPracticeTasks))
            .putString(KEY_LAST_RESET_DATE, currentDate)
            .putInt(bankedMinsKey, 0)
            .putInt(totalStarsKey, 0)
            .putInt(KEY_EARNED_STARS_AT_BATTLE_END, 0)
            .remove(cumulativeTimesKey)
            .commit() // Use commit() for synchronous write to prevent race conditions
        if (!success) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save daily reset data!")
        }
        
        // Reset earned berries
        resetEarnedBerries()
        
        // NOTE: Video sequence progress is NOT reset - it persists across days
        // (resetVideoSequenceProgress() is a no-op that just logs this fact)
        
        // Clear TimeTracker sessions (for time tracking, not completion tracking)
        try {
            TimeTracker(context).clearAllData()
            Log.d("DailyProgressManager", "Cleared TimeTracker sessions for daily reset")
        } catch (e: Exception) {
            Log.e("DailyProgressManager", "Error clearing TimeTracker sessions", e)
        }
        
        Log.d("DailyProgressManager", "Cleared practice_tasks_cumulative_times so times_completed reflects today only")
        
        Log.d("DailyProgressManager", "resetLocalProgressOnly: Local reset completed")
    }

    /**
     * Clears the banked reward minutes for the current profile.
     */
    fun clearBankedRewardMinutes() {
        val profile = getCurrentKid()
        val key = "${profile}_banked_reward_minutes"
        val success = prefs.edit().putInt(key, 0).commit() // Use commit() for synchronous write
        if (!success) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to clear banked reward minutes!")
        }
        Log.d("DailyProgressManager", "Banked reward minutes cleared for profile: $profile")
    }

    /**
     * Resets video sequence progress for all video files
     * NOTE: Sequential videos should NOT reset daily - they continue across days
     */
    private fun resetVideoSequenceProgress() {
        // Do NOT reset video sequence progress - videos should continue across days
        // val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
        // videoPrefs.edit().clear().apply()
        Log.d("DailyProgressManager", "Video sequence progress is persistent across days (not reset)")
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
     * Gets the required tasks map for today (NEW: Uses cloud format - task names → TaskProgress)
     */
    private fun getRequiredTasks(): MutableMap<String, TaskProgress> {
        // Always check if we need to reset before returning data
        if (shouldResetProgress()) {
            resetProgressForNewDay()
        }

        val profile = getCurrentKid()
        val key = "${profile}_$KEY_REQUIRED_TASKS"
        val json = prefs.getString(key, "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, TaskProgress>>() {}.type
        val tasks: MutableMap<String, TaskProgress> = gson.fromJson<MutableMap<String, TaskProgress>>(json, type) ?: mutableMapOf()
        
        // CRITICAL: Log Math task status when reading
        val mathTask = tasks["Math"]
        if (mathTask != null) {
            Log.d("DailyProgressManager", "CRITICAL: getRequiredTasks() - Math task read: status='${mathTask.status}', correct=${mathTask.correct ?: "null"}, incorrect=${mathTask.incorrect ?: "null"}, questions=${mathTask.questions ?: "null"}")
        } else {
            val keysList = tasks.keys.take(10).toList()
            Log.d("DailyProgressManager", "CRITICAL: getRequiredTasks() - Math task NOT FOUND in tasks map! Available keys: ${keysList.joinToString()}")
        }
        
        return tasks
    }

    /**
     * Gets the completion status map for today (public method for batch reads)
     * NEW: Returns Map<String, TaskProgress> with task names as keys
     * For backward compatibility, also provides a method that returns Map<String, Boolean>
     * 
     * @param mapType Optional: "required" or "optional" to filter by map type. If null, returns both (for backward compatibility)
     */
    fun getCompletedTasksMap(mapType: String? = null): Map<String, Boolean> {
        val allTasks = mutableMapOf<String, Boolean>()
        
        // CRITICAL: Required tasks and practice tasks are completely separate
        // Only include the relevant tasks based on mapType to avoid collisions
        when (mapType) {
            "required" -> {
                // Only required tasks for required map
                val requiredTasks = getRequiredTasks()
                requiredTasks.forEach { (name, progress) ->
                    val isComplete = progress.status == "complete"
                    allTasks[name] = isComplete
                    // CRITICAL: Log Math task status specifically
                    if (name == "Math" || name.contains("Math")) {
                        Log.d("DailyProgressManager", "CRITICAL: getCompletedTasksMap(required) - Math task: name='$name', status='${progress.status}', isComplete=$isComplete")
                    }
                }
            }
            "optional" -> {
                // Only practice tasks for optional/practice map
                val practiceTasks = getPracticeTasks()
                practiceTasks.forEach { (name, progress) ->
                    val isComplete = progress.status == "complete"
                    allTasks[name] = isComplete
                    // CRITICAL: Log Math task status specifically
                    if (name == "Math" || name.contains("Math")) {
                        Log.d("DailyProgressManager", "CRITICAL: getCompletedTasksMap(optional) - Math task: name='$name', status='${progress.status}', isComplete=$isComplete")
                    }
                }
            }
            else -> {
                // Backward compatibility: include both (but this shouldn't be used for map display)
                val requiredTasks = getRequiredTasks()
                val practiceTasks = getPracticeTasks()
                requiredTasks.forEach { (name, progress) ->
                    allTasks[name] = progress.status == "complete"
                }
                practiceTasks.forEach { (name, progress) ->
                    // Don't overwrite if already exists from required tasks
                    if (!allTasks.containsKey(name)) {
                        allTasks[name] = progress.status == "complete"
                    }
                }
            }
        }
        
        Log.d("DailyProgressManager", "CRITICAL: getCompletedTasksMap($mapType) - completed tasks: ${allTasks.filter { it.value }.keys}")
        return allTasks
    }

    /**
     * Gets the required tasks map in new format (task names → TaskProgress)
     */
    fun getRequiredTasksMap(): Map<String, TaskProgress> {
        return getRequiredTasks()
    }
    
    /**
     * Gets the practice tasks map (task names → TaskProgress)
     * Practice tasks are stored separately from required tasks
     */
    private fun getPracticeTasks(): MutableMap<String, TaskProgress> {
        val profile = getCurrentKid()
        val key = "${profile}_$KEY_PRACTICE_TASKS"
        val json = prefs.getString(key, "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, TaskProgress>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }
    
    /**
     * Gets the practice tasks map in new format (task names → TaskProgress)
     */
    fun getPracticeTasksMap(): Map<String, TaskProgress> {
        return getPracticeTasks()
    }

    /**
     * Saves the required tasks map for the current profile (NEW: Uses cloud format)
     * Also updates local.profile.last_updated timestamp
     */
    private fun saveRequiredTasks(requiredTasks: Map<String, TaskProgress>) {
        val profile = getCurrentKid()
        val key = "${profile}_$KEY_REQUIRED_TASKS"
        val json = gson.toJson(requiredTasks)
        
        Log.d("DailyProgressManager", "CRITICAL: Saving required tasks for profile: $profile")
        Log.d("DailyProgressManager", "CRITICAL: JSON being saved: $json")
        Log.d("DailyProgressManager", "CRITICAL: Task keys being saved: ${requiredTasks.keys}")
        
        // CRITICAL: Use commit() instead of apply() to ensure synchronous write
        // This prevents race conditions where TrainingMapActivity might read before data is written
        val success = prefs.edit()
            .putString(key, json)
            .commit()
        
        if (!success) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save required tasks!")
        } else {
            Log.d("DailyProgressManager", "CRITICAL: Successfully saved required tasks")
            // Verify what was actually saved
            val savedJson = prefs.getString(key, "{}")
            Log.d("DailyProgressManager", "CRITICAL: Verified saved JSON: $savedJson")
        }
        
        // Update local.profile.last_updated to now() EST when tasks are saved
        // CRITICAL: Must update timestamp SYNCHRONOUSLY using commit() to ensure it's written
        // before any other code (like onResume cloud sync) can read it
        val syncService = CloudSyncService()
        val nowISO = syncService.generateESTTimestamp()
        val timestampKey = "${profile}_last_updated_timestamp"
        val timestampSuccess = prefs.edit()
            .putString(timestampKey, nowISO)
            .commit() // Use commit() for synchronous write
        
        if (!timestampSuccess) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save last_updated timestamp!")
        } else {
            Log.d("DailyProgressManager", "CRITICAL: Updated last_updated timestamp to: $nowISO (saved synchronously)")
        }
        
        // NOTE: Cloud sync is handled by the caller (e.g., GameActivity) after all updates are complete
        // This prevents concurrent syncs and ensures all data is updated before syncing
    }
    
    /**
     * Saves the practice tasks map for the current profile
     * Also updates local.profile.last_updated timestamp
     */
    private fun savePracticeTasks(practiceTasks: Map<String, TaskProgress>) {
        val profile = getCurrentKid()
        val key = "${profile}_$KEY_PRACTICE_TASKS"
        val json = gson.toJson(practiceTasks)
        
        Log.d("DailyProgressManager", "CRITICAL: Saving practice tasks for profile: $profile")
        Log.d("DailyProgressManager", "CRITICAL: JSON being saved: $json")
        Log.d("DailyProgressManager", "CRITICAL: Task keys being saved: ${practiceTasks.keys}")
        
        // CRITICAL: Use commit() instead of apply() to ensure synchronous write
        val success = prefs.edit()
            .putString(key, json)
            .commit()
        
        if (!success) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save practice tasks!")
        } else {
            Log.d("DailyProgressManager", "CRITICAL: Successfully saved practice tasks")
        }
        
        // Update local.profile.last_updated to now() EST when tasks are saved
        val syncService = CloudSyncService()
        val nowISO = syncService.generateESTTimestamp()
        val timestampKey = "${profile}_last_updated_timestamp"
        val timestampSuccess = prefs.edit()
            .putString(timestampKey, nowISO)
            .commit() // Use commit() for synchronous write
        
        if (!timestampSuccess) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save last_updated timestamp!")
        } else {
            Log.d("DailyProgressManager", "CRITICAL: Updated last_updated timestamp to: $nowISO (saved synchronously)")
        }
        
        // NOTE: Cloud sync is handled by the caller (e.g., GameActivity) after all updates are complete
    }

    /**
     * Parses a date string in format "Nov 24, 2025" and returns a Calendar instance
     * Returns null if parsing fails
     */
    private fun parseDisableDate(dateString: String?): Calendar? {
        if (dateString.isNullOrEmpty()) return null
        
        return try {
            // Try parsing format like "Nov 24, 2025"
            val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.US)
            val date = formatter.parse(dateString.trim())
            if (date != null) {
                Calendar.getInstance().apply {
                    time = date
                    // Set time to start of day for accurate comparison
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            } else null
        } catch (e: Exception) {
            Log.e("DailyProgressManager", "Error parsing disable date: $dateString", e)
            null
        }
    }

    /**
     * Checks if a task should be visible based on day restrictions and disable date
     */
    private fun isTaskVisible(showdays: String?, hidedays: String?, displayDays: String? = null, disable: String? = null): Boolean {
        // Check disable date first - if current date is before disable date, hide the task
        if (!disable.isNullOrEmpty()) {
            val disableDate = parseDisableDate(disable)
            if (disableDate != null) {
                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // If today is before the disable date, task is disabled (not visible)
                if (today.before(disableDate)) {
                    return false
                }
            }
        }

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

        // Check displayDays first (if set, only show on those days)
        if (!displayDays.isNullOrEmpty()) {
            return displayDays.split(",").contains(todayShort) // Show only if today is in displayDays
        }

        if (!showdays.isNullOrEmpty()) {
            return showdays.split(",").contains(todayShort) // Show only if today is in showdays
        }

        return true // Visible by default if no restrictions
    }

    fun filterVisibleContent(originalContent: MainContent): MainContent {
        val filteredSections = originalContent.sections?.map { section ->
            val filteredTasks = section.tasks?.filter { task ->
                isTaskVisible(task.showdays, task.hidedays, task.displayDays, task.disable)
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
     * Checks if a task is from the required section
     */
    fun isTaskFromRequiredSection(taskId: String, config: MainContent): Boolean {
        val visibleContent = filterVisibleContent(config)
        return visibleContent.sections?.any { section ->
            section.id == "required" && section.tasks?.any { (it.launch ?: "unknown_task") == taskId } == true
        } ?: false
    }

    /**
     * Generates a unique task ID that includes section information
     * This allows the same task (same launch ID) to be tracked separately in different sections
     */
    fun getUniqueTaskId(taskId: String, sectionId: String?): String {
        return if (sectionId != null && sectionId != "required") {
            // For optional/bonus sections, include section ID to make it unique
            // Required tasks use just the taskId for backward compatibility and progress tracking
            "${sectionId}_$taskId"
        } else {
            taskId
        }
    }

    /**
     * Marks a task as completed with a display name and returns the stars earned
     * For required tasks: counts toward progress and coins, only award once per day
     * For optional tasks: only banks reward time, doesn't affect progress, can be completed multiple times
     */
    fun markTaskCompletedWithName(
        taskId: String, 
        taskName: String, 
        stars: Int, 
        isRequiredTask: Boolean = false, 
        config: MainContent? = null, 
        sectionId: String? = null,
        correctAnswers: Int? = null,
        incorrectAnswers: Int? = null,
        questionsAnswered: Int? = null
    ): Int {
        Log.d("DailyProgressManager", "markTaskCompletedWithName called: taskId=$taskId, taskName=$taskName, stars=$stars, isRequiredTask=$isRequiredTask, sectionId=$sectionId")
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format

        // Generate unique task ID that includes section information (for optional tasks)
        val uniqueTaskId = getUniqueTaskId(taskId, sectionId)

        // Determine if this is actually a required task based on the section ID first
        // If sectionId is explicitly provided, use that (sectionId == "required" means it's required)
        // Otherwise, fall back to checking config or the flag
        val actualIsRequired = when {
            sectionId != null -> sectionId == "required"  // Explicit section ID takes precedence
            config != null -> isTaskFromRequiredSection(taskId, config)  // Check config if no sectionId
            else -> isRequiredTask  // Fall back to flag
        }

        Log.d("DailyProgressManager", "markTaskCompletedWithName: taskId=$taskId, uniqueTaskId=$uniqueTaskId, taskName=$taskName, stars=$stars, isRequiredTask=$isRequiredTask, actualIsRequired=$actualIsRequired, sectionId=$sectionId")
        Log.d("DailyProgressManager", "Current required tasks: ${requiredTasks.keys}")

        if (actualIsRequired) {
            // Required tasks: only award once per day, counts toward progress
            // NEW: Use task name as key (cloud format)
            val existingProgress = requiredTasks[taskName]
            Log.d("DailyProgressManager", "CRITICAL: Checking existing progress for '$taskName': status=${existingProgress?.status}, exists=${existingProgress != null}")
            
            // Get task from config to preserve visibility fields and get star value
            val task = findTaskInConfig(taskId, config)
            val taskStars = task?.stars ?: stars // Use stars from config if available, otherwise use passed stars
            
            // CRITICAL: Always update the task with latest completion data, even if already complete
            // This ensures answer counts and status are current after each game completion
            val wasAlreadyComplete = existingProgress?.status == "complete"
            val taskProgress = TaskProgress(
                status = "complete",
                correct = correctAnswers,
                incorrect = incorrectAnswers,
                questions = questionsAnswered,
                stars = taskStars, // Store stars value for future reference
                showdays = task?.showdays,
                hidedays = task?.hidedays,
                displayDays = task?.displayDays,
                disable = task?.disable
            )
            requiredTasks[taskName] = taskProgress
            saveRequiredTasks(requiredTasks)
            
            if (wasAlreadyComplete) {
                Log.d("DailyProgressManager", "CRITICAL: Required task $taskId ($taskName) was already complete, but updated with latest completion data (correct=$correctAnswers, incorrect=$incorrectAnswers, questions=$questionsAnswered)")
            } else {
                Log.d("DailyProgressManager", "CRITICAL: Required task $taskId ($taskName) completed, earned $stars stars (counts toward progress), stored $taskStars stars in TaskProgress")
            }
            Log.d("DailyProgressManager", "CRITICAL: Saved task with key: '$taskName', status: '${taskProgress.status}'")
            Log.d("DailyProgressManager", "CRITICAL: Current required tasks keys after save: ${requiredTasks.keys}")
            
            // Only award stars if this is the first completion today
            return if (wasAlreadyComplete) 0 else stars
        } else {
            // Practice tasks: ALWAYS award stars each time completed, banks reward time but doesn't affect progress
            // CRITICAL: Practice tasks are stored separately from required tasks
            val practiceTasks = getPracticeTasks()
            val task = findTaskInConfig(taskId, config)
            val taskStars = task?.stars ?: stars // Use stars from config if available, otherwise use passed stars
            
            // For practice tasks: set correct/incorrect/questions and times_completed (additive)
            // Get existing progress to add to it
            val existingProgress = practiceTasks[taskName]
            val existingCorrect = existingProgress?.correct ?: 0
            val existingIncorrect = existingProgress?.incorrect ?: 0
            val existingQuestions = existingProgress?.questions ?: 0
            
            val taskProgress = TaskProgress(
                status = "complete", // Practice tasks can be completed multiple times, but show as complete on map
                correct = (existingCorrect + (correctAnswers ?: 0)),
                incorrect = (existingIncorrect + (incorrectAnswers ?: 0)),
                questions = (existingQuestions + (questionsAnswered ?: 0)),
                stars = taskStars, // Store stars value for future reference
                showdays = task?.showdays,
                hidedays = task?.hidedays,
                displayDays = task?.displayDays,
                disable = task?.disable
            )
            practiceTasks[taskName] = taskProgress
            savePracticeTasks(practiceTasks)
            Log.d("DailyProgressManager", "Practice task $taskId ($taskName) completed, earned $stars stars (banks reward time only, doesn't affect progress) - can be completed again for more reward time, stored $taskStars stars in TaskProgress")
            
            // Increment cumulative times_completed for this practice task in database
            incrementPracticeTaskTimesCompleted(taskName)
            
            // Check if all practice tasks are completed and reset them if so
            if (config != null) {
                checkAndResetPracticeTasksIfAllCompleted(config)
            }
            
            return stars  // Always return stars for practice tasks, regardless of previous completions
        }
    }
    
    /**
     * Increments the cumulative times_completed for a practice task.
     * This is called each time a practice task is completed to track total completions in the database.
     */
    private fun incrementPracticeTaskTimesCompleted(taskName: String) {
        try {
            val profile = getCurrentKid()
            val cumulativeKey = "${profile}_practice_tasks_cumulative_times"
            
            // Get current cumulative times_completed from SharedPreferences
            val cumulativeJson = prefs.getString(cumulativeKey, "{}") ?: "{}"
            val cumulativeType = object : TypeToken<MutableMap<String, Int>>() {}.type
            val cumulativeTimes = gson.fromJson<MutableMap<String, Int>>(cumulativeJson, cumulativeType) ?: mutableMapOf()
            
            // Increment for this task
            val currentCount = cumulativeTimes[taskName] ?: 0
            cumulativeTimes[taskName] = currentCount + 1
            
            // Save cumulative times_completed
            val success = prefs.edit()
                .putString(cumulativeKey, gson.toJson(cumulativeTimes))
                .commit() // Use commit() for synchronous write to prevent race conditions
            if (!success) {
                Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save cumulative times_completed!")
            }
            
            Log.d("DailyProgressManager", "Incremented cumulative times_completed for '$taskName': $currentCount -> ${cumulativeTimes[taskName]}")
        } catch (e: Exception) {
            Log.e("DailyProgressManager", "Error incrementing practice task times_completed", e)
        }
    }
    
    /**
     * Checks if all visible practice tasks (optional section) are completed.
     * If all are completed, increments cumulative times_completed for each in storage and resets them.
     */
    fun checkAndResetPracticeTasksIfAllCompleted(config: MainContent) {
        try {
            // Get all visible optional tasks from config
            val optionalSection = config.sections?.find { it.id == "optional" }
            val visibleOptionalTasks = optionalSection?.tasks?.filter { task ->
                task.title != null && 
                task.launch != null && 
                TaskVisibilityChecker.isTaskVisible(task)
            } ?: emptyList()
            
            if (visibleOptionalTasks.isEmpty()) {
                Log.d("DailyProgressManager", "No visible optional tasks to check")
                return
            }
            
            // Check if all visible optional tasks are completed (via practice tasks storage)
            val practiceTasks = getPracticeTasks()
            
            val allCompleted = visibleOptionalTasks.all { task ->
                val taskTitle = task.title ?: ""
                practiceTasks[taskTitle]?.status == "complete"
            }
            
            if (allCompleted) {
                Log.d("DailyProgressManager", "All ${visibleOptionalTasks.size} visible practice tasks are completed - resetting them")
                
                // Reset all practice tasks to incomplete (this makes them appear uncompleted on the map)
                // Note: Cumulative times_completed is already being incremented each time a task is completed,
                // so we just need to reset the completion status
                val updatedPracticeTasks = practiceTasks.toMutableMap()
                visibleOptionalTasks.forEach { task ->
                    val taskTitle = task.title ?: ""
                    val existingProgress = updatedPracticeTasks[taskTitle]
                    if (existingProgress != null) {
                        // Reset status to incomplete, preserve other data
                        updatedPracticeTasks[taskTitle] = existingProgress.copy(status = "incomplete")
                    }
                }
                savePracticeTasks(updatedPracticeTasks)
                Log.d("DailyProgressManager", "Reset practice tasks completion status - they will now appear uncompleted on the map")
                
                // NOTE: Cloud sync is handled by the caller (e.g., GameActivity) after all updates are complete
            } else {
                val completedCount = visibleOptionalTasks.count { task ->
                    val taskTitle = task.title ?: ""
                    practiceTasks[taskTitle]?.status == "complete"
                }
                Log.d("DailyProgressManager", "Practice tasks not all completed: $completedCount/${visibleOptionalTasks.size} completed")
            }
        } catch (e: Exception) {
            Log.e("DailyProgressManager", "Error checking/resetting practice tasks", e)
        }
    }

    /**
     * Finds a task in config by task ID (task.launch)
     */
    private fun findTaskInConfig(taskId: String, config: MainContent?): Task? {
        if (config == null) return null
        return config.sections?.flatMap { it.tasks?.filterNotNull() ?: emptyList() }
            ?.find { (it.launch ?: "") == taskId }
    }

    /**
     * Checks if a task is completed today
     * NEW: Looks up by task name instead of task ID
     */
    fun isTaskCompleted(taskId: String): Boolean {
        // Need to find task name from config or use taskId as fallback
        val config = try {
            val contentUpdateService = ContentUpdateService()
            val configJson = contentUpdateService.getCachedMainContent(context)
            if (!configJson.isNullOrEmpty()) {
                gson.fromJson(configJson, MainContent::class.java)
            } else null
        } catch (e: Exception) {
            null
        }
        
        val task = findTaskInConfig(taskId, config)
        val taskName = task?.title ?: taskId // Fallback to taskId if name not found
        
        val requiredTasks = getRequiredTasks()
        val isCompleted = requiredTasks[taskName]?.status == "complete"
        Log.d("DailyProgressManager", "isTaskCompleted: taskId=$taskId, taskName=$taskName, isCompleted=$isCompleted, all required tasks: ${requiredTasks.keys}")
        return isCompleted
    }

    /**
     * Gets current progress (earned stars / total possible stars)
     * NEW: Uses cloud format
     */
    fun getCurrentProgress(totalPossibleStars: Int): Pair<Int, Int> {
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format
        val earnedStars = requiredTasks.values.count { it.status == "complete" } // Count completed tasks
        return Pair(earnedStars, totalPossibleStars)
    }

    /**
     * Gets current progress with actual star values from config
     * Only counts required section tasks for progress
     * NEW: Uses task names from cloud format
     */
    fun getCurrentProgressWithActualStars(config: MainContent): Pair<Int, Int> {
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format
        var earnedStars = 0

        val visibleContent = filterVisibleContent(config) // Filter content for visible items

        visibleContent.sections?.forEach { section ->
            // Only count required section tasks for progress
            if (section.id == "required") {
                section.tasks?.forEach { task ->
                    val taskName = task.title ?: "Unknown Task"
                    val stars = task.stars ?: 0

                    // Only required tasks contribute to progress
                    // NEW: Look up by task name instead of task ID
                    if (requiredTasks[taskName]?.status == "complete" && stars > 0) {
                        earnedStars += stars
                    }
                }
            }
            // Note: Optional section tasks are not counted for progress, but they still bank reward time

            section.items?.forEach { item ->
                val itemName = item.label ?: "Unknown Item"
                val stars = item.stars ?: 0

                // All checklist items contribute to total stars in config
                // For progress tracking, count if completed today
                // NEW: Look up by item name (label) instead of item ID
                if (requiredTasks[itemName]?.status == "complete" && stars > 0) {
                    earnedStars += stars
                }
            }
        }

        val (totalCoins, totalStars) = calculateTotalsFromConfig(config)
        return Pair(earnedStars, totalStars)
    }

    /**
     * Calculates total possible coins and stars from config
     * Only counts required section tasks for totals
     */
    fun calculateTotalsFromConfig(config: MainContent): Pair<Int, Int> {
        var totalCoins = 0
        var totalStars = 0

        val visibleContent = filterVisibleContent(config) // Filter content for visible items

        visibleContent.sections?.forEach { section ->
            // Required section tasks
            if (section.id == "required") {
                section.tasks?.forEach { task ->
                    val stars = task.stars ?: 0
                    totalCoins += stars
                    if (stars > 0) {
                        totalStars += stars
                    }
                }
            }
            // Optional (practice) section tasks: include in total coins so progress reflects practice
            if (section.id == "optional") {
                section.tasks?.forEach { task ->
                    val stars = task.stars ?: 0
                    if (stars > 0) {
                        totalCoins += stars
                    }
                }
            }

            // Check for checklist items if they have stars
            section.items?.forEach { item ->
                val stars = item.stars ?: 0
                // ALL checklist items award coins equal to their stars
                if (stars > 0) {
                    totalCoins += stars
                    totalStars += stars
                }
            }
        }

        // Cache the totals for performance (profile-specific)
        val profile = getCurrentKid()
        val totalStarsKey = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        prefs.edit()
            .putInt("total_coins", totalCoins)
            .putInt(totalStarsKey, totalStars)
            .apply()

        Log.d("DailyProgressManager", "Calculated totals from config - Coins: $totalCoins, Stars: $totalStars (required section only)")
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
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format
        var earnedCoins = 0
        var earnedStars = 0

        // We need the config to calculate actual earned amounts
        // This method will be called with the config available
        return Pair(Pair(earnedCoins, totalCoins), Pair(earnedStars, totalStars))
    }

    /**
     * Gets current progress with both coins and stars using actual values
     * Only counts required section tasks for progress
     * NEW: Uses task names from cloud format
     */
    fun getCurrentProgressWithCoinsAndStars(config: MainContent): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format
        val practiceTasks = getPracticeTasks()
        var earnedCoins = 0
        var earnedStars = 0

        val visibleContent = filterVisibleContent(config) // Filter content for visible items

        visibleContent.sections?.forEach { section ->
            // Required section tasks
            if (section.id == "required") {
                section.tasks?.forEach { task ->
                    val taskName = task.title ?: "Unknown Task"
                    val stars = task.stars ?: 0
                    val isCompleted = requiredTasks[taskName]?.status == "complete"

                    Log.d("DailyProgressManager", "getCurrentProgressWithCoinsAndStars: taskName=$taskName, stars=$stars, isCompleted=$isCompleted, requiredTasks keys=${requiredTasks.keys}")

                    if (isCompleted && stars > 0) {
                        earnedStars += stars
                        earnedCoins += stars // Required tasks award coins equal to their stars
                        Log.d("DailyProgressManager", "Added to progress: taskName=$taskName, stars=$stars, total earnedStars=$earnedStars, total earnedCoins=$earnedCoins")
                    }
                }
            }
            // Optional (practice) section tasks: count completed for coins/display
            if (section.id == "optional") {
                section.tasks?.forEach { task ->
                    val taskName = task.title ?: "Unknown Task"
                    val stars = task.stars ?: 0
                    val isCompleted = practiceTasks[taskName]?.status == "complete"
                    if (isCompleted && stars > 0) {
                        earnedCoins += stars // Practice tasks award coins equal to their stars
                    }
                }
            }

            section.items?.forEach { item ->
                val itemName = item.label ?: "Unknown Item"
                val stars = item.stars ?: 0

                // NEW: Look up by item name (label) instead of item ID
                if (requiredTasks[itemName]?.status == "complete" && stars > 0) {
                    earnedStars += stars
                    earnedCoins += stars  // ALL checklist items award coins
                }
            }
        }

        val (totalCoins, totalStars) = calculateTotalsFromConfig(config)
        
        // Subtract spent berries (berries used in battle)
        // Use simple earned berries counter
        val displayEarnedStars = getEarnedBerries()
        
        return Pair(Pair(earnedCoins, totalCoins), Pair(displayEarnedStars, totalStars))
    }

    /**
     * Gets current progress with totals for display (fallback when config not available)
     * Earned coins/stars are computed from required_tasks so coins update after task completion.
     */
    fun getCurrentProgressWithTotals(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val profile = getCurrentKid()
        val totalStarsKey = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        val totalCoins = prefs.getInt("total_coins", 0)
        val totalStars = prefs.getInt(totalStarsKey, 0)

        val requiredTasks = getRequiredTasks()
        // Compute earned coins from completed tasks (required + checklist) so coins update when config isn't passed
        val earnedCoins = requiredTasks.values
            .filter { it.status == "complete" }
            .sumOf { it.stars ?: 0 }
        val displayEarnedStars = getEarnedBerries()

        return Pair(Pair(earnedCoins, totalCoins), Pair(displayEarnedStars, totalStars))
    }

    /**
     * Gets current progress with totals for display (with config for accurate calculation)
     */
    fun getCurrentProgressWithTotals(config: MainContent): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        return getCurrentProgressWithCoinsAndStars(config)
    }

    /**
     * Gets cached total possible stars for the current profile (for when config isn't available)
     */
    fun getCachedTotalPossibleStars(): Int {
        val profile = getCurrentKid()
        val totalStarsKey = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        return prefs.getInt(totalStarsKey, 10) // Default to 10 if not calculated
    }

    /**
     * Gets the date of the last reset for debugging
     */
    fun getLastResetDate(): String {
        return prefs.getString(KEY_LAST_RESET_DATE, "Never") ?: "Never"
    }

    /**
     * Battle Berry Management - tracks berries spent on battles
     */
    
    /**
     * Gets the earned berries count (simple counter that resets to 0 on battle)
     */
    fun getEarnedBerries(): Int {
        val profile = getCurrentKid()
        val key = "${profile}_earnedBerries"
        return context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
            .getInt(key, 0)
    }
    
    /**
     * Sets earned berries (local only). Caller is responsible for syncing to cloud after
     * all local updates (e.g. mark task complete + grant rewards) so one sync pushes everything.
     */
    fun setEarnedBerries(amount: Int) {
        val profile = getCurrentKid()
        val key = "${profile}_earnedBerries"
        // CRITICAL: Use commit() for synchronous write so caller can sync immediately after
        val success = context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
            .edit()
            .putInt(key, amount)
            .commit() // Use commit() for synchronous write
        
        if (!success) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save earned berries!")
        } else {
            Log.d("DailyProgressManager", "CRITICAL: Saved earned berries: $amount for profile: $profile")
        }
        // NOTE: Do NOT trigger sync here. Caller (GameActivity, TrainingMapActivity, BattleHubActivity)
        // updates all local state first, then calls updateLocalTimestampAndSyncToCloud once.
    }
    
    /**
     * Adds to earned berries (when tasks complete)
     */
    fun addEarnedBerries(amount: Int) {
        val current = getEarnedBerries()
        setEarnedBerries(current + amount)
    }

    /**
     * Grants all rewards for a task completion in one place: reward time (banked minutes), berries, and coins.
     * Call this after markTaskCompletedWithName when earnedStars > 0.
     * Ensures time, berries, and (via progress) coins are updated together so no partial rewards occur.
     *
     * @param earnedStars Stars earned from this completion
     * @param sectionId "required", "optional", or null. Required and optional tasks get time + berries; others get time only.
     */
    fun grantRewardsForTaskCompletion(earnedStars: Int, sectionId: String?) {
        if (earnedStars <= 0) return
        addStarsToRewardBank(earnedStars)
        if (sectionId == "required" || sectionId == "optional") {
            addEarnedBerries(earnedStars)
        }
    }
    
    /**
     * Resets earned berries to 0 (when battle happens)
     */
    fun resetEarnedBerries() {
        setEarnedBerries(0)
    }
    
    /**
     * Gets the earned stars count when battle ended (to detect new task completions)
     */
    fun getEarnedStarsAtBattleEnd(): Int {
        return prefs.getInt(KEY_EARNED_STARS_AT_BATTLE_END, 0)
    }
    
    /**
     * Sets the earned stars count when battle ended
     */
    fun setEarnedStarsAtBattleEnd(amount: Int) {
        prefs.edit().putInt(KEY_EARNED_STARS_AT_BATTLE_END, amount).apply()
    }
    
    /**
     * Resets battle end tracking (called when new required tasks are completed)
     */
    fun resetBattleEndTracking() {
        prefs.edit().putInt(KEY_EARNED_STARS_AT_BATTLE_END, 0).apply()
    }
    
    /**
     * Gets earned stars WITHOUT subtracting spent berries (for internal calculations)
     * This is used when we need to know the actual earned stars before battle spending
     * NEW: Uses task names from cloud format
     */
    fun getEarnedStarsWithoutSpentBerries(config: MainContent): Int {
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format
        var earnedStars = 0

        val visibleContent = filterVisibleContent(config)

        visibleContent.sections?.forEach { section ->
            // Only count required section tasks for progress
            if (section.id == "required") {
                section.tasks?.forEach { task ->
                    val taskName = task.title ?: "Unknown Task"
                    val stars = task.stars ?: 0

                    // NEW: Look up by task name instead of task ID
                    if (requiredTasks[taskName]?.status == "complete" && stars > 0) {
                        earnedStars += stars
                    }
                }
            }

            section.items?.forEach { item ->
                val itemName = item.label ?: "Unknown Item"
                val stars = item.stars ?: 0

                // NEW: Look up by item name (label) instead of item ID
                if (requiredTasks[itemName]?.status == "complete" && stars > 0) {
                    earnedStars += stars
                }
            }
        }

        return earnedStars
    }

    /**
     * Gets ALL earned stars including both required and optional tasks (for berry display)
     * This counts stars from all completed tasks regardless of section
     * NEW: Uses task names from cloud format
     */
    fun getAllEarnedStars(config: MainContent): Int {
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format
        var earnedStars = 0

        val visibleContent = filterVisibleContent(config)

        visibleContent.sections?.forEach { section ->
            // Count stars from ALL sections (required, optional, bonus)
            section.tasks?.forEach { task ->
                val taskName = task.title ?: "Unknown Task"
                val stars = task.stars ?: 0
                
                // NEW: Look up by task name (cloud format uses names as keys)
                if (requiredTasks[taskName]?.status == "complete" && stars > 0) {
                    earnedStars += stars
                }
            }

            // Count checklist items from all sections
            section.items?.forEach { item ->
                val itemName = item.label ?: "Unknown Item"
                val stars = item.stars ?: 0

                // NEW: Look up by item name (label) instead of item ID
                if (requiredTasks[itemName]?.status == "complete" && stars > 0) {
                    earnedStars += stars
                }
            }
        }

        // Subtract spent berries (berries used in battle)
        // Simple: just return earned stars (no subtraction needed)
        return earnedStars
    }
    
    /**
     * Gets ALL earned stars including both required and optional tasks WITHOUT subtracting spent berries
     * This is used to get the baseline total when a battle ends
     * NEW: Uses task names from cloud format
     */
    fun getAllEarnedStarsWithoutSpentBerries(config: MainContent): Int {
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format
        var earnedStars = 0

        val visibleContent = filterVisibleContent(config)

        visibleContent.sections?.forEach { section ->
            // Count stars from ALL sections (required, optional, bonus)
            section.tasks?.forEach { task ->
                val taskName = task.title ?: "Unknown Task"
                val stars = task.stars ?: 0
                
                // NEW: Look up by task name (cloud format uses names as keys)
                // All tasks use their title as the key
                if (requiredTasks[taskName]?.status == "complete" && stars > 0) {
                    earnedStars += stars
                }
            }

            // Count checklist items from all sections
            section.items?.forEach { item ->
                val itemName = item.label ?: "Unknown Item"
                val stars = item.stars ?: 0

                // NEW: Look up by item name (label) instead of item ID
                if (requiredTasks[itemName]?.status == "complete" && stars > 0) {
                    earnedStars += stars
                }
            }
        }

        // DON'T subtract spent berries - return total earned stars
        return earnedStars
    }

    /**
     * Pokemon Collection Management
     */

    /**
     * Converts local profile (A/B) to cloud profile format (AM/BM)
     * If already in cloud format, returns as-is
     */
    private fun toCloudProfile(profile: String?): String {
        return when (profile) {
            "A" -> "AM"
            "B" -> "BM"
            else -> profile ?: "AM" // Default to AM if null, or return as-is if already AM/BM
        }
    }

    /**
     * Gets the current number of unlocked Pokemon for the current kid
     */
    fun getUnlockedPokemonCount(): Int {
        val kid = getCurrentKid()
        val cloudKid = toCloudProfile(kid)
        // Check both formats for backward compatibility
        val count = prefs.getInt("${cloudKid}_$KEY_POKEMON_UNLOCKED", 0)
        if (count == 0 && kid != cloudKid) {
            // Try old format as fallback
            val oldCount = prefs.getInt("${kid}_$KEY_POKEMON_UNLOCKED", 0)
            if (oldCount > 0) {
                // Migrate to new format
                setUnlockedPokemonCount(oldCount)
                return oldCount
            }
        }
        return count
    }

    /**
     * Sets the number of unlocked Pokemon for the current kid
     */
    fun setUnlockedPokemonCount(count: Int) {
        val kid = getCurrentKid()
        val cloudKid = toCloudProfile(kid)
        val success1 = prefs.edit().putInt("${cloudKid}_$KEY_POKEMON_UNLOCKED", count).commit() // Use commit() for synchronous write
        if (!success1) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save unlocked Pokemon count!")
        }
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
        val now = java.util.Date()
        val offsetMillis = estTimeZone.getOffset(now.time)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
        val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        val lastUpdatedTimestamp = dateFormat.format(now) + offsetString
        
        val success2 = prefs.edit().putString("${cloudKid}_last_updated_timestamp", lastUpdatedTimestamp).commit() // Use commit() for synchronous write
        if (!success2) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save last_updated_timestamp in setUnlockedPokemonCount!")
        }
        Log.d("DailyProgressManager", "Set unlocked Pokemon count to $count for profile: $cloudKid, updated last_updated: $lastUpdatedTimestamp")
    }

    /**
     * Gets the current kid identifier (A or B, or AM/BM if already converted)
     */
    fun getCurrentKid(): String {
        return SettingsManager.readProfile(context) ?: "AM"
    }

    /**
     * Checks if all coins have been earned today (for showing Pokemon button)
     */
    fun hasAllCoinsBeenEarned(config: MainContent): Boolean {
        val (earnedCoins, totalCoins) = getCurrentProgressWithCoinsAndStars(config).first
        return earnedCoins >= totalCoins && totalCoins > 0
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
     * Gets the current banked reward minutes for the current profile
     */
    fun getBankedRewardMinutes(): Int {
        val profile = getCurrentKid()
        val key = "${profile}_banked_reward_minutes"
        return try {
            prefs.getInt(key, 0)
        } catch (e: ClassCastException) {
            // Handle case where it was previously stored as a String or Float
            try {
                val floatValue = prefs.getFloat(key, 0f)
                floatValue.toInt()
            } catch (e2: ClassCastException) {
                // Fallback for extremely old versions or incorrect storage type
                val stringValue = prefs.getString(key, "0")
                stringValue?.toFloatOrNull()?.toInt() ?: 0
            }
        }
    }

    /**
     * Sets the banked reward minutes for the current profile.
     * Includes validation to prevent suspicious values.
     * Per 000Requirements.md and Daily Reset Logic: sync is full (update_cloud_with_local) only —
     * on game completion or when BattleHub/Trainer Map load runs cloud_sync(). No partial sync.
     */
    fun setBankedRewardMinutes(minutes: Int) {
        // Validate: reward minutes should be non-negative and reasonable (max 1000 minutes = ~16 hours)
        val validatedMinutes = when {
            minutes < 0 -> {
                Log.w("DailyProgressManager", "Attempted to set negative reward minutes ($minutes), setting to 0")
                0
            }
            minutes > 1000 -> {
                Log.w("DailyProgressManager", "Attempted to set suspiciously high reward minutes ($minutes), capping at 1000")
                1000
            }
            else -> minutes
        }
        
        val profile = getCurrentKid()
        val key = "${profile}_banked_reward_minutes"
        val timestampKey = "${profile}_banked_reward_minutes_timestamp"
        
        // Generate timestamp in ISO 8601 format with EST timezone (same format as cloud)
        val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
        val now = java.util.Date()
        val offsetMillis = estTimeZone.getOffset(now.time)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
        val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        val timestamp = dateFormat.format(now) + offsetString
        
        // Update last_updated timestamp (in EST format) as per Daily Reset Logic
        // Reuse the same timestamp since both represent "now" in EST
        val lastUpdatedKey = "${profile}_last_updated"
        val lastUpdatedTimestamp = timestamp
        
        val success = prefs.edit()
            .remove(key) // Explicitly remove to avoid type conflicts
            .putInt(key, validatedMinutes)
            .putString(timestampKey, timestamp) // Store timestamp for this update
            .putString(lastUpdatedKey, lastUpdatedTimestamp) // Update last_updated so next cloud_sync() can push
            .commit() // Use commit() for synchronous write to prevent race conditions
        if (!success) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save banked reward minutes and timestamp!")
        }
        
        Log.d("DailyProgressManager", "Set banked reward minutes to $validatedMinutes (requested: $minutes) for profile: $profile, timestamp: $timestamp, last_updated: $lastUpdatedTimestamp")
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
        val requiredTasks = getRequiredTasks() // NEW: Use cloud format

        // NEW: Task names are now the keys in requiredTasks map
        // Convert to old format for backward compatibility with ComprehensiveProgressReport
        val completedTasks = requiredTasks.mapValues { it.value.status == "complete" }
        val completedTaskNames = requiredTasks.keys.associateWith { it } // Names are keys

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
        val webGameSessions = todaySummary.sessions.filter { it.activityType == "webgame" }
        val chromePageSessions = todaySummary.sessions.filter { it.activityType == "chromepage" }
        val completedGameSessions = todaySummary.completedGameSessions

        // Calculate total correct and incorrect answers across all game sessions (including web games)
        val totalCorrectAnswers = (gameSessions + webGameSessions).sumOf { it.correctAnswers }
        val totalIncorrectAnswers = (gameSessions + webGameSessions).sumOf { it.incorrectAnswers }

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
            webGameSessions = webGameSessions,
            chromePageSessions = chromePageSessions,
            completedGameSessions = completedGameSessions,
            averageGameTimeMinutes = if (gameSessions.isNotEmpty()) gameSessions.map { it.durationMinutes }.average().toInt() else 0,
            averageVideoTimeMinutes = if (videoSessions.isNotEmpty()) videoSessions.map { it.durationMinutes }.average().toInt() else 0,
            longestSessionMinutes = todaySummary.sessions.maxOfOrNull { it.durationMinutes }?.toInt() ?: 0,
            mostPlayedGame = gameSessions.groupBy { it.activityName }.maxByOrNull { it.value.size }?.key,
            totalSessions = todaySummary.sessions.size,
            totalCorrectAnswers = totalCorrectAnswers,
            totalIncorrectAnswers = totalIncorrectAnswers,
            config = config
        )
    }

}
