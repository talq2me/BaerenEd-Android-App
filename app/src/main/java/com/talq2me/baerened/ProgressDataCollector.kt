package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * Collects progress data from local storage for cloud sync
 * Extracted from CloudStorageManager for better separation of concerns
 */
class ProgressDataCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "ProgressDataCollector"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
    }

    private val gson = Gson()

    /**
     * Collects all local data for a profile into CloudUserData
     */
    fun collectLocalData(profile: String): CloudUserData {
        val cloudProfile = profile
        val localProfileId = profile

        // Use progressManager consistently to ensure data consistency with UI
        val progressManager = DailyProgressManager(context)

        // Get daily progress data from SharedPreferences
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)

        // Get last reset date (format it properly for cloud storage in ISO 8601 with EST timezone)
        val lastResetDateString = progressPrefs.getString("last_reset_date", null)
        val lastResetDate = formatLastResetDate(lastResetDateString)

        // Collect required tasks data
        // NEW: Pass profile and read directly from new format
        val requiredTasks = collectRequiredTasksData(localProfileId, progressPrefs)

        // Collect practice tasks data (games/videos/webgames as practice tasks)
        val practiceTasks = collectPracticeTasksData()

        // Collect checklist items data
        // NEW: Pass profile and read directly from new format
        val checklistItems = collectChecklistItemsData(localProfileId, progressPrefs)

        // Get progress metrics (all profile-specific)
        val possibleStarsKey = "${localProfileId}_total_possible_stars"
        val possibleStars = progressPrefs.getInt(possibleStarsKey, 0)
        val bankedMinsKey = "${localProfileId}_banked_reward_minutes"
        val bankedMins = progressPrefs.getInt(bankedMinsKey, 0)
        
        // CRITICAL: Log what we're reading
        Log.d(TAG, "CRITICAL: Reading bankedMins from SharedPreferences: $bankedMins (key: $bankedMinsKey) for profile: $localProfileId")

        // Get berries earned directly from SharedPreferences (profile-specific)
        val berriesKey = "${localProfileId}_earnedBerries"
        val berriesEarned = try {
            val berries = context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
                .getInt(berriesKey, 0)
            // CRITICAL: Log what we're reading
            Log.d(TAG, "CRITICAL: Reading berries_earned from SharedPreferences: $berries (key: $berriesKey) for profile: $localProfileId")
            berries
        } catch (e: Exception) {
            Log.e(TAG, "Error getting earned berries", e)
            0
        }

        // Get Pokemon data
        val pokemonUnlocked = progressPrefs.getInt("${localProfileId}_$KEY_POKEMON_UNLOCKED", 0)

        // Collect all game indices (games, web games, videos)
        val gameIndices = collectAllGameIndices(localProfileId)

        // Collect app lists from BaerenLock settings (if available)
        val rewardApps = collectAppListFromBaerenLock(profile, "reward_apps")
        val blacklistedApps = collectAppListFromBaerenLock(profile, "blacklisted_apps")
        val whiteListedApps = collectAppListFromBaerenLock(profile, "white_listed_apps")

        // Format timestamp in ISO 8601 format with EST timezone for Supabase
        val lastUpdated = generateESTTimestamp()

        // CRITICAL: Log what we're about to return
        Log.d(TAG, "CRITICAL: Creating CloudUserData for profile: $cloudProfile")
        Log.d(TAG, "  requiredTasks size: ${requiredTasks.size}")
        Log.d(TAG, "  practiceTasks size: ${practiceTasks.size}")
        Log.d(TAG, "  checklistItems size: ${checklistItems.size}")
        Log.d(TAG, "  gameIndices size: ${gameIndices.size}")
        Log.d(TAG, "  berriesEarned: $berriesEarned, bankedMins: $bankedMins")
        
        if (requiredTasks.isNotEmpty()) {
            Log.d(TAG, "  Sample required task names: ${requiredTasks.keys.take(5).joinToString()}")
        }
        if (practiceTasks.isNotEmpty()) {
            Log.d(TAG, "  Sample practice task names: ${practiceTasks.keys.take(5).joinToString()}")
        }
        if (gameIndices.isNotEmpty()) {
            Log.d(TAG, "  Sample game indices: ${gameIndices.entries.take(5).joinToString { "${it.key}=${it.value}" }}")
        }

        // Create cloud data
        val cloudData = CloudUserData(
            profile = cloudProfile,
            lastReset = lastResetDate,
            requiredTasks = requiredTasks,
            practiceTasks = practiceTasks,
            checklistItems = checklistItems,
            possibleStars = possibleStars,
            bankedMins = bankedMins,
            berriesEarned = berriesEarned,
            pokemonUnlocked = pokemonUnlocked,
            gameIndices = gameIndices,
            rewardApps = rewardApps,
            blacklistedApps = blacklistedApps,
            whiteListedApps = whiteListedApps,
            lastUpdated = lastUpdated
        )
        
        // CRITICAL: Verify the data is actually in the object
        Log.d(TAG, "CRITICAL: CloudUserData created - verifying data is present")
        Log.d(TAG, "  cloudData.requiredTasks.size: ${cloudData.requiredTasks.size}")
        Log.d(TAG, "  cloudData.practiceTasks.size: ${cloudData.practiceTasks.size}")
        Log.d(TAG, "  cloudData.checklistItems.size: ${cloudData.checklistItems.size}")
        Log.d(TAG, "  cloudData.gameIndices.size: ${cloudData.gameIndices.size}")
        
        return cloudData
    }

    /**
     * Formats last reset date to ISO 8601 with EST timezone
     */
    private fun formatLastResetDate(lastResetDateString: String?): String? {
        if (lastResetDateString == null) {
            // No last_reset_date exists, create new timestamp in EST
            return generateESTTimestamp()
        }

        // Convert from "dd-MM-yyyy hh:mm:ss a" format to ISO 8601 with EST timezone
        return try {
            val parseFormat = SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault())
            parseFormat.timeZone = TimeZone.getTimeZone("America/New_York")
            val parsedDate = parseFormat.parse(lastResetDateString)
            if (parsedDate != null) {
                val estTimeZone = TimeZone.getTimeZone("America/New_York")
                val offsetMillis = estTimeZone.getOffset(parsedDate.time)
                val offsetHours = offsetMillis / (1000 * 60 * 60)
                val offsetMinutes = Math.abs((offsetMillis % (1000 * 60 * 60)) / (1000 * 60))
                val offsetString = String.format("%+03d:%02d", offsetHours, offsetMinutes)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                dateFormat.timeZone = estTimeZone
                val formatted = dateFormat.format(parsedDate) + offsetString
                Log.d(TAG, "Converted last_reset from '$lastResetDateString' to EST format: $formatted")
                formatted
            } else {
                generateESTTimestamp()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing last_reset_date: $lastResetDateString", e)
            generateESTTimestamp()
        }
    }

    /**
     * Generates EST timestamp in ISO 8601 format
     */
    private fun generateESTTimestamp(): String {
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
     * Collects required tasks data from local storage, ensuring all config tasks are included
     * NEW: Reads directly from new format (task names → TaskProgress)
     */
    private fun collectRequiredTasksData(profile: String, progressPrefs: SharedPreferences): Map<String, TaskProgress> {
        val requiredTasks = mutableMapOf<String, TaskProgress>()

        try {
            // NEW: Read directly from new format (task names → TaskProgress)
            val requiredTasksKey = "${profile}_required_tasks"
            val requiredTasksJson = progressPrefs.getString(requiredTasksKey, "{}") ?: "{}"
            Log.d(TAG, "CRITICAL: Reading required_tasks from local storage for profile: $profile")
            Log.d(TAG, "  JSON from SharedPreferences: $requiredTasksJson")
            
            val existingRequiredTasks: Map<String, TaskProgress> = gson.fromJson(
                requiredTasksJson,
                object : TypeToken<Map<String, TaskProgress>>() {}.type
            ) ?: emptyMap()
            
            Log.d(TAG, "  Parsed existing tasks count: ${existingRequiredTasks.size}")

            // CRITICAL: Get ALL tasks from config (not filtered by visibility) for database syncing
            val configTasks = getConfigTasksForSection("required", filterByVisibility = false)
            Log.d(TAG, "  Config tasks count: ${configTasks.size}")

            // Get time tracking sessions to calculate correct/incorrect answers
            val timeTracker = TimeTracker(context)
            val allSessions = timeTracker.getTodaySessionsList()

            // For each task in config, create/merge TaskProgress
            configTasks.forEach { task ->
                val taskName = task.title ?: "Unknown Task"
                val taskId = task.launch ?: taskName.lowercase().replace(" ", "_")

                // NEW: Get existing TaskProgress from new format (look up by task name)
                val existingProgress = existingRequiredTasks[taskName]
                val status = existingProgress?.status ?: "incomplete"

                // Calculate correct/incorrect answers from sessions matching this task
                val matchingSessions = allSessions.filter { session ->
                    session.activityId == taskId
                }

                val totalCorrect = matchingSessions.sumOf { it.correctAnswers }
                val totalIncorrect = matchingSessions.sumOf { it.incorrectAnswers }

                // Determine questions, correct, and incorrect based on task type
                val isWebGame = task.webGame == true
                val hasAnswerData = totalCorrect > 0 || totalIncorrect > 0

                val questions: Int?
                val correct: Int?
                val incorrect: Int?

                if (isWebGame) {
                    if (hasAnswerData) {
                        correct = totalCorrect
                        incorrect = totalIncorrect
                        questions = totalCorrect + totalIncorrect
                    } else {
                        correct = null
                        incorrect = null
                        questions = null
                    }
                } else {
                    if (task.totalQuestions != null) {
                        questions = task.totalQuestions
                        correct = if (totalCorrect > 0) totalCorrect else null
                        incorrect = if (totalIncorrect > 0) totalIncorrect else null
                    } else {
                        questions = -1
                        correct = -1
                        incorrect = -1
                    }
                }

                // NEW: Preserve existing data (correct/incorrect/questions) or calculate from sessions
                requiredTasks[taskName] = TaskProgress(
                    status = status,
                    correct = existingProgress?.correct ?: correct,
                    incorrect = existingProgress?.incorrect ?: incorrect,
                    questions = existingProgress?.questions ?: questions,
                    stars = existingProgress?.stars ?: task.stars, // Preserve stars from existing progress or get from config
                    // Preserve visibility fields from existing progress or get from config
                    showdays = existingProgress?.showdays ?: task.showdays,
                    hidedays = existingProgress?.hidedays ?: task.hidedays,
                    displayDays = existingProgress?.displayDays ?: task.displayDays,
                    disable = existingProgress?.disable ?: task.disable
                )
            }
            
            Log.d(TAG, "CRITICAL: Final requiredTasks count after collection: ${requiredTasks.size}")
            if (requiredTasks.isNotEmpty()) {
                Log.d(TAG, "  Sample task names: ${requiredTasks.keys.take(3).joinToString()}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error collecting required tasks data", e)
            e.printStackTrace()
        }

        return requiredTasks
    }

    /**
     * Collects practice tasks data from local storage, ensuring all config tasks are included
     * Only includes tasks from the "optional" section, not "bonus" section
     */
    private fun collectPracticeTasksData(): Map<String, PracticeProgress> {
        val practiceTasks = mutableMapOf<String, PracticeProgress>()

        try {
            // CRITICAL: Get ALL tasks from optional section (not filtered by visibility) for database syncing
            val optionalTasks = getConfigTasksForSection("optional", filterByVisibility = false)

            // Get time tracking sessions to calculate performance data
            val timeTracker = TimeTracker(context)
            val allSessions = timeTracker.getTodaySessionsList()

            // For each task in config, create PracticeProgress with real data
            optionalTasks.forEach { task ->
                val taskName = task.title ?: "Unknown Task"
                val baseTaskId = task.launch ?: taskName.lowercase().replace(" ", "_")

                // For optional tasks, sessions use unique task IDs with section prefix
                val optionalTaskId = "optional_$baseTaskId"

                // Find all sessions for this task (could be completed multiple times)
                val matchingSessions = allSessions.filter { session ->
                    session.activityId == optionalTaskId
                }

                // Get current cycle completions from sessions
                val currentCycleCompletions = matchingSessions.count { it.completed }
                
                // Get cumulative times_completed from storage (from previous cycles)
                val profile = SettingsManager.readProfile(context) ?: "AM"
                val cumulativeKey = "${profile}_practice_tasks_cumulative_times"
                val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
                val cumulativeJson = prefs.getString(cumulativeKey, "{}") ?: "{}"
                val cumulativeType = object : com.google.gson.reflect.TypeToken<Map<String, Int>>() {}.type
                val cumulativeTimes = com.google.gson.Gson().fromJson<Map<String, Int>>(cumulativeJson, cumulativeType) ?: emptyMap()
                val cumulativeCount = cumulativeTimes[taskName] ?: 0
                
                // Total times_completed = cumulative (from previous cycles) + current cycle
                val timesCompleted = cumulativeCount + currentCycleCompletions
                
                val totalCorrect = matchingSessions.sumOf { it.correctAnswers }
                val totalIncorrect = matchingSessions.sumOf { it.incorrectAnswers }

                // Determine questionsAnswered, correct, and incorrect based on task type
                val isWebGame = task.webGame == true
                val hasAnswerData = totalCorrect > 0 || totalIncorrect > 0

                val questionsAnswered: Int?
                val correct: Int?
                val incorrect: Int?

                if (isWebGame) {
                    if (hasAnswerData) {
                        correct = totalCorrect
                        incorrect = totalIncorrect
                        questionsAnswered = totalCorrect + totalIncorrect
                    } else {
                        correct = null
                        incorrect = null
                        questionsAnswered = null
                    }
                } else {
                    if (task.totalQuestions != null) {
                        questionsAnswered = if (hasAnswerData) totalCorrect + totalIncorrect else null
                        correct = if (totalCorrect > 0) totalCorrect else null
                        incorrect = if (totalIncorrect > 0) totalIncorrect else null
                    } else {
                        questionsAnswered = -1
                        correct = -1
                        incorrect = -1
                    }
                }

                practiceTasks[taskName] = PracticeProgress(
                    timesCompleted = timesCompleted,
                    correct = correct,
                    incorrect = incorrect,
                    questionsAnswered = questionsAnswered,
                    showdays = task.showdays,
                    hidedays = task.hidedays,
                    displayDays = task.displayDays,
                    disable = task.disable
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error collecting practice tasks data", e)
        }

        return practiceTasks
    }

    /**
     * Collects checklist items data from config, including done status, stars, and display days
     */
    private fun collectChecklistItemsData(profile: String, progressPrefs: SharedPreferences): Map<String, ChecklistItemProgress> {
        val checklistItems = mutableMapOf<String, ChecklistItemProgress>()

        try {
            // NEW: Read directly from new format (task names → TaskProgress)
            val requiredTasksKey = "${profile}_required_tasks"
            val requiredTasksJson = progressPrefs.getString(requiredTasksKey, "{}") ?: "{}"
            val existingRequiredTasks: Map<String, TaskProgress> = gson.fromJson(
                requiredTasksJson,
                object : TypeToken<Map<String, TaskProgress>>() {}.type
            ) ?: emptyMap()

            // CRITICAL: Get ALL checklist items (not filtered by visibility) for database syncing
            val checklistSection = getConfigChecklistSection()

            if (checklistSection == null) {
                Log.w(TAG, "Checklist section not found in config")
                return checklistItems
            }

            val allItems = checklistSection.items?.filterNotNull() ?: emptyList()

            // For each checklist item in config, create ChecklistItemProgress
            allItems.forEach { item ->
                val itemLabel = item.label ?: "Unknown Item"

                // NEW: Look up by item label (name) instead of item ID
                val existingProgress = existingRequiredTasks[itemLabel]
                val isDone = existingProgress?.status == "complete"

                // Get stars from config
                val stars = item.stars ?: 0

                // Get display days from config (if any)
                val displayDays = item.displayDays

                checklistItems[itemLabel] = ChecklistItemProgress(
                    done = isDone,
                    stars = stars,
                    displayDays = displayDays
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting checklist items data", e)
            e.printStackTrace()
        }

        return checklistItems
    }

    /**
     * Collects all game indices from various SharedPreferences
     */
    private fun collectAllGameIndices(localProfileId: String): Map<String, Int> {
        val gameIndices = mutableMapOf<String, Int>()

        try {
            // Collect regular game progress
            val gamePrefs = context.getSharedPreferences("game_progress", Context.MODE_PRIVATE)
            val profilePrefix = "${localProfileId}_progress_"
            gamePrefs.all.forEach { (key, value) ->
                if (key.startsWith(profilePrefix) && value is Int) {
                    val launchId = key.removePrefix(profilePrefix)
                    gameIndices[launchId] = value
                }
            }

            // Collect web game progress
            val webGamePrefs = context.getSharedPreferences("web_game_progress", Context.MODE_PRIVATE)
            val webPrefix = "${localProfileId}_web_progress_"
            webGamePrefs.all.forEach { (key, value) ->
                if (key.startsWith(webPrefix) && value is Int) {
                    val gameId = key.removePrefix(webPrefix)
                    gameIndices[gameId] = value
                }
            }

            // Collect video progress
            val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val videoPrefix = "${localProfileId}_"
            videoPrefs.all.forEach { (key, value) ->
                if (key.startsWith(videoPrefix) && value is Int) {
                    if (key.endsWith("_index")) {
                        val videoFile = key.removePrefix(videoPrefix).removeSuffix("_index")
                        gameIndices[videoFile] = value
                    } else if (key.endsWith("_completed")) {
                        val taskId = key.removePrefix(videoPrefix).removeSuffix("_completed")
                        gameIndices[taskId] = value
                    }
                }
            }

            Log.d(TAG, "Collected ${gameIndices.size} game indices")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting game indices", e)
        }

        return gameIndices
    }

    /**
     * Collects app list from BaerenLock SharedPreferences (if BaerenLock is installed)
     */
    private fun collectAppListFromBaerenLock(profile: String, appListType: String): String? {
        return try {
            val baerenLockContext = try {
                context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
            } catch (e: Exception) {
                return null
            }

            val prefsName = when (appListType) {
                "reward_apps" -> "settings"
                "blacklisted_apps" -> "blacklist_prefs"
                "white_listed_apps" -> "whitelist_prefs"
                else -> "settings"
            }

            val keyName = when (appListType) {
                "reward_apps" -> "reward_apps"
                "blacklisted_apps" -> "packages"
                "white_listed_apps" -> "allowed"
                else -> appListType
            }

            val prefs = baerenLockContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

            when (appListType) {
                "reward_apps", "blacklisted_apps", "white_listed_apps" -> {
                    val appSet = prefs.getStringSet(keyName, null)
                    if (appSet != null && appSet.isNotEmpty()) {
                        gson.toJson(appSet.toList())
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not collect $appListType from BaerenLock: ${e.message}")
            null
        }
    }

    /**
     * Gets checklist section from config
     */
    private fun getConfigChecklistSection(): Section? {
        try {
            val contentUpdateService = ContentUpdateService()
            val jsonString = contentUpdateService.getCachedMainContent(context)

            if (jsonString.isNullOrEmpty()) {
                Log.w(TAG, "No cached main content available for checklist section")
                return null
            }

            val mainContent = gson.fromJson(jsonString, MainContent::class.java)
            return mainContent?.sections?.find { it.id == "checklist" }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading checklist section from config", e)
            return null
        }
    }

    /**
     * Gets all tasks from a specific config section
     * @param sectionId The section ID ("required" or "optional")
     * @param filterByVisibility If true, only returns tasks visible today. If false, returns ALL tasks for database syncing
     */
    private fun getConfigTasksForSection(sectionId: String, filterByVisibility: Boolean): List<Task> {
        try {
            val contentUpdateService = ContentUpdateService()
            val jsonString = contentUpdateService.getCachedMainContent(context)

            if (jsonString.isNullOrEmpty()) {
                Log.w(TAG, "No cached main content available for config tasks")
                return emptyList()
            }

            val mainContent = gson.fromJson(jsonString, MainContent::class.java)
            val section = mainContent?.sections?.find { it.id == sectionId }

            val allTasks = section?.tasks?.filterNotNull() ?: emptyList()

            // For database syncing, return ALL tasks (not filtered by visibility)
            if (!filterByVisibility) {
                return allTasks
            }

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
