package com.talq2me.baerened

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
/**
 * DB is the only source of truth. No in-memory cache.
 * Fetch reads from DB (caller applies result); writes use granular RPCs via [DailyProgressManager] / [SupabaseInterface].
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

    private val syncService = SupabaseInterface()

    /** Fetches from DB. Caller must use the result (e.g. apply to prefs) and must not rely on any cache. */
    suspend fun fetchUserData(profile: String): Result<DbUserData> = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        // Run Postgres daily reset for this profile so row with last_reset date <> today (America/Toronto) is reset before we read
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
}
