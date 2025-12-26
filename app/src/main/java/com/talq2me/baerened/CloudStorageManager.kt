package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

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
    private val gson = Gson()
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
        val pin: String? = null,
        val email: String? = null,
        val adminPin: String? = null,
        
        // Daily progress data
        val completedTasks: Map<String, Boolean> = emptyMap(),
        val completedTaskNames: Map<String, String> = emptyMap(),
        val lastResetDate: String? = null,
        val totalPossibleStars: Int = 0,
        val bankedRewardMinutes: Int = 0,
        
        // Pokemon data
        val pokemonUnlocked: Int = 0,
        val lastPokemonUnlockDate: String? = null,
        
        // Game progress (map of launchId -> index)
        val gameProgress: Map<String, Int> = emptyMap(),
        
        // Video progress (map of videoFile -> index)
        val videoProgress: Map<String, Int> = emptyMap(),
        
        // Time tracking sessions
        val dailySessions: List<TimeTracker.ActivitySession> = emptyList(),
        val lastTimeTrackerReset: String? = null,
        
        // Metadata
        val lastUpdated: Long = System.currentTimeMillis(),
        val deviceId: String = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
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
    private fun toCloudProfile(localProfile: String): String {
        return when (localProfile) {
            "A" -> "AM"
            "B" -> "BM"
            else -> localProfile // Already in cloud format or unknown
        }
    }
    
    /**
     * Converts profile from cloud format (AM/BM) to local format (A/B)
     */
    private fun toLocalProfile(cloudProfile: String): String {
        return when (cloudProfile) {
            "AM" -> "A"
            "BM" -> "B"
            else -> cloudProfile // Already in local format or unknown
        }
    }

    /**
     * Uploads all local data to cloud for the current profile
     * Profile can be in local format (A/B) or cloud format (AM/BM)
     */
    suspend fun uploadToCloud(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            // Convert to cloud format for storage
            val cloudProfile = toCloudProfile(profile)
            val userData = collectLocalData(profile)
            val json = gson.toJson(userData)
            
            val url = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                Log.d(TAG, "Successfully uploaded data to cloud for profile: $cloudProfile")
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to upload to cloud: ${response.code} - $errorBody")
                Result.failure(Exception("Upload failed: ${response.code} - $errorBody"))
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
            // Convert to cloud format for query
            val cloudProfile = toCloudProfile(profile)
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
                    applyCloudDataToLocal(userData)
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
     * Collects all local data for a profile into CloudUserData
     */
    private fun collectLocalData(profile: String): CloudUserData {
        // Convert to cloud format
        val cloudProfile = toCloudProfile(profile)
        
        // Get settings
        val pin = SettingsManager.readPin(context)
        val email = SettingsManager.readEmail(context)
        
        // Get daily progress data
        val progressManager = DailyProgressManager(context)
        val completedTasks = progressManager.getCompletedTasksMap()
        val completedTaskNames = try {
            val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString("completed_task_names", "{}") ?: "{}"
            gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        val lastResetDate = progressManager.getLastResetDate()
        val totalPossibleStars = progressManager.getCachedTotalPossibleStars()
        val bankedRewardMinutes = progressManager.getBankedRewardMinutes()
        val adminPin = progressManager.getAdminPin()
        
        // Get Pokemon data (use local profile format for lookup)
        val localProfile = toLocalProfile(profile)
        val pokemonUnlocked = try {
            val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
            prefs.getInt("${localProfile}_$KEY_POKEMON_UNLOCKED", 0)
        } catch (e: Exception) {
            0
        }
        val lastPokemonUnlockDate = try {
            val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
            prefs.getString("last_pokemon_unlock_date", null)
        } catch (e: Exception) {
            null
        }
        
        // Get game progress
        val gameProgressMap = mutableMapOf<String, Int>()
        try {
            val gamePrefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)
            val allEntries = gamePrefs.all
            val profilePrefix = "${localProfile}_progress_"
            allEntries.forEach { (key, value) ->
                if (key.startsWith(profilePrefix) && value is Int) {
                    val launchId = key.removePrefix(profilePrefix)
                    gameProgressMap[launchId] = value
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting game progress", e)
        }
        
        // Get video progress
        val videoProgressMap = mutableMapOf<String, Int>()
        try {
            val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val allEntries = videoPrefs.all
            val profilePrefix = "${localProfile}_"
            allEntries.forEach { (key, value) ->
                if (key.startsWith(profilePrefix) && key.endsWith("_index") && value is Int) {
                    val videoFile = key.removePrefix(profilePrefix).removeSuffix("_index")
                    videoProgressMap[videoFile] = value
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting video progress", e)
        }
        
        // Get time tracking sessions
        val timeTracker = TimeTracker(context)
        val dailySessions = timeTracker.getTodaySessionsList()
        val lastTimeTrackerReset = try {
            val prefs = context.getSharedPreferences("time_tracking_prefs", Context.MODE_PRIVATE)
            prefs.getString("time_tracker_last_reset", null)
        } catch (e: Exception) {
            null
        }
        
        return CloudUserData(
            profile = cloudProfile,
            pin = pin,
            email = email,
            adminPin = adminPin,
            completedTasks = completedTasks,
            completedTaskNames = completedTaskNames,
            lastResetDate = lastResetDate,
            totalPossibleStars = totalPossibleStars,
            bankedRewardMinutes = bankedRewardMinutes,
            pokemonUnlocked = pokemonUnlocked,
            lastPokemonUnlockDate = lastPokemonUnlockDate,
            gameProgress = gameProgressMap,
            videoProgress = videoProgressMap,
            dailySessions = dailySessions,
            lastTimeTrackerReset = lastTimeTrackerReset
        )
    }

    /**
     * Applies cloud data to local storage
     */
    private fun applyCloudDataToLocal(data: CloudUserData) {
        try {
            // Convert cloud profile to local format
            val localProfile = toLocalProfile(data.profile)
            
            // Apply settings
            data.pin?.let { SettingsManager.writePin(context, it) }
            data.email?.let { SettingsManager.writeEmail(context, it) }
            SettingsManager.writeProfile(context, localProfile)
            
            // Apply daily progress
            val progressManager = DailyProgressManager(context)
            val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
            progressPrefs.edit()
                .putString("completed_tasks", gson.toJson(data.completedTasks))
                .putString("completed_task_names", gson.toJson(data.completedTaskNames))
                .putString("last_reset_date", data.lastResetDate ?: "")
                .putInt("total_possible_stars", data.totalPossibleStars)
                .putInt("banked_reward_minutes", data.bankedRewardMinutes)
                .putString("admin_pin", data.adminPin ?: "1981")
                .apply()
            
            // Apply Pokemon data (use local profile format)
            progressPrefs.edit()
                .putInt("${localProfile}_$KEY_POKEMON_UNLOCKED", data.pokemonUnlocked)
                .apply()
            data.lastPokemonUnlockDate?.let {
                progressPrefs.edit().putString("last_pokemon_unlock_date", it).apply()
            }
            
            // Apply game progress (use local profile format)
            val gamePrefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)
            val editor = gamePrefs.edit()
            data.gameProgress.forEach { (launchId, index) ->
                editor.putInt("${localProfile}_progress_$launchId", index)
            }
            editor.apply()
            
            // Apply video progress (use local profile format)
            val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val videoEditor = videoPrefs.edit()
            data.videoProgress.forEach { (videoFile, index) ->
                videoEditor.putInt("${localProfile}_${videoFile}_index", index)
            }
            videoEditor.apply()
            
            // Apply time tracking
            val timePrefs = context.getSharedPreferences("time_tracking_prefs", Context.MODE_PRIVATE)
            timePrefs.edit()
                .putString("daily_sessions", gson.toJson(data.dailySessions))
                .putString("time_tracker_last_reset", data.lastTimeTrackerReset ?: "")
                .apply()
            
            Log.d(TAG, "Successfully applied cloud data to local storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying cloud data to local", e)
            throw e
        }
    }

    /**
     * Syncs data (downloads from cloud if cloud is enabled)
     */
    suspend fun syncIfEnabled(profile: String): Result<Unit> {
        if (!isCloudStorageEnabled() || !isConfigured()) {
            return Result.success(Unit)
        }
        
        return when (val result = downloadFromCloud(profile)) {
            is Result.Success -> Result.success(Unit)
            is Result.Failure -> result
        }
    }

    /**
     * Saves data to cloud if cloud storage is enabled
     */
    suspend fun saveIfEnabled(profile: String): Result<Unit> {
        if (!isCloudStorageEnabled() || !isConfigured()) {
            return Result.success(Unit)
        }
        
        return uploadToCloud(profile)
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


