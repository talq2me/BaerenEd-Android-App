package com.talq2me.baerened

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the current profile from Supabase into prefs + [DailyProgressManager] session.
 *
 * **Precipitated by:** screen open, pull-to-refresh, after task RPCs (refetch), trainer-map config refresh.
 *
 * **DB:** [UserDataRepository.fetchUserData] runs `af_daily_reset` then `af_get_user_data`.
 * This class does not decide daily reset; Postgres does.
 */
class DbProfileSessionLoader(private val context: Context) {

    private val syncService = SupabaseInterface()
    private val progressManager = DailyProgressManager(context)
    private val userDataRepository = UserDataRepository.getInstance(context)
    private val dataApplier = DbUserDataApplier(context) { profile, timestamp ->
        setStoredLastUpdatedTimestamp(context, profile, timestamp)
    }

    private fun setStoredLastUpdatedTimestamp(ctx: Context, profile: String, timestamp: String) {
        val progressPrefs = ctx.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        val key = "${profile}_last_updated_timestamp"
        progressPrefs.edit().putString(key, timestamp).commit()
    }

    /**
     * Screen load / refresh: `af_daily_reset` (inside fetch) → `af_get_user_data` → apply to prefs + session.
     */
    suspend fun loadAfterDailyResetRpcThenApply(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadAfterDailyResetRpcThenApply(profile=$profile): fetchUserData → apply")
        if (!syncService.isConfigured()) {
            Log.w(TAG, "Supabase not configured")
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        val fetchResult = userDataRepository.fetchUserData(profile)
        if (fetchResult.isFailure) {
            Log.w(TAG, "Fetch failed: ${fetchResult.exceptionOrNull()?.message}")
            return@withContext Result.failure(fetchResult.exceptionOrNull() ?: Exception("Failed to load progress from server"))
        }
        val data = fetchResult.getOrNull()
        if (data == null) {
            dataApplier.applyDbDataToPrefs(DbUserData(profile = profile))
            progressManager.setProgressDataAfterFetch(DbUserData(profile = profile))
            Log.d(TAG, "No user_data row; applied empty session for $profile")
            return@withContext Result.success(Unit)
        }
        dataApplier.applyDbDataToPrefs(data)
        progressManager.setProgressDataAfterFetch(data)
        Log.d(TAG, "Applied user_data for $profile")
        Result.success(Unit)
    }

    /**
     * Trainer map (and similar): Postgres merges GitHub config into `user_data`, then same fetch+apply as above.
     */
    suspend fun runGithubTaskConfigRpcsThenRefetch(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        Log.d(TAG, "runGithubTaskConfigRpcsThenRefetch(profile=$profile): af_update_*_from_config ×5, then fetchUserData")
        listOf(
            syncService::invokeAfUpdateRequiredTasksFromConfig,
            syncService::invokeAfUpdatePracticeTasksFromConfig,
            syncService::invokeAfUpdateBonusTasksFromConfig,
            syncService::invokeAfUpdateChecklistItemsFromConfig,
            syncService::invokeAfUpdateChoresFromGitHub
        ).forEach { invoke ->
            invoke(profile).onFailure { Log.w(TAG, "Config RPC failed (continuing): ${it.message}") }
        }
        loadAfterDailyResetRpcThenApply(profile)
    }

    /**
     * After task/game RPCs already wrote to DB: refetch `user_data` and re-apply (no extra reset RPC beyond fetch).
     */
    suspend fun refetchUserDataAndApply(profile: String): Result<Unit> =
        loadAfterDailyResetRpcThenApply(profile)

    /** Legacy hook; online-only writes use RPC timestamps in DB. */
    fun advanceLocalTimestampForProfile(profile: String) {
        Log.d(TAG, "advanceLocalTimestampForProfile($profile): no-op (DB-authoritative)")
    }

    companion object {
        private const val TAG = "DbProfileSessionLoader"
    }
}
