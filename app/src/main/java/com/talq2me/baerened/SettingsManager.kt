package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SettingsManager {

    private const val TAG = "SettingsManager"
    private const val LOCAL_PREFS_NAME = "baeren_shared_settings"
    private val gson = Gson()
    private val client = OkHttpClient()
    
    // Cache for settings to avoid repeated network calls
    private var cachedSettings: SettingsData? = null
    private val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class SettingsData(
        val pin: String? = null,
        val parentEmail: String? = null
    )

    /**
     * Reads settings from local SharedPreferences as fallback
     */
    private fun loadSettingsFromLocal(context: Context): SettingsData {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        return SettingsData(
            pin = prefs.getString("pin", null),
            parentEmail = prefs.getString("parent_email", null)
        )
    }

    /**
     * Saves settings to local SharedPreferences for offline persistence
     */
    private fun saveSettingsToLocal(context: Context, data: SettingsData) {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            data.pin?.let { putString("pin", it) }
            data.parentEmail?.let { putString("parent_email", it) }
            apply()
        }
    }

    /**
     * Gets Supabase URL from BuildConfig
     */
    private fun getSupabaseUrl(context: Context): String {
        return try {
            BuildConfig.SUPABASE_URL.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase URL from BuildConfig", e)
            ""
        }
    }

    /**
     * Gets Supabase API key from BuildConfig
     */
    private fun getSupabaseKey(context: Context): String {
        return try {
            BuildConfig.SUPABASE_KEY.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase key from BuildConfig", e)
            ""
        }
    }

    /**
     * Gets the unique Android device ID (ANDROID_ID from Settings.Secure)
     * This is a unique identifier per device/app signing key combination
     */
    private fun getDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device ID", e)
            "unknown"
        }
    }
    
    /**
     * Gets the user-friendly device name
     * Uses Settings.Global.DEVICE_NAME on API 25+, falls back to Build.MANUFACTURER + Build.MODEL
     */
    private fun getDeviceName(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                // API 25+ supports Settings.Global.DEVICE_NAME
                val deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                if (!deviceName.isNullOrBlank()) {
                    return deviceName
                }
            }
            // Fallback to manufacturer + model
            "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device name", e)
            "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        }
    }
    
    /**
     * Checks if Supabase is configured
     */
    private fun isConfigured(context: Context): Boolean {
        val url = getSupabaseUrl(context)
        val key = getSupabaseKey(context)
        return url.isNotBlank() && key.isNotBlank()
    }

    /**
     * Loads settings from Supabase (async)
     */
    /**
     * Loads settings from cloud and returns both data and timestamp
     */
    private suspend fun loadSettingsFromCloud(context: Context): Map<String, Any>? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, returning null")
            return@withContext null
        }

        try {
            val url = "${getSupabaseUrl(context)}/rest/v1/settings?id=eq.1&select=*"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()

                if (responseBody != null && responseBody != "[]" && responseBody != "{}") {
                    val settingsArray = gson.fromJson(responseBody, object : TypeToken<Array<Map<String, Any>>>() {}.type) as? Array<Map<String, Any>>
                    if (settingsArray != null && settingsArray.isNotEmpty()) {
                        val settings = settingsArray[0]
                        val pin = settings["pin"] as? String
                        val parentEmail = settings["parent_email"] as? String
                        val lastUpdated = settings["last_updated"] as? String
                        
                        val data = SettingsData(pin, parentEmail)
                        cachedSettings = data
                        Log.d(TAG, "Loaded settings from cloud: pin=${pin?.take(1)}..., email=$parentEmail, timestamp=$lastUpdated")
                        return@withContext mapOf(
                            "data" to data,
                            "last_updated" to (lastUpdated ?: "")
                        )
                    }
                }
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to load settings: ${response.code} - $errorBody")
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings from cloud", e)
        }
        null
    }

    /**
     * Saves settings to Supabase (async)
     */
    /**
     * Saves settings to Supabase
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     */
    private suspend fun saveSettingsToCloud(context: Context, data: SettingsData): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping save")
            return@withContext false
        }

        try {
            // CRITICAL: Check cloud timestamp first before updating
            val cloudResult = loadSettingsFromCloud(context)
            if (cloudResult != null) {
                val cloudTimestamp = cloudResult["last_updated"] as? String
                val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                val localTimestamp = prefs.getString("settings_timestamp", null)
                
                if (cloudTimestamp != null && localTimestamp != null) {
                    val cloudIsNewer = compareTimestamps(cloudTimestamp, localTimestamp) > 0
                    Log.d(TAG, "saveSettingsToCloud timestamp check: cloudIsNewer=$cloudIsNewer (cloud=$cloudTimestamp, local=$localTimestamp)")
                    if (cloudIsNewer) {
                        Log.d(TAG, "Cloud settings are newer, not updating cloud")
                        return@withContext true // Cloud is newer, don't overwrite
                    }
                } else if (cloudTimestamp != null && localTimestamp == null) {
                    Log.d(TAG, "Cloud has timestamp but local doesn't, not updating cloud")
                    return@withContext true // Cloud has timestamp, don't overwrite
                }
            }
            
            val settingsMap = mutableMapOf<String, Any?>()
            data.pin?.let { settingsMap["pin"] = it }
            data.parentEmail?.let { settingsMap["parent_email"] = it }

            val json = gson.toJson(settingsMap)
            val baseUrl = "${getSupabaseUrl(context)}/rest/v1/settings"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            // Try to update existing settings, fallback to insert
            val updateUrl = "$baseUrl?id=eq.1"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .addHeader("Prefer", "return=minimal")
                .build()

            val patchResponse = client.newCall(patchRequest).execute()
            if (patchResponse.isSuccessful) {
                patchResponse.close()
                cachedSettings = data
                Log.d(TAG, "Updated settings in cloud")
                return@withContext true
            } else if (patchResponse.code == 404) {
                // No existing record, try to insert
                patchResponse.close()
                val insertRequest = Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .addHeader("apikey", getSupabaseKey(context))
                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                val insertResponse = client.newCall(insertRequest).execute()
                if (insertResponse.isSuccessful) {
                    insertResponse.close()
                    cachedSettings = data
                    Log.d(TAG, "Inserted settings in cloud")
                    return@withContext true
                } else {
                    val errorBody = insertResponse.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Failed to insert settings: ${insertResponse.code} - $errorBody")
                    insertResponse.close()
                }
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to update settings: ${patchResponse.code} - $errorBody")
                patchResponse.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings to cloud", e)
        }
        false
    }

    /**
     * Synchronously reads profile from local SharedPreferences
     * Note: Profile is NOT stored in cloud settings table, only in local storage and user_data table
     */
    fun readProfile(context: Context): String? {
        // Profile is stored in local SharedPreferences only (not in cloud settings table)
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("profile", null)
    }

    /**
     * Syncs active profile to cloud devices table asynchronously
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     */
    private fun syncActiveProfileToCloudAsync(context: Context, profile: String) {
        settingsScope.launch {
            try {
                if (!isConfigured(context)) {
                    Log.d(TAG, "Supabase not configured, skipping active profile sync")
                    return@launch
                }
                
                val deviceId = getDeviceId(context)
                val deviceName = getDeviceName(context)
                val localTimestamp = getLocalProfileTimestamp(context)
                
                // CRITICAL: Check cloud timestamp first before updating
                val cloudProfileData = getActiveProfileFromCloud(context)
                if (cloudProfileData != null) {
                    val cloudTimestamp = cloudProfileData.lastUpdated
                    Log.d(TAG, "syncActiveProfileToCloud: local=$profile (timestamp=$localTimestamp), cloud=${cloudProfileData.profile} (timestamp=$cloudTimestamp)")
                    
                    // Compare timestamps to determine if we should update
                    val shouldUpdate = if (cloudTimestamp != null && localTimestamp != null) {
                        val localTime = parseTimestampForComparison(localTimestamp)
                        val cloudTime = parseTimestampForComparison(cloudTimestamp)
                        val localIsNewer = localTime > cloudTime
                        Log.d(TAG, "syncActiveProfileToCloud timestamp comparison:")
                        Log.d(TAG, "  Local: $localTimestamp (parsed: $localTime)")
                        Log.d(TAG, "  Cloud: $cloudTimestamp (parsed: $cloudTime)")
                        Log.d(TAG, "  Local is newer: $localIsNewer")
                        localIsNewer
                    } else if (localTimestamp != null) {
                        // Local has timestamp but cloud doesn't - update cloud
                        Log.d(TAG, "Local has timestamp but cloud doesn't, updating cloud")
                        true
                    } else {
                        // No local timestamp - don't overwrite cloud
                        Log.d(TAG, "No local timestamp, not updating cloud")
                        false
                    }
                    
                    if (!shouldUpdate) {
                        Log.d(TAG, "Cloud profile is newer or equal, not updating device record")
                        return@launch
                    }
                    
                    // Also check if profiles match - if they're the same, no need to update
                    if (cloudProfileData.profile == profile && cloudTimestamp != null && localTimestamp != null) {
                        Log.d(TAG, "Profiles match and timestamps exist, skipping update")
                        return@launch
                    }
                }
                
                val updateMap = mapOf(
                    "device_id" to deviceId,
                    "device_name" to deviceName,
                    "active_profile" to profile // Store in AM/BM format
                    // Note: last_updated will be set by database trigger to current time
                )
                
                val json = gson.toJson(updateMap)
                val baseUrl = "${getSupabaseUrl(context)}/rest/v1/devices"
                val requestBody = json.toRequestBody("application/json".toMediaType())
                
                // Try to update existing device record first
                val updateUrl = "$baseUrl?device_id=eq.$deviceId"
                val patchRequest = Request.Builder()
                    .url(updateUrl)
                    .patch(requestBody)
                    .addHeader("apikey", getSupabaseKey(context))
                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                    .addHeader("Prefer", "return=representation")
                    .build()
                
                val patchResponse = client.newCall(patchRequest).execute()
                val patchResponseBody = patchResponse.body?.string() ?: "[]"
                patchResponse.close()
                
                if (patchResponse.isSuccessful && patchResponseBody != "[]" && patchResponseBody != "{}") {
                    // Update succeeded
                    Log.d(TAG, "Synced active profile to cloud devices table: deviceId=$deviceId, profile=$profile")
                    return@launch
                }
                
                // PATCH failed or no rows updated - try to insert
                val insertRequest = Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .addHeader("apikey", getSupabaseKey(context))
                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                    .addHeader("Prefer", "return=representation")
                    .build()
                
                val insertResponse = client.newCall(insertRequest).execute()
                val insertResponseBody = insertResponse.body?.string() ?: "[]"
                insertResponse.close()
                
                if (insertResponse.isSuccessful && insertResponseBody != "[]" && insertResponseBody != "{}") {
                    Log.d(TAG, "Inserted active profile in cloud devices table: deviceId=$deviceId, profile=$profile")
                } else if (insertResponse.code == 409) {
                    // 409 = duplicate key - record already exists (another concurrent request created it)
                    Log.d(TAG, "Device record already exists during active profile sync (409), record is fine: deviceId=$deviceId")
                } else {
                    Log.w(TAG, "Failed to sync active profile to cloud: ${insertResponse.code} - $insertResponseBody")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync active profile to cloud: ${e.message}")
            }
        }
    }

    /**
     * Synchronously writes profile to local storage and syncs to cloud devices table
     * Note: Profile is stored in local SharedPreferences and synced to devices table
     * Also stores a timestamp for comparison with cloud
     */
    fun writeProfile(context: Context, newProfile: String) {
        // Profile is stored in local SharedPreferences
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = generateESTTimestamp()
        prefs.edit().apply {
            putString("profile", newProfile)
            putString("profile_timestamp", timestamp)
            apply()
        }
        Log.d(TAG, "Profile written: $newProfile, timestamp: $timestamp")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        updateLastUpdatedTimestamp(context, newProfile)
        
        // Sync to cloud devices table asynchronously
        syncActiveProfileToCloudAsync(context, newProfile)
    }
    
    /**
     * Gets the local profile timestamp (when profile was last changed locally)
     */
    private fun getLocalProfileTimestamp(context: Context): String? {
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("profile_timestamp", null)
    }
    
    /**
     * Generates EST timestamp string
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
     * Updates last_updated timestamp in daily_progress_prefs to trigger cloud sync
     * This is called whenever settings that should sync to cloud are changed (as per Daily Reset Logic)
     */
    private fun updateLastUpdatedTimestamp(context: Context, profile: String) {
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        val timestamp = generateESTTimestamp()
        val key = "${profile}_last_updated_timestamp"
        progressPrefs.edit().putString(key, timestamp).apply()
        Log.d(TAG, "Updated last_updated timestamp for profile $profile: $timestamp")
    }
    
    /**
     * Compares two timestamps and returns:
     * - Positive value if timestamp1 is newer than timestamp2
     * - Negative value if timestamp1 is older than timestamp2
     * - Zero if they are equal
     */
    private fun compareTimestamps(timestamp1: String, timestamp2: String): Int {
        val time1 = parseTimestampForComparison(timestamp1)
        val time2 = parseTimestampForComparison(timestamp2)
        return time1.compareTo(time2)
    }
    
    /**
     * Parses timestamp string to milliseconds for comparison
     */
    private fun parseTimestampForComparison(timestamp: String): Long {
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
            
            // Parse the base time as EST (America/New_York timezone)
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/New_York")
            }
            
            val date = dateFormat.parse(baseTimestamp)
            val result = date?.time ?: 0L
            Log.d(TAG, "parseTimestampForComparison: $timestamp -> base=$baseTimestamp -> $result (parsed as EST)")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing timestamp for comparison: $timestamp", e)
            0L
        }
    }

    /**
     * Synchronously reads PIN from cache, cloud, or local storage (in that order)
     */
    fun readPin(context: Context): String? {
        // Try cache first
        cachedSettings?.pin?.let { return it }

        // Try to load from cloud synchronously (with timeout)
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val cloudSuccess = AtomicReference<Boolean>(false)
        
        settingsScope.launch {
            try {
                val cloudResult = loadSettingsFromCloud(context)
                if (cloudResult != null) {
                    val settings = cloudResult["data"] as? SettingsData
                    if (settings != null) {
                        cachedSettings = settings
                        saveSettingsToLocal(context, settings)
                        result.set(settings.pin)
                        cloudSuccess.set(true)
                    } else {
                        cloudSuccess.set(false)
                    }
                } else {
                    cloudSuccess.set(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading PIN from cloud, will try local storage: ${e.message}")
                cloudSuccess.set(false)
            } finally {
                latch.countDown()
            }
        }
        
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while reading PIN", e)
        }
        
        // If cloud load succeeded, return the result
        if (cloudSuccess.get() == true) {
            return result.get()
        }
        
        // Fallback to local storage
        Log.d(TAG, "Falling back to local storage for PIN")
        val localSettings = loadSettingsFromLocal(context)
        if (localSettings.pin != null) {
            cachedSettings = localSettings
            return localSettings.pin
        }
        
        return null
    }

    /**
     * Synchronously writes PIN to cache and local storage, then attempts cloud save
     * Also stores timestamp for comparison with cloud
     */
    fun writePin(context: Context, newPin: String) {
        // Update cache immediately
        val updatedSettings = cachedSettings?.copy(pin = newPin) ?: SettingsData(pin = newPin)
        cachedSettings = updatedSettings
        
        // Store timestamp when PIN is changed
        val timestamp = generateESTTimestamp()
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("settings_timestamp", timestamp).apply()
        Log.d(TAG, "PIN written, timestamp: $timestamp")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        val profile = readProfile(context) ?: "AM"
        updateLastUpdatedTimestamp(context, profile)
        
        // Save to local storage immediately for offline persistence
        saveSettingsToLocal(context, updatedSettings)
        
        // Attempt to save to cloud asynchronously
        settingsScope.launch {
            try {
                val currentSettings = cachedSettings ?: loadSettingsFromLocal(context)
                saveSettingsToCloud(context, currentSettings.copy(pin = newPin))
            } catch (e: Exception) {
                Log.w(TAG, "Could not save PIN to cloud, saved locally: ${e.message}")
            }
        }
    }

    /**
     * Synchronously reads email from cache, cloud, or local storage (in that order)
     */
    fun readEmail(context: Context): String? {
        // Try cache first
        cachedSettings?.parentEmail?.let { return it }

        // Try to load from cloud synchronously (with timeout)
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val cloudSuccess = AtomicReference<Boolean>(false)
        
        settingsScope.launch {
            try {
                val cloudResult = loadSettingsFromCloud(context)
                if (cloudResult != null) {
                    val settings = cloudResult["data"] as? SettingsData
                    if (settings != null) {
                        cachedSettings = settings
                        saveSettingsToLocal(context, settings)
                        result.set(settings.parentEmail)
                        cloudSuccess.set(true)
                    } else {
                        cloudSuccess.set(false)
                    }
                } else {
                    cloudSuccess.set(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading email from cloud, will try local storage: ${e.message}")
                cloudSuccess.set(false)
            } finally {
                latch.countDown()
            }
        }
        
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while reading email", e)
        }
        
        // If cloud load succeeded, return the result
        if (cloudSuccess.get() == true) {
            return result.get()
        }
        
        // Fallback to local storage
        Log.d(TAG, "Falling back to local storage for email")
        val localSettings = loadSettingsFromLocal(context)
        if (localSettings.parentEmail != null) {
            cachedSettings = localSettings
            return localSettings.parentEmail
        }
        
        return null
    }

    /**
     * Synchronously writes email to cache and local storage, then attempts cloud save
     */
    fun writeEmail(context: Context, newEmail: String) {
        // Update cache immediately
        val updatedSettings = cachedSettings?.copy(parentEmail = newEmail) ?: SettingsData(parentEmail = newEmail)
        cachedSettings = updatedSettings
        
        // Store timestamp when email is changed
        val timestamp = generateESTTimestamp()
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("settings_timestamp", timestamp).apply()
        Log.d(TAG, "Email written, timestamp: $timestamp")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        val profile = readProfile(context) ?: "AM"
        updateLastUpdatedTimestamp(context, profile)
        
        // Save to local storage immediately for offline persistence
        saveSettingsToLocal(context, updatedSettings)
        
        // Attempt to save to cloud asynchronously
        settingsScope.launch {
            try {
                val currentSettings = cachedSettings ?: loadSettingsFromLocal(context)
                saveSettingsToCloud(context, currentSettings.copy(parentEmail = newEmail))
            } catch (e: Exception) {
                Log.w(TAG, "Could not save email to cloud, saved locally: ${e.message}")
            }
        }
    }

    /**
     * Gets available profiles based on config files in assets
     * Returns a list of profile IDs (e.g., ["A", "B"]) for profiles that have config files
     */
    fun getAvailableProfiles(context: Context): List<String> {
        val availableProfiles = mutableListOf<String>()

        try {
            // Check for config files in assets/config/ directory
            val assetManager = context.assets
            val configFiles = assetManager.list("config") ?: emptyArray()

            // Look for files matching the pattern: {ProfileId}_config.json
            configFiles.forEach { filename ->
                if (filename.endsWith("_config.json")) {
                    // Extract profile ID from filename (e.g., "AM_config.json" -> "AM")
                    val profileId = filename.removeSuffix("_config.json")
                    if (profileId.isNotEmpty()) {
                        availableProfiles.add(profileId)
                    }
                }
            }

            // Sort profiles for consistent ordering
            availableProfiles.sort()

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting available profiles", e)
            // Fallback to basic profiles if detection fails
            return listOf("AM", "BM")
        }

        return availableProfiles
    }

    /**
     * Gets display names for profiles
     * Returns a map of profile ID to display name
     */
    fun getProfileDisplayNames(): Map<String, String> {
        return mapOf(
            "AM" to "AM Profile",
            "BM" to "BM Profile"
        )
    }

    /**
     * Preloads settings from cloud or local storage (call this on app startup)
     */
    fun preloadSettings(context: Context) {
        settingsScope.launch {
            try {
                val cloudResult = loadSettingsFromCloud(context)
                if (cloudResult != null) {
                    val cloudSettings = cloudResult["data"] as? SettingsData
                    if (cloudSettings != null) {
                        cachedSettings = cloudSettings
                        Log.d(TAG, "Preloaded settings from cloud")
                    }
                }
                // Fallback to local storage if cloud didn't have data
                if (cachedSettings == null) {
                    val localSettings = loadSettingsFromLocal(context)
                    if (localSettings.pin != null || localSettings.parentEmail != null) {
                        cachedSettings = localSettings
                        Log.d(TAG, "Preloaded settings from local storage (cloud unavailable)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error preloading settings, trying local storage: ${e.message}")
                // Fallback to local storage
                val localSettings = loadSettingsFromLocal(context)
                if (localSettings.pin != null || localSettings.parentEmail != null) {
                    cachedSettings = localSettings
                    Log.d(TAG, "Preloaded settings from local storage (error: ${e.message})")
                }
            }
        }
    }

    /**
     * Data class to hold profile and timestamp information
     */
    private data class ProfileWithTimestamp(
        val profile: String,
        val lastUpdated: String? // ISO timestamp string from database
    )
    
    /**
     * Gets the active profile and last_updated timestamp from cloud devices table for this device
     * Returns ProfileWithTimestamp or null if not found/error
     */
    private suspend fun getActiveProfileFromCloud(context: Context): ProfileWithTimestamp? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping active profile fetch")
            return@withContext null
        }
        
        try {
            val deviceId = getDeviceId(context)
            val url = "${getSupabaseUrl(context)}/rest/v1/devices?device_id=eq.$deviceId&select=active_profile,last_updated"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey(context))
                .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                response.close()
                
                if (responseBody != "[]" && responseBody != "{}") {
                    val devicesArray = gson.fromJson(responseBody, object : TypeToken<Array<Map<String, Any>>>() {}.type) as? Array<Map<String, Any>>
                    val device = devicesArray?.firstOrNull()
                    val activeProfile = device?.get("active_profile") as? String
                    val lastUpdated = device?.get("last_updated") as? String
                    if (activeProfile != null) {
                        Log.d(TAG, "Got active profile from cloud: $activeProfile, last_updated: $lastUpdated")
                        return@withContext ProfileWithTimestamp(activeProfile, lastUpdated)
                    }
                }
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to get active profile: ${response.code} - $errorBody")
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active profile from cloud", e)
        }
        null
    }

    /**
     * Checks for profile changes from cloud devices table and applies them locally if different
     * This allows BaerenLock and BaerenEd to sync the active profile between apps
     * Should be called on app startup/resume, BEFORE other operations that depend on profile
     * This version is asynchronous to avoid blocking the main thread (e.g., in onResume)
     * Returns immediately - profile changes will be applied in background if detected
     */
    fun checkAndApplyProfileFromCloud(context: Context): Boolean {
        // Run asynchronously to avoid blocking the main thread
        // Return false immediately - profile changes will be applied in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(5000) { // 5 second timeout
                    val cloudProfileData = getActiveProfileFromCloud(context)
                    if (cloudProfileData != null) {
                        val currentProfile = readProfile(context)
                        val localTimestamp = getLocalProfileTimestamp(context)
                        val cloudTimestamp = cloudProfileData.lastUpdated
                        
                        Log.d(TAG, "Profile check: local=$currentProfile (timestamp=$localTimestamp), cloud=${cloudProfileData.profile} (timestamp=$cloudTimestamp)")
                        
                        // Compare timestamps to determine which is newer
                        val shouldApplyCloud = if (cloudTimestamp != null && localTimestamp != null) {
                            // Both timestamps exist - compare them
                            try {
                                val localTime = parseTimestampForComparison(localTimestamp)
                                val cloudTime = parseTimestampForComparison(cloudTimestamp)
                                val cloudIsNewer = cloudTime > localTime
                                Log.d(TAG, "checkAndApplyProfileFromCloud timestamp comparison:")
                                Log.d(TAG, "  Local: $localTimestamp (parsed: $localTime)")
                                Log.d(TAG, "  Cloud: $cloudTimestamp (parsed: $cloudTime)")
                                Log.d(TAG, "  Cloud is newer: $cloudIsNewer")
                                cloudIsNewer
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing timestamps for comparison", e)
                                // On error, default to applying cloud (safer)
                                true
                            }
                        } else if (cloudTimestamp != null) {
                            // Only cloud has timestamp - apply cloud
                            Log.d(TAG, "No local timestamp, applying cloud profile")
                            true
                        } else if (localTimestamp != null) {
                            // Only local has timestamp - keep local (don't apply cloud)
                            Log.d(TAG, "No cloud timestamp, keeping local profile")
                            false
                        } else {
                            // Neither has timestamp - apply cloud if profiles differ (cloud is source of truth when no timestamps)
                            val profilesDiffer = cloudProfileData.profile != currentProfile
                            Log.d(TAG, "Neither has timestamp, profiles differ: $profilesDiffer, will ${if (profilesDiffer) "apply cloud" else "keep local"}")
                            profilesDiffer
                        }
                        
                        if (shouldApplyCloud && cloudProfileData.profile != currentProfile) {
                            Log.d(TAG, "Profile changed in cloud: $currentProfile -> ${cloudProfileData.profile}, applying locally")
                            // Write profile WITHOUT syncing to cloud (we already have the cloud value)
                            // CRITICAL: Always use the cloud's timestamp - never generate a new one
                            val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit().apply {
                                putString("profile", cloudProfileData.profile)
                                if (cloudTimestamp != null) {
                                    putString("profile_timestamp", cloudTimestamp)
                                    Log.d(TAG, "Set local profile_timestamp to match cloud: $cloudTimestamp")
                                } else {
                                    // Remove local timestamp if cloud doesn't have one (shouldn't happen, but be safe)
                                    remove("profile_timestamp")
                                    Log.w(TAG, "Cloud has no timestamp, removed local profile_timestamp")
                                }
                                apply()
                            }
                            Log.d(TAG, "Applied cloud profile to local storage: ${cloudProfileData.profile} with cloud timestamp: $cloudTimestamp")
                            // Profile was changed - could notify listeners here if needed
                        } else if (!shouldApplyCloud && cloudProfileData.profile != currentProfile && localTimestamp != null) {
                            // Only sync local to cloud if local has a timestamp (proving it was set locally)
                            // If neither has timestamp, we already applied cloud above, so don't sync local
                            Log.d(TAG, "Local profile is newer (has timestamp), will sync local to cloud: $currentProfile")
                            // Local is newer - sync to cloud
                            currentProfile?.let { profile ->
                                syncActiveProfileToCloudAsync(context, profile)
                            }
                        } else if (!shouldApplyCloud && cloudProfileData.profile != currentProfile && localTimestamp == null) {
                            // Profiles differ but neither has timestamp - this shouldn't happen, but if it does, apply cloud
                            Log.w(TAG, "Profiles differ but neither has timestamp - applying cloud as source of truth: ${cloudProfileData.profile}")
                            val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit().apply {
                                putString("profile", cloudProfileData.profile)
                                apply()
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Timeout checking profile from cloud: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Error checking profile from cloud: ${e.message}")
            }
        }
        // Return false immediately since operation is now async
        return false
    }
}
