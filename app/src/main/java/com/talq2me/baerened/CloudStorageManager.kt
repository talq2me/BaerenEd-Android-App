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
 * 
 * User progress: DB is source of truth ([UserDataRepository.fetchUserData]); writes use RPCs from [DailyProgressManager].
 * [downloadFromCloud] applies the fetched row to prefs/session whenever the download succeeds (no local-vs-DB last_updated gate).
 */
class CloudStorageManager(private val context: Context) : ICloudStorageManager {

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
    
    // Delegates for better separation of concerns
    private val dataCollector = ProgressDataCollector(context)
    private val syncService = CloudSyncService()
    private val userDataRepository = UserDataRepository.getInstance(context)
    private val dataApplier = CloudDataApplier(context) { profile, timestamp ->
        setStoredLastUpdatedTimestamp(profile, timestamp)
    }

    /**
     * Checks if Supabase is configured (has URL and key in BuildConfig)
     */
    override fun isConfigured(): Boolean {
        return syncService.isConfigured()
    }

    // Data classes moved to CloudData.kt for better organization
    
    // Helper methods - TODO: Move to CloudSyncService or remove after full refactor
    private fun getSupabaseUrl(): String {
        return try {
            BuildConfig.SUPABASE_URL.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase URL from BuildConfig", e)
            ""
        }
    }

    private fun getSupabaseKey(): String {
        return try {
            BuildConfig.SUPABASE_KEY.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase key from BuildConfig", e)
            ""
        }
    }

    /**
     * Checks if cloud storage is enabled
     */
    override fun isCloudStorageEnabled(): Boolean {
        return prefs.getBoolean(KEY_USE_CLOUD, false)
    }

