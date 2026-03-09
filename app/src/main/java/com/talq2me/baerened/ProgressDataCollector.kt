package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * Builds upload payload from prefs (prefs hold last DB apply + user edits). DB is source of truth.
 */
class ProgressDataCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "ProgressDataCollector"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
    }

    private val gson = Gson()

    /**
     * Builds CloudUserData for upload to DB from prefs only (no cache).
     * Prefs are updated when we apply cloud data after fetch, and when we make local changes. Write to DB immediately after changes.
     */
    fun buildUploadPayloadFromPrefs(profile: String): CloudUserData {
        val lastUpdated = generateESTTimestamp()
        Log.d(TAG, "Building upload payload from prefs for profile: $profile")
        return buildFullPayloadFromPrefs(profile, lastUpdated)
    }

    /** Builds complete CloudUserData from prefs for DB upload. */
    private fun buildFullPayloadFromPrefs(profile: String, lastUpdated: String): CloudUserData {
        val progressPrefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
        val progressManager = DailyProgressManager(context)

        val requiredTasks = collectRequiredTasksData(profile, progressPrefs)
        val practiceTasks = collectPracticeTasksData()
        val checklistItems = collectChecklistItemsData(profile, progressPrefs)

        val possibleStars = progressPrefs.getInt("${profile}_total_possible_stars", 0)
        val berriesPrefs = context.getSharedPreferences("pokemonBattleHub", Context.MODE_PRIVATE)
        val berriesEarned = berriesPrefs.getInt("${profile}_earnedBerries", 0)
        val bankedMins = progressManager.getBankedRewardMinutes()
        // last_reset is never sent on progress uploads (CloudSyncService strips it). Only runDailyResetInDb sets last_reset in DB.
        val lastReset: String? = null
        val coinsEarned = progressPrefs.getInt("${profile}_coins_earned", 0)
        val pokemonUnlocked = progressPrefs.getInt("${profile}_pokemon_unlocked", 0)

        val choresJson = progressPrefs.getString("${profile}_chores", "[]") ?: "[]"
        val chores: List<ChoreProgress> = try {
            gson.fromJson(choresJson, object : TypeToken<List<ChoreProgress>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val gameIndices = collectAllGameIndices(profile)
        val rewardApps = collectAppListFromBaerenLock(profile, "reward_apps")
        val blacklistedApps = collectAppListFromBaerenLock(profile, "blacklisted_apps")
        val whiteListedApps = collectAppListFromBaerenLock(profile, "white_listed_apps")

        Log.d(TAG, "Built upload payload from prefs: requiredTasks=${requiredTasks.size}, checklistItems=${checklistItems.size}, berries=$berriesEarned for profile: $profile")

        return CloudUserData(
            profile = profile,
            lastUpdated = lastUpdated,
            lastReset = lastReset,
            requiredTasks = requiredTasks,
            practiceTasks = practiceTasks,
            checklistItems = checklistItems,
            possibleStars = possibleStars,
            bankedMins = bankedMins,
            berriesEarned = berriesEarned,
            coinsEarned = coinsEarned,
            pokemonUnlocked = pokemonUnlocked,
            chores = chores,
            gameIndices = gameIndices,
            rewardApps = rewardApps,
            blacklistedApps = blacklistedApps,
            whiteListedApps = whiteListedApps
        )
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
     * Now() at EST in ISO 8601 format (America/New_York).
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
                // CRITICAL: Log Math task specifically for debugging
                if (taskName == "Math" || taskName.contains("Math")) {
                    Log.d(TAG, "CRITICAL: Collecting Math task - existing status: ${existingProgress?.status}, final status: $status")
                }
                
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

            // CRITICAL: Include checklist items in required_tasks so upload and cloud have them;
            // otherwise applying cloud later overwrites local and we lose checklist completions.
            val checklistSection = getConfigChecklistSection()
            checklistSection?.items?.filterNotNull()?.forEach { item ->
                val itemLabel = item.label ?: "Unknown Item"
                val existingProgress = existingRequiredTasks[itemLabel]
                val status = existingProgress?.status ?: "incomplete"
                requiredTasks[itemLabel] = TaskProgress(
                    status = status,
                    correct = existingProgress?.correct,
                    incorrect = existingProgress?.incorrect,
                    questions = existingProgress?.questions,
                    stars = existingProgress?.stars ?: item.stars ?: 0,
                    showdays = item.showdays,
                    hidedays = item.hidedays,
                    displayDays = item.displayDays,
                    disable = null
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
     * CRITICAL: Reads completion status and answer data from practice_tasks storage (not TimeTracker)
     */
    private fun collectPracticeTasksData(): Map<String, PracticeProgress> {
        val practiceTasks = mutableMapOf<String, PracticeProgress>()

        try {
            // CRITICAL: Get ALL tasks from optional section (not filtered by visibility) for database syncing
            val optionalTasks = getConfigTasksForSection("optional", filterByVisibility = false)

            // Get practice tasks from storage (completion status and answer data)
            val profile = SettingsManager.readProfile(context) ?: "AM"
            val prefs = context.getSharedPreferences("daily_progress_prefs", Context.MODE_PRIVATE)
            val practiceTasksKey = "${profile}_practice_tasks"
            val practiceTasksJson = prefs.getString(practiceTasksKey, "{}") ?: "{}"
            val practiceTasksType = object : com.google.gson.reflect.TypeToken<Map<String, TaskProgress>>() {}.type
            val storedPracticeTasks = com.google.gson.Gson().fromJson<Map<String, TaskProgress>>(practiceTasksJson, practiceTasksType) ?: emptyMap()
            
            // Get times_completed for today from storage (cleared on daily reset)
            val cumulativeKey = "${profile}_practice_tasks_cumulative_times"
            val cumulativeJson = prefs.getString(cumulativeKey, "{}") ?: "{}"
            val cumulativeType = object : com.google.gson.reflect.TypeToken<Map<String, Int>>() {}.type
            val cumulativeTimes = com.google.gson.Gson().fromJson<Map<String, Int>>(cumulativeJson, cumulativeType) ?: emptyMap()

            // For each task in config, create PracticeProgress with real data
            optionalTasks.forEach { task ->
                val taskName = task.title ?: "Unknown Task"
                
                // Get stored progress for this task (correct/incorrect/questions reset daily)
                val storedProgress = storedPracticeTasks[taskName]
                
                // times_completed = count for today only (cumulative_times is cleared on daily reset)
                val timesCompleted = cumulativeTimes[taskName] ?: 0
                
                // Get answer data from stored progress (or null if not available)
                val correct = storedProgress?.correct
                val incorrect = storedProgress?.incorrect
                val questionsAnswered = storedProgress?.questions

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
     * Collects checklist items data for upload. Reads from the checklist_items prefs key only.
     * required_tasks and checklist_items are separate DB columns — never mix them.
     */
    private fun collectChecklistItemsData(profile: String, progressPrefs: SharedPreferences): Map<String, ChecklistItemProgress> {
        val checklistItems = mutableMapOf<String, ChecklistItemProgress>()

        try {
            val checklistPrefsKey = "${profile}_checklist_items"
            val checklistJson = progressPrefs.getString(checklistPrefsKey, "{}") ?: "{}"
            val existingChecklist: Map<String, ChecklistItemProgress> = gson.fromJson(
                checklistJson,
                object : TypeToken<Map<String, ChecklistItemProgress>>() {}.type
            ) ?: emptyMap()

            val checklistSection = getConfigChecklistSection()
            if (checklistSection == null) {
                Log.w(TAG, "Checklist section not found in config")
                return checklistItems
            }

            val allItems = checklistSection.items?.filterNotNull() ?: emptyList()

            allItems.forEach { item ->
                val itemLabel = item.label ?: "Unknown Item"
                val existing = existingChecklist[itemLabel]
                val isDone = existing?.done ?: false
                val stars = item.stars ?: existing?.stars ?: 0
                val displayDays = item.displayDays ?: existing?.displayDays

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
