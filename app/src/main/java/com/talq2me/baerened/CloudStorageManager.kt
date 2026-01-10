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

        // Checklist items progress (name: done status, stars, display days)
        @SerializedName("checklist_items") val checklistItems: Map<String, ChecklistItemProgress> = emptyMap(),

        // Progress metrics
        @SerializedName("possible_stars") val possibleStars: Int = 0,
        @SerializedName("banked_mins") val bankedMins: Int = 0,
        @SerializedName("berries_earned") val berriesEarned: Int = 0,

        // Pokemon data
        @SerializedName("pokemon_unlocked") val pokemonUnlocked: Int = 0,

        // Game indices for all game types (name: index)
        @SerializedName("game_indices") val gameIndices: Map<String, Int> = emptyMap(),

        // App lists (from BaerenLock) - stored as JSON strings in database
        @SerializedName("reward_apps") val rewardApps: String? = null, // JSON array string
        @SerializedName("blacklisted_apps") val blacklistedApps: String? = null, // JSON array string
        @SerializedName("white_listed_apps") val whiteListedApps: String? = null, // JSON array string

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
        @SerializedName("questions") val questions: Int? = null,
        @SerializedName("showdays") val showdays: String? = null, // Visibility: show on these days
        @SerializedName("hidedays") val hidedays: String? = null, // Visibility: hide on these days
        @SerializedName("displayDays") val displayDays: String? = null, // Visibility: display only on these days
        @SerializedName("disable") val disable: String? = null // Visibility: disable before this date
    )

    /**
     * Data class for practice task progress
     * Fields are nullable to allow omitting them from JSON when not applicable
     */
    data class PracticeProgress(
        @SerializedName("times_completed") val timesCompleted: Int = 0,
        @SerializedName("correct") val correct: Int? = null,
        @SerializedName("incorrect") val incorrect: Int? = null,
        @SerializedName("questions_answered") val questionsAnswered: Int? = null,
        @SerializedName("showdays") val showdays: String? = null, // Visibility: show on these days
        @SerializedName("hidedays") val hidedays: String? = null, // Visibility: hide on these days
        @SerializedName("displayDays") val displayDays: String? = null, // Visibility: display only on these days
        @SerializedName("disable") val disable: String? = null // Visibility: disable before this date
    )

    /**
     * Data class for checklist item progress
     * Fields are nullable to allow omitting them from JSON when not applicable
     */
    data class ChecklistItemProgress(
        @SerializedName("done") val done: Boolean = false, // Whether the checklist item is done
        @SerializedName("stars") val stars: Int = 0, // Star count for this item
        @SerializedName("displayDays") val displayDays: String? = null // Visibility: display only on these days
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
                        // Check if we should apply cloud data based on timestamps and content
                        val shouldApplyCloudData = shouldApplyCloudData(userData, cloudProfile)
                        Log.d(TAG, "Cloud data last updated: ${userData.lastUpdated}, should apply: $shouldApplyCloudData")

                        if (shouldApplyCloudData) {
                            applyCloudDataToLocal(userData)
                            Log.d(TAG, "Applied cloud data to local storage for profile: $cloudProfile")
                        } else {
                            Log.d(TAG, "Skipped applying cloud data - local data is newer or same day for profile: $cloudProfile")
                            
                            // Even if we don't apply all data, still apply app lists if cloud has them and local doesn't
                            applyAppListsFromCloudIfLocalEmpty(userData)
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
     * Syncs banked reward minutes to cloud user_data table
     * This is called immediately when banked minutes are updated in BaerenEd
     */
    suspend fun syncBankedMinutesToCloud(minutes: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            // Get current profile
            val profile = SettingsManager.readProfile(context) ?: "AM"
            val cloudProfile = profile // Profile is already in AM/BM format in BaerenEd
            
            // Generate timestamp in EST timezone (consistent with all local storage)
            val lastUpdated = generateESTTimestamp()
            
            // Update banked_mins AND last_updated timestamp in user_data table
            // This ensures the timestamp reflects when reward time was last changed
            val updateMap = mapOf(
                "banked_mins" to minutes,
                "last_updated" to lastUpdated
            )
            
            val json = gson.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            // Update user_data for this profile
            val updateUrl = "$baseUrl?profile=eq.$cloudProfile"
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
                Log.d(TAG, "Successfully synced banked minutes to cloud for profile: $cloudProfile, minutes: $minutes, timestamp: $lastUpdated")
                Result.success(Unit)
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to sync banked minutes to cloud: ${patchResponse.code} - $errorBody")
                Log.e(TAG, "Profile: $cloudProfile, URL: $updateUrl, JSON: $json")
                patchResponse.close()
                Result.failure(Exception("Sync failed: ${patchResponse.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing banked minutes to cloud", e)
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
     * Generates a timestamp in ISO 8601 format with EST timezone.
     * This ensures all local timestamps use EST, matching cloud timestamps.
     */
    private fun generateESTTimestamp(): String {
        val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
        val now = java.util.Date()
        val offsetMillis = estTimeZone.getOffset(now.time)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
        val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        return dateFormat.format(now) + offsetString
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

        // Collect checklist items data
        val checklistItems = collectChecklistItemsData(progressManager.getCompletedTasksMap())
        Log.d(TAG, "Collected checklistItems: ${checklistItems.size} items")

        // Get progress metrics (all profile-specific)
        val possibleStarsKey = "${localProfileId}_total_possible_stars"
        val possibleStars = progressPrefs.getInt(possibleStarsKey, 0)
        // Banked minutes are now profile-specific
        val bankedMinsKey = "${localProfileId}_banked_reward_minutes"
        val bankedMins = progressPrefs.getInt(bankedMinsKey, 0)

        // Get berries earned directly from SharedPreferences (profile-specific)
        val berriesKey = "${localProfileId}_earnedBerries"
        val berriesEarned = try {
            context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                .getInt(berriesKey, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting earned berries", e)
            0
        }
        Log.d(TAG, "Collected berriesEarned: $berriesEarned")

        // Get Pokemon data
        val pokemonUnlocked = progressPrefs.getInt("${localProfileId}_$KEY_POKEMON_UNLOCKED", 0)

        // Collect all game indices (games, web games, videos)
        val gameIndices = collectAllGameIndices(localProfileId)

        // Collect app lists from BaerenLock settings (if available)
        // Note: BaerenLock stores these globally, but we sync them per profile for cloud storage
        // Profile mapping: A->AM, B->BM
        val rewardApps = collectAppListFromBaerenLock(profile, "reward_apps")
        val blacklistedApps = collectAppListFromBaerenLock(profile, "blacklisted_apps")
        val whiteListedApps = collectAppListFromBaerenLock(profile, "white_listed_apps")

        // Format timestamp in ISO 8601 format with EST timezone for Supabase
        // Generate timestamp in EST timezone (consistent with all local storage)
        val lastUpdated = generateESTTimestamp()

        // Create cloud data
        val cloudData = CloudUserData(
            profile = cloudProfile,
            lastReset = lastResetDate,
            requiredTasks = requiredTasks,
            practiceTasks = practiceTasks,
            checklistItems = checklistItems,
            possibleStars = possibleStars,
            bankedMins = bankedMins,
            berriesEarned = berriesEarned,
            pokemonUnlocked = pokemonUnlocked,
            gameIndices = gameIndices,
            rewardApps = rewardApps,
            blacklistedApps = blacklistedApps,
            whiteListedApps = whiteListedApps,
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
            // CRITICAL: Get ALL tasks from config (not filtered by visibility) for database syncing
            // This ensures all tasks are in the database for reporting even if not visible today
            val configTasks = getConfigTasksForSection("required", filterByVisibility = false)

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
                    questions = questions,
                    showdays = task.showdays,
                    hidedays = task.hidedays,
                    displayDays = task.displayDays,
                    disable = task.disable
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
     * Only includes tasks from the "optional" section, not "bonus" section
     */
    private fun collectPracticeTasksData(): Map<String, PracticeProgress> {
        val practiceTasks = mutableMapOf<String, PracticeProgress>()

        try {
            // CRITICAL: Get ALL tasks from optional section (not filtered by visibility) for database syncing
            // This ensures all tasks are in the database for reporting even if not visible today
            // Only includes tasks from the "optional" section, not "bonus" section
            val optionalTasks = getConfigTasksForSection("optional", filterByVisibility = false)

            // Get time tracking sessions to calculate performance data
            val timeTracker = TimeTracker(context)
            val allSessions = timeTracker.getTodaySessionsList()

            // For each task in config, create PracticeProgress with real data
            optionalTasks.forEach { task ->
                val taskName = task.title ?: "Unknown Task"
                val baseTaskId = task.launch ?: taskName.lowercase().replace(" ", "_")

                // For optional tasks, sessions use unique task IDs with section prefix
                val optionalTaskId = "optional_$baseTaskId"

                // Find all sessions for this task (could be completed multiple times)
                val matchingSessions = allSessions.filter { session ->
                    session.activityId == optionalTaskId
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
                    questionsAnswered = questionsAnswered,
                    showdays = task.showdays,
                    hidedays = task.hidedays,
                    displayDays = task.displayDays,
                    disable = task.disable
                )
            }

            Log.d(TAG, "Collected ${practiceTasks.size} practice tasks from optional section (${optionalTasks.size} total optional tasks in config)")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting practice tasks data", e)
        }

        return practiceTasks
    }

    /**
     * Collects checklist items data from config, including done status, stars, and display days
     */
    private fun collectChecklistItemsData(completedTasks: Map<String, Boolean>): Map<String, ChecklistItemProgress> {
        val checklistItems = mutableMapOf<String, ChecklistItemProgress>()

        try {
            // Get checklist items from config
            val checklistSection = getConfigChecklistSection()

            // For each checklist item in config, create ChecklistItemProgress
            checklistSection?.items?.forEach { item ->
                val itemLabel = item.label ?: "Unknown Item"
                val itemId = item.id ?: "checkbox_$itemLabel"
                
                // Check if this item is done
                val isDone = completedTasks[itemId] == true
                
                // Get stars from config
                val stars = item.stars ?: 0
                
                // Get display days from config (if any)
                val displayDays = item.displayDays

                checklistItems[itemLabel] = ChecklistItemProgress(
                    done = isDone,
                    stars = stars,
                    displayDays = displayDays
                )
            }

            Log.d(TAG, "Collected ${checklistItems.size} checklist items from config")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting checklist items data", e)
        }

        return checklistItems
    }

    /**
     * Gets checklist section from config
     */
    private fun getConfigChecklistSection(): Section? {
        try {
            // Load config content
            val contentUpdateService = ContentUpdateService()
            val jsonString = contentUpdateService.getCachedMainContent(context)

            if (jsonString.isNullOrEmpty()) {
                Log.w(TAG, "No cached main content available for checklist section")
                return null
            }

            val mainContent = gson.fromJson(jsonString, MainContent::class.java)
            return mainContent?.sections?.find { it.id == "checklist" }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading checklist section from config", e)
            return null
        }
    }

    /**
     * Gets all tasks from a specific config section, filtered by visibility for today
     * Only includes tasks that are visible on the current day
     */
    private fun getConfigTasksForSection(sectionId: String): List<Task> {
        return getConfigTasksForSection(sectionId, filterByVisibility = true)
    }

    /**
     * Gets all tasks from a specific config section
     * @param sectionId The section ID ("required" or "optional")
     * @param filterByVisibility If true, only returns tasks visible today. If false, returns ALL tasks for database syncing
     */
    private fun getConfigTasksForSection(sectionId: String, filterByVisibility: Boolean): List<Task> {
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

            // Get all tasks from section
            val allTasks = section?.tasks?.filterNotNull() ?: emptyList()
            
            // For database syncing, return ALL tasks (not filtered by visibility)
            // This ensures all tasks are in the database for reporting even if not visible today
            if (!filterByVisibility) {
                Log.d(TAG, "getConfigTasksForSection($sectionId, filterByVisibility=false): Returning ALL ${allTasks.size} tasks for database sync")
                return allTasks
            }
            
            // Filter tasks by visibility - only include tasks visible today
            val visibleTasks = allTasks.filter { task ->
                isTaskVisible(task.showdays, task.hidedays, task.displayDays, task.disable)
            }

            Log.d(TAG, "getConfigTasksForSection($sectionId): ${allTasks.size} total tasks, ${visibleTasks.size} visible today")
            return visibleTasks
        } catch (e: Exception) {
            Log.e(TAG, "Error loading config tasks for section: $sectionId", e)
            return emptyList()
        }
    }

    /**
     * Checks if a task should be visible based on day restrictions and disable date
     * This matches the logic in DailyProgressManager.isTaskVisible()
     */
    private fun isTaskVisible(showdays: String?, hidedays: String?, displayDays: String? = null, disable: String? = null): Boolean {
        // Check disable date first - if current date is before disable date, hide the task
        if (!disable.isNullOrEmpty()) {
            val disableDate = parseDisableDate(disable)
            if (disableDate != null) {
                val today = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                // If today is before the disable date, task is disabled (not visible)
                if (today.before(disableDate)) {
                    return false
                }
            }
        }

        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        val todayShort = when (today) {
            java.util.Calendar.MONDAY -> "mon"
            java.util.Calendar.TUESDAY -> "tue"
            java.util.Calendar.WEDNESDAY -> "wed"
            java.util.Calendar.THURSDAY -> "thu"
            java.util.Calendar.FRIDAY -> "fri"
            java.util.Calendar.SATURDAY -> "sat"
            java.util.Calendar.SUNDAY -> "sun"
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

    /**
     * Parses a date string in format "Nov 24, 2025" and returns a Calendar instance
     * Returns null if parsing fails
     */
    private fun parseDisableDate(dateString: String?): java.util.Calendar? {
        if (dateString.isNullOrEmpty()) return null
        
        return try {
            // Try parsing format like "Nov 24, 2025"
            val formatter = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US)
            val date = formatter.parse(dateString.trim())
            if (date != null) {
                java.util.Calendar.getInstance().apply {
                    time = date
                    // Set time to start of day for accurate comparison
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing disable date: $dateString", e)
            null
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
     * Applies app lists from cloud data to BaerenLock if local doesn't have them
     */
    private fun applyAppListsFromCloudIfLocalEmpty(data: CloudUserData) {
        try {
            val baerenLockContext = context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
            
            // Check if local has these lists
            val rewardPrefs = baerenLockContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val blacklistPrefs = baerenLockContext.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
            val whitelistPrefs = baerenLockContext.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            
            val localHasReward = rewardPrefs.getStringSet("reward_apps", null)?.isNotEmpty() == true
            val localHasBlacklist = blacklistPrefs.getStringSet("packages", null)?.isNotEmpty() == true
            val localHasWhitelist = whitelistPrefs.getStringSet("allowed", null)?.isNotEmpty() == true
            
            // Only apply if cloud has data and local doesn't
            if (!localHasReward && !data.rewardApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.rewardApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        rewardPrefs.edit().putStringSet("reward_apps", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} reward apps from cloud (local was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying reward apps from cloud", e)
                }
            }
            
            if (!localHasBlacklist && !data.blacklistedApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.blacklistedApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        blacklistPrefs.edit().putStringSet("packages", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} blacklisted apps from cloud (local was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying blacklisted apps from cloud", e)
                }
            }
            
            if (!localHasWhitelist && !data.whiteListedApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.whiteListedApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        whitelistPrefs.edit().putStringSet("allowed", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} whitelisted apps from cloud (local was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying whitelisted apps from cloud", e)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "BaerenLock not accessible for app list sync: ${e.message}")
        }
    }

    /**
     * Applies app lists from cloud data to BaerenLock SharedPreferences
     */
    private fun applyAppListsToBaerenLock(data: CloudUserData) {
        try {
            val baerenLockContext = context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
            
            // Apply reward apps
            data.rewardApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("reward_apps", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} reward apps to BaerenLock")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying reward apps to BaerenLock", e)
                }
            }
            
            // Apply blacklisted apps
            data.blacklistedApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("packages", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} blacklisted apps to BaerenLock")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying blacklisted apps to BaerenLock", e)
                }
            }
            
            // Apply whitelisted apps
            data.whiteListedApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("allowed", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} whitelisted apps to BaerenLock")
                        // Also refresh RewardManager if accessible
                        try {
                            val rewardManagerClass = Class.forName("com.talq2me.baerenlock.RewardManager")
                            val refreshMethod = rewardManagerClass.getMethod("refreshRewardEligibleApps", Context::class.java)
                            refreshMethod.invoke(null, baerenLockContext)
                        } catch (e: Exception) {
                            // RewardManager not accessible, that's okay
                            Log.d(TAG, "Could not refresh RewardManager: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying whitelisted apps to BaerenLock", e)
                }
            }
        } catch (e: Exception) {
            // BaerenLock not installed or not accessible - that's okay
            Log.d(TAG, "BaerenLock not accessible, skipping app list sync: ${e.message}")
        }
    }

    /**
     * Collects app list from BaerenLock SharedPreferences (if BaerenLock is installed)
     * Note: BaerenLock stores these globally, but we sync them per profile to cloud
     */
    private fun collectAppListFromBaerenLock(profile: String, appListType: String): String? {
        return try {
            // Try to access BaerenLock's SharedPreferences via package context
            val baerenLockContext = try {
                context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
            } catch (e: Exception) {
                // BaerenLock not installed or not accessible
                return null
            }

            val prefsName = when (appListType) {
                "reward_apps" -> "settings"
                "blacklisted_apps" -> "blacklist_prefs"
                "white_listed_apps" -> "whitelist_prefs"
                else -> "settings"
            }

            val keyName = when (appListType) {
                "reward_apps" -> "reward_apps"
                "blacklisted_apps" -> "packages"
                "white_listed_apps" -> "allowed"
                else -> appListType
            }

            val prefs = baerenLockContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            
            when (appListType) {
                "reward_apps" -> {
                    // Reward apps are stored as StringSet via SettingsManager in BaerenLock
                    // Try to read directly from SharedPreferences
                    val appSet = prefs.getStringSet(keyName, null)
                    if (appSet != null && appSet.isNotEmpty()) {
                        gson.toJson(appSet.toList())
                    } else {
                        null
                    }
                }
                "blacklisted_apps", "white_listed_apps" -> {
                    // Both are stored as StringSet
                    val appSet = prefs.getStringSet(keyName, null)
                    if (appSet != null && appSet.isNotEmpty()) {
                        gson.toJson(appSet.toList())
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not collect $appListType from BaerenLock: ${e.message}")
            null
        }
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

            // Check if cloud has data that local doesn't have (app lists, etc.)
            val cloudHasAppLists = !cloudData.rewardApps.isNullOrBlank() || 
                                  !cloudData.blacklistedApps.isNullOrBlank() || 
                                  !cloudData.whiteListedApps.isNullOrBlank()
            
            // Check if local has any of these app lists
            val localHasAppLists = try {
                val baerenLockContext = context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
                val rewardPrefs = baerenLockContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val blacklistPrefs = baerenLockContext.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
                val whitelistPrefs = baerenLockContext.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
                val hasReward = rewardPrefs.getStringSet("reward_apps", null)?.isNotEmpty() == true
                val hasBlacklist = blacklistPrefs.getStringSet("packages", null)?.isNotEmpty() == true
                val hasWhitelist = whitelistPrefs.getStringSet("allowed", null)?.isNotEmpty() == true
                hasReward || hasBlacklist || hasWhitelist
            } catch (e: Exception) {
                false // BaerenLock not accessible
            }

            // If cloud has app lists and local doesn't, apply cloud data
            if (cloudHasAppLists && !localHasAppLists) {
                Log.d(TAG, "Cloud has app lists but local doesn't - will apply cloud data")
                return true
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

            // Apply cloud data if it has more total progress (syncing between devices on the same day)
            // OR if local has no progress at all (fresh install, restore from cloud)
            val shouldApply = cloudTotalProgress > localTotalProgress || localTotalProgress == 0
            Log.d(TAG, "Should apply cloud data: $shouldApply (cloud has more progress: ${cloudTotalProgress > localTotalProgress}, local has no progress: ${localTotalProgress == 0})")

            return shouldApply

        } catch (e: Exception) {
            Log.e(TAG, "Error checking if cloud data should be applied", e)
            return false
        }
    }

    /**
     * Gets the count of completed tasks from local storage for a specific profile
     */
    private fun getLocalCompletedTasksCount(profile: String): Int {
        return try {
            val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
            val key = "${profile}_completed_tasks"
            val json = prefs.getString(key, "{}") ?: "{}"
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

            // Apply required tasks data - merge with existing local data, but ONLY tasks visible today
            if (data.requiredTasks?.isNotEmpty() == true) {
                // Get existing local data (profile-specific)
                val completedTasksKey = "${localProfile}_completed_tasks"
                val completedTaskNamesKey = "${localProfile}_completed_task_names"
                val existingCompletedTasksJson = progressPrefs.getString(completedTasksKey, "{}") ?: "{}"
                val existingCompletedTasks = gson.fromJson<Map<String, Boolean>>(existingCompletedTasksJson, object : TypeToken<Map<String, Boolean>>() {}.type)?.toMutableMap() ?: mutableMapOf()

                val existingCompletedTaskNamesJson = progressPrefs.getString(completedTaskNamesKey, "{}") ?: "{}"
                val existingCompletedTaskNames = gson.fromJson<Map<String, String>>(existingCompletedTaskNamesJson, object : TypeToken<Map<String, String>>() {}.type)?.toMutableMap() ?: mutableMapOf()

                // CRITICAL: Only apply tasks that are visible today
                // Get current visible tasks from config to filter cloud data
                val visibleConfigTasks = getConfigTasksForSection("required")
                val visibleTaskNames = visibleConfigTasks.mapNotNull { it.title }.toSet()
                
                var appliedCount = 0
                var skippedCount = 0

                // Merge cloud data with local data, but only for visible tasks
                data.requiredTasks.forEach { (taskName, taskProgress) ->
                    // Only apply if task is visible today
                    if (visibleTaskNames.contains(taskName)) {
                        // Find the taskId for this task name from visible config
                        val task = visibleConfigTasks.find { it.title == taskName }
                        val taskId = task?.launch ?: taskName.lowercase().replace(" ", "_").replace("-", "_")

                        existingCompletedTasks[taskId] = taskProgress.status == "complete"
                        existingCompletedTaskNames[taskId] = taskName
                        appliedCount++
                    } else {
                        Log.d(TAG, "Skipping cloud task '$taskName' - not visible today (filtered by visibility rules)")
                        skippedCount++
                    }
                }

                progressPrefs.edit()
                    .putString(completedTasksKey, gson.toJson(existingCompletedTasks))
                    .putString(completedTaskNamesKey, gson.toJson(existingCompletedTaskNames))
                    .apply()

                Log.d(TAG, "Applied $appliedCount required tasks to local storage for profile: $localProfile (skipped $skippedCount tasks not visible today)")
            }

            // Apply other progress metrics (all profile-specific)
            val bankedMinsKey = "${localProfile}_banked_reward_minutes"
            val bankedMinsTimestampKey = "${localProfile}_banked_reward_minutes_timestamp"
            val possibleStarsKey = "${localProfile}_total_possible_stars"
            
            // TIMESTAMP-BASED SYNC: Compare local banked_mins timestamp vs cloud last_updated timestamp
            // Apply whichever is newer (most recent timestamp wins)
            val currentLocalBankedMins = progressPrefs.getInt(bankedMinsKey, 0)
            val localBankedMinsTimestamp = progressPrefs.getString(bankedMinsTimestampKey, null)
            val cloudTimestamp = data.lastUpdated
            
            val (bankedMinsToApply, shouldSyncLocalToCloud) = when {
                localBankedMinsTimestamp.isNullOrEmpty() && currentLocalBankedMins == 0 -> {
                    // No local timestamp and local is 0 - fresh install/reset
                    // On fresh install, default to 0 to prevent stale cloud data from being applied
                    Log.d(TAG, "Fresh install detected - setting banked_mins to 0 (cloud had ${data.bankedMins}, but ignoring on fresh install)")
                    // Set EST timestamp to prevent cloud value from being applied again
                    val estTimestamp = generateESTTimestamp()
                    progressPrefs.edit()
                        .putString(bankedMinsTimestampKey, estTimestamp)
                        .apply()
                    Log.d(TAG, "Set initial banked_mins_timestamp in EST ($estTimestamp) to prevent cloud value from being applied again")
                    Pair(0, true) // Sync 0 to cloud to clear any stale data
                }
                cloudTimestamp.isNullOrEmpty() -> {
                    // No cloud timestamp - keep local value and sync to cloud
                    Log.d(TAG, "Keeping local banked_mins ($currentLocalBankedMins) - cloud has no timestamp")
                    Pair(currentLocalBankedMins, true)
                }
                else -> {
                    // Both have timestamps - compare and use the newer one
                    try {
                        val localTime = if (!localBankedMinsTimestamp.isNullOrEmpty()) {
                            parseTimestamp(localBankedMinsTimestamp)
                        } else {
                            // No local timestamp but local has value - treat as very old (0) to prefer cloud
                            0L
                        }
                        val cloudTime = parseTimestamp(cloudTimestamp)
                        
                        Log.d(TAG, "Comparing timestamps - local: $localBankedMinsTimestamp ($localTime), cloud: $cloudTimestamp ($cloudTime)")
                        
                        if (cloudTime > localTime) {
                            // Cloud is newer - apply cloud value
                            Log.d(TAG, "Applying cloud banked_mins ($data.bankedMins) - cloud timestamp ($cloudTimestamp) is newer than local ($localBankedMinsTimestamp)")
                            Pair(data.bankedMins, false)
                        } else {
                            // Local is newer or equal - keep local value and sync to cloud
                            Log.d(TAG, "Keeping local banked_mins ($currentLocalBankedMins) - local timestamp ($localBankedMinsTimestamp) is newer than or equal to cloud ($cloudTimestamp)")
                            Pair(currentLocalBankedMins, true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error comparing timestamps for banked_mins, keeping local value", e)
                        Pair(currentLocalBankedMins, true)
                    }
                }
            }
            
            // Update local storage with the chosen value
            progressPrefs.edit()
                .putInt(possibleStarsKey, data.possibleStars)
                .putInt(bankedMinsKey, bankedMinsToApply)
                .apply()
            
            // If we kept local value and it differs from cloud, sync local to cloud
            if (shouldSyncLocalToCloud && bankedMinsToApply != data.bankedMins) {
                Log.d(TAG, "Local banked_mins ($bankedMinsToApply) differs from cloud ($data.bankedMins), syncing local to cloud")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        syncBankedMinutesToCloud(bankedMinsToApply)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing local banked_mins to cloud", e)
                    }
                }
            } else if (bankedMinsToApply != currentLocalBankedMins) {
                // We applied cloud value - update local timestamp to match cloud timestamp
                if (!cloudTimestamp.isNullOrEmpty()) {
                    progressPrefs.edit()
                        .putString(bankedMinsTimestampKey, cloudTimestamp)
                        .apply()
                    Log.d(TAG, "Updated local banked_mins timestamp to match cloud: $cloudTimestamp")
                }
            }

            // Apply berries earned (store in pokemonBattleHub preferences where UI expects it, profile-specific)
            // IMPORTANT: Only apply cloud berries if they're HIGHER than local berries
            // This prevents overwriting local progress with outdated cloud data
            try {
                val berriesKey = "${localProfile}_earnedBerries"
                val prefs = context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                val localBerries = prefs.getInt(berriesKey, 0)
                
                // Only apply cloud berries if they're higher than local (preserve local progress)
                val berriesToApply = if (data.berriesEarned > localBerries) {
                    data.berriesEarned
                } else {
                    localBerries
                }
                
                if (berriesToApply != localBerries) {
                    prefs.edit()
                        .putInt(berriesKey, berriesToApply)
                        .apply()
                    Log.d(TAG, "Applied berries_earned: $berriesToApply (cloud: ${data.berriesEarned}, local: $localBerries) for profile: $localProfile")
                } else {
                    Log.d(TAG, "Preserved local berries: $localBerries (cloud: ${data.berriesEarned} is lower or equal) for profile: $localProfile")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying berries to local storage", e)
            }

            // Apply Pokemon data
            progressPrefs.edit()
                .putInt("${localProfile}_$KEY_POKEMON_UNLOCKED", data.pokemonUnlocked)
                .apply()

            // Apply game indices (all types: games, web games, videos)
            applyGameIndicesToLocal(data.gameIndices ?: emptyMap(), localProfile)

            // Apply app lists to BaerenLock (if BaerenLock is installed)
            applyAppListsToBaerenLock(data)

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

            // Count completed tasks (profile-specific)
            val completedTasksKey = "${profile}_completed_tasks"
            val completedTasksJson = progressPrefs.getString(completedTasksKey, "{}") ?: "{}"
            val completedTasks = gson.fromJson<Map<String, Boolean>>(completedTasksJson, object : TypeToken<Map<String, Boolean>>() {}.type) ?: emptyMap()

            // Count pokemon unlocked
            val pokemonUnlocked = progressPrefs.getInt("${profile}_pokemon_unlocked", 0)

            // Count berries (profile-specific)
            val berriesKey = "${profile}_earnedBerries"
            val berriesEarned = try {
                context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                    .getInt(berriesKey, 0)
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
            
            // Find timezone offset - look for +/- after the time part (after seconds/milliseconds)
            // Format: "2026-01-09T14:30:00.123-05:00" or "2026-01-09T14:30:00.123+05:00"
            val timePartEnd = timestamp.indexOf('.')
            val timeEndIndex = if (timePartEnd > 0) {
                // Has milliseconds - find the end of milliseconds
                val millisEnd = timestamp.indexOfAny(charArrayOf('+', '-', 'Z'), timePartEnd)
                if (millisEnd > 0) millisEnd else timestamp.length
            } else {
                // No milliseconds - find ':' after seconds
                val secondsColon = timestamp.lastIndexOf(':')
                if (secondsColon > 0) secondsColon + 3 else timestamp.length
            }
            
            val plusIndex = timestamp.indexOf("+", timeEndIndex)
            val minusIndex = timestamp.indexOf("-", timeEndIndex)
            val hasPlusOffset = plusIndex > 0
            val hasMinusOffset = minusIndex > 0
            
            if (timestamp.contains("T") && (hasZ || hasPlusOffset || hasMinusOffset)) {
                // Extract timezone offset
                val timezonePart = when {
                    hasZ -> "+00:00"
                    hasPlusOffset -> {
                        timestamp.substring(plusIndex + 1)
                    }
                    hasMinusOffset -> {
                        "-" + timestamp.substring(minusIndex + 1)
                    }
                    else -> "+00:00"
                }
                
                // Extract clean timestamp (date and time without timezone)
                val cleanTimestamp = when {
                    hasZ -> timestamp.substringBefore("Z")
                    hasPlusOffset -> timestamp.substring(0, plusIndex)
                    hasMinusOffset -> timestamp.substring(0, minusIndex)
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


