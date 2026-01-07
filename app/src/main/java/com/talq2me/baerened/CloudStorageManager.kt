package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

// Import data classes from MainActivity
import com.talq2me.baerened.MainContent
import com.talq2me.baerened.Task

/**
 * Manages cloud storage for user settings and preferences using Supabase REST API
 * This works alongside local storage and can be toggled on/off
 * Supabase credentials are embedded in BuildConfig at build time
 */
class CloudStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudStorageManager"
        private const val PREFS_NAME = "cloud_storage_prefs"
        private const val KEY_USE_CLOUD = "use_cloud_storage"
        private const val KEY_LAST_SYNC = "last_sync_timestamp"
        private const val KEY_CURRENT_PROFILE = "current_cloud_profile"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson() // Default behavior excludes null values from JSON serialization
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Gets Supabase URL from BuildConfig (set at build time from local.properties)
     */
    private fun getSupabaseUrl(): String {
        return try {
            BuildConfig.SUPABASE_URL.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase URL from BuildConfig", e)
            ""
        }
    }

    /**
     * Gets Supabase API key from BuildConfig (set at build time from local.properties)
     */
    private fun getSupabaseKey(): String {
        return try {
            BuildConfig.SUPABASE_KEY.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase key from BuildConfig", e)
            ""
        }
    }

    /**
     * Checks if Supabase is configured (has URL and key in BuildConfig)
     */
    fun isConfigured(): Boolean {
        val url = getSupabaseUrl()
        val key = getSupabaseKey()
        return url.isNotBlank() && key.isNotBlank()
    }

    /**
     * Data class representing all user data stored in cloud
     */
    data class CloudUserData(
        val profile: String, // "AM" or "BM"

        // Daily reset timestamp
        @SerializedName("last_reset") val lastReset: String? = null,

        // Required tasks progress (name: complete/incomplete, #correct/#incorrect, #questions)
        @SerializedName("required_tasks") val requiredTasks: Map<String, TaskProgress> = emptyMap(),

        // Practice tasks progress (name: # times_completed, #correct/#incorrect, #questions_answered)
        @SerializedName("practice_tasks") val practiceTasks: Map<String, PracticeProgress> = emptyMap(),

        // Progress metrics
        @SerializedName("possible_stars") val possibleStars: Int = 0,
        @SerializedName("banked_mins") val bankedMins: Int = 0,
        @SerializedName("berries_earned") val berriesEarned: Int = 0,

        // Pokemon data
        @SerializedName("pokemon_unlocked") val pokemonUnlocked: Int = 0,

        // Game indices for all game types (name: index)
        @SerializedName("game_indices") val gameIndices: Map<String, Int> = emptyMap(),

        // Metadata
        @SerializedName("last_updated") val lastUpdated: String? = null,
    )

    /**
     * Data class for required task progress
     * Fields are nullable to allow omitting them from JSON when not applicable
     */
    data class TaskProgress(
        @SerializedName("status") val status: String = "incomplete", // "complete" or "incomplete"
        @SerializedName("correct") val correct: Int? = null,
        @SerializedName("incorrect") val incorrect: Int? = null,
        @SerializedName("questions") val questions: Int? = null
    )

    /**
     * Data class for practice task progress
     * Fields are nullable to allow omitting them from JSON when not applicable
     */
    data class PracticeProgress(
        @SerializedName("times_completed") val timesCompleted: Int = 0,
        @SerializedName("correct") val correct: Int? = null,
        @SerializedName("incorrect") val incorrect: Int? = null,
        @SerializedName("questions_answered") val questionsAnswered: Int? = null
    )

    /**
     * Checks if cloud storage is enabled
     */
    fun isCloudStorageEnabled(): Boolean {
        return prefs.getBoolean(KEY_USE_CLOUD, false)
    }

    /**
     * Enables or disables cloud storage
     */
    fun setCloudStorageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CLOUD, enabled).apply()
        Log.d(TAG, "Cloud storage ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Sets the current profile for cloud operations
     */
    fun setCurrentProfile(profile: String) {
        prefs.edit().putString(KEY_CURRENT_PROFILE, profile).apply()
    }

    /**
     * Gets the current profile
     */
    fun getCurrentProfile(): String? {
        return prefs.getString(KEY_CURRENT_PROFILE, null)
    }

    /**
     * Converts profile from local format (A/B) to cloud format (AM/BM)
     */
    // Removed profile conversion - use profile names directly

    /**
     * Uploads all local data to cloud for the current profile
     * Profile can be in local format (A/B) or cloud format (AM/BM)
     */
    suspend fun uploadToCloud(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        Log.d(TAG, "uploadToCloud called for profile: $profile")

        try {
            // Use profile name directly (no conversion)
            val cloudProfile = profile
            val userData = collectLocalData(profile)
            
            // Log timestamp values before serialization
            Log.d(TAG, "Uploading data for profile $cloudProfile")
            Log.d(TAG, "last_reset timestamp: ${userData.lastReset}")
            Log.d(TAG, "last_updated timestamp: ${userData.lastUpdated}")
            
            val json = gson.toJson(userData)

            Log.d(TAG, "Uploading data for profile $cloudProfile, JSON length: ${json.length}")
            Log.d(TAG, "Upload data preview: ${json.take(500)}...")

            // Check if Supabase is configured
            val url = getSupabaseUrl()
            val key = getSupabaseKey()
            if (url.isEmpty() || key.isEmpty()) {
                Log.e(TAG, "Supabase not configured - URL: '${url.isNotEmpty()}', Key: '${key.isNotEmpty()}'")
                return@withContext Result.failure(Exception("Supabase not configured"))
            }
            Log.d(TAG, "Supabase configured - URL: $url, Key length: ${key.length}")

            val baseUrl = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            Log.d(TAG, "POST URL: $baseUrl")
            Log.d(TAG, "POST headers: apikey=${getSupabaseKey().take(10)}..., Authorization=Bearer ${getSupabaseKey().take(10)}...")

            // First check if record exists
            val checkUrl = "$baseUrl?profile=eq.$cloudProfile&select=profile"
            Log.d(TAG, "CHECK URL: $checkUrl")

            val checkRequest = Request.Builder()
                .url(checkUrl)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()

            Log.d(TAG, "Checking if record exists for profile: $cloudProfile")
            val checkResponse = client.newCall(checkRequest).execute()
            val checkResponseBody = checkResponse.body?.string() ?: "[]"
            checkResponse.close()

            val recordExists = checkResponseBody != "[]" && checkResponseBody.isNotBlank()
            Log.d(TAG, "Record exists check - response: ${checkResponseBody.take(100)}, exists: $recordExists")

            if (recordExists) {
                // Record exists, try UPDATE (PATCH)
                val patchUrl = "$baseUrl?profile=eq.$cloudProfile"
                Log.d(TAG, "PATCH URL: $patchUrl")

                val patchRequest = Request.Builder()
                    .url(patchUrl)
                    .patch(requestBody)
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                Log.d(TAG, "Attempting PATCH for existing record")
                val patchResponse = client.newCall(patchRequest).execute()
                Log.d(TAG, "PATCH response code: ${patchResponse.code}")

                if (patchResponse.isSuccessful) {
                    Log.d(TAG, "PATCH succeeded - updated existing record")
                    prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                    patchResponse.close()
                    return@withContext Result.success(Unit)
                } else {
                    val patchErrorBody = patchResponse.body?.string()
                    Log.e(TAG, "PATCH failed with code: ${patchResponse.code}, body: $patchErrorBody")
                    patchResponse.close()
                    return@withContext Result.failure(Exception("PATCH failed with code: ${patchResponse.code}"))
                }
            } else {
                // Record doesn't exist, try INSERT (POST)
                Log.d(TAG, "Record doesn't exist, trying POST to create new record")
                val postRequest = Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                val postResponse = client.newCall(postRequest).execute()

                if (postResponse.isSuccessful) {
                    Log.d(TAG, "POST succeeded - created new record")
                    prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                    postResponse.close()
                    return@withContext Result.success(Unit)
                } else {
                    val postErrorBody = postResponse.body?.string()
                    Log.e(TAG, "POST failed with code: ${postResponse.code}, body: $postErrorBody")
                    postResponse.close()
                    return@withContext Result.failure(Exception("POST failed with code: ${postResponse.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads data from cloud for the current profile
     * Profile can be in local format (A/B) or cloud format (AM/BM)
     */
    suspend fun downloadFromCloud(profile: String): Result<CloudUserData?> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            // Use profile directly for query
            val cloudProfile = profile
            val url = "${getSupabaseUrl()}/rest/v1/user_data?profile=eq.$cloudProfile&select=*"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                val dataList: List<CloudUserData> = gson.fromJson(
                    responseBody,
                    object : TypeToken<List<CloudUserData>>() {}.type
                )
                
                val userData = dataList.firstOrNull()
                if (userData != null) {
                    // Check if we should apply cloud data based on timestamps
                    val shouldApplyCloudData = shouldApplyCloudData(userData, cloudProfile)
                    Log.d(TAG, "Cloud data last updated: ${userData.lastUpdated}, should apply: $shouldApplyCloudData")

                    if (shouldApplyCloudData) {
                        applyCloudDataToLocal(userData)
                        Log.d(TAG, "Applied cloud data to local storage for profile: $cloudProfile")
                    } else {
                        Log.d(TAG, "Skipped applying cloud data - local data is newer or same day for profile: $cloudProfile")
                    }

                    prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                    Log.d(TAG, "Successfully downloaded data from cloud for profile: $cloudProfile")
                    Result.success(userData)
                } else {
                    Log.d(TAG, "No data found in cloud for profile: $cloudProfile")
                    Result.success(null)
                }
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to download from cloud: ${response.code} - $errorBody")
                Result.failure(Exception("Download failed: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading from cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads settings (pin and email) to the settings table
     */
    suspend fun uploadSettingsToCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            val pin = SettingsManager.readPin(context)
            val email = SettingsManager.readEmail(context)

            if (pin == null && email == null) {
                return@withContext Result.success(Unit) // Nothing to upload
            }

            val settingsData = mapOf(
                "parent_email" to (email ?: ""),
                "pin" to (pin ?: "")
            )
            val json = gson.toJson(settingsData)

            val baseUrl = "${getSupabaseUrl()}/rest/v1/settings"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            // Try to update existing settings, fallback to insert
            val updateUrl = "$baseUrl?id=eq.1"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .addHeader("Prefer", "return=minimal")
                .build()

            val patchResponse = client.newCall(patchRequest).execute()
            if (patchResponse.isSuccessful) {
                patchResponse.close()
                Result.success(Unit)
            } else if (patchResponse.code == 404) {
                // No existing record, try to insert
                patchResponse.close()
                val insertRequest = Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                val insertResponse = client.newCall(insertRequest).execute()
                if (insertResponse.isSuccessful) {
                    insertResponse.close()
                    Result.success(Unit)
                } else {
                    val errorBody = insertResponse.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Failed to insert settings: ${insertResponse.code} - $errorBody")
                    insertResponse.close()
                    Result.failure(Exception("Insert failed: ${insertResponse.code} - $errorBody"))
                }
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to update settings: ${patchResponse.code} - $errorBody")
                patchResponse.close()
                Result.failure(Exception("Update failed: ${patchResponse.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading settings to cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads settings (pin and email) from the settings table
     */
    suspend fun downloadSettingsFromCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            val url = "${getSupabaseUrl()}/rest/v1/settings?id=eq.1&select=*"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()

                if (responseBody != null && responseBody != "[]" && responseBody != "{}") {
                    val settingsArray = gson.fromJson(responseBody, object : TypeToken<Array<Map<String, Any>>>() {}.type) as Array<Map<String, Any>>
                    if (settingsArray.isNotEmpty()) {
                        val settings = settingsArray[0]
                        val pin = settings["pin"] as? String
                        val email = settings["parent_email"] as? String

                        pin?.let { SettingsManager.writePin(context, it) }
                        email?.let { SettingsManager.writeEmail(context, it) }
                    }
                }
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to download settings: ${response.code} - $errorBody")
                response.close()
                Result.failure(Exception("Download failed: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading settings from cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Converts ISO timestamp string to local display format
     */
    private fun formatTimestampForDisplay(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrEmpty()) return ""

        return try {
            // Parse ISO timestamp
            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            isoFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = isoFormat.parse(isoTimestamp.substringBefore("."))

            // Format for display
            val displayFormat = java.text.SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", java.util.Locale.getDefault())
            displayFormat.format(date)
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting timestamp: $isoTimestamp", e)
            isoTimestamp // fallback to original
        }
    }

    /**
     * Collects all local data for a profile into CloudUserData
     */
    private fun collectLocalData(profile: String): CloudUserData {
        Log.d(TAG, "collectLocalData called with profile: $profile")
        val cloudProfile = profile
        val localProfileId = profile

        // Use progressManager consistently to ensure data consistency with UI
        // Note: Don't call getCompletedTasksMap() here as it might trigger unwanted resets during sync
        val progressManager = DailyProgressManager(context)

        // Get daily progress data from SharedPreferences
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)

        // Get last reset date (format it properly for cloud storage in ISO 8601 with EST timezone)
        val lastResetDateString = progressPrefs.getString("last_reset_date", null)
        val lastResetDate = if (lastResetDateString != null) {
            // Convert from "dd-MM-yyyy hh:mm:ss a" format to ISO 8601 with EST timezone
            try {
                val parseFormat = java.text.SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", java.util.Locale.getDefault())
                parseFormat.timeZone = java.util.TimeZone.getTimeZone("America/New_York")
                val parsedDate = parseFormat.parse(lastResetDateString)
                if (parsedDate != null) {
                    // Format as ISO 8601 in EST
                    // parsedDate is already parsed with EST timezone, just format it with EST timezone
                    val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
                    val offsetMillis = estTimeZone.getOffset(parsedDate.time)
                    val offsetHours = offsetMillis / (1000 * 60 * 60)
                    val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
                    val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    dateFormat.timeZone = estTimeZone
                    val formatted = dateFormat.format(parsedDate) + offsetString
                    Log.d(TAG, "Converted last_reset from '$lastResetDateString' to EST format: $formatted")
                    formatted
                } else {
                    // Fallback: create new timestamp in EST
                    val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
                    val now = java.util.Date()
                    val offsetMillis = estTimeZone.getOffset(now.time)
                    val offsetHours = offsetMillis / (1000 * 60 * 60)
                    val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
                    val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    dateFormat.timeZone = estTimeZone
                    dateFormat.format(now) + offsetString
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing last_reset_date: $lastResetDateString", e)
                // Fallback: create new timestamp in EST
                val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
                val now = java.util.Date()
                val offsetMillis = estTimeZone.getOffset(now.time)
                val offsetHours = offsetMillis / (1000 * 60 * 60)
                val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
                val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                dateFormat.timeZone = estTimeZone
                dateFormat.format(now) + offsetString
            }
        } else {
            // No last_reset_date exists, create new timestamp in EST
            val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
            val now = java.util.Date()
            val offsetMillis = estTimeZone.getOffset(now.time)
            val offsetHours = offsetMillis / (1000 * 60 * 60)
            val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
            val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            dateFormat.timeZone = estTimeZone
            dateFormat.format(now) + offsetString
        }

        // Collect required tasks data
        val requiredTasks = collectRequiredTasksData(progressPrefs, progressManager.getCompletedTasksMap())
        Log.d(TAG, "Collected requiredTasks: ${requiredTasks.size} tasks")

        // Collect practice tasks data (games/videos/webgames as practice tasks)
        val practiceTasks = collectPracticeTasksData()
        Log.d(TAG, "Collected practiceTasks: ${practiceTasks.size} tasks")

        // Get progress metrics
        val possibleStars = progressPrefs.getInt("total_possible_stars", 0)
        val bankedMins = progressPrefs.getInt("banked_reward_minutes", 0)

        // Get berries earned directly from SharedPreferences (simple counter)
        val berriesEarned = try {
            context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                .getInt("earnedBerries", 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting earned berries", e)
            0
        }
        Log.d(TAG, "Collected berriesEarned: $berriesEarned")

        // Get Pokemon data
        val pokemonUnlocked = progressPrefs.getInt("${localProfileId}_$KEY_POKEMON_UNLOCKED", 0)

        // Collect all game indices (games, web games, videos)
        val gameIndices = collectAllGameIndices(localProfileId)

        // Format timestamp in ISO 8601 format with EST timezone for Supabase
        // Use manual offset calculation (works with API 23) instead of XXX pattern
        val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
        val now = java.util.Date()
        val offsetMillis = estTimeZone.getOffset(now.time)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
        val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        val lastUpdated = dateFormat.format(now) + offsetString

        // Create cloud data
        val cloudData = CloudUserData(
            profile = cloudProfile,
            lastReset = lastResetDate,
            requiredTasks = requiredTasks,
            practiceTasks = practiceTasks,
            possibleStars = possibleStars,
            bankedMins = bankedMins,
            berriesEarned = berriesEarned,
            pokemonUnlocked = pokemonUnlocked,
            gameIndices = gameIndices,
            lastUpdated = lastUpdated
        )

        Log.d(TAG, "Created CloudUserData: profile=$cloudProfile, requiredTasks=${requiredTasks.size}, practiceTasks=${practiceTasks.size}, gameIndices=${gameIndices.size}")
        return cloudData
    }

    /**
     * Collects required tasks data from local storage, ensuring all config tasks are included
     */
    private fun collectRequiredTasksData(progressPrefs: android.content.SharedPreferences, completedTasks: Map<String, Boolean>): Map<String, TaskProgress> {
        val requiredTasks = mutableMapOf<String, TaskProgress>()

        try {
            // First, get all tasks from config to ensure we have complete list
            val configTasks = getConfigTasksForSection("required")

            // Read completed task names to get existing task details
            val completedTaskNamesJson = progressPrefs.getString("completed_task_names", "{}") ?: "{}"
            val completedTaskNames = gson.fromJson<Map<String, String>>(completedTaskNamesJson, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()

            // Get time tracking sessions to calculate correct/incorrect answers
            val timeTracker = TimeTracker(context)
            val allSessions = timeTracker.getTodaySessionsList()

            // For each task in config, create/merge TaskProgress
            configTasks.forEach { task ->
                val taskName = task.title ?: "Unknown Task"
                val taskId = task.launch ?: taskName.lowercase().replace(" ", "_")

                // Check if this task is completed (required tasks use base taskId)
                val isCompleted = completedTasks[taskId] == true
                val status = if (isCompleted) "complete" else "incomplete"

                // Calculate correct/incorrect answers from sessions matching this task
                val matchingSessions = allSessions.filter { session ->
                    // For required tasks, the activityId should match the taskId
                    session.activityId == taskId
                }

                val totalCorrect = matchingSessions.sumOf { it.correctAnswers }
                val totalIncorrect = matchingSessions.sumOf { it.incorrectAnswers }
                
                // Determine questions, correct, and incorrect based on task type
                val isWebGame = task.webGame == true
                val hasAnswerData = totalCorrect > 0 || totalIncorrect > 0
                
                val questions: Int?
                val correct: Int?
                val incorrect: Int?
                
                if (isWebGame) {
                    // HTML games: only include data if game completion passed back correct/incorrect counts
                    if (hasAnswerData) {
                        // Game completed and passed back answer counts
                        correct = totalCorrect
                        incorrect = totalIncorrect
                        questions = totalCorrect + totalIncorrect // Total questions = correct + incorrect
                    } else {
                        // Game not completed yet or didn't pass back answer counts
                        correct = null
                        incorrect = null
                        questions = null
                    }
                } else {
                    // Non-HTML games: use totalQuestions from config if available
                    if (task.totalQuestions != null) {
                        // Task has totalQuestions defined in config
                        questions = task.totalQuestions
                        correct = if (totalCorrect > 0) totalCorrect else null
                        incorrect = if (totalIncorrect > 0) totalIncorrect else null
                    } else {
                        // Task doesn't have totalQuestions (like Handwriting)
                        // Set to -1 to indicate not applicable
                        questions = -1
                        correct = -1
                        incorrect = -1
                    }
                }

                requiredTasks[taskName] = TaskProgress(
                    status = status,
                    correct = correct,
                    incorrect = incorrect,
                    questions = questions
                )
            }

            Log.d(TAG, "Collected ${requiredTasks.size} required tasks from config (${configTasks.size} total in config)")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting required tasks data", e)
        }

        return requiredTasks
    }

    /**
     * Collects practice tasks data from local storage, ensuring all config tasks are included
     */
    private fun collectPracticeTasksData(): Map<String, PracticeProgress> {
        val practiceTasks = mutableMapOf<String, PracticeProgress>()

        try {
            // Get all tasks from optional and bonus sections for practice tasks
            val optionalTasks = getConfigTasksForSection("optional")
            val bonusTasks = getConfigTasksForSection("bonus")
            val allPracticeTasks = optionalTasks + bonusTasks

            // Get time tracking sessions to calculate performance data
            val timeTracker = TimeTracker(context)
            val allSessions = timeTracker.getTodaySessionsList()

            // For each task in config, create PracticeProgress with real data
            allPracticeTasks.forEach { task ->
                val taskName = task.title ?: "Unknown Task"
                val baseTaskId = task.launch ?: taskName.lowercase().replace(" ", "_")

                // For optional/bonus tasks, sessions use unique task IDs with section prefix
                val optionalTaskId = "optional_$baseTaskId"
                val bonusTaskId = "bonus_$baseTaskId"

                // Find all sessions for this task (could be completed multiple times)
                val matchingSessions = allSessions.filter { session ->
                    session.activityId == optionalTaskId || session.activityId == bonusTaskId
                }

                val timesCompleted = matchingSessions.count { it.completed }
                val totalCorrect = matchingSessions.sumOf { it.correctAnswers }
                val totalIncorrect = matchingSessions.sumOf { it.incorrectAnswers }
                
                // Determine questionsAnswered, correct, and incorrect based on task type
                val isWebGame = task.webGame == true
                val hasAnswerData = totalCorrect > 0 || totalIncorrect > 0
                
                val questionsAnswered: Int?
                val correct: Int?
                val incorrect: Int?
                
                if (isWebGame) {
                    // HTML games: only include data if game completion passed back correct/incorrect counts
                    if (hasAnswerData) {
                        // Game completed and passed back answer counts
                        correct = totalCorrect
                        incorrect = totalIncorrect
                        questionsAnswered = totalCorrect + totalIncorrect // Total questions = correct + incorrect
                    } else {
                        // Game not completed yet or didn't pass back answer counts
                        correct = null
                        incorrect = null
                        questionsAnswered = null
                    }
                } else {
                    // Non-HTML games: use totalQuestions from config if available
                    if (task.totalQuestions != null) {
                        // Task has totalQuestions defined in config
                        questionsAnswered = if (hasAnswerData) totalCorrect + totalIncorrect else null
                        correct = if (totalCorrect > 0) totalCorrect else null
                        incorrect = if (totalIncorrect > 0) totalIncorrect else null
                    } else {
                        // Task doesn't have totalQuestions (like Handwriting)
                        // Set to -1 to indicate not applicable
                        questionsAnswered = -1
                        correct = -1
                        incorrect = -1
                    }
                }

                practiceTasks[taskName] = PracticeProgress(
                    timesCompleted = timesCompleted,
                    correct = correct,
                    incorrect = incorrect,
                    questionsAnswered = questionsAnswered
                )
            }

            Log.d(TAG, "Collected ${practiceTasks.size} practice tasks from config (${allPracticeTasks.size} total in config)")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting practice tasks data", e)
        }

        return practiceTasks
    }

    /**
     * Gets all tasks from a specific config section
     */
    private fun getConfigTasksForSection(sectionId: String): List<Task> {
        try {
            // Load config content
            val contentUpdateService = ContentUpdateService()
            val jsonString = contentUpdateService.getCachedMainContent(context)

            if (jsonString.isNullOrEmpty()) {
                Log.w(TAG, "No cached main content available for config tasks")
                return emptyList()
            }

            val mainContent = gson.fromJson(jsonString, MainContent::class.java)
            val section = mainContent?.sections?.find { it.id == sectionId }

            return section?.tasks?.filterNotNull() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading config tasks for section: $sectionId", e)
            return emptyList()
        }
    }

    /**
     * Collects all game indices from various SharedPreferences
     */
    private fun collectAllGameIndices(localProfileId: String): Map<String, Int> {
        val gameIndices = mutableMapOf<String, Int>()

        try {
            // Collect regular game progress
            val gamePrefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)
            val profilePrefix = "${localProfileId}_progress_"
            gamePrefs.all.forEach { (key, value) ->
                if (key.startsWith(profilePrefix) && value is Int) {
                    val launchId = key.removePrefix(profilePrefix)
                    gameIndices[launchId] = value
                }
            }

            // Collect web game progress
            val webGamePrefs = context.getSharedPreferences("web_game_progress", Context.MODE_PRIVATE)
            val webPrefix = "${localProfileId}_web_progress_"
            webGamePrefs.all.forEach { (key, value) ->
                if (key.startsWith(webPrefix) && value is Int) {
                    val gameId = key.removePrefix(webPrefix)
                    gameIndices[gameId] = value
                }
            }

            // Collect video progress
            val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val videoPrefix = "${localProfileId}_"
            videoPrefs.all.forEach { (key, value) ->
                if (key.startsWith(videoPrefix) && value is Int) {
                    if (key.endsWith("_index")) {
                        val videoFile = key.removePrefix(videoPrefix).removeSuffix("_index")
                        gameIndices[videoFile] = value
                    } else if (key.endsWith("_completed")) {
                        val taskId = key.removePrefix(videoPrefix).removeSuffix("_completed")
                        gameIndices[taskId] = value
                    }
                }
            }

            Log.d(TAG, "Collected ${gameIndices.size} game indices")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting game indices", e)
        }

        return gameIndices
    }

    /**
     * Determines if cloud data should be applied to local storage
     * Only apply cloud data if it's from today AND has MORE progress than local data (for cross-device syncing)
     */
    private fun shouldApplyCloudData(cloudData: CloudUserData, profile: String): Boolean {
        try {
            // First check if cloud data is from today
            val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/New_York")
            }.format(java.util.Date())

            val cloudTimestamp = cloudData.lastUpdated
            if (cloudTimestamp.isNullOrEmpty()) {
                Log.d(TAG, "Cloud data has no timestamp, not applying")
                return false
            }

            // Extract date from cloud timestamp
            val cloudDate = try {
                val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                isoFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = isoFormat.parse(cloudTimestamp.substringBefore("."))
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("America/New_York")
                }.format(date)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing cloud timestamp: $cloudTimestamp", e)
                return false
            }

            // If cloud data is from a different day, don't apply it (preserve today's local progress)
            if (cloudDate != currentDate) {
                Log.d(TAG, "Cloud data is from different day ($cloudDate vs $currentDate), not applying")
                return false
            }

            // Cloud data is from today - now check if it has more progress than local data
            val localCompletedTasks = getLocalCompletedTasksCount(profile)
            val localGameProgress = getLocalGameProgressCount(profile)
            val localVideoProgress = getLocalVideoProgressCount(profile)

            val cloudCompletedTasks = cloudData.requiredTasks?.size ?: 0
            val cloudGameIndices = cloudData.gameIndices?.size ?: 0

            val localTotalProgress = localCompletedTasks + localGameProgress + localVideoProgress
            val cloudTotalProgress = cloudCompletedTasks + cloudGameIndices

            Log.d(TAG, "Progress comparison - Local: tasks=$localCompletedTasks, games=$localGameProgress, videos=$localVideoProgress (total=$localTotalProgress)")
            Log.d(TAG, "Progress comparison - Cloud: tasks=$cloudCompletedTasks, gameIndices=$cloudGameIndices (total=$cloudTotalProgress)")

            // Apply cloud data only if it has more total progress (syncing between devices on the same day)
            val shouldApply = cloudTotalProgress > localTotalProgress
            Log.d(TAG, "Should apply cloud data: $shouldApply (cloud has more progress from today)")

            return shouldApply

        } catch (e: Exception) {
            Log.e(TAG, "Error checking if cloud data should be applied", e)
            return false
        }
    }

    /**
     * Gets the count of completed tasks from local storage
     */
    private fun getLocalCompletedTasksCount(profile: String): Int {
        return try {
            val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString("completed_tasks", "{}") ?: "{}"
            val tasks = gson.fromJson<Map<String, Boolean>>(json, object : TypeToken<Map<String, Boolean>>() {}.type) ?: emptyMap()
            tasks.count { it.value }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Gets the count of game progress entries from local storage
     */
    private fun getLocalGameProgressCount(profile: String): Int {
        return try {
            val prefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)
            val allPrefs = prefs.all
            val profilePrefix = "${profile}_progress_"
            allPrefs.count { it.key.startsWith(profilePrefix) }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Gets the count of video progress entries from local storage
     */
    private fun getLocalVideoProgressCount(profile: String): Int {
        return try {
            val prefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val allPrefs = prefs.all
            val profilePrefix = "${profile}_"
            allPrefs.count { it.key.startsWith(profilePrefix) && (it.key.endsWith("_index") || it.key.endsWith("_completed")) }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Gets the local last updated timestamp for comparison with cloud data
     * Returns the most recent timestamp from local data sources
     */
    private fun getLocalLastUpdatedTimestamp(): String {
        // Use current time as local timestamp since we're always collecting fresh data
        // Format in ISO 8601 in EST (same format as we send to Supabase)
        val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
        val now = java.util.Date()
        val offsetMillis = estTimeZone.getOffset(now.time)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
        val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        return dateFormat.format(now) + offsetString
    }

    /**
     * Applies cloud data to local storage
     */
    private fun applyCloudDataToLocal(data: CloudUserData) {
        try {
            Log.d(TAG, "Applying cloud data to local storage: profile=${data.profile}, requiredTasks=${data.requiredTasks?.size ?: 0}, berries=${data.berriesEarned}")

            // Convert cloud profile to local format
            val localProfile = data.profile

            // Apply settings
            SettingsManager.writeProfile(context, localProfile)

            // Apply daily progress data
            val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)

            // Apply required tasks data - merge with existing local data to avoid overwriting
            if (data.requiredTasks?.isNotEmpty() == true) {
                // Get existing local data
                val existingCompletedTasksJson = progressPrefs.getString("completed_tasks", "{}") ?: "{}"
                val existingCompletedTasks = gson.fromJson<Map<String, Boolean>>(existingCompletedTasksJson, object : TypeToken<Map<String, Boolean>>() {}.type)?.toMutableMap() ?: mutableMapOf()

                val existingCompletedTaskNamesJson = progressPrefs.getString("completed_task_names", "{}") ?: "{}"
                val existingCompletedTaskNames = gson.fromJson<Map<String, String>>(existingCompletedTaskNamesJson, object : TypeToken<Map<String, String>>() {}.type)?.toMutableMap() ?: mutableMapOf()

                // Merge cloud data with local data (cloud takes precedence for tasks it knows about)
                data.requiredTasks.forEach { (taskName, taskProgress) ->
                    // Try to find the local task ID by matching the task name
                    val localTaskId = existingCompletedTaskNames.entries.find { it.value == taskName }?.key
                        ?: taskName.lowercase().replace(" ", "_").replace("-", "_") // fallback to generated ID

                    existingCompletedTasks[localTaskId] = taskProgress.status == "complete"
                    existingCompletedTaskNames[localTaskId] = taskName
                }

                progressPrefs.edit()
                    .putString("completed_tasks", gson.toJson(existingCompletedTasks))
                    .putString("completed_task_names", gson.toJson(existingCompletedTaskNames))
                    .apply()

                Log.d(TAG, "Applied ${data.requiredTasks.size} required tasks to local storage")
            }

            // Apply other progress metrics
            progressPrefs.edit()
                .putInt("total_possible_stars", data.possibleStars)
                .putInt("banked_reward_minutes", data.bankedMins)
                .apply()

            // Apply berries earned (store in pokemonBattleHub preferences where UI expects it)
            try {
                context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("earnedBerries", data.berriesEarned)
                    .apply()
                Log.d(TAG, "Applied berries_earned: ${data.berriesEarned}")
            } catch (e: Exception) {
                Log.e(TAG, "Error applying berries to local storage", e)
            }

            // Apply Pokemon data
            progressPrefs.edit()
                .putInt("${localProfile}_$KEY_POKEMON_UNLOCKED", data.pokemonUnlocked)
                .apply()

            // Apply game indices (all types: games, web games, videos)
            applyGameIndicesToLocal(data.gameIndices ?: emptyMap(), localProfile)

            Log.d(TAG, "Successfully applied cloud data to local storage: requiredTasks=${data.requiredTasks?.size ?: 0}, gameIndices=${data.gameIndices?.size ?: 0}, berries=${data.berriesEarned}")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying cloud data to local", e)
            throw e
        }
    }

    /**
     * Applies game indices to appropriate local SharedPreferences
     */
    private fun applyGameIndicesToLocal(gameIndices: Map<String, Int>, localProfile: String) {
        try {
            // Apply to game_progress prefs
            val gamePrefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)
            val gameEditor = gamePrefs.edit()

            // Apply to web_game_progress prefs
            val webGamePrefs = context.getSharedPreferences("web_game_progress", Context.MODE_PRIVATE)
            val webGameEditor = webGamePrefs.edit()

            // Apply to video_progress prefs
            val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val videoEditor = videoPrefs.edit()

            gameIndices.forEach { (gameId, index) ->
                // For now, we'll try to intelligently distribute based on naming patterns
                // This is a heuristic - you may want to enhance this logic
                when {
                    gameId.contains("diagram") -> {
                        // Web game (diagram labeler)
                        webGameEditor.putInt("${localProfile}_web_progress_$gameId", index)
                    }
                    gameId.contains(".json") || gameId.contains("video") -> {
                        // Video
                        videoEditor.putInt("${localProfile}_${gameId}_index", index)
                    }
                    else -> {
                        // Regular game
                        gameEditor.putInt("${localProfile}_progress_$gameId", index)
                    }
                }
            }

            gameEditor.apply()
            webGameEditor.apply()
            videoEditor.apply()

            Log.d(TAG, "Applied ${gameIndices.size} game indices to local storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying game indices to local storage", e)
        }
    }

    /**
     * Syncs data (downloads from cloud if cloud is enabled)
     */
    suspend fun syncIfEnabled(profile: String): Result<Unit> {
        if (!isCloudStorageEnabled() || !isConfigured()) {
            Log.d(TAG, "Cloud sync disabled or not configured")
            return Result.success(Unit)
        }

        Log.d(TAG, "Starting bidirectional sync for profile: $profile")

        try {
            // First, check what data exists in cloud
            val cloudDataResult = downloadFromCloud(profile)
            val cloudUserData = if (cloudDataResult.isSuccess) cloudDataResult.getOrNull() else null

            val localTimestamp = getLocalLastUpdatedTimestamp()
            val cloudTimestamp = cloudUserData?.lastUpdated

            Log.d(TAG, "Sync timestamps - Local: $localTimestamp, Cloud: $cloudTimestamp")

            // Determine sync direction based on timestamps
            val shouldUploadLocal = shouldUploadLocalData(localTimestamp, cloudTimestamp)
            val shouldDownloadCloud = shouldDownloadCloudData(localTimestamp, cloudTimestamp, cloudUserData, profile)

            Log.d(TAG, "Sync decisions - Local timestamp: $localTimestamp, Cloud timestamp: $cloudTimestamp")
            Log.d(TAG, "Sync decisions - Upload local: $shouldUploadLocal, Download cloud: $shouldDownloadCloud")
            Log.d(TAG, "Sync decisions - Cloud data exists: ${cloudUserData != null}, berries in cloud: ${cloudUserData?.berriesEarned ?: 0}")

            var uploadResult: Result<Unit> = Result.success(Unit)
            var settingsUploadResult: Result<Unit> = Result.success(Unit)
            var settingsDownloadResult: Result<Unit> = Result.success(Unit)

            // Upload local data if it's newer
            if (shouldUploadLocal) {
                uploadResult = uploadToCloud(profile)
                settingsUploadResult = uploadSettingsToCloud()
                Log.d(TAG, "Uploaded local data to cloud - userData: ${uploadResult.isSuccess}, settings: ${settingsUploadResult.isSuccess}")
            } else {
                Log.d(TAG, "Skipped uploading local data (cloud is newer or same)")
            }

            // Download and apply cloud data if it's newer
            if (shouldDownloadCloud) {
                // Re-download to ensure we get the latest (in case we just uploaded)
                val freshCloudResult = downloadFromCloud(profile)
                settingsDownloadResult = downloadSettingsFromCloud()
                Log.d(TAG, "Downloaded cloud data - userData: ${freshCloudResult.isSuccess}, settings: ${settingsDownloadResult.isSuccess}")
            } else {
                Log.d(TAG, "Skipped downloading cloud data (local is newer or same)")
            }

            val allSuccess = (shouldUploadLocal || uploadResult.isSuccess) &&
                            (shouldDownloadCloud || settingsDownloadResult.isSuccess)

            return if (allSuccess) {
                Log.d(TAG, "Bidirectional sync completed successfully for profile: $profile")
                Result.success(Unit)
            } else {
                val error = uploadResult.exceptionOrNull() ?: settingsUploadResult.exceptionOrNull() ?:
                           cloudDataResult.exceptionOrNull() ?: settingsDownloadResult.exceptionOrNull() ?: Exception("Sync failed")
                Log.e(TAG, "Bidirectional sync failed for profile: $profile", error)
                Result.failure(error)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during bidirectional sync", e)
            return Result.failure(e)
        }
    }

    /**
     * Determines if local data should be uploaded to cloud based on timestamps
     */
    private fun shouldUploadLocalData(localTimestamp: String, cloudTimestamp: String?): Boolean {
        if (cloudTimestamp.isNullOrEmpty()) {
            // No cloud data exists, upload local data
            return true
        }

        return try {
            val localTime = parseTimestamp(localTimestamp)
            val cloudTime = parseTimestamp(cloudTimestamp)

            // Upload if local data is newer (within a reasonable threshold)
            val timeDiff = localTime - cloudTime
            val shouldUpload = timeDiff > 1000 // Local is at least 1 second newer
            Log.d(TAG, "Upload decision - localTime: $localTime, cloudTime: $cloudTime, diff: ${timeDiff}ms, upload: $shouldUpload")
            shouldUpload
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing timestamps for upload decision", e)
            true // Default to uploading if we can't compare
        }
    }

    /**
     * Determines if cloud data should be downloaded and applied based on timestamps and content
     */
    private fun shouldDownloadCloudData(localTimestamp: String, cloudTimestamp: String?, cloudUserData: CloudUserData?, profile: String): Boolean {
        if (cloudTimestamp.isNullOrEmpty() || cloudUserData == null) {
            // No cloud data to download
            return false
        }

        // Check if local has any meaningful progress
        val localProgress = getLocalProgressCount(profile)
        val hasLocalProgress = localProgress > 0

        if (!hasLocalProgress) {
            // Local has no progress, so download cloud data regardless of timestamps
            Log.d(TAG, "Download decision - local has no progress ($localProgress), downloading cloud data")
            return true
        }

        // Local has progress, use timestamp comparison
        return try {
            val localTime = parseTimestamp(localTimestamp)
            val cloudTime = parseTimestamp(cloudTimestamp)

            // Download if cloud data is significantly newer (more than 5 seconds to account for sync delays)
            val timeDiff = cloudTime - localTime
            val cloudIsNewer = timeDiff > 5000 // Cloud is at least 5 seconds newer

            if (cloudIsNewer) {
                // Additional check: only apply if cloud data has meaningful content or is from today
                val shouldApply = shouldApplyCloudData(cloudUserData, profile)
                Log.d(TAG, "Download decision - cloudTime: $cloudTime, localTime: $localTime, diff: ${timeDiff}ms, cloud newer: $cloudIsNewer, should apply: $shouldApply")
                return shouldApply
            } else {
                Log.d(TAG, "Download decision - cloud is not significantly newer (diff: ${timeDiff}ms)")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing timestamps for download decision", e)
            false // Default to not downloading if we can't compare
        }
    }

    /**
     * Gets a count of local progress items to determine if local has meaningful data
     */
    private fun getLocalProgressCount(profile: String): Int {
        try {
            val context = this.context
            val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)

            // Count completed tasks
            val completedTasksJson = progressPrefs.getString("completed_tasks", "{}") ?: "{}"
            val completedTasks = gson.fromJson<Map<String, Boolean>>(completedTasksJson, object : TypeToken<Map<String, Boolean>>() {}.type) ?: emptyMap()

            // Count pokemon unlocked
            val pokemonUnlocked = progressPrefs.getInt("${profile}_pokemon_unlocked", 0)

            // Count berries
            val berriesEarned = try {
                context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                    .getInt("earnedBerries", 0)
            } catch (e: Exception) {
                0
            }

            return completedTasks.count { it.value } + pokemonUnlocked + berriesEarned
        } catch (e: Exception) {
            return 0
        }
    }

    /**
     * Parses timestamp string to milliseconds for comparison
     * Handles both ISO 8601 format (from Supabase) and our EST format
     * Uses API 23 compatible patterns (Z instead of XXX)
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            // Handle ISO 8601 formats with timezone
            // Formats: "2026-01-06T19:52:08.190Z", "2026-01-07T00:35:11.680263+00:00", "2026-01-06T19:35:11-05:00"
            
            // Check if it has timezone indicator
            val hasZ = timestamp.endsWith("Z")
            val hasPlusOffset = timestamp.contains("+", ignoreCase = false) && timestamp.indexOf("+") > 10 // + after the date part
            val hasMinusOffset = timestamp.contains("-", ignoreCase = false) && timestamp.indexOf("-", startIndex = 19) > 0 // - after the time part
            
            if (timestamp.contains("T") && (hasZ || hasPlusOffset || hasMinusOffset)) {
                // Extract timezone offset
                val timezonePart = when {
                    hasZ -> "+00:00"
                    hasPlusOffset -> {
                        val plusIndex = timestamp.indexOf("+")
                        timestamp.substring(plusIndex + 1)
                    }
                    hasMinusOffset -> {
                        val minusIndex = timestamp.indexOf("-", startIndex = 19)
                        "-" + timestamp.substring(minusIndex + 1)
                    }
                    else -> "+00:00"
                }
                
                // Extract clean timestamp (date and time without timezone)
                val cleanTimestamp = when {
                    hasZ -> timestamp.substringBefore("Z")
                    hasPlusOffset -> timestamp.substringBefore("+")
                    hasMinusOffset -> {
                        val minusIndex = timestamp.indexOf("-", startIndex = 19)
                        timestamp.substring(0, minusIndex)
                    }
                    else -> timestamp
                }
                
                // Parse the date/time part (handle with or without milliseconds)
                val date: java.util.Date?
                if (cleanTimestamp.contains(".")) {
                    // Has milliseconds - try parsing with milliseconds
                    val millisPart = cleanTimestamp.substringAfter(".")
                    val withoutMillis = cleanTimestamp.substringBefore(".")
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    val baseDate = dateFormat.parse(withoutMillis)
                    if (baseDate != null && millisPart.isNotEmpty()) {
                        // Add milliseconds manually
                        val millis = millisPart.take(3).padEnd(3, '0').toIntOrNull() ?: 0
                        date = java.util.Date(baseDate.time + millis)
                    } else {
                        date = baseDate
                    }
                } else {
                    // No milliseconds
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    date = dateFormat.parse(cleanTimestamp)
                }
                
                if (date == null) {
                    Log.e(TAG, "Failed to parse timestamp: $timestamp (clean: $cleanTimestamp)")
                    return 0L
                }
                
                // Apply timezone offset manually
                // Offset format: "+05:00" or "-05:00"
                val offsetParts = timezonePart.split(":")
                val offsetHours = offsetParts[0].toIntOrNull() ?: 0
                val offsetMinutes = offsetParts.getOrNull(1)?.toIntOrNull() ?: 0
                val offsetMillis = (offsetHours * 60 + offsetMinutes) * 60 * 1000
                
                // Convert to UTC: if offset is -05:00 (EST), we need to add 5 hours to get UTC
                // So we subtract the offset (negative offset means behind UTC, so we add to get UTC)
                return date.time - offsetMillis
            }
            
            // Fallback: try simple format without timezone
            val simpleFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            simpleFormat.parse(timestamp)?.time ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp: $timestamp", e)
            0L
        }
    }

    /**
     * Saves data to cloud if cloud storage is enabled
     */
    suspend fun saveIfEnabled(profile: String): Result<Unit> {
        Log.d(TAG, "saveIfEnabled called for profile: $profile")
        if (!isCloudStorageEnabled() || !isConfigured()) {
            Log.d(TAG, "Cloud sync skipped - enabled: ${isCloudStorageEnabled()}, configured: ${isConfigured()}")
            return Result.success(Unit)
        }

        Log.d(TAG, "Starting cloud sync for profile: $profile")
        val userDataResult = uploadToCloud(profile)
        val settingsResult = uploadSettingsToCloud()

        Log.d(TAG, "Cloud sync results - userData: ${userDataResult.isSuccess}, settings: ${settingsResult.isSuccess}")

        return if (userDataResult.isSuccess && settingsResult.isSuccess) {
            Log.d(TAG, "Cloud sync completed successfully")
            Result.success(Unit)
        } else {
            val error = userDataResult.exceptionOrNull() ?: settingsResult.exceptionOrNull() ?: Exception("Save failed")
            Log.e(TAG, "Cloud sync failed", error)
            Result.failure(error)
        }
    }

    /**
     * Gets last sync timestamp
     */
    fun getLastSyncTimestamp(): Long {
        return prefs.getLong(KEY_LAST_SYNC, 0)
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        scope.cancel()
    }
}


