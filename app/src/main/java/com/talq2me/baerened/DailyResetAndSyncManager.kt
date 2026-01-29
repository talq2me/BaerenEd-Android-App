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
 * Manages daily reset process and cloud sync according to the Daily Reset Logic requirements.
 * This ensures proper synchronization between local and cloud data while preventing data loss.
 * 
 * CRITICAL: Follows exact specification from Daily Reset Logic.md
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
        setLocalLastUpdatedTimestamp(profile, timestamp)
    }
    
    /**
     * Performs daily reset process followed by cloud sync.
     * This is the main entry point that should be called on screen loads.
     * 
     * @param profile The profile to process (e.g., "AM" or "BM")
     */
    suspend fun dailyResetProcessAndSync(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting daily_reset_process() and cloud_sync() for profile: $profile")
        
        // Normalize any legacy local timestamps to EST DB format (no offset).
        normalizeAllTimestamps()
        
        // Step 1: Run daily_reset_process()
        dailyResetProcess(profile)
        
        // Step 2: Run cloud_sync()
        cloudSync(profile)
        
        Log.d(TAG, "Completed daily_reset_process() and cloud_sync() for profile: $profile")
    }
    
    /**
     * Daily reset process according to requirements:
     * - If local.profile.last_reset is today (date part only, EST), do nothing
     * - Otherwise, compare with cloud.profile.last_reset
     *   - If cloud not available after retries -> call reset_local()
     *   - If cloud is today -> attempt cloud_sync()
     *   - If cloud is older than today -> call reset_local()
     */
    private suspend fun dailyResetProcess(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "daily_reset_process() started for profile: $profile")
        
        val localLastReset = getLocalLastReset(profile)
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
            Log.d(TAG, "Local last_reset is today, no reset needed")
            return@withContext
        }
        
        // Local is old, need to compare with cloud
        Log.d(TAG, "Local last_reset is old: $localLastReset, checking cloud...")
        
        val cloudLastReset = getCloudLastResetWithRetry(profile)
        
        when {
            cloudLastReset == null -> {
                // Cloud not available after retries
                Log.d(TAG, "Cloud last_reset not available after retries, calling reset_local()")
                resetLocal(profile)
                // CRITICAL: Immediately push reset to cloud to prevent overwrite
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
        setLocalLastUpdatedTimestamp(profile, convertToISOTimestamp(estTimestamp))
        
        // Call get_content_from_json()
        getContentFromJson(profile)
        
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
            val localLastReset = getLocalLastReset(profile)
            val cloudLastReset = getCloudLastReset(profile)
            val localResetIsToday = isTodayInEST(localLastReset)
            val cloudResetIsOlder = cloudLastReset != null && !isTodayInEST(cloudLastReset)

            if (localResetIsToday && cloudResetIsOlder) {
                Log.d(TAG, "CRITICAL: Reset just happened (local today, cloud older), pushing reset to cloud immediately")
                updateCloudWithLocal(profile)
                return@withContext
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking reset status, proceeding with normal sync", e)
            // Continue with normal sync if check fails
        }

        val localLastUpdated = getLocalLastUpdatedTimestamp(profile)
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
                // CRITICAL: Before pushing local to cloud, verify that local data actually changed
                // If cloud was just applied, local should match cloud, so we shouldn't push
                val localData = dataCollector.collectLocalData(profile)
                val cloudDataResult = syncService.downloadUserData(profile)
                if (cloudDataResult.isSuccess) {
                    val cloudData = cloudDataResult.getOrNull()
                    if (cloudData != null) {
                        // Check if data actually differs (excluding timestamp)
                        val dataDiffers = localData.berriesEarned != cloudData.berriesEarned ||
                                localData.bankedMins != cloudData.bankedMins ||
                                localData.requiredTasks != cloudData.requiredTasks ||
                                localData.practiceTasks != cloudData.practiceTasks

                        if (!dataDiffers) {
                            Log.w(TAG, "CRITICAL: Local timestamp is newer but data matches cloud - this shouldn't happen!")
                            Log.w(TAG, "  This suggests local timestamp was incorrectly updated after cloud sync")
                            Log.w(TAG, "  Skipping update_cloud_with_local() to prevent overwriting cloud data")
                            // Update local timestamp to match cloud to prevent this from happening again
                            setLocalLastUpdatedTimestamp(profile, cloudLastUpdated)
                            Log.d(TAG, "  Updated local timestamp to match cloud: $cloudLastUpdated")
                            return@withContext
                        }
                    }
                }
                updateCloudWithLocal(profile)
            }
            else -> {
                // Cloud is newer
                Log.d(TAG, "Cloud is newer ($cloudLastUpdated > $localLastUpdated), calling update_local_with_cloud()")
                updateLocalWithCloud(profile)
                // CRITICAL: After applying cloud data, verify timestamp was stored correctly
                val storedTimestamp = getLocalLastUpdatedTimestamp(profile)
                if (storedTimestamp != cloudLastUpdated) {
                    Log.e(TAG, "CRITICAL: After update_local_with_cloud(), local timestamp ($storedTimestamp) doesn't match cloud ($cloudLastUpdated)")
                    Log.e(TAG, "  This suggests timestamp was overwritten - fixing by setting to cloud timestamp")
                    setLocalLastUpdatedTimestamp(profile, cloudLastUpdated)
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
            updateCloudWithLocal(profile)
            Log.d(TAG, "pushResetToCloud() completed for profile: $profile")
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing reset to cloud, will retry in cloud_sync()", e)
            // Don't throw - allow cloud_sync() to retry later
        }
    }
    
    /**
     * Advances local last_updated to now() EST (sync commit). Call this when a task/game completes
     * *before* launching any async sync, so that cloudSync (e.g. on BattleHub return) sees local
     * newer than cloud and uploads instead of applying stale cloud over local.
     */
    fun advanceLocalTimestampForProfile(profile: String) {
        val nowISO = generateESTISOTimestamp()
        setLocalLastUpdatedTimestamp(profile, nowISO)
        Log.d(TAG, "CRITICAL: advanceLocalTimestampForProfile($profile) -> $nowISO (sync)")
    }

    /**
     * Updates local.profile.last_updated timestamp to now() EST and then calls update_cloud_with_local().
     * This should be called when tasks are completed or settings are changed.
     */
    suspend fun updateLocalTimestampAndSyncToCloud(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "updateLocalTimestampAndSyncToCloud() started for profile: $profile")
        
        // CRITICAL: Update local.profile.last_updated to now() EST using commit() for synchronous write
        // This ensures the timestamp is written before any other code can read it
        val nowISO = generateESTISOTimestamp()
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        val timestampKey = "${profile}_last_updated_timestamp"
        val timestampSuccess = progressPrefs.edit()
            .putString(timestampKey, nowISO)
            .commit() // Use commit() for synchronous write
        
        if (!timestampSuccess) {
            Log.e(TAG, "CRITICAL ERROR: Failed to save last_updated timestamp in updateLocalTimestampAndSyncToCloud!")
        } else {
            Log.d(TAG, "CRITICAL: Updated last_updated timestamp to: $nowISO (saved synchronously)")
        }
        
        // Then call update_cloud_with_local()
        updateCloudWithLocal(profile)
    }
    
    /**
     * Updates cloud with local data (all or nothing operation).
     * All fields are updated atomically. last_updated should already be set before calling this.
     */
    private suspend fun updateCloudWithLocal(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "update_cloud_with_local() started for profile: $profile")
        
        try {
            // CRITICAL: Log what data we're about to push
            Log.d(TAG, "CRITICAL: About to collect local data for upload to cloud for profile: $profile")
            
            // Collect all local data
            val localData = dataCollector.collectLocalData(profile)
            
            // CRITICAL: Log what data was collected
            Log.d(TAG, "CRITICAL: Collected local data - berriesEarned: ${localData.berriesEarned}, bankedMins: ${localData.bankedMins}, requiredTasks size: ${localData.requiredTasks.size} for profile: $profile")
            
            // CRITICAL: Log Math task status specifically
            val mathTask = localData.requiredTasks["Math"]
            if (mathTask != null) {
                Log.d(TAG, "CRITICAL: Math task in collected data - status: ${mathTask.status}, correct: ${mathTask.correct}, incorrect: ${mathTask.incorrect}, questions: ${mathTask.questions}")
            } else {
                Log.d(TAG, "CRITICAL: Math task NOT FOUND in collected requiredTasks! Available tasks: ${localData.requiredTasks.keys.take(10).joinToString()}")
            }
            
            // Set timestamp to "now" right before upload so we win the timestamp check.
            // Collect can take several seconds (e.g. GitHub fetch); cloud may have been updated in between.
            val nowISO = generateESTISOTimestamp()
            setLocalLastUpdatedTimestamp(profile, nowISO)
            val localDataWithCorrectTimestamp = localData.copy(lastUpdated = nowISO)
            
            Log.d(TAG, "CRITICAL: About to upload with timestamp: $nowISO for profile: $profile")
            
            // Upload to cloud (all or nothing)
            val result = syncService.uploadUserData(localDataWithCorrectTimestamp)
            
            if (result.isSuccess) {
                Log.d(TAG, "update_cloud_with_local() completed successfully for profile: $profile")
            } else {
                Log.e(TAG, "update_cloud_with_local() failed for profile: $profile: ${result.exceptionOrNull()?.message}")
                throw result.exceptionOrNull() ?: Exception("Upload failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in update_cloud_with_local()", e)
            throw e // Re-throw to allow retry later
        }
    }
    
    /**
     * Updates local with cloud data (all or nothing operation).
     * All fields are updated atomically. last_updated should already be set before calling this.
     */
    private suspend fun updateLocalWithCloud(profile: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "update_local_with_cloud() started for profile: $profile")
        
        try {
            // Download cloud data
            val result = syncService.downloadUserData(profile)
            
            if (result.isSuccess) {
                val cloudData = result.getOrNull()
                if (cloudData != null) {
                    // CRITICAL: Log what we're about to apply
                    Log.d(TAG, "CRITICAL: About to apply cloud data - berriesEarned: ${cloudData.berriesEarned}, bankedMins: ${cloudData.bankedMins}, requiredTasks size: ${cloudData.requiredTasks.size} for profile: $profile")
                    
                    // Apply cloud data to local (all or nothing)
                    dataApplier.applyCloudDataToLocal(cloudData)

                    // If local required_tasks are empty, rebuild from GitHub content and update last_updated.
                    // This matches the spec: empty local tasks should be populated from GitHub, then synced.
                    val progressPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val requiredTasksKey = "${profile}_required_tasks"
                    val requiredTasksJson = progressPrefs.getString(requiredTasksKey, "{}") ?: "{}"
                    val requiredTasksMap = gson.fromJson<Map<String, TaskProgress>>(
                        requiredTasksJson,
                        object : TypeToken<Map<String, TaskProgress>>() {}.type
                    ) ?: emptyMap()

                    if (requiredTasksMap.isEmpty()) {
                        Log.w(TAG, "CRITICAL: Local required_tasks empty after applying cloud data - refreshing from GitHub")
                        getContentFromJson(profile, updateTimestamp = true)
                        
                        // CRITICAL: Verify tasks were actually written after getContentFromJson()
                        val progressPrefsAfter = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        val requiredTasksJsonAfter = progressPrefsAfter.getString(requiredTasksKey, "{}") ?: "{}"
                        val requiredTasksMapAfter = gson.fromJson<Map<String, TaskProgress>>(
                            requiredTasksJsonAfter,
                            object : TypeToken<Map<String, TaskProgress>>() {}.type
                        ) ?: emptyMap()
                        
                        if (requiredTasksMapAfter.isEmpty()) {
                            Log.e(TAG, "CRITICAL ERROR: Tasks still empty after getContentFromJson()! This should never happen!")
                        } else {
                            Log.d(TAG, "CRITICAL: Successfully populated ${requiredTasksMapAfter.size} tasks from GitHub after cloud sync")
                            
                            // CRITICAL: After populating from GitHub, local last_updated was updated.
                            // We must now push to cloud since local is now newer (has tasks, cloud has empty).
                            // Check if local is now newer than cloud and push immediately.
                            val localLastUpdatedAfter = getLocalLastUpdatedTimestamp(profile)
                            val cloudLastUpdatedAfter = getCloudLastUpdated(profile)
                            
                            if (cloudLastUpdatedAfter != null) {
                                val comparisonAfter = compareTimestamps(localLastUpdatedAfter, cloudLastUpdatedAfter)
                                if (comparisonAfter > 0) {
                                    Log.d(TAG, "CRITICAL: After populating from GitHub, local is newer ($localLastUpdatedAfter > $cloudLastUpdatedAfter), pushing to cloud immediately")
                                    updateCloudWithLocal(profile)
                                } else {
                                    Log.w(TAG, "CRITICAL: After populating from GitHub, local timestamp ($localLastUpdatedAfter) is not newer than cloud ($cloudLastUpdatedAfter) - this shouldn't happen!")
                                }
                            } else {
                                Log.w(TAG, "CRITICAL: Cloud last_updated is null, cannot push populated tasks to cloud")
                            }
                        }
                    }
                    
                    // CRITICAL: Verify the data was actually written correctly
                    val progressPrefsVerify = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
                    val bankedMinsKey = "${profile}_banked_reward_minutes"
                    val writtenBankedMins = progressPrefsVerify.getInt(bankedMinsKey, -999)
                    
                    val berriesPrefs = context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                    val berriesKey = "${profile}_earnedBerries"
                    val writtenBerries = berriesPrefs.getInt(berriesKey, -999)
                    
                    Log.d(TAG, "CRITICAL: After applyCloudDataToLocal() - written bankedMins: $writtenBankedMins (expected: ${cloudData.bankedMins}), written berries: $writtenBerries (expected: ${cloudData.berriesEarned}) for profile: $profile")
                    
                    if (writtenBankedMins != cloudData.bankedMins || writtenBerries != cloudData.berriesEarned) {
                        Log.e(TAG, "CRITICAL ERROR: Data mismatch after applying cloud data! This should never happen!")
                        Log.e(TAG, "  Expected: bankedMins=${cloudData.bankedMins}, berries=${cloudData.berriesEarned}")
                        Log.e(TAG, "  Actual: bankedMins=$writtenBankedMins, berries=$writtenBerries")
                    }
                    
                    Log.d(TAG, "update_local_with_cloud() completed successfully for profile: $profile")
                } else {
                    Log.w(TAG, "update_local_with_cloud() - cloud data is null")
                }
            } else {
                Log.e(TAG, "update_local_with_cloud() failed for profile: $profile: ${result.exceptionOrNull()?.message}")
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
                setLocalLastUpdatedTimestamp(profile, nowISO)
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
                // CRITICAL: Add checklist items to required_tasks so getRequiredTasks() returns them;
                // progress, coins, and possible_stars then include checklist stars.
                requiredTasks[itemName] = TaskProgress(
                    status = "incomplete",
                    correct = null,
                    incorrect = null,
                    questions = null,
                    stars = itemStars,
                    showdays = null,
                    hidedays = null,
                    displayDays = item.displayDays,
                    disable = null
                )
            }
        }
        
        // Calculate possible_stars: sum of stars from required tasks AND checklist items visible today
        val possibleStars = requiredTasks.values.sumOf { taskProgress ->
            if (isTaskVisibleToday(taskProgress.showdays, taskProgress.hidedays, taskProgress.displayDays, taskProgress.disable)) {
                taskProgress.stars ?: 0
            } else {
                0
            }
        }
        
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
                val existingItem = existingRequiredTasks[itemName]
                val isDone = existingItem?.status == "complete"
                checklistItems[itemName] = ChecklistItemProgress(
                    done = isDone,
                    stars = itemStars,
                    displayDays = item.displayDays
                )
                // CRITICAL: Preserve checklist items in required_tasks so completions and possible_stars are correct
                updatedRequiredTasks[itemName] = existingItem ?: TaskProgress(
                    status = "incomplete",
                    correct = null,
                    incorrect = null,
                    questions = null,
                    stars = itemStars,
                    showdays = null,
                    hidedays = null,
                    displayDays = item.displayDays,
                    disable = null
                )
            }
        }
        
        // Remove tasks that no longer exist in JSON
        val configTaskNames = config.sections?.flatMap { section ->
            (section.tasks?.mapNotNull { it.title } ?: emptyList()) +
            (section.items?.mapNotNull { it.label } ?: emptyList())
        }?.toSet() ?: emptySet()
        
        updatedRequiredTasks.keys.removeAll { it !in configTaskNames }
        
        // Calculate possible_stars: sum of stars from required tasks AND checklist items visible today
        val possibleStars = updatedRequiredTasks.values.sumOf { taskProgress ->
            if (isTaskVisibleToday(taskProgress.showdays, taskProgress.hidedays, taskProgress.displayDays, taskProgress.disable)) {
                taskProgress.stars ?: 0
            } else {
                0
            }
        }
        
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
     * Resets local progress data (berries, banked_mins, tasks, etc.)
     */
    private fun resetLocalProgressData(profile: String) {
        val progressPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val bankedMinsKey = "${profile}_banked_reward_minutes"
        val berriesKey = "${profile}_earnedBerries"
        
        // Reset berries
        val success1 = context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
            .edit()
            .putInt(berriesKey, 0)
            .commit() // Use commit() for synchronous write
        if (!success1) {
            Log.e(TAG, "CRITICAL ERROR: Failed to reset berries!")
        }
        
        // Reset banked_mins
        val success2 = progressPrefs.edit()
            .putInt(bankedMinsKey, 0)
            .commit() // Use commit() for synchronous write
        if (!success2) {
            Log.e(TAG, "CRITICAL ERROR: Failed to reset banked_mins!")
        }
        
        // Reset tasks (set to null by clearing the keys)
        val success3 = progressPrefs.edit()
            .remove("${profile}_required_tasks")
            .remove("${profile}_practice_tasks")
            .remove("${profile}_checklist_items")
            .commit() // Use commit() for synchronous write
        if (!success3) {
            Log.e(TAG, "CRITICAL ERROR: Failed to reset tasks!")
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
     * Checks if a timestamp (in format yyyy-MM-dd HH:mm:ss.SSS) is today in EST
     */
    private fun isTodayInEST(timestamp: String?): Boolean {
        if (timestamp.isNullOrEmpty()) return false
        
        return try {
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            timestampFormat.timeZone = TimeZone.getTimeZone("America/New_York")
            val resetDate = timestampFormat.parse(timestamp)
            
            if (resetDate == null) return false
            
            val today = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            val resetCalendar = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            resetCalendar.time = resetDate
            
            // Compare date part only
            today.get(Calendar.YEAR) == resetCalendar.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == resetCalendar.get(Calendar.DAY_OF_YEAR)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp: $timestamp", e)
            false
        }
    }
    
    /**
     * Gets local last_reset timestamp for profile
     */
    private fun getLocalLastReset(profile: String): String? {
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
    private fun getLocalLastUpdatedTimestamp(profile: String): String {
        val key = "${profile}_$KEY_LAST_UPDATED"
        val timestamp = prefs.getString(key, null)
        return timestamp ?: "1970-01-01T00:00:00.000-05:00" // Very old timestamp if not found
    }
    
    /**
     * Sets local last_updated timestamp
     * Uses commit() to ensure timestamp is written synchronously before cloud sync
     */
    private fun setLocalLastUpdatedTimestamp(profile: String, timestamp: String) {
        val key = "${profile}_$KEY_LAST_UPDATED"
        val saved = prefs.edit().putString(key, timestamp).commit()
        if (!saved) {
            Log.e(TAG, "CRITICAL: Failed to save last_updated timestamp for profile: $profile")
        }
    }

    /**
     * Normalizes local timestamps to EST DB format (yyyy-MM-dd HH:mm:ss.SSS, no offset).
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

        val estZone = TimeZone.getTimeZone("America/New_York")
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

            val estZone = TimeZone.getTimeZone("America/New_York")
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
     * Generates EST timestamp in format: yyyy-MM-dd HH:mm:ss.SSS
     */
    private fun generateESTTimestampString(): String {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        timestampFormat.timeZone = TimeZone.getTimeZone("America/New_York")
        return timestampFormat.format(Date())
    }
    
    /**
     * Generates EST timestamp in database format (no timezone suffix).
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
                    parseFormat.timeZone = TimeZone.getTimeZone("America/New_York")
                    parsedDate = parseFormat.parse(baseTimestamp)
                    if (parsedDate != null) break
                } catch (e: Exception) {
                    // Try next format
                }
            }
            
            if (parsedDate != null) {
                val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                outputFormat.timeZone = TimeZone.getTimeZone("America/New_York")
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
     * This matches the logic in DailyProgressManager.isTaskVisible().
     */
    private fun isTaskVisibleToday(showdays: String?, hidedays: String?, displayDays: String?, disable: String?): Boolean {
        // Check disable date first - if current date is before disable date, hide the task
        if (!disable.isNullOrEmpty()) {
            try {
                val disableDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).parse(disable)
                if (disableDate != null) {
                    val today = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    // If today is before the disable date, task is disabled (not visible)
                    if (today.before(Calendar.getInstance().apply {
                        time = disableDate
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    })) {
                        return false
                    }
                }
            } catch (e: Exception) {
                // Invalid disable date format, ignore
            }
        }

        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
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
        val estTimeZone = TimeZone.getTimeZone("America/New_York")
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

