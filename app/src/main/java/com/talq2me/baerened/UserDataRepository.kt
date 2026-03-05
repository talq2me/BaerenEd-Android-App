package com.talq2me.baerened

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for user progress: reads and writes go through Supabase with retry.
 * In-memory cache holds the last successfully fetched or uploaded data; never use stale data.
 *
 * - Read: fetch from DB with retry; cache only after successful fetch. Callers get data only after fetch succeeds.
 * - Write: upload to DB with retry; on success update cache. No progress is "saved" until DB write succeeds.
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
    private val cache = ConcurrentHashMap<String, CloudUserData>()

    /**
     * Fetches user data from Supabase with retry. On success updates in-memory cache and returns the data.
     * Call this when about to show progress UI (e.g. onResume) so UI never shows stale data.
     */
    suspend fun fetchUserData(profile: String): Result<CloudUserData> = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            val result = syncService.downloadUserData(profile)
            when {
                result.isSuccess -> {
                    val data = result.getOrNull()
                    if (data != null) {
                        cache[profile] = data
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

    /**
     * Uploads user data to Supabase with retry. On success updates in-memory cache.
     * Call after any progress change (game complete, task complete, berries, banked_mins, etc.).
     */
    suspend fun uploadUserData(data: CloudUserData): Result<Unit> = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            val result = syncService.uploadUserData(data)
            when {
                result.isSuccess -> {
                    cache[data.profile] = data
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

    /** Returns cached data only (from last successful fetch or upload). May be null if no fetch/upload has succeeded yet. */
    fun getCached(profile: String): CloudUserData? = cache[profile]

    /** Clears cache for profile (e.g. after daily reset so next read refetches). */
    fun invalidateCache(profile: String) {
        cache.remove(profile)
        Log.d(TAG, "invalidateCache: $profile")
    }

    /** Sets cache from external source (e.g. after update_local_with_cloud so UI reads fresh data). */
    fun setCache(profile: String, data: CloudUserData) {
        cache[profile] = data
        Log.d(TAG, "setCache: $profile (from download)")
    }
}
