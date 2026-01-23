package com.talq2me.baerened

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Handles conversion and application of cloud data format to local storage format
 * Extracted from CloudStorageManager to improve separation of concerns
 */
class CloudDataApplier(
    private val context: Context,
    private val onTimestampSet: ((profile: String, timestamp: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CloudDataApplier"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
    }

    private val gson = Gson()

    /**
     * Applies cloud data to local storage, converting from cloud format to local format
     * @param data Cloud data to apply
     */
    fun applyCloudDataToLocal(data: CloudUserData) {
        try {
            Log.d(TAG, "Applying cloud data to local storage: profile=${data.profile}, requiredTasks=${data.requiredTasks?.size ?: 0}, berries=${data.berriesEarned}")

            // Convert cloud profile to local format
            val localProfile = data.profile

            // Apply settings
            SettingsManager.writeProfile(context, localProfile)

            // Apply daily progress data
            val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)

            // Apply required tasks data
            // CRITICAL: Use NEW format (required_tasks with TaskProgress), not old format (completed_tasks)
            // This matches what DailyProgressManager.getRequiredTasks() reads from
            val requiredTasksKey = "${localProfile}_required_tasks"
            
            if (data.requiredTasks?.isEmpty() != false) {
                // Cloud has empty required_tasks - this is a reset, clear local tasks
                progressPrefs.edit()
                    .putString(requiredTasksKey, gson.toJson(emptyMap<String, TaskProgress>()))
                    .apply()
                Log.d(TAG, "Cloud reset detected - cleared local required tasks for profile: $localProfile")
            } else {
                // Cloud has tasks - merge with existing local data, but ONLY tasks visible today
                // Get existing local data in NEW format (task names → TaskProgress)
                val existingRequiredTasksJson = progressPrefs.getString(requiredTasksKey, "{}") ?: "{}"
                val existingRequiredTasks = gson.fromJson<Map<String, TaskProgress>>(
                    existingRequiredTasksJson, 
                    object : TypeToken<Map<String, TaskProgress>>() {}.type
                )?.toMutableMap() ?: mutableMapOf()

                // CRITICAL: Only apply tasks that are visible today
                // Get current visible tasks from config to filter cloud data
                val visibleConfigTasks = getConfigTasksForSection("required")
                val visibleTaskNames = visibleConfigTasks.mapNotNull { it.title }.toSet()
                
                var appliedCount = 0
                var skippedCount = 0

                // Merge cloud data with local data, but only for visible tasks
                data.requiredTasks.forEach { (taskName, taskProgress) ->
                    // Only apply if task is visible today
                    if (visibleTaskNames.contains(taskName)) {
                        // Store using task name as key (cloud format)
                        // Preserve visibility fields from config if available
                        val task = visibleConfigTasks.find { it.title == taskName }
                        val mergedTaskProgress = TaskProgress(
                            status = taskProgress.status,
                            correct = taskProgress.correct,
                            incorrect = taskProgress.incorrect,
                            questions = taskProgress.questions,
                            stars = task?.stars ?: taskProgress.stars, // Preserve stars from config or existing data
                            showdays = task?.showdays ?: taskProgress.showdays,
                            hidedays = task?.hidedays ?: taskProgress.hidedays,
                            displayDays = task?.displayDays ?: taskProgress.displayDays,
                            disable = task?.disable ?: taskProgress.disable
                        )
                        existingRequiredTasks[taskName] = mergedTaskProgress
                        appliedCount++
                    } else {
                        Log.d(TAG, "Skipping cloud task '$taskName' - not visible today (filtered by visibility rules)")
                        skippedCount++
                    }
                }

                progressPrefs.edit()
                    .putString(requiredTasksKey, gson.toJson(existingRequiredTasks))
                    .apply()

                Log.d(TAG, "Applied $appliedCount required tasks to local storage (NEW format) for profile: $localProfile (skipped $skippedCount tasks not visible today)")
            }

            // Apply other progress metrics (all profile-specific)
            // SIMPLE: Overwrite local with cloud values (timestamp-based sync)
            val bankedMinsKey = "${localProfile}_banked_reward_minutes"
            val possibleStarsKey = "${localProfile}_total_possible_stars"
            
            // CRITICAL: Log what we're about to write
            val oldBankedMins = progressPrefs.getInt(bankedMinsKey, -999)
            Log.d(TAG, "CRITICAL: About to apply bankedMins from cloud - old value: $oldBankedMins, new value: ${data.bankedMins} for profile: $localProfile")
            
            // CRITICAL: Use commit() instead of apply() to ensure synchronous write
            // This prevents race conditions where data might be read before it's written
            progressPrefs.edit()
                .putInt(possibleStarsKey, data.possibleStars)
                .putInt(bankedMinsKey, data.bankedMins)
                .commit()
            
            // CRITICAL: Verify what was actually written
            val writtenBankedMins = progressPrefs.getInt(bankedMinsKey, -999)
            Log.d(TAG, "CRITICAL: After applying, bankedMins value in SharedPreferences: $writtenBankedMins (expected: ${data.bankedMins}) for profile: $localProfile")
            Log.d(TAG, "Applied progress metrics from cloud - possibleStars: ${data.possibleStars}, bankedMins: ${data.bankedMins} for profile: $localProfile")

            // Apply berries earned (store in pokemonBattleHub preferences where UI expects it, profile-specific)
            // SIMPLE: Overwrite local with cloud value (timestamp-based sync)
            try {
                val berriesKey = "${localProfile}_earnedBerries"
                val prefs = context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                
                // CRITICAL: Log what we're about to write
                val oldBerries = prefs.getInt(berriesKey, -999)
                Log.d(TAG, "CRITICAL: About to apply berries_earned from cloud - old value: $oldBerries, new value: ${data.berriesEarned} for profile: $localProfile")
                
                // CRITICAL: Use commit() instead of apply() to ensure synchronous write
                // This prevents race conditions where data might be read before it's written
                prefs.edit()
                    .putInt(berriesKey, data.berriesEarned)
                    .commit()
                
                // CRITICAL: Verify what was actually written
                val writtenBerries = prefs.getInt(berriesKey, -999)
                Log.d(TAG, "CRITICAL: After applying, berries_earned value in SharedPreferences: $writtenBerries (expected: ${data.berriesEarned}) for profile: $localProfile")
                Log.d(TAG, "Applied berries_earned from cloud: ${data.berriesEarned} for profile: $localProfile")
            } catch (e: Exception) {
                Log.e(TAG, "Error applying berries to local storage", e)
            }

            // Apply Pokemon data
            progressPrefs.edit()
                .putInt("${localProfile}_$KEY_POKEMON_UNLOCKED", data.pokemonUnlocked)
                .apply()

            // Apply game indices (all types: games, web games, videos)
            applyGameIndicesToLocal(data.gameIndices ?: emptyMap(), localProfile)

            // Apply app lists to BaerenLock (if BaerenLock is installed)
            applyAppListsToBaerenLock(data)
            
            // CRITICAL: Store the cloud timestamp as local timestamp after applying cloud data
            // This prevents re-uploading local data immediately after applying cloud reset
            if (!data.lastUpdated.isNullOrEmpty()) {
                onTimestampSet?.invoke(localProfile, data.lastUpdated)
                Log.d(TAG, "Stored cloud timestamp as local timestamp: ${data.lastUpdated}")
            }
            
            // CRITICAL: Store the cloud's last_reset as local last_reset_date
            // This ensures shouldResetProgress() can check against the cloud's last_reset
            if (!data.lastReset.isNullOrEmpty()) {
                try {
                    // Convert from ISO 8601 format to local format (dd-MM-yyyy hh:mm:ss a)
                    val estTimeZone = java.util.TimeZone.getTimeZone("America/New_York")
                    
                    // Strip timezone offset and milliseconds if present
                    // Handle formats like: "2026-01-12T07:40:28", "2026-01-12T07:40:28.123", "2026-01-12T07:40:28-05:00", "2026-01-12T07:40:28Z"
                    var timestampToParse = data.lastReset
                    
                    // Remove timezone offset (at the end: +HH:MM, -HH:MM, or Z)
                    if (timestampToParse.endsWith("Z")) {
                        timestampToParse = timestampToParse.substringBeforeLast('Z')
                    } else if (timestampToParse.matches(Regex(".*[+-]\\d{2}:\\d{2}$"))) {
                        // Find the last occurrence of + or - followed by digits (timezone offset)
                        val lastPlus = timestampToParse.lastIndexOf('+')
                        val lastMinus = timestampToParse.lastIndexOf('-')
                        val offsetStart = if (lastPlus > lastMinus) lastPlus else lastMinus
                        if (offsetStart > 10) { // Must be after the date part (YYYY-MM-DD is 10 chars)
                            timestampToParse = timestampToParse.substring(0, offsetStart)
                        }
                    }
                    
                    // Remove milliseconds if present
                    if (timestampToParse.contains('.')) {
                        timestampToParse = timestampToParse.substringBefore('.')
                    }
                    
                    // Parse the timestamp (format: yyyy-MM-ddTHH:mm:ss)
                    val parseFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    parseFormat.timeZone = estTimeZone
                    val parsedDate = parseFormat.parse(timestampToParse)
                    if (parsedDate != null) {
                        val localFormat = java.text.SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", java.util.Locale.getDefault())
                        localFormat.timeZone = estTimeZone
                        val localDateString = localFormat.format(parsedDate)
                        
                        // CRITICAL: Always overwrite local last_reset_date with cloud's value (cloud is source of truth)
                        progressPrefs.edit()
                            .putString("last_reset_date", localDateString)
                            .apply()
                        Log.d(TAG, "Stored cloud last_reset as local last_reset_date: $localDateString (from cloud: ${data.lastReset})")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting cloud last_reset to local format: ${data.lastReset}", e)
                }
            }

            Log.d(TAG, "Successfully applied cloud data to local storage: requiredTasks=${data.requiredTasks?.size ?: 0}, gameIndices=${data.gameIndices?.size ?: 0}, berries=${data.berriesEarned}")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying cloud data to local", e)
            throw e
        }
    }

    /**
     * Applies game indices to appropriate local SharedPreferences
     */
    private fun applyGameIndicesToLocal(gameIndices: Map<String, Int>, localProfile: String) {
        try {
            // Apply to game_progress prefs
            val gamePrefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)
            val gameEditor = gamePrefs.edit()

            // Apply to web_game_progress prefs
            val webGamePrefs = context.getSharedPreferences("web_game_progress", Context.MODE_PRIVATE)
            val webGameEditor = webGamePrefs.edit()

            // Apply to video_progress prefs
            val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val videoEditor = videoPrefs.edit()

            gameIndices.forEach { (gameId, index) ->
                // For now, we'll try to intelligently distribute based on naming patterns
                // This is a heuristic - you may want to enhance this logic
                when {
                    gameId.contains("diagram") -> {
                        // Web game (diagram labeler)
                        webGameEditor.putInt("${localProfile}_web_progress_$gameId", index)
                    }
                    gameId.contains(".json") || gameId.contains("video") -> {
                        // Video
                        videoEditor.putInt("${localProfile}_${gameId}_index", index)
                    }
                    else -> {
                        // Regular game
                        gameEditor.putInt("${localProfile}_progress_$gameId", index)
                    }
                }
            }

            gameEditor.apply()
            webGameEditor.apply()
            videoEditor.apply()

            Log.d(TAG, "Applied ${gameIndices.size} game indices to local storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying game indices to local storage", e)
        }
    }

    /**
     * Applies app lists from cloud data to BaerenLock SharedPreferences
     */
    private fun applyAppListsToBaerenLock(data: CloudUserData) {
        try {
            val baerenLockContext = context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
            
            // Apply reward apps
            data.rewardApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("reward_apps", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} reward apps to BaerenLock")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying reward apps to BaerenLock", e)
                }
            }
            
            // Apply blacklisted apps
            data.blacklistedApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("packages", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} blacklisted apps to BaerenLock")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying blacklisted apps to BaerenLock", e)
                }
            }
            
            // Apply whitelisted apps
            data.whiteListedApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("allowed", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} whitelisted apps to BaerenLock")
                        // Also refresh RewardManager if accessible
                        try {
                            val rewardManagerClass = Class.forName("com.talq2me.baerenlock.RewardManager")
                            val refreshMethod = rewardManagerClass.getMethod("refreshRewardEligibleApps", Context::class.java)
                            refreshMethod.invoke(null, baerenLockContext)
                        } catch (e: Exception) {
                            // RewardManager not accessible, that's okay
                            Log.d(TAG, "Could not refresh RewardManager: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying whitelisted apps to BaerenLock", e)
                }
            }
        } catch (e: Exception) {
            // BaerenLock not installed or not accessible - that's okay
            Log.d(TAG, "BaerenLock not accessible, skipping app list sync: ${e.message}")
        }
    }

    /**
     * Applies app lists from cloud data to BaerenLock if local doesn't have them
     */
    fun applyAppListsFromCloudIfLocalEmpty(data: CloudUserData) {
        try {
            val baerenLockContext = context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
            
            // Check if local has these lists
            val rewardPrefs = baerenLockContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val blacklistPrefs = baerenLockContext.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
            val whitelistPrefs = baerenLockContext.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            
            val localHasReward = rewardPrefs.getStringSet("reward_apps", null)?.isNotEmpty() == true
            val localHasBlacklist = blacklistPrefs.getStringSet("packages", null)?.isNotEmpty() == true
            val localHasWhitelist = whitelistPrefs.getStringSet("allowed", null)?.isNotEmpty() == true
            
            // Only apply if cloud has data and local doesn't
            if (!localHasReward && !data.rewardApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.rewardApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        rewardPrefs.edit().putStringSet("reward_apps", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} reward apps from cloud (local was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying reward apps from cloud", e)
                }
            }
            
            if (!localHasBlacklist && !data.blacklistedApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.blacklistedApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        blacklistPrefs.edit().putStringSet("packages", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} blacklisted apps from cloud (local was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying blacklisted apps from cloud", e)
                }
            }
            
            if (!localHasWhitelist && !data.whiteListedApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.whiteListedApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        whitelistPrefs.edit().putStringSet("allowed", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} whitelisted apps from cloud (local was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying whitelisted apps from cloud", e)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "BaerenLock not accessible for app list sync: ${e.message}")
        }
    }

    /**
     * Gets all tasks from a specific config section, filtered by visibility for today
     * Only includes tasks that are visible on the current day
     */
    private fun getConfigTasksForSection(sectionId: String): List<Task> {
        try {
            // Load config content
            val contentUpdateService = ContentUpdateService()
            val jsonString = contentUpdateService.getCachedMainContent(context)

            if (jsonString.isNullOrEmpty()) {
                Log.w(TAG, "No cached main content available for config tasks")
                return emptyList()
            }

            val mainContent = gson.fromJson(jsonString, MainContent::class.java)
            val section = mainContent?.sections?.find { it.id == sectionId }

            // Get all tasks from section
            val allTasks = section?.tasks?.filterNotNull() ?: emptyList()
            
            // Filter tasks by visibility - only include tasks visible today
            val visibleTasks = allTasks.filter { task ->
                TaskVisibilityChecker.isTaskVisible(task)
            }

            return visibleTasks
        } catch (e: Exception) {
            Log.e(TAG, "Error loading config tasks for section: $sectionId", e)
            return emptyList()
        }
    }
}
