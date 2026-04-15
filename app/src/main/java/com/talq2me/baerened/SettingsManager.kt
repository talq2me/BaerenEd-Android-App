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
            val sync = SupabaseInterface()
            if (!sync.isConfigured()) return@withContext null
            val row = sync.invokeAfGetSettingsRow().getOrNull() ?: return@withContext null
            val pin = row.get("pin")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
            val parentEmail = row.get("parent_email")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
            val lastUpdated = row.get("last_updated")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
            val data = SettingsData(pin, parentEmail)
            cachedSettings = data
            Log.d(TAG, "Loaded settings from cloud: pin=${pin?.take(1)}..., email=$parentEmail, timestamp=$lastUpdated")
            return@withContext mapOf(
                "data" to data,
                "last_updated" to (lastUpdated ?: "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings from cloud", e)
        }
        null
    }

    /**
     * Saves settings to Supabase (async)
     */
    /**
     * Saves settings to Supabase.
     * Dumb-UI mode: no local-vs-cloud timestamp arbitration.
     */
    private suspend fun saveSettingsToCloud(context: Context, data: SettingsData): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping save")
            return@withContext false
        }

        try {
            val sync = SupabaseInterface()
            val r = sync.invokeAfUpsertSettingsRow(
                parentEmail = data.parentEmail,
                pin = data.pin,
                aggressiveCleanup = null
            )
            if (r.isSuccess) {
                cachedSettings = data
                Log.d(TAG, "Updated settings in cloud via RPC")
                return@withContext true
            }
            Log.e(TAG, "Failed to patch settings: ${r.exceptionOrNull()?.message}")
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
     * Syncs active profile to cloud devices table asynchronously.
     * Dumb-UI mode: no client-side timestamp comparison.
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
                val sync = SupabaseInterface()
                if (!sync.isConfigured()) return@launch
                val r = sync.invokeAfUpsertDevice(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    activeProfile = profile,
                    lastUpdated = null
                )
                if (r.isSuccess) {
                    Log.d(TAG, "Synced active profile to cloud devices via RPC: deviceId=$deviceId, profile=$profile")
                } else {
                    Log.w(TAG, "Failed to sync active profile via RPC: ${r.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync active profile to cloud: ${e.message}")
            }
        }
    }

    /**
     * Synchronously writes profile to local storage and syncs to cloud devices table.
     */
    fun writeProfile(context: Context, newProfile: String) {
        // Profile is stored in local SharedPreferences
        context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("profile", newProfile)
            .apply()
        Log.d(TAG, "Profile written: $newProfile")
        
        // Update last_updated timestamp to trigger cloud sync (as per Daily Reset Logic)
        updateLastUpdatedTimestamp(context, newProfile)
        
        // Sync to cloud devices table asynchronously
        syncActiveProfileToCloudAsync(context, newProfile)
    }
    
    /**
     * Generates EST timestamp string
     */
    private fun generateESTTimestamp(): String {
        val estTimeZone = java.util.TimeZone.getTimeZone("America/Toronto")
        val now = java.util.Date()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        return dateFormat.format(now)
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
            // Exclude Main and Icon configs (internal/legacy, not user-selectable profiles)
            configFiles.forEach { filename ->
                if (filename.endsWith("_config.json")) {
                    // Extract profile ID from filename (e.g., "AM_config.json" -> "AM")
                    val profileId = filename.removeSuffix("_config.json")
                    if (profileId.isNotEmpty() &&
                        !profileId.equals("Main", ignoreCase = true) &&
                        !profileId.equals("Icon", ignoreCase = true)) {
                        availableProfiles.add(profileId)
                    }
                }
            }

            // Sort profiles for consistent ordering
            availableProfiles.sort()

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting available profiles", e)
            // Fallback to basic profiles if detection fails
            return listOf("AM", "BM", "TE")
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
            "BM" to "BM Profile",
            "TE" to "TE Profile"
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
            val sync = SupabaseInterface()
            if (!sync.isConfigured()) return@withContext null
            val row = sync.invokeAfGetDeviceRow(deviceId).getOrNull() ?: return@withContext null
            val activeProfile = row.get("active_profile")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
            val lastUpdated = row.get("last_updated")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
            if (activeProfile != null) {
                Log.d(TAG, "Got active profile from cloud: $activeProfile, last_updated: $lastUpdated")
                return@withContext ProfileWithTimestamp(activeProfile, lastUpdated)
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
                        if (cloudProfileData.profile != currentProfile) {
                            Log.d(TAG, "Profile changed in cloud: $currentProfile -> ${cloudProfileData.profile}, applying locally")
                            context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString("profile", cloudProfileData.profile)
                                .apply()
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
