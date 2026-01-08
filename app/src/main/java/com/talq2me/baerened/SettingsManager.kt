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
    private suspend fun loadSettingsFromCloud(context: Context): SettingsData? = withContext(Dispatchers.IO) {
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
                        
                        val data = SettingsData(pin, parentEmail)
                        cachedSettings = data
                        Log.d(TAG, "Loaded settings from cloud: pin=${pin?.take(1)}..., email=$parentEmail")
                        return@withContext data
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
    private suspend fun saveSettingsToCloud(context: Context, data: SettingsData): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skipping save")
            return@withContext false
        }

        try {
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
     * Synchronously writes profile to local storage only
     * Note: Profile is NOT stored in cloud settings table, only in local storage and user_data table
     */
    fun writeProfile(context: Context, newProfile: String) {
        // Profile is stored in local SharedPreferences only
        val prefs = context.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("profile", newProfile).apply()
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
                val settings = loadSettingsFromCloud(context)
                if (settings != null) {
                    cachedSettings = settings
                    saveSettingsToLocal(context, settings)
                    result.set(settings.pin)
                    cloudSuccess.set(true)
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
     */
    fun writePin(context: Context, newPin: String) {
        // Update cache immediately
        val updatedSettings = cachedSettings?.copy(pin = newPin) ?: SettingsData(pin = newPin)
        cachedSettings = updatedSettings
        
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
                val settings = loadSettingsFromCloud(context)
                if (settings != null) {
                    cachedSettings = settings
                    saveSettingsToLocal(context, settings)
                    result.set(settings.parentEmail)
                    cloudSuccess.set(true)
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
                val cloudSettings = loadSettingsFromCloud(context)
                if (cloudSettings != null) {
                    Log.d(TAG, "Preloaded settings from cloud")
                } else {
                    // Fallback to local storage
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
}