    /**
     * Enables or disables cloud storage
     */
    override fun setCloudStorageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CLOUD, enabled).apply()
    }

    /**
     * Sets the current profile for cloud operations
     */
    override fun setCurrentProfile(profile: String) {
        prefs.edit().putString(KEY_CURRENT_PROFILE, profile).apply()
    }

    /**
     * Gets the current profile
     */
    override fun getCurrentProfile(): String? {
        return prefs.getString(KEY_CURRENT_PROFILE, null)
    }

    /**
     * Converts profile from local format (A/B) to cloud format (AM/BM)
     */
    // Removed profile conversion - use profile names directly

    /**
     * Legacy full-document upload from prefs is disabled: progress writes use RPCs (af_update_*).
     * Config seeding uses [DailyResetAndSyncManager.invokeConfigFromGitHubRpcsThenRefetch].
     */
    override suspend fun uploadToCloud(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.w(TAG, "uploadToCloud(profile=$profile) ignored — full prefs upload disabled; use RPCs + DB refetch")
        Result.success(Unit)
    }

    /**
     * Fetches user_data from DB and applies it to prefs/session. DB row is authoritative.
     */
    private suspend fun downloadFromCloudInternal(profile: String): Result<CloudUserData?> = withContext(Dispatchers.IO) {
        val result = syncService.downloadUserData(profile)
        if (result.isSuccess) {
            val userData = result.getOrNull()
            if (userData != null) {
                Log.d(TAG, "Applying DB snapshot for profile: $profile (last_updated=${userData.lastUpdated})")
                dataApplier.applyDbDataToPrefs(userData)
                DailyProgressManager(context).setProgressDataAfterFetch(userData)
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                Log.d(TAG, "Applied downloaded DB data to local storage for profile: $profile")
            }
        }
        result
    }

    /**
     * Downloads data from cloud for the current profile
     * Profile can be in local format (A/B) or cloud format (AM/BM)
     */
    override suspend fun downloadFromCloud(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = downloadFromCloudInternal(profile)
        if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Download failed"))
        }
    }


    /**
     * Compares two timestamps and returns:
     * - Positive value if timestamp1 is newer than timestamp2
     * - Negative value if timestamp1 is older than timestamp2
     * - Zero if they are equal
     */
    private fun compareTimestamps(timestamp1: String, timestamp2: String): Int {
        val time1 = parseTimestampAsEST(timestamp1)
        val time2 = parseTimestampAsEST(timestamp2)
        return time1.compareTo(time2)
    }

    /**
     * Resets daily progress in the cloud database for a specific profile
     * Triggers the database trigger by setting last_updated to null, which causes the trigger
     * to detect a new day and automatically reset all daily progress fields.
     * This matches BaerenLock's approach and ensures both apps use the same reset mechanism.
     */
    override suspend fun resetProgressInCloud(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            // Note: Database trigger has been removed. We now handle reset in app code.
            // This method should set last_reset to now() EST and reset all daily progress fields.
            // For now, we'll use the syncService to update last_reset and reset fields directly.
            val resetTimestamp = syncService.generateESTTimestamp()
            val resetData: Map<String, Any> = mapOf(
                "last_reset" to resetTimestamp,
                "last_updated" to resetTimestamp,
                "required_tasks" to "{}",
                "practice_tasks" to "{}",
                "checklist_items" to "{}",
                "berries_earned" to 0,
                "banked_mins" to 0
            )
            
            syncService.resetProgressInCloud(profile, resetData)
            Log.d(TAG, "Triggered cloud reset for profile: $profile (database trigger will reset daily progress)")
            
            // Wait a moment for the database trigger to complete before returning
            // This ensures the reset is fully processed before any subsequent queries
            kotlinx.coroutines.delay(1000)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting progress in cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads settings (pin and email) to the settings table
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     */
    suspend fun uploadSettingsToCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            // CRITICAL: Check cloud timestamp first before updating
            val checkUrl = "${syncService.getSupabaseUrl()}/rest/v1/settings?id=eq.1&select=last_updated"
            val checkRequest = Request.Builder()
                .url(checkUrl)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()
            
            val checkResponse = syncService.getClient().newCall(checkRequest).execute()
            if (checkResponse.isSuccessful) {
                val responseBody = checkResponse.body?.string() ?: "[]"
                checkResponse.close()
                
                if (responseBody != "[]" && responseBody != "{}") {
                    val settingsArray = gson.fromJson(responseBody, object : com.google.gson.reflect.TypeToken<Array<Map<String, Any>>>() {}.type) as? Array<Map<String, Any>>
                    val settings = settingsArray?.firstOrNull()
                    val cloudTimestamp = settings?.get("last_updated") as? String
                    
                    if (cloudTimestamp != null) {
                        val prefs = context.getSharedPreferences("baeren_shared_settings", Context.MODE_PRIVATE)
                        val localTimestamp = prefs.getString("settings_timestamp", null)
                        
                        if (localTimestamp != null) {
                            val cloudIsNewer = compareTimestamps(cloudTimestamp, localTimestamp) > 0
                            Log.d(TAG, "uploadSettingsToCloud timestamp check: cloudIsNewer=$cloudIsNewer (cloud=$cloudTimestamp, local=$localTimestamp)")
                            if (cloudIsNewer) {
                                Log.d(TAG, "Cloud settings are newer, not updating cloud")
                                return@withContext Result.success(Unit) // Cloud is newer, don't overwrite
                            }
                        } else {
                            Log.d(TAG, "No local timestamp for settings, not updating cloud")
                            return@withContext Result.success(Unit) // No local timestamp, don't overwrite cloud
                        }
                    }
                }
            }
            
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

            val baseUrl = "${syncService.getSupabaseUrl()}/rest/v1/settings"
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

            val patchResponse = syncService.getClient().newCall(patchRequest).execute()
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

                val insertResponse = syncService.getClient().newCall(insertRequest).execute()
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
        val estTimeZone = java.util.TimeZone.getTimeZone("America/Toronto")
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
     * REMOVED: Duplicate methods (collectLocalData, collectRequiredTasksData, collectChecklistItemsData,
     * collectPracticeTasksData, collectAllGameIndices, collectAppListFromBaerenLock, getConfigChecklistSection,
     * getConfigTasksForSection, isTaskVisible, parseDisableDate) have been removed.
     * 
     * CloudStorageManager now uses dataCollector.buildUploadPayloadFromPrefs() instead.
     */
    
    /**
     * Parses timestamp string as Toronto time (America/Toronto timezone)
     * Both cloud and local timestamps are stored in EST format
     * Strips any timezone offset suffix if present and parses the base time as EST
     */
    private fun parseTimestampAsEST(timestamp: String): Long {
        return try {
            // Strip any timezone offset suffix (Z, +00:00, -05:00, etc.) and parse as EST
            val baseTimestamp = when {
                timestamp.endsWith("Z") -> timestamp.substring(0, timestamp.length - 1)
                timestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$")) -> {
                    // Strip offset suffix (e.g., "-05:00" or "+00:00")
                    val timePartEnd = timestamp.indexOf('.')
                    val timeEndIndex = if (timePartEnd > 0) {
                        val millisEnd = timestamp.indexOfAny(charArrayOf('+', '-'), timePartEnd)
                        if (millisEnd > 0) millisEnd else timestamp.length
                    } else {
                        val secondsColon = timestamp.lastIndexOf(':')
                        if (secondsColon > 0) {
                            val offsetStart = timestamp.indexOfAny(charArrayOf('+', '-'), secondsColon)
                            if (offsetStart > 0) offsetStart else timestamp.length
                        } else {
                            timestamp.length
                        }
                    }
                    timestamp.substring(0, timeEndIndex)
                }
                else -> timestamp
            }
            
            // Parse the base time as Toronto time (America/Toronto timezone)
            // Try multiple formats: with milliseconds (3 or 2 decimal places), without milliseconds
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss.SS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss.SS",
                "yyyy-MM-dd HH:mm:ss"
            )
            
            for (formatStr in formats) {
                try {
                    val dateFormat = java.text.SimpleDateFormat(formatStr, java.util.Locale.getDefault()).apply {
                        timeZone = java.util.TimeZone.getTimeZone("America/Toronto")
                    }
                    val date = dateFormat.parse(baseTimestamp)
                    if (date != null) {
                        return date.time
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }
            
            // If all formats failed, return 0
            return 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp as EST: $timestamp", e)
            0L
        }
    }
    
    /**
     * Gets the count of completed tasks from local storage in NEW format (required_tasks)
     */
    private fun getLocalCompletedTasksCountNewFormat(profile: String): Int {
        return try {
            val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
            val key = "${profile}_required_tasks"
            val json = prefs.getString(key, "{}") ?: "{}"
            val tasks = gson.fromJson<Map<String, TaskProgress>>(json, object : TypeToken<Map<String, TaskProgress>>() {}.type) ?: emptyMap()
            tasks.values.count { it.status == "complete" }
        } catch (e: Exception) {
            0
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
     * Returns the stored timestamp from local data, or a very old timestamp if not stored
     * CRITICAL: Do NOT generate a new timestamp here - this would make local always appear newer
     * If no timestamp exists, return a very old timestamp so cloud will be considered newer
     */
    private fun getStoredLastUpdatedTimestamp(profile: String): String {
        // Try to get stored timestamp from SharedPreferences
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        val storedTimestampKey = "${profile}_last_updated_timestamp"
        val storedTimestamp = progressPrefs.getString(storedTimestampKey, null)
        
        if (!storedTimestamp.isNullOrEmpty()) {
            return storedTimestamp
        }
        
        // No stored timestamp - return a very old timestamp (epoch) so cloud will always be newer
        // This ensures that if local has no timestamp, we download from cloud (cloud is source of truth)
        // Format: ISO 8601 EST format for a very old date (1970-01-01)
        return "1970-01-01T00:00:00.000-05:00"
    }
    
    /**
     * Stores the local last updated timestamp after applying cloud data
     */
    private fun setStoredLastUpdatedTimestamp(profile: String, timestamp: String) {
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        val storedTimestampKey = "${profile}_last_updated_timestamp"
        val success = progressPrefs.edit()
            .putString(storedTimestampKey, timestamp)
            .commit() // Use commit() for synchronous write to prevent race conditions
        if (!success) {
            android.util.Log.e(TAG, "CRITICAL ERROR: Failed to save last_updated_timestamp in CloudStorageManager!")
        }
    }

    // Data application logic moved to CloudDataApplier class

    /**
     * Pulls DB (source of truth) and applies to prefs/session. No local-vs-cloud timestamp compare and no prefs upload.
     */
    override suspend fun syncIfEnabled(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isCloudStorageEnabled() || !isConfigured()) {
            Log.d(TAG, "Cloud sync disabled or not configured")
            return@withContext Result.success(Unit)
        }
        Log.d(TAG, "syncIfEnabled: fetch from DB for profile $profile (no prefs upload)")
        try {
            val fetchResult = userDataRepository.fetchUserData(profile)
            if (fetchResult.isFailure) {
                return@withContext Result.failure(fetchResult.exceptionOrNull() ?: Exception("Fetch failed"))
            }
            val data = fetchResult.getOrNull()
            if (data != null) {
                dataApplier.applyDbDataToPrefs(data)
                DailyProgressManager(context).setProgressDataAfterFetch(data)
            } else {
                val empty = CloudUserData(profile = profile)
                dataApplier.applyDbDataToPrefs(empty)
                DailyProgressManager(context).setProgressDataAfterFetch(empty)
            }
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            downloadSettingsFromCloud().onFailure {
                Log.w(TAG, "downloadSettingsFromCloud failed: ${it.message}")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "syncIfEnabled failed", e)
            Result.failure(e)
        }
    }

    /**
     * Determines if local data should be uploaded to cloud based on timestamps
     * Simple timestamp-based: if local timestamp is newer than cloud, upload local data
     */
    private fun shouldUploadLocalData(localTimestamp: String, cloudTimestamp: String?): Boolean {
        if (cloudTimestamp.isNullOrEmpty()) {
            // No cloud data exists, upload local data
            return true
        }

        return try {
            val localTime = parseTimestampAsEST(localTimestamp)
            val cloudTime = parseTimestampAsEST(cloudTimestamp)

            // Upload if local is newer than cloud
            val shouldUpload = localTime > cloudTime
            Log.d(TAG, "Upload decision - localTime: $localTime, cloudTime: $cloudTime, upload: $shouldUpload")
            shouldUpload
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing timestamps for upload decision", e)
            true // Default to uploading if we can't compare
        }
    }

    /**
     * Determines if cloud data should be downloaded and applied based on timestamps
     * Simple timestamp-based: if cloud timestamp is newer than local, download cloud data
     */
    private fun shouldDownloadCloudData(localTimestamp: String, cloudTimestamp: String?, cloudUserData: CloudUserData?, profile: String): Boolean {
        if (cloudTimestamp.isNullOrEmpty() || cloudUserData == null) {
            // No cloud data to download
            return false
        }

        // Use timestamp comparison only
        return try {
            val localTime = parseTimestampAsEST(localTimestamp)
            val cloudTime = parseTimestampAsEST(cloudTimestamp)

            // Download if cloud is newer than local
            val shouldDownload = cloudTime > localTime
            Log.d(TAG, "Download decision - cloudTime: $cloudTime, localTime: $localTime, download: $shouldDownload")
            shouldDownload
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
     * All timestamps are stored in EST without timezone suffixes
     * Strips any timezone suffix (Z, +00:00, -05:00) and parses the base time as EST
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            // All timestamps are stored in EST, regardless of the offset suffix
            // Strip any offset or Z suffix and parse the base time as EST
            val baseTimestamp = when {
                timestamp.endsWith("Z") -> timestamp.substring(0, timestamp.length - 1)
                timestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$")) -> {
                    val timePartEnd = timestamp.indexOf('.')
                    val timeEndIndex = if (timePartEnd > 0) {
                        val millisEnd = timestamp.indexOfAny(charArrayOf('+', '-'), timePartEnd)
                        if (millisEnd > 0) millisEnd else timestamp.length
                    } else {
                        val secondsColon = timestamp.lastIndexOf(':')
                        if (secondsColon > 0) {
                            val offsetStart = timestamp.indexOfAny(charArrayOf('+', '-'), secondsColon)
                            if (offsetStart > 0) offsetStart else timestamp.length
                        } else {
                            timestamp.length
                        }
                    }
                    timestamp.substring(0, timeEndIndex)
                }
                else -> timestamp
            }
            
            // Parse the base time as Toronto time (America/Toronto timezone)
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/Toronto")
            }
            
            val date = dateFormat.parse(baseTimestamp)
            return date?.time ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp: $timestamp", e)
            0L
        }
    }

    /**
     * Persists app settings to cloud if enabled. User progress is not uploaded from prefs (RPCs only).
     */
    override suspend fun saveIfEnabled(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "saveIfEnabled called for profile: $profile (user-data upload disabled; uses RPCs)")
        if (!isCloudStorageEnabled() || !isConfigured()) {
            Log.d(TAG, "Cloud sync skipped - enabled: ${isCloudStorageEnabled()}, configured: ${isConfigured()}")
            return@withContext Result.success(Unit)
        }
        val settingsResult = uploadSettingsToCloud()
        if (settingsResult.isFailure) {
            Log.e(TAG, "saveIfEnabled: settings upload failed", settingsResult.exceptionOrNull())
        }
        settingsResult
    }

    /**
     * Gets last sync timestamp
     */
    override fun getLastSyncTimestamp(): Long {
        return prefs.getLong(KEY_LAST_SYNC, 0)
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        scope.cancel()
    }
}


