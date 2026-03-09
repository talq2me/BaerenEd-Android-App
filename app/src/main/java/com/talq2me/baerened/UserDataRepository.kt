package com.talq2me.baerened

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
/**
 * DB is the only source of truth. No in-memory cache.
 * - Fetch: read from DB (caller uses result and applies to prefs / uses for this flow only).
 * - Upload: write to DB. Caller builds payload from prefs (which were updated on last fetch + local changes).
 */
class UserDataRepository(private val context: Context) {

    companion object {
        private const val TAG = "UserDataRepository"
        private const val MAX_RETRIES = 4
        private const val RETRY_DELAY_MS = 2000L

        @Volatile
        private var instance: UserDataRepository? = null

        fun getInstance(context: Context): UserDataRepository {
            return instance ?: synchronized(this) {
                instance ?: UserDataRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val syncService = CloudSyncService()

    /** Fetches from DB. Caller must use the result (e.g. apply to prefs) and must not rely on any cache. */
    suspend fun fetchUserData(profile: String): Result<CloudUserData> = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        // Run Postgres daily reset for this profile so row with last_reset date <> today (EST) is reset before we read
        syncService.invokeAfDailyReset(profile).onFailure {
            Log.w(TAG, "af_daily_reset($profile) failed (continuing with fetch): ${it.message}")
        }
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            val result = syncService.downloadUserData(profile)
            when {
                result.isSuccess -> {
                    val data = result.getOrNull()
                    if (data != null) {
                        Log.d(TAG, "fetchUserData: success for $profile (attempt ${attempt + 1})")
                        return@withContext Result.success(data)
                    }
                    lastError = Exception("Download returned null")
                }
                else -> lastError = result.exceptionOrNull() as? Exception ?: Exception("Unknown error")
            }
            Log.w(TAG, "fetchUserData: attempt ${attempt + 1}/$MAX_RETRIES failed for $profile: ${lastError?.message}")
            if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS)
        }
        Result.failure(lastError ?: Exception("Fetch failed after $MAX_RETRIES attempts"))
    }

    /** Uploads to DB. No in-memory store; caller keeps the data they passed if needed. */
    suspend fun uploadUserData(data: CloudUserData): Result<Unit> = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        // Run Postgres daily reset for this profile before write so DB is in reset state
        syncService.invokeAfDailyReset(data.profile).onFailure {
            Log.w(TAG, "af_daily_reset(${data.profile}) before upload failed (continuing): ${it.message}")
        }
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            val result = syncService.uploadUserData(data)
            when {
                result.isSuccess -> {
                    Log.d(TAG, "uploadUserData: success for ${data.profile} (attempt ${attempt + 1})")
                    return@withContext Result.success(Unit)
                }
                else -> lastError = result.exceptionOrNull() as? Exception ?: Exception("Unknown error")
            }
            Log.w(TAG, "uploadUserData: attempt ${attempt + 1}/$MAX_RETRIES failed for ${data.profile}: ${lastError?.message}")
            if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS)
        }
        Result.failure(lastError ?: Exception("Upload failed after $MAX_RETRIES attempts"))
    }
}
