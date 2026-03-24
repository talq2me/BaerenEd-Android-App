package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages daily reset and progress load according to Daily Reset Logic.md.
 * DB-only: all progress is read from and written to the DB. Prefs hold the last applied DB data for display; we upload from prefs to DB on change.
 * - Entry point: dailyResetProcessAndSync(profile) — fetch from DB; if last_reset (date, EST) is not today,
 *   run daily reset in DB, restore chores from GitHub, then load from DB. Writes go straight to DB (e.g. uploadToDb).
 */
class DailyResetAndSyncManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DailyResetAndSyncManager"
        private const val PREF_NAME = "daily_progress_prefs"
        private const val KEY_PROFILE_LAST_RESET = "profile_last_reset" // Format: yyyy-MM-dd HH:mm:ss.SSS (EST)
        private const val KEY_LAST_UPDATED = "last_updated_timestamp" // Format: yyyy-MM-dd HH:mm:ss.SSS (EST)
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cloudStorageManager = CloudStorageManager(context)
    private val progressManager = DailyProgressManager(context)
    private val syncService = CloudSyncService()
    private val dataCollector = ProgressDataCollector(context)
    private val dataApplier = CloudDataApplier(context) { profile, timestamp ->
        setStoredLastUpdatedTimestamp(profile, timestamp)
    }
    private val userDataRepository = UserDataRepository.getInstance(context)
    
    /**
     * ONLINE-ONLY: Fetches from DB, runs daily reset in DB if last_reset is not today (date part, EST),
     * restores chores from GitHub after reset, then applies DB data to prefs.
     *
     * @param profile The profile to process (e.g., "AM" or "BM")
     * @return Result.success(Unit) when fetch (and optional reset) succeeded; Result.failure when fetch from DB failed
     */
    suspend fun dailyResetProcessAndSync(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "ONLINE-ONLY: dailyResetProcessAndSync for profile: $profile")
        if (!syncService.isConfigured()) {
            Log.w(TAG, "Supabase not configured")
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        val fetchResult = userDataRepository.fetchUserData(profile)
        if (fetchResult.isFailure) {
            Log.w(TAG, "Fetch from DB failed: ${fetchResult.exceptionOrNull()?.message}")
            return@withContext Result.failure(fetchResult.exceptionOrNull() ?: Exception("Failed to load progress from server"))
        }
        var data = fetchResult.getOrNull()
        if (data == null) {
            dataApplier.applyDbDataToPrefs(CloudUserData(profile = profile))
            progressManager.setProgressDataAfterFetch(CloudUserData(profile = profile))
            Log.d(TAG, "No DB row for profile $profile; applied empty progress to session")
            return@withContext Result.success(Unit)
        }
        // af_daily_reset() was invoked before fetch; if chores were blanked, restore from GitHub
        if (data.chores.isNullOrEmpty()) {
            restoreChoresFromGitHubAndPatch(profile)
            val refetch = userDataRepository.fetchUserData(profile)
            data = refetch.getOrNull() ?: data
        }
        dataApplier.applyDbDataToPrefs(data)
        progressManager.setProgressDataAfterFetch(data)
        Log.d(TAG, "Applied DB data for profile: $profile (session data set; required_tasks=${data.requiredTasks.size}, checklist_items=${data.checklistItems.size})")
        Result.success(Unit)
    }

    /** Parses chores.json from GitHub into List<ChoreProgress> with done=false. Returns null if fetch/parse fails. */
    private suspend fun restoreChoresFromGitHubAndPatch(profile: String) {
        val contentUpdateService = ContentUpdateService()
        val json = contentUpdateService.getCachedChores(context) ?: return
        val list = try {
            val type = object : TypeToken<List<ChoreJsonItem>>() {}.type
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(json, type) as? List<ChoreJsonItem>) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chores.json", e)
            return
        }
        val chores = list.map { ChoreProgress(choreId = it.id, description = it.description, coinsReward = it.coins, done = false) }
        if (chores.isEmpty()) return
        val now = syncService.generateESTTimestamp()
        val payload = mapOf(
            "chores" to chores,
            "last_updated" to now
        )
        val result = syncService.patchUserDataColumns(profile, payload)
        if (result.isSuccess) {
            Log.d(TAG, "Restored ${chores.size} chores from GitHub to DB for profile: $profile")
        } else {
            Log.e(TAG, "Failed to patch chores to DB: ${result.exceptionOrNull()?.message}")
        }
    }

    private data class ChoreJsonItem(val id: Int, val description: String, val coins: Int)

    /**
     * Invokes the Postgres RPCs (required_tasks, practice_tasks, bonus_tasks, checklist_items, chores from config/GitHub),
     * then refetches user_data and applies to prefs/session. Call when Trainer Map loads so DB has latest config merged with progress.
     * Each RPC is best-effort: failures are logged and we continue.
     */
    suspend fun invokeConfigFromGitHubRpcsThenRefetch(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        listOf(
            syncService::invokeAfRequiredTasksFromConfig,
            syncService::invokeAfPracticeTasksFromConfig,
            syncService::invokeAfBonusTasksFromConfig,
            syncService::invokeAfChecklistItemsFromConfig,
            syncService::invokeAfChoresFromGitHub
        ).forEach { invoke ->
            invoke(profile).onFailure { Log.w(TAG, "Config RPC failed (continuing): ${it.message}") }
        }
        val fetchResult = userDataRepository.fetchUserData(profile)
        if (fetchResult.isFailure) {
            Log.w(TAG, "Refetch after config RPCs failed: ${fetchResult.exceptionOrNull()?.message}")
            return@withContext Result.failure(fetchResult.exceptionOrNull() ?: Exception("Refetch failed"))
        }
        val data = fetchResult.getOrNull() ?: run {
            dataApplier.applyDbDataToPrefs(CloudUserData(profile = profile))
            progressManager.setProgressDataAfterFetch(CloudUserData(profile = profile))
            return@withContext Result.success(Unit)
        }
        dataApplier.applyDbDataToPrefs(data)
        progressManager.setProgressDataAfterFetch(data)
        Log.d(TAG, "invokeConfigFromGitHubRpcsThenRefetch: applied refetched data for profile $profile")
        Result.success(Unit)
    }

    /**
     * ONLINE-ONLY: Merges task/checklist structures from GitHub config with current DB data (preserves
     * completion/correct/incorrect/times_completed), uploads to DB, and updates in-memory cache.
     * Prefer invokeConfigFromGitHubRpcsThenRefetch when loading Trainer Map (uses DB RPCs).
     */
    suspend fun mergeTaskStructuresFromGitHubAndPatchDb(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        val contentUpdateService = ContentUpdateService()
        val jsonString = contentUpdateService.getCachedMainContent(context)
            ?: run {
                Log.w(TAG, "mergeTaskStructuresFromGitHubAndPatchDb: no config from GitHub")
                return@withContext Result.failure(Exception("Could not load config from GitHub"))
            }
        val config = try {
            Gson().fromJson(jsonString, MainContent::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "mergeTaskStructuresFromGitHubAndPatchDb: parse error", e)
            return@withContext Result.failure(e)
        }
        // Use fresh fetch from DB for current (berries, banked_mins, etc.). This manager's progressManager
        // may be a different instance than the one that ran dailyResetProcessAndSync, so getProgressDataForUpload
        // can be null/empty and would otherwise upload 0 for berries_earned/banked_mins and wipe them.
        val current = userDataRepository.fetchUserData(profile).getOrNull()
        val existingRequired = current?.requiredTasks ?: emptyMap()
        val existingPractice = current?.practiceTasks ?: emptyMap()
        val existingBonus = current?.bonusTasks ?: emptyMap()
        val existingChecklist = current?.checklistItems ?: emptyMap()

        val mergedRequired = mutableMapOf<String, TaskProgress>()
        val mergedPractice = mutableMapOf<String, PracticeProgress>()
        val mergedBonus = mutableMapOf<String, PracticeProgress>()
        val mergedChecklist = mutableMapOf<String, ChecklistItemProgress>()

        config.sections?.forEach { section ->
            section.tasks?.forEach { task ->
                val taskName = task.title ?: return@forEach
                when (section.id) {
                    "required" -> {
                        val existing = existingRequired[taskName]
                        mergedRequired[taskName] = existing?.copy(
                            stars = task.stars,
                            showdays = task.showdays,
                            hidedays = task.hidedays,
                            displayDays = task.displayDays,
                            disable = task.disable
                        ) ?: TaskProgress(
                            status = "incomplete",
                            correct = null,
                            incorrect = null,
                            questions = null,
                            stars = task.stars,
                            showdays = task.showdays,
                            hidedays = task.hidedays,
                            displayDays = task.displayDays,
                            disable = task.disable
                        )
                    }
                    "optional" -> {
                        val existing = existingPractice[taskName]
                        mergedPractice[taskName] = existing?.copy(
                            showdays = task.showdays,
                            hidedays = task.hidedays,
                            displayDays = task.displayDays,
                            disable = task.disable
                        ) ?: PracticeProgress(
                            timesCompleted = 0,
                            correct = null,
                            incorrect = null,
                            questionsAnswered = null,
                            showdays = task.showdays,
                            hidedays = task.hidedays,
                            displayDays = task.displayDays,
                            disable = task.disable
                        )
                    }
                    "bonus" -> {
                        val existing = existingBonus[taskName]
                        mergedBonus[taskName] = existing?.copy(
                            showdays = task.showdays,
                            hidedays = task.hidedays,
                            displayDays = task.displayDays,
                            disable = task.disable
                        ) ?: PracticeProgress(
                            timesCompleted = 0,
                            correct = null,
                            incorrect = null,
                            questionsAnswered = null,
                            showdays = task.showdays,
                            hidedays = task.hidedays,
                            displayDays = task.displayDays,
                            disable = task.disable
                        )
                    }
                    else -> { /* other sections ignored for merge */ }
                }
            }
            section.items?.forEach { item ->
                val itemName = item.label ?: return@forEach
                val itemStars = item.stars ?: 0
                val existing = existingChecklist[itemName]
                mergedChecklist[itemName] = ChecklistItemProgress(
                    done = existing?.done ?: false,
                    stars = itemStars,
                    displayDays = item.displayDays
                )
            }
        }

        val optionalTaskNames = config.sections?.find { it.id == "optional" }?.tasks?.mapNotNull { it.title }?.toSet() ?: emptySet()
        val bonusTaskNames = config.sections?.find { it.id == "bonus" }?.tasks?.mapNotNull { it.title }?.toSet() ?: emptySet()
        val requiredTaskNames = config.sections?.find { it.id == "required" }?.tasks?.mapNotNull { it.title }?.toSet() ?: emptySet()
        mergedRequired.keys.removeAll { it !in requiredTaskNames }
        mergedPractice.keys.removeAll { it !in optionalTaskNames }
        mergedBonus.keys.removeAll { it !in bonusTaskNames }
        val configChecklistNames = config.sections?.flatMap { section ->
            section.items?.mapNotNull { it.label } ?: emptyList()
        }?.toSet() ?: emptySet()
        mergedChecklist.keys.removeAll { it !in configChecklistNames }

        val requiredStars = mergedRequired.values.sumOf { tp ->
            if (isTaskVisibleToday(tp.showdays, tp.hidedays, tp.displayDays, tp.disable)) tp.stars ?: 0 else 0
        }
        val checklistStars = mergedChecklist.values.sumOf { p ->
            if (isTaskVisibleToday(null, null, p.displayDays, null)) p.stars else 0
        }
        val possibleStars = requiredStars + checklistStars

        val now = syncService.generateESTTimestamp()
        val merged = CloudUserData(
            profile = profile,
            lastReset = current?.lastReset,
            requiredTasks = mergedRequired,
            practiceTasks = mergedPractice,
            bonusTasks = mergedBonus,
            checklistItems = mergedChecklist,
            possibleStars = possibleStars,
            bankedMins = current?.bankedMins ?: 0,
            berriesEarned = current?.berriesEarned ?: 0,
            coinsEarned = current?.coinsEarned ?: 0,
            chores = current?.chores ?: emptyList(),
            pokemonUnlocked = current?.pokemonUnlocked ?: 0,
            gameIndices = current?.gameIndices ?: emptyMap(),
            lastUpdated = now
        )
        // PATCH only task-related columns so we never overwrite berries_earned or banked_mins (they stay in DB).
        val patchPayload = mapOf<String, Any>(
            "required_tasks" to mergedRequired,
            "checklist_items" to mergedChecklist,
            "practice_tasks" to mergedPractice,
            "bonus_tasks" to mergedBonus,
            "last_updated" to now
        )
        val uploadResult = syncService.patchUserDataColumns(profile, patchPayload)
        if (uploadResult.isSuccess) {
            val refetch = userDataRepository.fetchUserData(profile).getOrNull()
            if (refetch != null) progressManager.setProgressDataAfterFetch(refetch)
            Log.d(TAG, "mergeTaskStructuresFromGitHubAndPatchDb: merged and patched task columns for profile $profile; session data refreshed from DB")
        }
        uploadResult
    }
    
    /**
     * UNUSED in ONLINE-ONLY mode. Legacy path (local prefs + sync). The only reset flow is
     * dailyResetProcessAndSync: fetch from DB → if last_reset not today run reset in DB → load from DB.
     */
    @Suppress("UNUSED")
    private suspend fun dailyResetProcess(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "daily_reset_process() started for profile: $profile")
        
        val localLastReset = getStoredLastReset(profile)
        val isLocalToday = isTodayInEST(localLastReset)
        
        // Check if required_tasks is empty - if so, populate it even if last_reset is today
        val progressPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val requiredTasksKey = "${profile}_required_tasks"
        val existingRequiredTasksJson = progressPrefs.getString(requiredTasksKey, null)
        val hasRequiredTasks = existingRequiredTasksJson != null && existingRequiredTasksJson.isNotEmpty() && existingRequiredTasksJson != "{}"
        
        if (!hasRequiredTasks) {
            Log.d(TAG, "required_tasks is empty, calling get_content_from_json() to populate")
            getContentFromJson(profile, updateTimestamp = true) // update timestamp and populate tasks
        }
        
        if (isLocalToday) {
            // CRITICAL: Still refresh required_tasks from GitHub so cloud stays in sync with config we display.
            // Otherwise the map shows new config (from createMapView's fetch) but required_tasks and Supabase stay old.
            Log.d(TAG, "Local last_reset is today, refreshing required_tasks from GitHub so cloud matches display")
            getContentFromJson(profile, updateTimestamp = true)
            return@withContext
        }
        
        // Local is old, need to compare with cloud
        Log.d(TAG, "Local last_reset is old: $localLastReset, checking cloud...")
        
        val cloudLastReset = getCloudLastResetWithRetry(profile)
        
        when {
            cloudLastReset == null -> {
                // Cloud not available after retries (network issue or no cloud record yet).
                // Reset locally and push reset to cloud so cloud stays in sync with reset state.
                // coins_earned and pokemon_unlocked are protected: uploadToDb() uses
                // max(local, cloud) for those two columns so we never overwrite with lower values.
                Log.d(TAG, "Cloud last_reset not available after retries, calling reset_local() then pushResetToCloud()")
                resetLocal(profile)
                pushResetToCloud(profile)
            }
            isTodayInEST(cloudLastReset) -> {
                // Cloud is today, attempt cloud sync
                Log.d(TAG, "Cloud last_reset is today: $cloudLastReset, will attempt cloud_sync()")
                // Note: cloud_sync() will be called after this method returns
            }
            else -> {
                // Cloud is older than today
                Log.d(TAG, "Cloud last_reset is older than today: $cloudLastReset, calling reset_local()")
                resetLocal(profile)
                // CRITICAL: Immediately push reset to cloud to prevent overwrite
                pushResetToCloud(profile)
            }
        }
    }
    
    /**
     * Resets local progress according to requirements:
     * - Set local.profile.last_reset = now() at EST
     * - Reset local data (berries=0, banked_mins=0, required_tasks=null, practice_tasks=null, checklist_items=null)
     * - Set local.profile.last_updated = local.profile.last_reset
     * - Call get_content_from_json()
     */
    private suspend fun resetLocal(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "reset_local() started for profile: $profile")
        
        // Generate EST timestamp in format: yyyy-MM-dd HH:mm:ss.SSS
        val estTimestamp = generateESTTimestampString()
        
        // Set local.profile.last_reset
        val profileLastResetKey = "${profile}_$KEY_PROFILE_LAST_RESET"
        val success = prefs.edit().putString(profileLastResetKey, estTimestamp).commit() // Use commit() for synchronous write
        if (!success) {
            Log.e(TAG, "CRITICAL ERROR: Failed to save profile_last_reset in performDailyReset!")
        }
        
        // Reset local data
        resetLocalProgressData(profile)
        
        // Set local.profile.last_updated = local.profile.last_reset
        setStoredLastUpdatedTimestamp(profile, convertToISOTimestamp(estTimestamp))
        
        // Call get_content_from_json()
        getContentFromJson(profile)
        
        // Clear progress data for this flow so next read comes from fetch/DB
        progressManager.clearProgressDataAfterRequest()
        
        Log.d(TAG, "reset_local() completed for profile: $profile, timestamp: $estTimestamp")
    }
    
    /**
     * Cloud sync according to requirements:
     * - Compare local.profile.last_updated with cloud.profile.last_updated
     * - If equal or cloud not found -> do nothing
     * - If local is newer -> call update_cloud_with_local()
     * - If cloud is newer -> call update_local_with_cloud()
     * 
     * CRITICAL: If local last_reset is today and cloud last_reset is older, always push to cloud
     * to ensure reset values (berries=0, banked_mins=0) are not overwritten.
     */
    private suspend fun cloudSync(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "cloud_sync() started for profile: $profile")
        
        if (!cloudStorageManager.isCloudStorageEnabled() || !syncService.isConfigured()) {
            Log.d(TAG, "Cloud storage disabled or not configured, skipping cloud_sync()")
            return@withContext
        }
        
        // CRITICAL: Check if a reset just happened (local last_reset is today but cloud is older)
        // In this case, we must push to cloud to ensure reset values are saved
        try {
            val localLastReset = getStoredLastReset(profile)
            val cloudLastReset = getCloudLastReset(profile)
            val localResetIsToday = isTodayInEST(localLastReset)
            val cloudResetIsOlder = cloudLastReset != null && !isTodayInEST(cloudLastReset)

            if (localResetIsToday && cloudResetIsOlder) {
                Log.d(TAG, "CRITICAL: Reset just happened (local today, cloud older), pushing reset to cloud immediately")
                uploadToDb(profile)
                return@withContext
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking reset status, proceeding with normal sync", e)
            // Continue with normal sync if check fails
        }

        val localLastUpdated = getStoredLastUpdatedTimestamp(profile)
        val cloudLastUpdated = getCloudLastUpdated(profile)

        if (cloudLastUpdated == null) {
            Log.d(TAG, "Cloud last_updated not found, doing nothing")
            return@withContext
        }

        // CRITICAL: Log timestamp comparison details for debugging
        val localTime = parseISOTimestampAsEST(localLastUpdated)
        val cloudTime = parseISOTimestampAsEST(cloudLastUpdated)
        Log.d(TAG, "cloud_sync() timestamp comparison:")
        Log.d(TAG, "  Local: $localLastUpdated (parsed: $localTime)")
        Log.d(TAG, "  Cloud: $cloudLastUpdated (parsed: $cloudTime)")
        Log.d(TAG, "  Difference: ${localTime - cloudTime}ms (positive = local newer)")

        // Compare timestamps (both in EST)
        val comparison = compareTimestamps(localLastUpdated, cloudLastUpdated)

        when {
            comparison == 0 -> {
                // Timestamps are equal
                Log.d(TAG, "Local and cloud timestamps are equal, doing nothing")
            }
            comparison > 0 -> {
                // Local is newer
                Log.d(TAG, "Local is newer ($localLastUpdated > $cloudLastUpdated), calling update_cloud_with_local()")
                // Use session data for comparison when available (DB-only); else fall back to prefs payload
                val localData = progressManager.getCurrentSessionData(profile) ?: dataCollector.buildUploadPayloadFromPrefs(profile)
                val cloudDataResult = syncService.downloadUserData(profile)
                if (cloudDataResult.isSuccess) {
                    val cloudData = cloudDataResult.getOrNull()
                    if (cloudData != null) {
                        // Check if data actually differs (excluding timestamp).
                        // Include coins_earned and chores so chore/coin-only changes trigger upload.
                        val dataDiffers = localData.berriesEarned != cloudData.berriesEarned ||
                                localData.bankedMins != cloudData.bankedMins ||
                                localData.requiredTasks != cloudData.requiredTasks ||
                                localData.practiceTasks != cloudData.practiceTasks ||
                                localData.checklistItems != cloudData.checklistItems ||
                                localData.coinsEarned != cloudData.coinsEarned ||
                                localData.chores != cloudData.chores

                        if (!dataDiffers) {
                            Log.w(TAG, "CRITICAL: Local timestamp is newer but data matches cloud - this shouldn't happen!")
                            Log.w(TAG, "  This suggests local timestamp was incorrectly updated after cloud sync")
                            Log.w(TAG, "  Skipping update_cloud_with_local() to prevent overwriting cloud data")
                            // Update local timestamp to match cloud to prevent this from happening again
                            setStoredLastUpdatedTimestamp(profile, cloudLastUpdated)
                            Log.d(TAG, "  Updated local timestamp to match cloud: $cloudLastUpdated")
                            return@withContext
                        }
                    }
                }
                uploadToDb(profile)
            }
            else -> {
                // Cloud is newer: pull from DB (timestamps determine winner; no protective overwrite).
                Log.d(TAG, "Cloud is newer ($cloudLastUpdated > $localLastUpdated), calling update_local_with_cloud()")
                fetchFromDbAndApplyToPrefs(profile)
                val storedTimestamp = getStoredLastUpdatedTimestamp(profile)
                if (storedTimestamp != cloudLastUpdated) {
                    Log.e(TAG, "CRITICAL: After update_local_with_cloud(), local timestamp ($storedTimestamp) doesn't match cloud ($cloudLastUpdated)")
                    setStoredLastUpdatedTimestamp(profile, cloudLastUpdated)
                } else {
                    Log.d(TAG, "Verified: Local timestamp correctly matches cloud after update_local_with_cloud()")
                }
            }
        }
    }
    
    /**
     * Immediately pushes reset values to cloud after resetLocal() is called.
     * This prevents cloud sync from overwriting the reset with old cloud data.
     */
    private suspend fun pushResetToCloud(profile: String) = withContext(Dispatchers.IO) {
        if (!cloudStorageManager.isCloudStorageEnabled() || !syncService.isConfigured()) {
            Log.d(TAG, "Cloud storage disabled, skipping pushResetToCloud()")
            return@withContext
        }
        
        try {
            Log.d(TAG, "pushResetToCloud() started for profile: $profile")
            uploadToDb(profile)
            Log.d(TAG, "pushResetToCloud() completed for profile: $profile")
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing reset to cloud, will retry in cloud_sync()", e)
            // Don't throw - allow cloud_sync() to retry later
        }
    }
    
    /**
     * No-op for ONLINE-ONLY. Call when a task/game completes; next upload to DB uses now() at EST in payload.
     * (Previously wrote last_updated to prefs; progress is DB-only now.)
     */
    fun advanceLocalTimestampForProfile(profile: String) {
        Log.d(TAG, "advanceLocalTimestampForProfile($profile) (DB-only, no prefs)")
    }

    /**
     * Uploads current progress to DB with lastUpdated = now() at EST. DB only; no local storage.
     * Call after task/game completion so progress is persisted. Success = DB write succeeded.
     *
     * @return Result.success(Unit) when upload to DB succeeded; Result.failure when upload failed after retries
     */
    suspend fun updateLocalTimestampAndSyncToCloud(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Uploading progress to DB for profile: $profile")
        try {
            uploadToDb(profile)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Upload to DB failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * No-op: all writes go via RPCs (af_update_*, af_daily_reset). Kept for API compatibility.
     * Callers that need fresh session data should refetch (fetchUserData + setProgressDataAfterFetch).
     */
    private suspend fun uploadToDb(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "uploadToDb() no-op for profile: $profile (writes via RPCs)")
    }

    /**
     * Fetches from DB and applies to prefs (all or nothing).
     */
    private suspend fun fetchFromDbAndApplyToPrefs(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetchFromDbAndApplyToPrefs() started for profile: $profile")
        try {
            val result = userDataRepository.fetchUserData(profile)
            if (result.isSuccess) {
                val dbData = result.getOrNull()
                if (dbData != null) {
                    Log.d(TAG, "Applying DB data - berriesEarned: ${dbData.berriesEarned}, bankedMins: ${dbData.bankedMins}, requiredTasks size: ${dbData.requiredTasks.size} for profile: $profile")
                    dataApplier.applyDbDataToPrefs(dbData)
                    progressManager.setProgressDataAfterFetch(dbData)
                    // If DB required_tasks are empty, populate from GitHub then sync.
                    if (dbData.requiredTasks.isEmpty()) {
                        Log.w(TAG, "DB required_tasks empty after apply - running from-config RPCs then refetch")
                        invokeConfigFromGitHubRpcsThenRefetch(profile)
                    }
                    Log.d(TAG, "fetchFromDbAndApplyToPrefs() completed for profile: $profile")
                } else {
                    Log.w(TAG, "fetchFromDbAndApplyToPrefs() - DB data is null")
                }
            } else {
                Log.e(TAG, "fetchFromDbAndApplyToPrefs() failed for profile: $profile: ${result.exceptionOrNull()?.message}")
                throw result.exceptionOrNull() ?: Exception("Download failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in update_local_with_cloud()", e)
            throw e // Re-throw to allow retry later
        }
    }
    
    /**
     * Gets content from JSON according to requirements:
     * - Always check GitHub first (source of truth)
     * - If GitHub JSON found, overwrite local copy
     * - Update local.profile.last_updated to now() EST if GitHub JSON was found
     *   BUT ONLY if this is being called from resetLocal() (not from cloud sync)
     * - Build required_tasks, practice_tasks, checklist_items from JSON
     * - Preserve existing progress when updating
     * 
     * @param updateTimestamp If true, update local.profile.last_updated to now() EST when GitHub JSON is found.
     *                        Should be false when called after update_local_with_cloud() to preserve the cloud timestamp.
     */
    private suspend fun getContentFromJson(profile: String, updateTimestamp: Boolean = true) = withContext(Dispatchers.IO) {
        Log.d(TAG, "get_content_from_json() started for profile: $profile, updateTimestamp: $updateTimestamp")
        
        val contentUpdateService = ContentUpdateService()
        
        // Always check GitHub first (source of truth)
        val githubJson = try {
            val mainContent = contentUpdateService.fetchMainContent(context)
            mainContent?.let { Gson().toJson(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching from GitHub: ${e.message}")
            null
        }
        
        if (githubJson != null) {
            // GitHub JSON found, overwrite local copy
            Log.d(TAG, "GitHub JSON found, overwriting local copy")
            contentUpdateService.saveMainContentToCache(context, githubJson)
            
            // CRITICAL: Only update timestamp if explicitly requested (e.g., from resetLocal())
            // Do NOT update if this is called after update_local_with_cloud() to preserve cloud timestamp
            if (updateTimestamp) {
                val nowISO = generateESTISOTimestamp()
                setStoredLastUpdatedTimestamp(profile, nowISO)
                Log.d(TAG, "Updated local.profile.last_updated to now() EST: $nowISO")
            } else {
                Log.d(TAG, "Skipping timestamp update to preserve cloud timestamp")
            }
        }
        
        // Use local JSON (now updated from GitHub if available)
        val jsonString = contentUpdateService.getCachedMainContent(context)
        if (jsonString == null) {
            Log.w(TAG, "No local JSON available, cannot build task structures")
            return@withContext
        }
        
        val mainContent = try {
            Gson().fromJson(jsonString, MainContent::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON", e)
            return@withContext
        }
        
        // Get current local data
        val progressPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val requiredTasksKey = "${profile}_required_tasks"
        val existingRequiredTasksJson = progressPrefs.getString(requiredTasksKey, null)
        val existingRequiredTasks = if (existingRequiredTasksJson != null) {
            try {
                gson.fromJson<Map<String, TaskProgress>>(
                    existingRequiredTasksJson,
                    object : TypeToken<Map<String, TaskProgress>>() {}.type
                ) ?: emptyMap()
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing existing required_tasks", e)
                emptyMap()
            }
        } else {
            emptyMap()
        }
        
        if (existingRequiredTasks.isEmpty()) {
            // Build new task structures from JSON
            Log.d(TAG, "Building new task structures from JSON")
            buildTaskStructuresFromConfig(mainContent, profile, progressPrefs)
        } else {
            // Preserve existing progress and update
            Log.d(TAG, "Preserving existing progress and updating from JSON")
            updateTaskStructuresFromConfig(mainContent, profile, progressPrefs, existingRequiredTasks)
        }

        // Chores must be DB-backed; local chores.json fallback intentionally disabled.
    }
    
    /**
     * Builds task structures from config when local.required_tasks is null
     */
    private fun buildTaskStructuresFromConfig(
        config: MainContent,
        profile: String,
        progressPrefs: SharedPreferences
    ) {
        val requiredTasks = mutableMapOf<String, TaskProgress>()
        val practiceTasks = mutableMapOf<String, PracticeProgress>()
        val checklistItems = mutableMapOf<String, ChecklistItemProgress>()
        
        config.sections?.forEach { section ->
            section.tasks?.forEach { task ->
                val taskName = task.title ?: return@forEach
                val taskProgress = TaskProgress(
                    status = "incomplete",
                    correct = null,
                    incorrect = null,
                    questions = null,
                    stars = task.stars,
                    showdays = task.showdays,
                    hidedays = task.hidedays,
                    displayDays = task.displayDays,
                    disable = task.disable
                )
                
                if (section.id == "required") {
                    requiredTasks[taskName] = taskProgress
                } else {
                    // Practice tasks
                    practiceTasks[taskName] = PracticeProgress(
                        timesCompleted = 0,
                        correct = null,
                        incorrect = null,
                        questionsAnswered = null,
                        showdays = task.showdays,
                        hidedays = task.hidedays,
                        displayDays = task.displayDays,
                        disable = task.disable
                    )
                }
            }
            
            section.items?.forEach { item ->
                val itemName = item.label ?: return@forEach
                val itemStars = item.stars ?: 0
                checklistItems[itemName] = ChecklistItemProgress(
                    done = false,
                    stars = itemStars,
                    displayDays = item.displayDays
                )
                // checklist_items are a separate DB column; do not add to required_tasks.
            }
        }
        
        // possible_stars: required section tasks + checklist items (each visible today)
        val requiredStars = requiredTasks.values.sumOf { tp ->
            if (isTaskVisibleToday(tp.showdays, tp.hidedays, tp.displayDays, tp.disable)) tp.stars ?: 0 else 0
        }
        val checklistStars = checklistItems.values.sumOf { p ->
            if (isTaskVisibleToday(null, null, p.displayDays, null)) p.stars else 0
        }
        val possibleStars = requiredStars + checklistStars
        
        // Save to SharedPreferences - use commit() to ensure data is written before cloud sync
        val saved = progressPrefs.edit()
            .putString("${profile}_required_tasks", gson.toJson(requiredTasks))
            .putString("${profile}_practice_tasks", gson.toJson(practiceTasks))
            .putString("${profile}_checklist_items", gson.toJson(checklistItems))
            .putInt("${profile}_total_possible_stars", possibleStars)
            .commit()
        
        if (!saved) {
            Log.e(TAG, "CRITICAL: Failed to save task structures to SharedPreferences!")
        } else {
            Log.d(TAG, "Built task structures: required=${requiredTasks.size}, practice=${practiceTasks.size}, checklist=${checklistItems.size}, possible_stars=$possibleStars")
        }
    }
    
    /**
     * Updates task structures from config while preserving existing progress
     */
    private fun updateTaskStructuresFromConfig(
        config: MainContent,
        profile: String,
        progressPrefs: SharedPreferences,
        existingRequiredTasks: Map<String, TaskProgress>
    ) {
        val updatedRequiredTasks = existingRequiredTasks.toMutableMap()
        val practiceTasks = mutableMapOf<String, PracticeProgress>()
        val checklistItems = mutableMapOf<String, ChecklistItemProgress>()
        val existingChecklistJson = progressPrefs.getString("${profile}_checklist_items", "{}") ?: "{}"
        val existingChecklist = gson.fromJson<Map<String, ChecklistItemProgress>>(
            existingChecklistJson,
            object : TypeToken<Map<String, ChecklistItemProgress>>() {}.type
        ) ?: emptyMap()

        config.sections?.forEach { section ->
            section.tasks?.forEach { task ->
                val taskName = task.title ?: return@forEach
                val existing = existingRequiredTasks[taskName]
                
                if (section.id == "required") {
                    // Update star_count, preserve other progress
                    updatedRequiredTasks[taskName] = existing?.copy(
                        stars = task.stars
                    ) ?: TaskProgress(
                        status = "incomplete",
                        correct = null,
                        incorrect = null,
                        questions = null,
                        stars = task.stars,
                        showdays = task.showdays,
                        hidedays = task.hidedays,
                        displayDays = task.displayDays,
                        disable = task.disable
                    )
                } else {
                    // Practice tasks
                    practiceTasks[taskName] = PracticeProgress(
                        timesCompleted = 0,
                        correct = null,
                        incorrect = null,
                        questionsAnswered = null,
                        showdays = task.showdays,
                        hidedays = task.hidedays,
                        displayDays = task.displayDays,
                        disable = task.disable
                    )
                }
            }
            
            section.items?.forEach { item ->
                val itemName = item.label ?: return@forEach
                val itemStars = item.stars ?: 0
                val isDone = existingChecklist[itemName]?.done == true
                checklistItems[itemName] = ChecklistItemProgress(
                    done = isDone,
                    stars = itemStars,
                    displayDays = item.displayDays
                )
            }
        }
        
        // Remove only required/practice task names that no longer exist (checklist_items are separate).
        val configTaskNames = config.sections?.flatMap { section ->
            section.tasks?.mapNotNull { it.title } ?: emptyList()
        }?.toSet() ?: emptySet()
        
        updatedRequiredTasks.keys.removeAll { it !in configTaskNames }
        
        val requiredStars = updatedRequiredTasks.values.sumOf { tp ->
            if (isTaskVisibleToday(tp.showdays, tp.hidedays, tp.displayDays, tp.disable)) tp.stars ?: 0 else 0
        }
        val checklistStars = checklistItems.values.sumOf { p ->
            if (isTaskVisibleToday(null, null, p.displayDays, null)) p.stars else 0
        }
        val possibleStars = requiredStars + checklistStars
        
        // Save to SharedPreferences - use commit() to ensure data is written before cloud sync
        val saved = progressPrefs.edit()
            .putString("${profile}_required_tasks", gson.toJson(updatedRequiredTasks))
            .putString("${profile}_practice_tasks", gson.toJson(practiceTasks))
            .putString("${profile}_checklist_items", gson.toJson(checklistItems))
            .putInt("${profile}_total_possible_stars", possibleStars)
            .commit()
        
        if (!saved) {
            Log.e(TAG, "CRITICAL: Failed to save updated task structures to SharedPreferences!")
        } else {
            Log.d(TAG, "Updated task structures: required=${updatedRequiredTasks.size}, practice=${practiceTasks.size}, checklist=${checklistItems.size}, possible_stars=$possibleStars")
        }
    }
    
    /**
     * Resets local progress data (berries, banked_mins, tasks, etc.). Banked mins: cache only (no prefs).
     */
    private fun resetLocalProgressData(profile: String) {
        val progressPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val berriesKey = "${profile}_earnedBerries"

        // Reset berries
        val success1 = context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
            .edit()
            .putInt(berriesKey, 0)
            .commit() // Use commit() for synchronous write
        if (!success1) {
            Log.e(TAG, "CRITICAL ERROR: Failed to reset berries!")
        }

        // Reset banked_mins in cache only (DB is source of truth; sync will persist)
        progressManager.setBankedRewardMinutesForProfile(profile, 0)
        
        // Reset tasks (set to null by clearing the keys)
        val success3 = progressPrefs.edit()
            .remove("${profile}_required_tasks")
            .remove("${profile}_practice_tasks")
            .remove("${profile}_checklist_items")
            .commit() // Use commit() for synchronous write
        if (!success3) {
            Log.e(TAG, "CRITICAL ERROR: Failed to reset tasks!")
        }

        // Reset chores[].done to false (do NOT reset coins_earned)
        val choresKey = "${profile}_chores"
        val choresJson = progressPrefs.getString(choresKey, "[]") ?: "[]"
        try {
            val choresList: List<ChoreProgress> = gson.fromJson(choresJson, object : TypeToken<List<ChoreProgress>>() {}.type) ?: emptyList()
            if (choresList.isNotEmpty()) {
                val resetChores = choresList.map { it.copy(done = false) }
                progressPrefs.edit().putString(choresKey, gson.toJson(resetChores)).commit()
                Log.d(TAG, "Reset chores done to false for profile: $profile (${choresList.size} chores)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting chores done", e)
        }
        
        // Clear TimeTracker sessions
        try {
            TimeTracker(context).clearAllData()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing TimeTracker sessions", e)
        }
        
        Log.d(TAG, "Reset local progress data for profile: $profile")
    }
    
    /**
     * Checks if a timestamp is today in EST (date part only).
     * Compares: (today in EST, date only yyyy-MM-dd) vs (last_reset interpreted in EST, date only).
     * If same date → do NOT run reset. If different or parse failure → treat as "today" (do NOT reset) to avoid accidental wipe.
     * Only run reset when we are certain the stored date is a different calendar day in EST.
     */
    private fun isTodayInEST(timestamp: String?): Boolean {
        val todayDatePart = getTodayDatePartEST()
        val storedDatePart = getLastResetDatePartEST(timestamp)
        return when {
            storedDatePart == null -> {
                Log.w(TAG, "Could not parse last_reset (treat as today, do not reset): $timestamp")
                true
            }
            storedDatePart != todayDatePart -> {
                Log.d(TAG, "last_reset date not today: stored=$storedDatePart, today=$todayDatePart → will run reset")
                false
            }
            else -> true
        }
    }

    /** Today's date in EST (yyyy-MM-dd). System now() is UTC; we convert to EST for comparison with last_reset. */
    private fun getTodayDatePartEST(): String {
        val estZone = TimeZone.getTimeZone("America/Toronto")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = estZone }
        return fmt.format(Date())
    }

    /** Parses last_reset to a Date then formats that instant in EST as yyyy-MM-dd. Returns null on parse failure. */
    private fun getLastResetDatePartEST(timestamp: String?): String? {
        if (timestamp.isNullOrEmpty()) return null
        val estZone = TimeZone.getTimeZone("America/Toronto")
        val date = parseLastResetToDate(timestamp.trim()) ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = estZone }
        return fmt.format(date)
    }

    /**
     * Parses last_reset string from user_data. The DB stores and returns last_reset in EST;
     * do not treat it as UTC or convert it — parse as EST only.
     */
    private fun parseLastResetToDate(timestamp: String): Date? {
        val trimmed = timestamp.trim()
        val estZone = TimeZone.getTimeZone("America/Toronto")
        return try {
            // user_data.last_reset is stored and returned in EST (no conversion).
            if (!trimmed.contains("T")) {
                // Space format: yyyy-MM-dd HH:mm:ss.SSS or yyyy-MM-dd HH:mm:ss
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = estZone }
                fmt.parse(trimmed)
                    ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = estZone }.parse(trimmed)
            } else {
                // ISO-style with T: 2026-02-18T15:30:00 or 2026-02-18T15:30:00.000 — still EST from DB
                val normalized = trimmed.replace("T", " ").let { s ->
                    when {
                        s.endsWith("Z") -> s.dropLast(1)
                        s.contains("+") -> s.substringBefore("+").trim()
                        s.lastIndexOf("-") > 10 -> s.substring(0, s.lastIndexOf("-")).trim()
                        else -> s
                    }
                }
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = estZone }.parse(normalized)
                    ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = estZone }.parse(normalized)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseLastResetToDate failed: $timestamp", e)
            null
        }
    }
    
    /**
     * Gets local last_reset timestamp for profile
     */
    private fun getStoredLastReset(profile: String): String? {
        val key = "${profile}_$KEY_PROFILE_LAST_RESET"
        return prefs.getString(key, null)
    }
    
    /**
     * Gets cloud last_reset with retry (up to 3 times)
     */
    private suspend fun getCloudLastResetWithRetry(profile: String, maxRetries: Int = 3): String? {
        for (attempt in 1..maxRetries) {
            try {
                val result = getCloudLastReset(profile)
                if (result != null) return result
                if (attempt < maxRetries) {
                    Log.d(TAG, "Cloud last_reset not available, retry $attempt/$maxRetries")
                    kotlinx.coroutines.delay(500) // Small delay before retry
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error getting cloud last_reset (attempt $attempt): ${e.message}")
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(500)
                }
            }
        }
        return null
    }
    
    /**
     * Gets cloud last_reset from Supabase
     */
    private suspend fun getCloudLastReset(profile: String): String? = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            Log.w(TAG, "Supabase not configured, cannot get cloud last_reset")
            return@withContext null
        }
        
        try {
            val baseUrl = syncService.getSupabaseUrl()
            val url = "$baseUrl/rest/v1/user_data?profile=eq.$profile&select=last_reset"
            Log.d(TAG, "Attempting to fetch cloud last_reset from: $baseUrl (profile: $profile)")
            
            val client = syncService.getClient()
            val supabaseKey = syncService.getSupabaseKey()
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                response.close()
                
                val dataList: List<Map<String, String?>> = gson.fromJson(
                    responseBody,
                    object : TypeToken<List<Map<String, String?>>>() {}.type
                )
                
                val cloudLastReset = dataList.firstOrNull()?.get("last_reset")
                if (cloudLastReset != null) {
                    // Convert ISO timestamp to EST format (yyyy-MM-dd HH:mm:ss.SSS)
                    return@withContext convertFromISOTimestamp(cloudLastReset)
                }
            } else {
                val errorBody = response.body?.string() ?: "No error body"
                Log.e(TAG, "Failed to get cloud last_reset: HTTP ${response.code} - $errorBody")
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cloud last_reset: ${e.javaClass.simpleName} - ${e.message}", e)
            // Log more details about network errors
            if (e is java.net.UnknownHostException) {
                Log.e(TAG, "  DNS resolution failed - cannot resolve Supabase hostname")
                Log.w(TAG, "  This is expected when offline or when DNS cannot resolve the Supabase domain.")
                Log.w(TAG, "  The app will continue using local data. Cloud sync will work when connectivity is restored.")
            } else if (e is java.net.SocketTimeoutException) {
                Log.e(TAG, "  Connection timeout - Supabase server not responding")
            } else if (e is java.net.ConnectException) {
                Log.e(TAG, "  Connection refused - cannot connect to Supabase server")
            } else if (e is java.io.IOException) {
                Log.e(TAG, "  Network I/O error: ${e.message}")
            }
        }
        
        null
    }
    
    /**
     * Gets cloud last_updated from Supabase
     */
    private suspend fun getCloudLastUpdated(profile: String): String? = withContext(Dispatchers.IO) {
        if (!syncService.isConfigured()) {
            Log.w(TAG, "Supabase not configured, cannot get cloud last_updated")
            return@withContext null
        }
        
        try {
            val baseUrl = syncService.getSupabaseUrl()
            val url = "$baseUrl/rest/v1/user_data?profile=eq.$profile&select=last_updated"
            Log.d(TAG, "Attempting to fetch cloud last_updated from: $baseUrl (profile: $profile)")
            
            val client = syncService.getClient()
            val supabaseKey = syncService.getSupabaseKey()
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                response.close()
                
                val dataList: List<Map<String, String?>> = gson.fromJson(
                    responseBody,
                    object : TypeToken<List<Map<String, String?>>>() {}.type
                )
                
                return@withContext dataList.firstOrNull()?.get("last_updated")
            } else {
                val errorBody = response.body?.string() ?: "No error body"
                Log.e(TAG, "Failed to get cloud last_updated: HTTP ${response.code} - $errorBody")
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cloud last_updated: ${e.javaClass.simpleName} - ${e.message}", e)
            // Log more details about network errors
            if (e is java.net.UnknownHostException) {
                Log.e(TAG, "  DNS resolution failed - cannot resolve Supabase hostname")
                Log.w(TAG, "  This is expected when offline or when DNS cannot resolve the Supabase domain.")
                Log.w(TAG, "  The app will continue using local data. Cloud sync will work when connectivity is restored.")
            } else if (e is java.net.SocketTimeoutException) {
                Log.e(TAG, "  Connection timeout - Supabase server not responding")
            } else if (e is java.net.ConnectException) {
                Log.e(TAG, "  Connection refused - cannot connect to Supabase server")
            } else if (e is java.io.IOException) {
                Log.e(TAG, "  Network I/O error: ${e.message}")
            }
        }
        
        null
    }
    
    /**
     * Gets local last_updated timestamp
     */
    private fun getStoredLastUpdatedTimestamp(profile: String): String {
        val key = "${profile}_$KEY_LAST_UPDATED"
        val timestamp = prefs.getString(key, null)
        return timestamp ?: "1970-01-01T00:00:00.000-05:00" // Very old timestamp if not found
    }
    
    /**
     * Sets stored last_updated timestamp in prefs (after apply or after upload). Uses commit() for synchronous write.
     */
    private fun setStoredLastUpdatedTimestamp(profile: String, timestamp: String) {
        val key = "${profile}_$KEY_LAST_UPDATED"
        val saved = prefs.edit().putString(key, timestamp).commit()
        if (!saved) {
            Log.e(TAG, "CRITICAL: Failed to save last_updated timestamp for profile: $profile")
        }
    }

    /**
     * Normalizes stored timestamps in prefs to EST DB format (yyyy-MM-dd HH:mm:ss.SSS, no offset).
     * This prevents offset/ISO variants from causing bad comparisons.
     */
    private fun normalizeAllTimestamps() {
        val prefsNames = listOf(
            PREF_NAME,
            "pokemonBattleHub",
            "settings",
            "game_progress",
            "web_game_progress",
            "video_progress",
            "read_along_session",
            "boukili_session",
            "baeren_shared_settings"
        )
        prefsNames.forEach { name ->
            val targetPrefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            normalizeTimestampPrefs(targetPrefs, name)
        }
    }

    private fun normalizeTimestampPrefs(targetPrefs: SharedPreferences, prefsName: String) {
        val all = targetPrefs.all
        if (all.isEmpty()) return

        val estZone = TimeZone.getTimeZone("America/Toronto")
        val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).apply {
            timeZone = estZone
        }
        val editor = targetPrefs.edit()
        var changed = false

        all.forEach { (key, value) ->
            val raw = value as? String ?: return@forEach
            val isTimestampKey = key.contains("timestamp") || key.contains("last_updated") || key.contains("last_reset")
            if (!isTimestampKey) return@forEach

            val needsNormalize = raw.contains('T') || raw.endsWith("Z") || raw.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))
            if (!needsNormalize) return@forEach

            val parsedMillis = parseISOTimestampAsEST(raw)
            if (parsedMillis <= 0L) return@forEach

            val normalized = outputFormat.format(Date(parsedMillis))
            if (normalized != raw) {
                editor.putString(key, normalized)
                changed = true
                Log.d(TAG, "Normalized $prefsName.$key from '$raw' to '$normalized'")
            }
        }

        if (changed) {
            editor.apply()
        }
    }
    
    /**
     * Compares two ISO timestamps (returns positive if first is newer, negative if second is newer, 0 if equal)
     */
    private fun compareTimestamps(timestamp1: String, timestamp2: String): Int {
        val time1 = parseISOTimestampAsEST(timestamp1)
        val time2 = parseISOTimestampAsEST(timestamp2)
        return time1.compareTo(time2)
    }
    
    /**
     * Parses timestamp as EST (milliseconds since epoch).
     * Accepts both DB format (yyyy-MM-dd HH:mm:ss.SSS) and ISO variants.
     */
    private fun parseISOTimestampAsEST(timestamp: String): Long {
        return try {
            // Normalize: strip timezone suffix and convert 'T' to space.
            var baseTimestamp = when {
                timestamp.endsWith("Z") -> timestamp.substringBeforeLast('Z')
                timestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$")) -> {
                    val offsetStart = timestamp.lastIndexOfAny(charArrayOf('+', '-'))
                    if (offsetStart > 10) timestamp.substring(0, offsetStart) else timestamp
                }
                else -> timestamp
            }
            baseTimestamp = baseTimestamp.replace('T', ' ')

            val estZone = TimeZone.getTimeZone("America/Toronto")
            val formats = listOf(
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss.SS",
                "yyyy-MM-dd HH:mm:ss"
            )
            for (pattern in formats) {
                try {
                    val df = SimpleDateFormat(pattern, Locale.getDefault())
                    df.timeZone = estZone
                    val parsed = df.parse(baseTimestamp)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {
                    // try next pattern
                }
            }
            0L
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp: $timestamp", e)
            0L
        }
    }
    
    /**
     * Now() in America/Toronto. Format: yyyy-MM-dd HH:mm:ss.SSS.
     */
    /** System now() is UTC; we convert to EST for the DB. */
    private fun generateESTTimestampString(): String {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        timestampFormat.timeZone = TimeZone.getTimeZone("America/Toronto")
        return timestampFormat.format(Date())
    }
    
    /**
     * Now() at EST in database format (yyyy-MM-dd HH:mm:ss.SSS, no timezone suffix).
     */
    private fun generateESTISOTimestamp(): String {
        return generateESTTimestampString()
    }
    
    /**
     * Converts EST timestamp string to database format (no timezone suffix).
     */
    private fun convertToISOTimestamp(estTimestamp: String): String {
        return estTimestamp
    }
    
    /**
     * Converts ISO timestamp to EST format (yyyy-MM-dd HH:mm:ss.SSS)
     */
    private fun convertFromISOTimestamp(isoTimestamp: String): String {
        return try {
            var baseTimestamp = isoTimestamp
            if (baseTimestamp.endsWith("Z")) {
                baseTimestamp = baseTimestamp.substringBeforeLast('Z')
            } else if (baseTimestamp.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))) {
                val lastPlus = baseTimestamp.lastIndexOf('+')
                val lastMinus = baseTimestamp.lastIndexOf('-')
                val offsetStart = if (lastPlus > lastMinus) lastPlus else lastMinus
                if (offsetStart > 10) {
                    baseTimestamp = baseTimestamp.substring(0, offsetStart)
                }
            }
            
            // Try multiple formats: with milliseconds, without milliseconds, with 2 decimal places
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss.SS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss"
            )
            
            var parsedDate: java.util.Date? = null
            for (formatStr in formats) {
                try {
                    val parseFormat = SimpleDateFormat(formatStr, Locale.getDefault())
                    parseFormat.timeZone = TimeZone.getTimeZone("America/Toronto")
                    parsedDate = parseFormat.parse(baseTimestamp)
                    if (parsedDate != null) break
                } catch (e: Exception) {
                    // Try next format
                }
            }
            
            if (parsedDate != null) {
                val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                outputFormat.timeZone = TimeZone.getTimeZone("America/Toronto")
                outputFormat.format(parsedDate)
            } else {
                Log.e(TAG, "Could not parse timestamp with any format: $isoTimestamp")
                generateESTTimestampString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting from ISO timestamp: $isoTimestamp", e)
            generateESTTimestampString()
        }
    }
    
    /**
     * Checks if a task is visible today based on showdays, hidedays, displayDays, and disable fields.
     * "Today" is in EST. Matches the logic in DailyProgressManager.isTaskVisible().
     */
    private fun isTaskVisibleToday(showdays: String?, hidedays: String?, displayDays: String?, disable: String?): Boolean {
        val estZone = TimeZone.getTimeZone("America/Toronto")
        // Check disable date first - if current date (EST) is before disable date, hide the task
        if (!disable.isNullOrEmpty()) {
            try {
                val disableDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).parse(disable)
                if (disableDate != null) {
                    val today = Calendar.getInstance(estZone).apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val disableCal = Calendar.getInstance(estZone).apply {
                        time = disableDate
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (today.before(disableCal)) return false
                }
            } catch (e: Exception) {
                // Invalid disable date format, ignore
            }
        }

        val today = Calendar.getInstance(estZone).get(Calendar.DAY_OF_WEEK)
        val todayShort = when (today) {
            Calendar.MONDAY -> "mon"
            Calendar.TUESDAY -> "tue"
            Calendar.WEDNESDAY -> "wed"
            Calendar.THURSDAY -> "thu"
            Calendar.FRIDAY -> "fri"
            Calendar.SATURDAY -> "sat"
            Calendar.SUNDAY -> "sun"
            else -> ""
        }

        if (!hidedays.isNullOrEmpty()) {
            if (hidedays.split(",").contains(todayShort)) {
                return false // Hide if today is in hidedays
            }
        }

        // Check displayDays first (if set, only show on those days)
        if (!displayDays.isNullOrEmpty()) {
            return displayDays.split(",").contains(todayShort) // Show only if today is in displayDays
        }

        if (!showdays.isNullOrEmpty()) {
            return showdays.split(",").contains(todayShort) // Show only if today is in showdays
        }

        return true // Visible by default if no restrictions
    }
    
    /**
     * Sets last_reset to now() EST - 1 day in both cloud and local storage.
     * This forces a reset the next time a screen is loaded.
     * Used by the "Reset All Progress" menu item.
     */
    suspend fun setLastResetToYesterday(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "setLastResetToYesterday() started for profile: $profile")
        
        // Calculate yesterday in EST
        val estTimeZone = TimeZone.getTimeZone("America/Toronto")
        val calendar = Calendar.getInstance(estTimeZone)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        
        // Format as yyyy-MM-dd HH:mm:ss.SSS
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        timestampFormat.timeZone = estTimeZone
        val yesterdayTimestamp = timestampFormat.format(calendar.time)
        
        // Set local.profile.last_reset
        val profileLastResetKey = "${profile}_$KEY_PROFILE_LAST_RESET"
        val success = prefs.edit().putString(profileLastResetKey, yesterdayTimestamp).commit() // Use commit() for synchronous write
        if (!success) {
            Log.e(TAG, "CRITICAL ERROR: Failed to save profile_last_reset!")
        }
        
        Log.d(TAG, "Set local last_reset to yesterday: $yesterdayTimestamp")
        
        // Set cloud.profile.last_reset if cloud is enabled
        if (cloudStorageManager.isCloudStorageEnabled() && syncService.isConfigured()) {
            try {
                val isoTimestamp = convertToISOTimestamp(yesterdayTimestamp)
                val url = "${syncService.getSupabaseUrl()}/rest/v1/user_data?profile=eq.$profile"
                val json = gson.toJson(mapOf("last_reset" to isoTimestamp))
                val requestBody = json.toRequestBody("application/json".toMediaType())
                
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .patch(requestBody)
                    .addHeader("apikey", syncService.getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${syncService.getSupabaseKey()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()
                
                val response = syncService.getClient().newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Set cloud last_reset to yesterday: $isoTimestamp")
                } else {
                    Log.e(TAG, "Failed to set cloud last_reset: ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error setting cloud last_reset to yesterday", e)
            }
        }
        
        Log.d(TAG, "setLastResetToYesterday() completed for profile: $profile")
    }
}

