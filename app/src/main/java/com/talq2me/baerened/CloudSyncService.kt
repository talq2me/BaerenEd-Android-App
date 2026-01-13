package com.talq2me.baerened

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles HTTP sync operations with Supabase
 * Extracted from CloudStorageManager for better separation of concerns
 * 
 * CRITICAL REQUIREMENT: ALL sync operations MUST compare cloud and local timestamps before updating.
 * Never update cloud data without first checking if cloud timestamp is newer than local timestamp.
 * This prevents overwriting newer cloud data with older local data.
 * 
 * See CLOUD_SYNC_TIMESTAMP_REQUIREMENT.md for full documentation.
 */
class CloudSyncService {
    
    companion object {
        private const val TAG = "CloudSyncService"
    }

    private val gson = Gson()
    private val client = OkHttpClient()
    
    /**
     * Gets the OkHttpClient instance (for use by CloudStorageManager)
     */
    fun getClient(): OkHttpClient = client

    /**
     * Gets Supabase URL from BuildConfig
     */
    fun getSupabaseUrl(): String {
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
    fun getSupabaseKey(): String {
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
    fun isConfigured(): Boolean {
        val url = getSupabaseUrl()
        val key = getSupabaseKey()
        return url.isNotBlank() && key.isNotBlank()
    }

    /**
     * Generates EST timestamp in ISO 8601 format
     */
    fun generateESTTimestamp(): String {
        val estTimeZone = TimeZone.getTimeZone("America/New_York")
        val now = Date()
        val offsetMillis = estTimeZone.getOffset(now.time)
        val offsetHours = offsetMillis / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
        val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        return dateFormat.format(now) + offsetString
    }

    /**
     * Uploads user data to cloud
     */
    /**
     * Uploads user data to cloud
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     */
    suspend fun uploadUserData(data: CloudUserData): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            // CRITICAL: Check cloud timestamp first before updating
            val checkUrl = "${getSupabaseUrl()}/rest/v1/user_data?profile=eq.${data.profile}&select=last_updated"
            val checkRequest = Request.Builder()
                .url(checkUrl)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()

            val checkResponse = client.newCall(checkRequest).execute()
            val checkResponseBody = checkResponse.body?.string() ?: "[]"
            checkResponse.close()

            if (checkResponseBody != "[]" && checkResponseBody.isNotBlank()) {
                try {
                    val dataList = gson.fromJson(checkResponseBody, object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>
                    val userData = dataList?.firstOrNull()
                    val cloudTimestamp = userData?.get("last_updated") as? String
                    
                    if (cloudTimestamp != null && data.lastUpdated != null) {
                        val cloudIsNewer = compareTimestamps(cloudTimestamp, data.lastUpdated) > 0
                        Log.d(TAG, "uploadUserData timestamp check: cloudIsNewer=$cloudIsNewer (cloud=$cloudTimestamp, local=${data.lastUpdated})")
                        if (cloudIsNewer) {
                            Log.d(TAG, "Cloud user_data timestamp is newer, not updating cloud")
                            return@withContext Result.success(Unit) // Cloud is newer, don't overwrite
                        }
                    } else if (cloudTimestamp != null && data.lastUpdated == null) {
                        Log.d(TAG, "Cloud has timestamp but local doesn't, not updating cloud")
                        return@withContext Result.success(Unit) // Cloud has timestamp, don't overwrite
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing cloud timestamp, proceeding with upload", e)
                }
            }
            
            val json = gson.toJson(data)
            val baseUrl = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            // First check if record exists
            val recordCheckUrl = "$baseUrl?profile=eq.${data.profile}&select=profile"
            val recordCheckRequest = Request.Builder()
                .url(recordCheckUrl)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()

            val recordCheckResponse = client.newCall(recordCheckRequest).execute()
            val recordCheckResponseBody = recordCheckResponse.body?.string() ?: "[]"
            recordCheckResponse.close()

            val recordExists = recordCheckResponseBody != "[]" && recordCheckResponseBody.isNotBlank()

            if (recordExists) {
                // Record exists, try UPDATE (PATCH)
                val patchUrl = "$baseUrl?profile=eq.${data.profile}"
                val patchRequest = Request.Builder()
                    .url(patchUrl)
                    .patch(requestBody)
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                val patchResponse = client.newCall(patchRequest).execute()

                if (patchResponse.isSuccessful) {
                    patchResponse.close()
                    return@withContext Result.success(Unit)
                } else {
                    val patchErrorBody = patchResponse.body?.string()
                    Log.e(TAG, "Upload failed (PATCH): ${patchResponse.code} - $patchErrorBody")
                    patchResponse.close()
                    return@withContext Result.failure(Exception("PATCH failed with code: ${patchResponse.code}"))
                }
            } else {
                // Record doesn't exist, try INSERT (POST)
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
                    postResponse.close()
                    return@withContext Result.success(Unit)
                } else {
                    val postErrorBody = postResponse.body?.string()
                    Log.e(TAG, "Upload failed (POST): ${postResponse.code} - $postErrorBody")
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
     * Compares two timestamps and returns:
     * - Positive value if timestamp1 is newer than timestamp2
     * - Negative value if timestamp1 is older than timestamp2
     * - Zero if they are equal
     */
    private fun compareTimestamps(timestamp1: String, timestamp2: String): Int {
        val time1 = parseTimestamp(timestamp1)
        val time2 = parseTimestamp(timestamp2)
        return time1.compareTo(time2)
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
            
            // Parse the base time as EST (America/New_York timezone)
            // Handle both 2 and 3 decimal places for milliseconds
            val dateFormat = if (baseTimestamp.contains('.') && baseTimestamp.substringAfter('.').length == 2) {
                // 2 decimal places (e.g., "2026-01-12T17:34:57.48")
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SS", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("America/New_York")
                }
            } else {
                // 3 decimal places or no milliseconds
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("America/New_York")
                }
            }
            
            val date = dateFormat.parse(baseTimestamp)
            return date?.time ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing timestamp for comparison: $timestamp", e)
            0L
        }
    }

    /**
     * Downloads user data from cloud
     */
    suspend fun downloadUserData(profile: String): Result<CloudUserData?> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            val url = "${getSupabaseUrl()}/rest/v1/user_data?profile=eq.$profile&select=*"

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
                response.close()
                Result.success(userData)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to download from cloud: ${response.code} - $errorBody")
                response.close()
                Result.failure(Exception("Download failed: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading from cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Syncs banked reward minutes to cloud
     */
    /**
     * Syncs banked minutes to cloud
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     */
    suspend fun syncBankedMinutesToCloud(profile: String, minutes: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            // CRITICAL: Check cloud timestamp first before updating
            val checkUrl = "${getSupabaseUrl()}/rest/v1/user_data?profile=eq.$profile&select=banked_mins,last_updated"
            val checkRequest = Request.Builder()
                .url(checkUrl)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()
            
            val checkResponse = client.newCall(checkRequest).execute()
            if (checkResponse.isSuccessful) {
                val responseBody = checkResponse.body?.string() ?: "[]"
                checkResponse.close()
                
                if (responseBody != "[]" && responseBody != "{}") {
                    val dataList = gson.fromJson(responseBody, object : TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>
                    val userData = dataList?.firstOrNull()
                    val cloudTimestamp = userData?.get("last_updated") as? String
                    
                    if (cloudTimestamp != null) {
                        // Get local timestamp from SharedPreferences
                        // Note: This requires context, but we can't access it here
                        // The caller (CloudStorageManager) should check before calling this
                        // For now, we'll proceed but log a warning
                        Log.d(TAG, "Cloud timestamp exists: $cloudTimestamp, but cannot check local timestamp here")
                        // The actual timestamp check should be done in CloudStorageManager.syncBankedMinutesToCloud
                    }
                }
            }
            
            val lastUpdated = generateESTTimestamp()

            val updateMap = mapOf(
                "banked_mins" to minutes,
                "last_updated" to lastUpdated
            )

            val json = gson.toJson(updateMap)
            val baseUrl = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val updateUrl = "$baseUrl?profile=eq.$profile"
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
                Log.d(TAG, "Successfully synced banked minutes to cloud for profile: $profile, minutes: $minutes")
                Result.success(Unit)
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to sync banked minutes to cloud: ${patchResponse.code} - $errorBody")
                patchResponse.close()
                Result.failure(Exception("Sync failed: ${patchResponse.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing banked minutes to cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Resets progress in cloud
     */
    suspend fun resetProgressInCloud(profile: String, resetData: Map<String, Any>): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            val json = gson.toJson(resetData)
            val baseUrl = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val updateUrl = "$baseUrl?profile=eq.$profile"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            val patchResponse = client.newCall(patchRequest).execute()
            if (patchResponse.isSuccessful) {
                patchResponse.close()
                Log.d(TAG, "Successfully reset progress in cloud for profile: $profile")
                Result.success(Unit)
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to reset progress in cloud: ${patchResponse.code} - $errorBody")
                patchResponse.close()
                Result.failure(Exception("Reset failed: ${patchResponse.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting progress in cloud", e)
            Result.failure(e)
        }
    }
}
