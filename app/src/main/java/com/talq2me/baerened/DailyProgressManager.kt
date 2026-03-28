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
import java.math.BigDecimal

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
        val config: MainContent? = null, // Config for task matching
        val choresCompletedToday: List<String> = emptyList(), // Chores 4 $$ descriptions where done=true
        val coinsEarnedToday: Int = 0 // Sum of coins_reward for chores completed today
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
        private const val KEY_COINS_EARNED = "coins_earned" // Chores 4 $$ - never reset
        private const val KEY_CHORES = "chores" // Chores 4 $$ - JSON array of ChoreProgress
        @Volatile internal var completedTasksMapCacheKey: String? = null
        @Volatile internal var completedTasksMapCache: Map<String, Boolean>? = null

        /** Shared across all instances so any Activity's progressManager sees last DB fetch. */
        @Volatile var currentSessionData: CloudUserData? = null

        /** Global callback for upload; set from Application. Receives app context and payload to upload. */
        var onUploadNeeded: ((Context, CloudUserData) -> Unit)? = null

        /** Callback to sync a single task/checklist/chore to DB via RPC (af_update_*). Set from Application. */
        var onSyncSingleItemToDb: ((Context, SingleItemUpdate) -> Unit)? = null

        fun setGlobalUploadCallback(callback: (Context, CloudUserData) -> Unit) {
            onUploadNeeded = callback
        }

        fun setGlobalSyncSingleItemCallback(callback: (Context, SingleItemUpdate) -> Unit) {
            onSyncSingleItemToDb = callback
        }
    }

    /** Payload for single-item DB update RPCs (af_update_required_task, etc.). */
    sealed class SingleItemUpdate {
        data class RequiredTask(
            val profile: String,
            val taskTitle: String,
            val status: String,
            val correct: Int?,
            val incorrect: Int?,
            val questions: Int?
        ) : SingleItemUpdate()
        data class PracticeTask(
            val profile: String,
            val taskTitle: String,
            val timesCompleted: Int,
            val stars: Int,
            val correct: Int?,
            val incorrect: Int?,
            val questionsAnswered: Int?
        ) : SingleItemUpdate()
        data class BonusTask(
            val profile: String,
            val taskTitle: String,
            val timesCompleted: Int,
            val stars: Int,
            val correct: Int?,
            val incorrect: Int?,
            val questionsAnswered: Int?
        ) : SingleItemUpdate()
        data class ChecklistItem(val profile: String, val itemLabel: String, val done: Boolean) : SingleItemUpdate()
        data class Chore(val profile: String, val choreId: Int, val done: Boolean) : SingleItemUpdate()
        data class GameIndex(val profile: String, val gameKey: String, val index: Int) : SingleItemUpdate()
        data class PokemonUnlocked(val profile: String, val pokemonUnlocked: Int) : SingleItemUpdate()
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val syncService = CloudSyncService()
    private val userDataRepository = UserDataRepository.getInstance(context.applicationContext)
    private val dataApplier = CloudDataApplier(context) { p, t -> setStoredLastUpdatedTimestamp(p, t) }

    /** Runs one RPC (no session mutation). */
    private suspend fun invokeSingleItemRpcOnly(update: SingleItemUpdate): Result<Unit> {
        val r = when (update) {
            is SingleItemUpdate.RequiredTask -> syncService.invokeAfUpdateRequiredTask(
                update.profile, update.taskTitle, update.status,
                update.correct, update.incorrect, update.questions
            )
            is SingleItemUpdate.PracticeTask -> syncService.invokeAfUpdatePracticeTask(
                update.profile, update.taskTitle, update.timesCompleted, update.stars,
                update.correct, update.incorrect, update.questionsAnswered
            )
            is SingleItemUpdate.BonusTask -> syncService.invokeAfUpdateBonusTask(
                update.profile, update.taskTitle, update.timesCompleted, update.stars,
                update.correct, update.incorrect, update.questionsAnswered
            )
            is SingleItemUpdate.ChecklistItem -> syncService.invokeAfUpdateChecklistItem(
                update.profile, update.itemLabel, update.done
            )
            is SingleItemUpdate.Chore -> syncService.invokeAfUpdateChore(update.profile, update.choreId, update.done)
            is SingleItemUpdate.GameIndex -> syncService.invokeAfUpdateGameIndex(update.profile, update.gameKey, update.index)
            is SingleItemUpdate.PokemonUnlocked -> syncService.invokeAfUpdatePokemonUnlocked(update.profile, update.pokemonUnlocked)
        }
        if (r.isFailure) {
            Log.e("DailyProgressManager", "RPC failed for ${update::class.simpleName}: ${r.exceptionOrNull()?.message}")
        }
        return r
    }

    /** Replaces session + prefs mirror from DB row (gold standard). */
    suspend fun refetchSessionFromDb(cloudProfile: String): Result<Unit> = withContext(Dispatchers.IO) {
        val fetch = userDataRepository.fetchUserData(cloudProfile)
        if (fetch.isFailure) {
            Log.e("DailyProgressManager", "refetchSessionFromDb failed: ${fetch.exceptionOrNull()?.message}")
            return@withContext Result.failure(fetch.exceptionOrNull() ?: Exception("Fetch failed"))
        }
        val data = fetch.getOrNull()
        if (data != null) {
            dataApplier.applyDbDataToPrefs(data)
            setProgressDataAfterFetch(data)
        } else {
            val empty = CloudUserData(profile = cloudProfile)
            dataApplier.applyDbDataToPrefs(empty)
            setProgressDataAfterFetch(empty)
        }
        Result.success(Unit)
    }

    /**
     * Writes each update to the DB via RPC in order, then **one** read from DB to refresh session.
     * On any RPC failure, session is unchanged and the failure is returned.
     */
    suspend fun applyRpcChainThenRefetch(updates: List<SingleItemUpdate>): Result<Unit> {
        if (updates.isEmpty()) return Result.success(Unit)
        for (u in updates) {
            val step = invokeSingleItemRpcOnly(u)
            if (step.isFailure) return step
        }
        val cloudProfile = when (val first = updates.first()) {
            is SingleItemUpdate.RequiredTask -> first.profile
            is SingleItemUpdate.PracticeTask -> first.profile
            is SingleItemUpdate.BonusTask -> first.profile
            is SingleItemUpdate.ChecklistItem -> first.profile
            is SingleItemUpdate.Chore -> first.profile
            is SingleItemUpdate.GameIndex -> first.profile
            is SingleItemUpdate.PokemonUnlocked -> first.profile
        }
        return refetchSessionFromDb(cloudProfile)
    }

    fun setProgressDataAfterFetch(data: CloudUserData?) {
        currentSessionData = data
        invalidateCompletedTasksMapCache()
    }

    fun clearProgressDataAfterRequest() {
        currentSessionData = null
        invalidateCompletedTasksMapCache()
    }

    /** Session data for profile. Shared globally; null if no fetch yet. */
    fun getCurrentSessionData(profile: String): CloudUserData? =
        currentSessionData?.takeIf { it.profile == toCloudProfile(profile) }

    /** @deprecated No prefs; use getCurrentSessionData */
    private fun getProgressDataForRequest(profile: String): CloudUserData? = getCurrentSessionData(profile)

    fun getProgressDataForUpload(profile: String): CloudUserData? = getCurrentSessionData(profile)

    /** Game index from session data (last DB fetch). No prefs. */
    fun getGameIndexFromCache(profile: String, gameKey: String): Int {
        val data = getCurrentSessionData(profile)
        return data?.gameIndices?.get(gameKey) ?: 0
    }

    /**
     * Persists game index: RPC + DB refetch. Session is only updated from the returned row.
     */
    suspend fun updateGameIndexInDbSync(profile: String, gameKey: String, index: Int): Result<Unit> {
        if (getCurrentSessionData(profile) == null) {
            return Result.failure(Exception("No session; load progress first"))
        }
        return applyRpcChainThenRefetch(
            listOf(SingleItemUpdate.GameIndex(toCloudProfile(profile), gameKey, index))
        )
    }

    /** Uses passed-in data, or current session data from last DB fetch. No prefs. */
    private fun dataForProfile(profile: String, progressData: CloudUserData?): CloudUserData? {
        if (progressData != null && progressData.profile == toCloudProfile(profile)) return progressData
        return currentSessionData?.takeIf { it.profile == toCloudProfile(profile) }
    }

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
     * Gets the current date as a string for daily reset tracking. Uses EST.
     */
    private fun getCurrentDateString(): String {
        val format = SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault())
        format.timeZone = TimeZone.getTimeZone("America/Toronto")
        val date = format.format(Date())
        Log.d("DailyProgressManager", "Current date string (EST): $date")
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
                                        // user_data.last_reset is returned in EST — parse as EST, do not convert
                                        try {
                                            val estTimeZone = java.util.TimeZone.getTimeZone("America/Toronto")
                                            
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
                        setStoredLastUpdatedTimestamp(profile, resetTimestamp)
                        Log.d("DailyProgressManager", "Successfully synced daily reset to cloud for profile: $profile, timestamp: $resetTimestamp")
                    } else {
                        Log.e("DailyProgressManager", "Failed to sync daily reset to cloud: ${resetResult.exceptionOrNull()?.message}")
                    }
                } else {
                    // Cloud not enabled, just update local timestamp
                    setStoredLastUpdatedTimestamp(profile, resetTimestamp)
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
    private fun setStoredLastUpdatedTimestamp(profile: String, timestamp: String) {
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        val storedTimestampKey = "${profile}_last_updated_timestamp"
        val success = progressPrefs.edit()
            .putString(storedTimestampKey, timestamp)
            .commit() // Use commit() for synchronous write to prevent race conditions
        if (!success) {
            Log.e("DailyProgressManager", "CRITICAL ERROR: Failed to save last_updated_timestamp in setStoredLastUpdatedTimestamp!")
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
            timestampFormat.timeZone = TimeZone.getTimeZone("America/Toronto")
            val resetDate = timestampFormat.parse(profileLastReset)
            
            if (resetDate == null) {
                Log.w("DailyProgressManager", "checkIfResetNeeded: Failed to parse profile_last_reset: $profileLastReset - reset needed")
                return@withContext true
            }
            
            // Get today's date in EST
            val today = Calendar.getInstance(TimeZone.getTimeZone("America/Toronto"))
            val resetCalendar = Calendar.getInstance(TimeZone.getTimeZone("America/Toronto"))
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
                                val estTimeZone = TimeZone.getTimeZone("America/Toronto")
                                
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
            timestampFormat.timeZone = TimeZone.getTimeZone("America/Toronto")
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
        val completedTasksKey = "${profile}_$KEY_COMPLETED_TASKS"
        val completedTaskNamesKey = "${profile}_$KEY_COMPLETED_TASK_NAMES"
        val totalStarsKey = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        val requiredTasksKey = "${profile}_$KEY_REQUIRED_TASKS"
        val practiceTasksKey = "${profile}_$KEY_PRACTICE_TASKS"
        val checklistItemsKey = "${profile}_checklist_items"
        
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

        // Reset banked minutes in cache only (no prefs; DB is source of truth)
        setBankedRewardMinutesForProfile(profile, 0)

        // Reset local storage (no banked_reward_minutes key — cache/DB only)
        val success = prefs.edit()
            .putString(completedTasksKey, gson.toJson(emptyMap<String, Boolean>()))
            .putString(completedTaskNamesKey, gson.toJson(emptyMap<String, String>()))
            .putString(requiredTasksKey, gson.toJson(resetRequiredTasks))
            .putString(practiceTasksKey, gson.toJson(resetPracticeTasks))
            .putString(checklistItemsKey, "{}")
            .putString(KEY_LAST_RESET_DATE, currentDate)
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
     * Clears the banked reward minutes for the current profile. Cache only; sync will persist to DB.
     */
    fun clearBankedRewardMinutes() {
        setBankedRewardMinutes(0)
        Log.d("DailyProgressManager", "Banked reward minutes cleared for current profile (cache only)")
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
     * Gets required tasks from the passed-in data (from a fetch). No in-memory storage; caller passes DB result.
     */
    /** Reads required_tasks from session data (last DB fetch). No prefs. */
    private fun getRequiredTasks(progressData: CloudUserData? = null): MutableMap<String, TaskProgress> {
        val data = dataForProfile(getCurrentKid(), progressData)
        return (data?.requiredTasks)?.toMutableMap() ?: mutableMapOf()
    }

    private fun getRequiredTasksForUpdate(progressData: CloudUserData? = null): MutableMap<String, TaskProgress> {
        return getRequiredTasks(progressData)
    }

    /** Builds updated CloudUserData with new required tasks (caller must upload to DB). No storage. */
    private fun buildWithRequiredTasks(progressData: CloudUserData?, requiredTasks: Map<String, TaskProgress>): CloudUserData {
        val profile = getCurrentKid()
        val cloudProfile = toCloudProfile(profile)
        return progressData?.copy(requiredTasks = requiredTasks)
            ?: CloudUserData(profile = cloudProfile, requiredTasks = requiredTasks)
    }

    /** Checklist_items are a separate DB column; never store them in required_tasks. */
    private val checklistItemsType = object : TypeToken<Map<String, ChecklistItemProgress>>() {}.type

    /** Checklist items from session data (last DB fetch). No prefs. */
    fun getChecklistItemsFromPrefs(profile: String): Map<String, ChecklistItemProgress> {
        val data = currentSessionData?.takeIf { it.profile == toCloudProfile(profile) }
        return data?.checklistItems ?: emptyMap()
    }

    /**
     * Marks a checklist item complete: RPC then refetch from DB. Session matches DB after success.
     */
    suspend fun markChecklistItemCompleted(itemLabel: String, stars: Int, displayDays: String? = null): Result<Int> {
        val profile = getCurrentKid()
        val current = getCurrentSessionData(profile)
            ?: return Result.failure(Exception("No session; load progress first"))
        val wasDone = current.checklistItems[itemLabel]?.done == true
        if (wasDone) return Result.success(0)
        val sync = applyRpcChainThenRefetch(
            listOf(
                SingleItemUpdate.ChecklistItem(
                    profile = toCloudProfile(profile),
                    itemLabel = itemLabel,
                    done = true
                )
            )
        )
        if (sync.isFailure) {
            return Result.failure(sync.exceptionOrNull() ?: Exception("Could not save checklist to server"))
        }
        return Result.success(stars)
    }

    /** Chore toggle: RPC then DB refetch. */
    suspend fun notifyChoreUpdated(profile: String, choreId: Int, done: Boolean): Result<Unit> {
        return applyRpcChainThenRefetch(
            listOf(SingleItemUpdate.Chore(toCloudProfile(profile), choreId, done))
        )
    }

    /** True when progressData is non-null for current profile (caller passes result of fetch). */
    fun hasProgressDataAvailable(progressData: CloudUserData? = null): Boolean {
        return dataForProfile(getCurrentKid(), progressData) != null
    }

    /**
     * Gets the completion status map for today (public method for batch reads).
     * Required, practice (optional), and bonus maps are separate; there is no combined case.
     * Keys: task TITLE for required/optional/bonus; checklist items by label. UI lookups use task.title (not task.launch).
     *
     * @param mapType "required", "optional", or "bonus". Other values return empty map.
     */
    fun getCompletedTasksMap(mapType: String? = null, progressData: CloudUserData? = null): Map<String, Boolean> {
        val profile = getCurrentKid()
        val cacheKey = "$profile|${mapType ?: "none"}"
        if (completedTasksMapCacheKey == cacheKey && completedTasksMapCache != null) {
            return completedTasksMapCache!!
        }
        val allTasks = mutableMapOf<String, Boolean>()
        val data = dataForProfile(profile, progressData)
        val checklistItems = data?.checklistItems ?: getChecklistItemsFromPrefs(profile)
        when (mapType) {
            "required" -> {
                val requiredTasks = getRequiredTasks(progressData)
                requiredTasks.forEach { (name, progress) ->
                    allTasks[name] = progress.status == "complete"
                }
                checklistItems.forEach { (name, progress) ->
                    if (progress.done) allTasks[name] = true
                }
            }
            "optional" -> {
                val practiceTasks = getPracticeTasks(progressData)
                practiceTasks.forEach { (name, progress) ->
                    allTasks[name] = progress.status == "complete"
                }
            }
            "bonus" -> {
                // Bonus tasks never show completion status; always show as playable (never green)
                val bonusTasks = getBonusTasks(progressData)
                bonusTasks.forEach { (name, _) ->
                    allTasks[name] = false
                }
            }
            else -> { /* No combined map; required/optional/bonus are separate. Return empty. */ }
        }
        completedTasksMapCacheKey = cacheKey
        completedTasksMapCache = allTasks.toMap()
        return completedTasksMapCache!!
    }

    internal fun invalidateCompletedTasksMapCache() {
        completedTasksMapCacheKey = null
        completedTasksMapCache = null
    }

    /**
     * Gets the required tasks map in new format (task names → TaskProgress)
     */
    fun getRequiredTasksMap(): Map<String, TaskProgress> {
        return getRequiredTasks()
    }
    
    /** Practice tasks (optional section) from session data. Display-incomplete set can override status. */
    private fun getPracticeTasks(progressData: CloudUserData? = null): MutableMap<String, TaskProgress> {
        val practice = dataForProfile(getCurrentKid(), progressData)?.practiceTasks ?: return mutableMapOf()
        return practice.mapValues { (_, p) ->
            val showComplete = p.timesCompleted > 0
            TaskProgress(
                status = if (showComplete) "complete" else "incomplete",
                correct = p.correct,
                incorrect = p.incorrect,
                questions = p.questionsAnswered,
                stars = null,
                showdays = p.showdays,
                hidedays = p.hidedays,
                displayDays = p.displayDays,
                disable = p.disable
            )
        }.toMutableMap()
    }

    /** Bonus tasks (bonus section) from session data. No display-incomplete reset (bonus do not reset to do-again). */
    private fun getBonusTasks(progressData: CloudUserData? = null): MutableMap<String, TaskProgress> {
        val bonus = dataForProfile(getCurrentKid(), progressData)?.bonusTasks ?: return mutableMapOf()
        return bonus.mapValues { (_, p) ->
            val showComplete = p.timesCompleted > 0
            TaskProgress(
                status = if (showComplete) "complete" else "incomplete",
                correct = p.correct,
                incorrect = p.incorrect,
                questions = p.questionsAnswered,
                stars = null,
                showdays = p.showdays,
                hidedays = p.hidedays,
                displayDays = p.displayDays,
                disable = p.disable
            )
        }.toMutableMap()
    }
    
    /**
     * Gets the practice tasks map in new format (task names → TaskProgress)
     */
    fun getPracticeTasksMap(): Map<String, TaskProgress> {
        return getPracticeTasks()
    }

    /** No-op: progress is in session data only; task completion uses merge + onSyncSingleItemToDb (RPC). */
    private fun saveRequiredTasks(requiredTasks: Map<String, TaskProgress>, progressData: CloudUserData? = null) {
        invalidateCompletedTasksMapCache()
    }

    /** No-op: progress is in session data only; task completion uses merge + onSyncSingleItemToDb (RPC). */
    private fun savePracticeTasks(practiceTasks: Map<String, TaskProgress>, taskNameJustCompleted: String? = null) {
        invalidateCompletedTasksMapCache()
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
    suspend fun markTaskCompleted(taskId: String, stars: Int, isRequiredTask: Boolean = false): Result<Int> {
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
     * Canonical identifier for completion tracking: section + title.
     * Falls back to section + taskId when title is missing.
     */
    fun getUniqueTaskId(taskId: String, taskTitle: String?, sectionId: String?): String {
        val normalizedTitle = taskTitle?.trim()?.takeIf { it.isNotEmpty() }
        return if (sectionId != null && normalizedTitle != null) {
            "$sectionId::$normalizedTitle"
        } else {
            getUniqueTaskId(taskId, sectionId)
        }
    }

    /**
     * Marks a task complete: RPC(s) then **one** DB refetch. Session is only updated from the DB row.
     * [preRpcUpdates] runs first (e.g. [SingleItemUpdate.GameIndex] with game completion index).
     */
    suspend fun markTaskCompletedWithName(
        taskId: String,
        taskName: String,
        stars: Int,
        isRequiredTask: Boolean = false,
        config: MainContent? = null,
        sectionId: String? = null,
        correctAnswers: Int? = null,
        incorrectAnswers: Int? = null,
        questionsAnswered: Int? = null,
        preRpcUpdates: List<SingleItemUpdate> = emptyList()
    ): Result<Int> {
        Log.d("DailyProgressManager", "markTaskCompletedWithName called: taskId=$taskId, taskName=$taskName, stars=$stars, isRequiredTask=$isRequiredTask, sectionId=$sectionId")
        val profile = getCurrentKid()
        val current = getCurrentSessionData(profile)
        if (current == null) {
            Log.w("DailyProgressManager", "markTaskCompletedWithName: no session data for $profile; ensure fetch ran first")
            return Result.failure(Exception("No session; load progress first"))
        }

        val uniqueTaskId = getUniqueTaskId(taskId, taskName, sectionId)
        val actualIsRequired = when {
            sectionId != null -> sectionId == "required"
            config != null -> isTaskFromRequiredSection(taskId, config)
            else -> isRequiredTask
        }

        Log.d("DailyProgressManager", "markTaskCompletedWithName: taskId=$taskId, uniqueTaskId=$uniqueTaskId, taskName=$taskName, actualIsRequired=$actualIsRequired")

        if (actualIsRequired) {
            val task = findTaskInConfig(taskId, taskName, sectionId, config)
            val storageKey = task?.takeIf { it.title == taskName }?.title ?: taskName
            val existingProgress = current.requiredTasks[storageKey] ?: current.requiredTasks[taskName]
            val checklistStars = findChecklistItemStarsInConfig(storageKey, config)
            val taskStars = task?.stars ?: checklistStars ?: stars
            val wasAlreadyComplete = existingProgress?.status == "complete"
            if (wasAlreadyComplete) return Result.success(0)
            val taskUpdate = SingleItemUpdate.RequiredTask(
                profile = toCloudProfile(profile),
                taskTitle = storageKey,
                status = "complete",
                correct = correctAnswers,
                incorrect = incorrectAnswers,
                questions = questionsAnswered
            )
            val chain = preRpcUpdates + taskUpdate
            val sync = applyRpcChainThenRefetch(chain)
            if (sync.isFailure) {
                return Result.failure(sync.exceptionOrNull() ?: Exception("Could not save task to server"))
            }
            Log.d("DailyProgressManager", "Required task $taskId ($taskName) completed, earned $taskStars stars (DB refetched)")
            return Result.success(taskStars)
        } else {
            val task = findTaskInConfig(taskId, taskName, sectionId, config)
            val checklistStars = findChecklistItemStarsInConfig(taskName, config)
            val taskStars = task?.stars ?: checklistStars ?: stars
            val isBonus = sectionId == "bonus"
            if (isBonus) {
                val existing = current.bonusTasks[taskName]
                val newTc = (existing?.timesCompleted ?: 0) + 1
                val taskUpdate = SingleItemUpdate.BonusTask(
                    profile = toCloudProfile(profile),
                    taskTitle = taskName,
                    timesCompleted = newTc,
                    stars = taskStars,
                    correct = (existing?.correct ?: 0) + (correctAnswers ?: 0),
                    incorrect = (existing?.incorrect ?: 0) + (incorrectAnswers ?: 0),
                    questionsAnswered = (existing?.questionsAnswered ?: 0) + (questionsAnswered ?: 0)
                )
                val chain = preRpcUpdates + taskUpdate
                val sync = applyRpcChainThenRefetch(chain)
                if (sync.isFailure) {
                    return Result.failure(sync.exceptionOrNull() ?: Exception("Could not save bonus task to server"))
                }
                Log.d("DailyProgressManager", "Bonus task $taskId ($taskName) completed, earned $taskStars stars (DB refetched)")
                return Result.success(taskStars)
            } else {
                val existing = current.practiceTasks[taskName]
                val newTc = (existing?.timesCompleted ?: 0) + 1
                val taskUpdate = SingleItemUpdate.PracticeTask(
                    profile = toCloudProfile(profile),
                    taskTitle = taskName,
                    timesCompleted = newTc,
                    stars = taskStars,
                    correct = (existing?.correct ?: 0) + (correctAnswers ?: 0),
                    incorrect = (existing?.incorrect ?: 0) + (incorrectAnswers ?: 0),
                    questionsAnswered = (existing?.questionsAnswered ?: 0) + (questionsAnswered ?: 0)
                )
                val chain = preRpcUpdates + taskUpdate
                val sync = applyRpcChainThenRefetch(chain)
                if (sync.isFailure) {
                    return Result.failure(sync.exceptionOrNull() ?: Exception("Could not save practice task to server"))
                }
                Log.d("DailyProgressManager", "Practice task $taskId ($taskName) completed, earned $taskStars stars (DB refetched)")
                return Result.success(taskStars)
            }
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
     * Finds a task in config by task ID (task.launch)
     */
    private fun findTaskInConfig(taskId: String, taskName: String, sectionId: String?, config: MainContent?): Task? {
        if (config == null) return null
        val sections = config.sections ?: return null

        // First choice: exact section + exact title.
        sections.firstOrNull { it.id == sectionId }
            ?.tasks
            ?.filterNotNull()
            ?.firstOrNull { (it.title ?: "") == taskName }
            ?.let { return it }

        // Second choice: exact title anywhere (handles old callers that don't pass section).
        sections.asSequence()
            .flatMap { (it.tasks ?: emptyList()).asSequence().filterNotNull() }
            .firstOrNull { (it.title ?: "") == taskName }
            ?.let { return it }

        // Last resort only: launch id.
        return sections.asSequence()
            .flatMap { (it.tasks ?: emptyList()).asSequence().filterNotNull() }
            .firstOrNull { (it.launch ?: "") == taskId }
    }

    /** Finds a checklist item in config by label (taskName) and returns its star value for reward calculation. */
    private fun findChecklistItemStarsInConfig(taskName: String, config: MainContent?): Int? {
        if (config == null) return null
        return config.sections?.flatMap { it.items?.filterNotNull() ?: emptyList() }
            ?.find { (it.label ?: "") == taskName }
            ?.stars
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
        
        val task = findTaskInConfig(taskId, taskId, null, config)
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
        var earnedStars = 0

        val visibleContent = filterVisibleContent(config) // Filter content for visible items

        visibleContent.sections?.forEach { section ->
            // Required section tasks (berries/stars only; coins come from Chores 4 $$)
            if (section.id == "required") {
                section.tasks?.forEach { task ->
                    val taskName = task.title ?: "Unknown Task"
                    val stars = task.stars ?: 0
                    val isCompleted = requiredTasks[taskName]?.status == "complete"

                    Log.d("DailyProgressManager", "getCurrentProgressWithCoinsAndStars: taskName=$taskName, stars=$stars, isCompleted=$isCompleted, requiredTasks keys=${requiredTasks.keys}")

                    if (isCompleted && stars > 0) {
                        earnedStars += stars
                        Log.d("DailyProgressManager", "Added to progress: taskName=$taskName, stars=$stars, total earnedStars=$earnedStars")
                    }
                }
            }
            // Optional (practice) section tasks: count for display only (no coins from tasks)
            if (section.id == "optional") {
                section.tasks?.forEach { task ->
                    val taskName = task.title ?: "Unknown Task"
                    val stars = task.stars ?: 0
                    val isCompleted = practiceTasks[taskName]?.status == "complete"
                    if (isCompleted && stars > 0) {
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

        val (_, totalStars) = calculateTotalsFromConfig(config)
        // Coins come only from Chores 4 $$; no total cap for display
        val earnedCoins = getCoinsEarned()
        val totalCoins = 0

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
        val totalStars = prefs.getInt(totalStarsKey, 0)

        // Coins come only from Chores 4 $$; no total from tasks
        val earnedCoins = getCoinsEarned()
        val totalCoins = 0
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
     * Gets cached total possible stars for the current profile (for when config isn't available).
     * Prefers DB-backed cache when available.
     */
    /** Reads from prefs (updated when we apply cloud data). No cache. */
    fun getCachedTotalPossibleStars(): Int {
        val profile = getCurrentKid()
        val key = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        return prefs.getInt(key, 0)
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
    
    /** Earned berries from session data (last DB fetch). No prefs. */
    fun getEarnedBerries(): Int {
        val data = dataForProfile(getCurrentKid(), null)
        return data?.berriesEarned ?: 0
    }

    /** user_data.possible_stars from last DB fetch (session). */
    fun getPossibleStarsFromSession(): Int {
        return dataForProfile(getCurrentKid(), null)?.possibleStars ?: 0
    }

    /**
     * True when every **visible today** required task and every **visible** checklist item (with stars) is done.
     * Uses `required_tasks` / `checklist_items` from the last DB fetch only — no GitHub config.
     */
    fun areAllVisibleRequiredAndChecklistCompleteFromDb(): Boolean {
        val data = dataForProfile(getCurrentKid(), null) ?: return false
        var anyVisible = false

        data.requiredTasks.forEach { (_, tp) ->
            if (!TaskVisibilityChecker.isTaskVisible(tp.showdays, tp.hidedays, tp.displayDays, tp.disable)) return@forEach
            anyVisible = true
            if (tp.status != "complete") return false
        }

        data.checklistItems.forEach { (_, cp) ->
            if (cp.stars <= 0) return@forEach
            if (!TaskVisibilityChecker.isTaskVisible(null, null, cp.displayDays, null)) return@forEach
            anyVisible = true
            if (!cp.done) return false
        }

        // Nothing visible today: only treat as "all done" if there is literally nothing in DB.
        if (!anyVisible) {
            return data.requiredTasks.isEmpty() && data.checklistItems.isEmpty()
        }
        return true
    }

    /** Updates session data with earned berries (display only). DB berries are updated only by task/checklist RPCs when status=complete or done=true. */
    fun setEarnedBerries(amount: Int) {
        val profile = getCurrentKid()
        val current = getCurrentSessionData(profile) ?: return
        currentSessionData = current.copy(berriesEarned = amount)
        Log.d("DailyProgressManager", "Set earned berries: $amount for profile: $profile (session only; DB updated by task RPCs)")
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
     * The task/checklist RPCs (af_update_required_task, af_update_practice_task, af_update_checklist_item)
     * already update berries_earned and banked_mins in the DB when status=complete or done=true; we only update local session here for display.
     *
     * @param earnedStars Stars earned from this completion
     * @param sectionId "required", "optional", or null. Required and optional tasks get time + berries; others get time only.
     */
    fun grantRewardsForTaskCompletion(earnedStars: Int, sectionId: String?) {
        if (earnedStars <= 0) return
        val profile = getCurrentKid()
        val current = getCurrentSessionData(profile) ?: return
        val addedMinutes = convertStarsToMinutes(earnedStars)
        val newBanked = (current.bankedMins + addedMinutes).coerceIn(0, 1000)
        val newBerries = if (sectionId == "required" || sectionId == "optional" || sectionId == "bonus") {
            current.berriesEarned + earnedStars
        } else {
            current.berriesEarned
        }
        currentSessionData = current.copy(berriesEarned = newBerries, bankedMins = newBanked)
        Log.d("DailyProgressManager", "grantRewardsForTaskCompletion: +$earnedStars stars, session now berries=$newBerries banked=$newBanked (DB already updated by task RPC)")
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
     * Prefers DB-backed cache when available.
     */
    /** Prefers last DB-backed session; then prefs. */
    fun getUnlockedPokemonCount(): Int {
        val kid = getCurrentKid()
        val cloud = toCloudProfile(kid)
        currentSessionData?.takeIf { it.profile == cloud }?.let { return it.pokemonUnlocked }
        return prefs.getInt("${kid}_$KEY_POKEMON_UNLOCKED", 0)
    }

    /** Pokemon count: RPC then DB refetch (prefs/session from row). */
    suspend fun setUnlockedPokemonCount(count: Int): Result<Unit> {
        val kid = getCurrentKid()
        val cloudProfile = toCloudProfile(kid)
        return applyRpcChainThenRefetch(listOf(SingleItemUpdate.PokemonUnlocked(cloudProfile, count)))
    }

    /**
     * Gets the current kid identifier (A or B, or AM/BM if already converted)
     */
    fun getCurrentKid(): String {
        return SettingsManager.readProfile(context) ?: "AM"
    }

    /**
     * Coins earned (Chores 4 $$) - never reset. Battle Hub displays this.
     * Prefers DB-backed cache when available.
     */
    /** Reads from prefs (updated when we apply cloud data or addCoinsEarned). No cache. */
    fun getCoinsEarned(profile: String? = null): Int {
        val p = profile ?: getCurrentKid()
        return prefs.getInt("${p}_$KEY_COINS_EARNED", 0)
    }

    /**
     * Kids virtual bank balance (parent adds/subtracts). Never reset.
     * This is read from the last DB fetch session data so app UI can display it,
     * while uploads/payloads omit it when unknown (nullable in CloudUserData).
     */
    fun getKidBankBalance(profile: String? = null): BigDecimal {
        val p = profile ?: getCurrentKid()
        return dataForProfile(p, null)?.kidBankBalance ?: BigDecimal.ZERO
    }

    /**
     * Add (or subtract) coins and return new total. Used when checking/unchecking chores.
     */
    fun addCoinsEarned(profile: String? = null, delta: Int): Int {
        val p = profile ?: getCurrentKid()
        val key = "${p}_$KEY_COINS_EARNED"
        val current = prefs.getInt(key, 0)
        val newVal = (current + delta).coerceAtLeast(0)
        prefs.edit().putInt(key, newVal).commit()
        return newVal
    }

    /**
     * Chores list (Chores 4 $$) for the profile. Prefers session data (from last DB fetch), then prefs.
     * Call loadChoresFromJsonIfNeeded() first if empty.
     */
    fun getChores(profile: String? = null): List<ChoreProgress> {
        val p = profile ?: getCurrentKid()
        val fromSession = currentSessionData?.takeIf { it.profile == toCloudProfile(p) }?.chores
        if (!fromSession.isNullOrEmpty()) return fromSession
        // No local/prefs fallback: chores must come from the last DB fetch/reset.
        Log.w("DailyProgressManager", "getChores: returning empty (no DB/session chores) for profile: $p")
        return emptyList()
    }

    /** Writes to prefs; caller must sync to DB. */
    fun saveChores(profile: String? = null, chores: List<ChoreProgress>) {
        val p = profile ?: getCurrentKid()
        prefs.edit().putString("${p}_chores", gson.toJson(chores)).apply()
        Log.d("DailyProgressManager", "Chores updated for profile: $p (${chores.size} items); persist via DB write")
    }

    /**
     * Load chores from config/chores.json (GitHub first, then cache, then assets — same as other config).
     * Merge with stored (by chore_id). If stored is empty, populate with file list (done=false).
     * Call from getContentFromJson and Chores screen.
     */
    fun loadChoresFromJsonIfNeeded(profile: String? = null) {
        // Intentionally disabled: chores must be DB-backed. If the DB/restore fails,
        // we want the UI to show empty + error (not silently repopulate from local GitHub/assets).
        Log.w("DailyProgressManager", "loadChoresFromJsonIfNeeded: ignored; chores must come from DB for profile=${profile ?: getCurrentKid()}")
    }

    private data class ChoreJsonItem(val id: Int, val description: String, val coins: Int)

    /**
     * Unlocks additional Pokemon (admin function). RPC + refetch on success.
     */
    suspend fun unlockPokemon(count: Int): Result<Unit> {
        val currentCount = getUnlockedPokemonCount()
        return setUnlockedPokemonCount(currentCount + count)
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
     * Whether to show the Pokemon unlock button. Coins are no longer tied to tasks; always false.
     */
    fun shouldShowPokemonUnlockButton(config: MainContent): Boolean {
        return false
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

    /** Banked minutes from session data (last DB fetch). No prefs. */
    fun getBankedRewardMinutes(): Int {
        val data = dataForProfile(getCurrentKid(), null)
        return data?.bankedMins ?: 0
    }

    fun setBankedRewardMinutes(minutes: Int) {
        setBankedRewardMinutesForProfile(getCurrentKid(), minutes)
    }

    /** Updates session data with banked minutes (display only). DB banked_mins is updated only by task/checklist RPCs when status=complete or done=true. */
    fun setBankedRewardMinutesForProfile(profile: String, minutes: Int) {
        val validatedMinutes = when {
            minutes < 0 -> 0
            minutes > 1000 -> 1000
            else -> minutes
        }
        val current = getCurrentSessionData(profile) ?: return
        currentSessionData = current.copy(bankedMins = validatedMinutes)
        Log.d("DailyProgressManager", "Set banked minutes: $validatedMinutes for profile: $profile (session only; DB updated by task RPCs)")
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

        val chores = getChores()
        val doneChores = chores.filter { it.done }
        val choresCompletedToday = doneChores.map { it.description }
        val coinsEarnedToday = doneChores.sumOf { it.coinsReward }

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
            config = config,
            choresCompletedToday = choresCompletedToday,
            coinsEarnedToday = coinsEarnedToday
        )
    }

}
