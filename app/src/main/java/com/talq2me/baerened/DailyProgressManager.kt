package com.talq2me.baerened

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.math.BigDecimal

/**
 * Manages daily progress tracking for games and tasks.
 * Tracks completion status and star earnings on a per-day basis.
 */
open class DailyProgressManager(private val context: Context) {

    companion object {
        private const val PREF_NAME = "daily_progress_prefs"
        // Note: These keys are now profile-prefixed at runtime (e.g., "${profile}_required_tasks")
        private const val KEY_REQUIRED_TASKS = "required_tasks" // NEW: Uses cloud format (task names → TaskProgress)
        private const val KEY_PRACTICE_TASKS = "practice_tasks" // Practice tasks stored separately (task names → TaskProgress)
        private const val KEY_COMPLETED_TASKS = "completed_tasks" // OLD: Deprecated, kept for migration
        private const val KEY_COMPLETED_TASK_NAMES = "completed_task_names" // OLD: Deprecated, kept for migration
        private const val KEY_LAST_RESET_DATE = "last_reset_date"
        private const val KEY_TOTAL_POSSIBLE_STARS = "total_possible_stars"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
        private const val KEY_LAST_POKEMON_UNLOCK_DATE = "last_pokemon_unlock_date"
        private const val KEY_EARNED_STARS_AT_BATTLE_END = "earned_stars_at_battle_end" // Track earned stars when battle ended (for reset detection)
        private const val KEY_COINS_EARNED = "coins_earned" // Chores 4 $$ - never reset
        private const val KEY_CHORES = "chores" // Chores 4 $$ - JSON array of ChoreProgress
        @Volatile internal var completedTasksMapCacheKey: String? = null
        @Volatile internal var completedTasksMapCache: Map<String, Boolean>? = null

        /** Shared across all instances so any Activity's progressManager sees last DB fetch. */
        @Volatile var currentSessionData: DbUserData? = null
        @Volatile private var earnedStarsAtBattleEndMemory: Int = 0
        @Volatile private var lastPokemonUnlockDateMemory: String = ""

        /** Global callback for upload; set from Application. Receives app context and payload to upload. */
        var onUploadNeeded: ((Context, DbUserData) -> Unit)? = null

        /** Callback to sync a single task/checklist/chore to DB via RPC (af_update_*). Set from Application. */
        var onSyncSingleItemToDb: ((Context, SingleItemUpdate) -> Unit)? = null

        fun setGlobalUploadCallback(callback: (Context, DbUserData) -> Unit) {
            onUploadNeeded = callback
        }

        fun setGlobalSyncSingleItemCallback(callback: (Context, SingleItemUpdate) -> Unit) {
            onSyncSingleItemToDb = callback
        }
    }

    /** Payload for single-item DB update RPCs. */
    sealed class SingleItemUpdate {
        data class ChecklistItem(val profile: String, val itemLabel: String, val done: Boolean) : SingleItemUpdate()
        data class Chore(val profile: String, val choreId: Int, val done: Boolean) : SingleItemUpdate()
        data class GameIndex(val profile: String, val gameKey: String, val index: Int) : SingleItemUpdate()
        data class PokemonUnlocked(val profile: String, val pokemonUnlocked: Int) : SingleItemUpdate()
    }

    private val gson = Gson()
    private val syncService = SupabaseInterface()
    private val userDataRepository = UserDataRepository.getInstance(context.applicationContext)
    private val dataApplier = DbUserDataApplier(context) { p, t -> setStoredLastUpdatedTimestamp(p, t) }

    /** Runs one RPC (no session mutation). */
    private suspend fun invokeSingleItemRpcOnly(update: SingleItemUpdate): Result<Unit> {
        val r = when (update) {
            is SingleItemUpdate.ChecklistItem -> syncService.invokeAfUpdateChecklistItem(
                update.profile, update.itemLabel, update.done
            )
            is SingleItemUpdate.Chore -> syncService.invokeAfUpdateChore(update.profile, update.choreId, update.done)
            is SingleItemUpdate.GameIndex -> syncService.invokeAfUpdateGameIndex(update.profile, update.gameKey, update.index)
            is SingleItemUpdate.PokemonUnlocked -> syncService.invokeAfUpdatePokemonUnlocked(update.profile, update.pokemonUnlocked)
        }
        if (r.isFailure) {
            Log.e("DailyProgressManager", "RPC failed for ${update::class.simpleName}: ${r.exceptionOrNull()?.message}")
        }
        return r
    }

    /** Replaces session + prefs mirror from DB row (gold standard). */
    suspend fun refetchSessionFromDb(cloudProfile: String): Result<Unit> = withContext(Dispatchers.IO) {
        val fetch = userDataRepository.fetchUserData(cloudProfile)
        if (fetch.isFailure) {
            Log.e("DailyProgressManager", "refetchSessionFromDb failed: ${fetch.exceptionOrNull()?.message}")
            return@withContext Result.failure(fetch.exceptionOrNull() ?: Exception("Fetch failed"))
        }
        val data = fetch.getOrNull()
        if (data != null) {
            dataApplier.applyDbDataToPrefs(data)
            setProgressDataAfterFetch(data)
        } else {
            val empty = DbUserData(profile = cloudProfile)
            dataApplier.applyDbDataToPrefs(empty)
            setProgressDataAfterFetch(empty)
        }
        Result.success(Unit)
    }

    /**
     * Writes each update to the DB via RPC in order, then **one** read from DB to refresh session.
     * On any RPC failure, session is unchanged and the failure is returned.
     */
    suspend fun applyRpcChainThenRefetch(updates: List<SingleItemUpdate>): Result<Unit> {
        if (updates.isEmpty()) return Result.success(Unit)
        for (u in updates) {
            val step = invokeSingleItemRpcOnly(u)
            if (step.isFailure) return step
        }
        val cloudProfile = when (val first = updates.first()) {
            is SingleItemUpdate.ChecklistItem -> first.profile
            is SingleItemUpdate.Chore -> first.profile
            is SingleItemUpdate.GameIndex -> first.profile
            is SingleItemUpdate.PokemonUnlocked -> first.profile
        }
        return refetchSessionFromDb(cloudProfile)
    }

    fun setProgressDataAfterFetch(data: DbUserData?) {
        currentSessionData = data
        invalidateCompletedTasksMapCache()
    }

    fun clearProgressDataAfterRequest() {
        currentSessionData = null
        invalidateCompletedTasksMapCache()
    }

    /** Session data for profile. Shared globally; null if no fetch yet. */
    fun getCurrentSessionData(profile: String): DbUserData? =
        currentSessionData?.takeIf { it.profile == toCloudProfile(profile) }

    /** @deprecated No prefs; use getCurrentSessionData */
    private fun getProgressDataForRequest(profile: String): DbUserData? = getCurrentSessionData(profile)

    fun getProgressDataForUpload(profile: String): DbUserData? = getCurrentSessionData(profile)

    /** Game index from session data (last DB fetch). No prefs. */
    fun getGameIndexFromCache(profile: String, gameKey: String): Int {
        val data = getCurrentSessionData(profile)
        return data?.gameIndices?.get(gameKey) ?: 0
    }

    /**
     * Persists game index: RPC + DB refetch. Session is only updated from the returned row.
     */
    suspend fun updateGameIndexInDbSync(profile: String, gameKey: String, index: Int): Result<Unit> {
        if (getCurrentSessionData(profile) == null) {
            return Result.failure(Exception("No session; load progress first"))
        }
        return applyRpcChainThenRefetch(
            listOf(SingleItemUpdate.GameIndex(toCloudProfile(profile), gameKey, index))
        )
    }

    /** Uses passed-in data, or current session data from last DB fetch. No prefs. */
    private fun dataForProfile(profile: String, progressData: DbUserData?): DbUserData? {
        if (progressData != null && progressData.profile == toCloudProfile(profile)) return progressData
        return currentSessionData?.takeIf { it.profile == toCloudProfile(profile) }
    }

    init {}

    /**
     * Gets the current date as a string for daily reset tracking. Uses EST.
     */
    private fun getCurrentDateString(): String {
        val format = SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault())
        format.timeZone = TimeZone.getTimeZone("America/Toronto")
        val date = format.format(Date())
        Log.d("DailyProgressManager", "Current date string (EST): $date")
        return date
    }

    /**
     * Helper function to set local last updated timestamp (used after DB apply)
     */
    private fun setStoredLastUpdatedTimestamp(profile: String, timestamp: String) {
        // DB-only mode: timestamp is consumed from DB row/session; no local prefs mirror.
    }

    /**
     * Clears local prefs mirror + in-memory session for tests / debug. Daily reset is decided in Postgres (`af_daily_reset`).
     *
     * WHAT GETS RESET:
     * - required_tasks: status → "incomplete", correct/incorrect/questions → 0 (preserves visibility fields: showdays, hidedays, displayDays, disable, stars)
     * - completed_tasks (old format): cleared
     * - completed_task_names (old format): cleared
     * - banked_reward_minutes: → 0
     * - total_possible_stars: → 0
     * - earned_stars_at_battle_end: → 0
     * - earned_berries: → 0 (via resetEarnedBerries())
     * - TimeTracker sessions: cleared (this resets practice_tasks locally)
     * - last_reset_date: updated to current date/time
     * 
     * WHAT DOES NOT GET RESET (preserved):
     * - Video sequence progress (persistent across days)
     * - Pokemon unlocks
     * - Other persistent data
     * 
     * @param profile The profile to reset
     */
    private fun resetLocalProgressOnly(profile: String) {
        Log.d("DailyProgressManager", "resetLocalProgressOnly: clearing in-memory/session only for profile: $profile")
        setBankedRewardMinutesForProfile(profile, 0)
        resetEarnedBerries()
        earnedStarsAtBattleEndMemory = 0
        try {
            TimeTracker(context).clearAllData()
            Log.d("DailyProgressManager", "Cleared TimeTracker sessions for daily reset")
        } catch (e: Exception) {
            Log.e("DailyProgressManager", "Error clearing TimeTracker sessions", e)
        }
        
        Log.d("DailyProgressManager", "resetLocalProgressOnly: Local reset completed")
    }

    /**
     * Clears the banked reward minutes for the current profile. Cache only; sync will persist to DB.
     */
    fun clearBankedRewardMinutes() {
        setBankedRewardMinutes(0)
        Log.d("DailyProgressManager", "Banked reward minutes cleared for current profile (cache only)")
    }

    /**
     * Resets video sequence progress for all video files
     * NOTE: Sequential videos should NOT reset daily - they continue across days
     */
    private fun resetVideoSequenceProgress() {
        // Do NOT reset video sequence progress - videos should continue across days
        // val videoPrefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
        // videoPrefs.edit().clear().apply()
        Log.d("DailyProgressManager", "Video sequence progress is persistent across days (not reset)")
    }

    /**
     * Manually reset video sequence progress (for debugging)
     */
    fun resetVideoProgress() {
        resetVideoSequenceProgress()
    }

    /**
     * Local cache wipe for tests / debug. Does not patch the DB; daily reset is [UserDataRepository.fetchUserData] → `af_daily_reset`.
     */
    fun resetAllProgress() {
        val profile = getCurrentKid()
        resetLocalProgressOnly(profile)
        setBankedRewardMinutes(0)
        // Empty session so integration tests still have a non-null row shape (RPC + refetch is real source of truth).
        setProgressDataAfterFetch(DbUserData(profile = toCloudProfile(profile)))
    }

    /**
     * Gets required tasks from the passed-in data (from a fetch). No in-memory storage; caller passes DB result.
     */
    /** Reads required_tasks from session data (last DB fetch). No prefs. */
    private fun getRequiredTasks(progressData: DbUserData? = null): MutableMap<String, TaskProgress> {
        val data = dataForProfile(getCurrentKid(), progressData)
        return (data?.requiredTasks)?.toMutableMap() ?: mutableMapOf()
    }

    private fun getRequiredTasksForUpdate(progressData: DbUserData? = null): MutableMap<String, TaskProgress> {
        return getRequiredTasks(progressData)
    }

    /** Builds updated DbUserData with new required tasks (caller must upload to DB). No storage. */
    private fun buildWithRequiredTasks(progressData: DbUserData?, requiredTasks: Map<String, TaskProgress>): DbUserData {
        val profile = getCurrentKid()
        val cloudProfile = toCloudProfile(profile)
        return progressData?.copy(requiredTasks = requiredTasks)
            ?: DbUserData(profile = cloudProfile, requiredTasks = requiredTasks)
    }

    /** Checklist_items are a separate DB column; never store them in required_tasks. */
    private val checklistItemsType = object : TypeToken<Map<String, ChecklistItemProgress>>() {}.type

    /** Checklist items from session data (last DB fetch). No prefs. */
    fun getChecklistItemsFromPrefs(profile: String): Map<String, ChecklistItemProgress> {
        val data = currentSessionData?.takeIf { it.profile == toCloudProfile(profile) }
        return data?.checklistItems ?: emptyMap()
    }

    /**
     * Marks a checklist item complete: RPC then refetch from DB. Session matches DB after success.
     */
    suspend fun markChecklistItemCompleted(itemLabel: String, stars: Int, displayDays: String? = null): Result<Int> {
        val profile = getCurrentKid()
        val current = getCurrentSessionData(profile)
            ?: return Result.failure(Exception("No session; load progress first"))
        val wasDone = current.checklistItems[itemLabel]?.done == true
        if (wasDone) return Result.success(0)
        val sync = applyRpcChainThenRefetch(
            listOf(
                SingleItemUpdate.ChecklistItem(
                    profile = toCloudProfile(profile),
                    itemLabel = itemLabel,
                    done = true
                )
            )
        )
        if (sync.isFailure) {
            return Result.failure(sync.exceptionOrNull() ?: Exception("Could not save checklist to server"))
        }
        return Result.success(stars)
    }

    /** Chore toggle: RPC then DB refetch. */
    suspend fun notifyChoreUpdated(profile: String, choreId: Int, done: Boolean): Result<Unit> {
        return applyRpcChainThenRefetch(
            listOf(SingleItemUpdate.Chore(toCloudProfile(profile), choreId, done))
        )
    }

    /** True when progressData is non-null for current profile (caller passes result of fetch). */
    fun hasProgressDataAvailable(progressData: DbUserData? = null): Boolean {
        return dataForProfile(getCurrentKid(), progressData) != null
    }

    /**
     * Gets the completion status map for today (public method for batch reads).
     * Required, practice (optional), and bonus maps are separate; there is no combined case.
     * Keys: task TITLE for required/optional/bonus; checklist items by label. UI lookups use task.title (not task.launch).
     *
     * @param mapType "required", "optional", or "bonus". Other values return empty map.
     */
    fun getCompletedTasksMap(mapType: String? = null, progressData: DbUserData? = null): Map<String, Boolean> {
        val profile = getCurrentKid()
        val cacheKey = "$profile|${mapType ?: "none"}"
        if (completedTasksMapCacheKey == cacheKey && completedTasksMapCache != null) {
            return completedTasksMapCache!!
        }
        val allTasks = mutableMapOf<String, Boolean>()
        val data = dataForProfile(profile, progressData)
        val checklistItems = data?.checklistItems ?: getChecklistItemsFromPrefs(profile)
        when (mapType) {
            "required" -> {
                val requiredTasks = getRequiredTasks(progressData)
                requiredTasks.forEach { (name, progress) ->
                    allTasks[name] = progress.status == "complete"
                }
                checklistItems.forEach { (name, progress) ->
                    if (progress.done) allTasks[name] = true
                }
            }
            "optional" -> {
                val practiceTasks = getPracticeTasks(progressData)
                practiceTasks.forEach { (name, progress) ->
                    allTasks[name] = progress.status == "complete"
                }
            }
            "bonus" -> {
                // Bonus tasks never show completion status; always show as playable (never green)
                val bonusTasks = getBonusTasks(progressData)
                bonusTasks.forEach { (name, _) ->
                    allTasks[name] = false
                }
            }
            else -> { /* No combined map; required/optional/bonus are separate. Return empty. */ }
        }
        completedTasksMapCacheKey = cacheKey
        completedTasksMapCache = allTasks.toMap()
        return completedTasksMapCache!!
    }

    internal fun invalidateCompletedTasksMapCache() {
        completedTasksMapCacheKey = null
        completedTasksMapCache = null
    }

    /**
     * Gets the required tasks map in new format (task names → TaskProgress)
     */
    fun getRequiredTasksMap(): Map<String, TaskProgress> {
        return getRequiredTasks()
    }
    
    /** Practice tasks (optional section) from session data. Display-incomplete set can override status. */
    private fun getPracticeTasks(progressData: DbUserData? = null): MutableMap<String, TaskProgress> {
        val practice = dataForProfile(getCurrentKid(), progressData)?.practiceTasks ?: return mutableMapOf()
        return practice.mapValues { (_, p) ->
            val showComplete = p.timesCompleted > 0
            TaskProgress(
                status = if (showComplete) "complete" else "incomplete",
                correct = p.correct,
                incorrect = p.incorrect,
                questions = p.questionsAnswered,
                stars = null,
                showdays = p.showdays,
                hidedays = p.hidedays,
                displayDays = p.displayDays,
                disable = p.disable
            )
        }.toMutableMap()
    }

    /** Bonus tasks (bonus section) from session data. No display-incomplete reset (bonus do not reset to do-again). */
    private fun getBonusTasks(progressData: DbUserData? = null): MutableMap<String, TaskProgress> {
        val bonus = dataForProfile(getCurrentKid(), progressData)?.bonusTasks ?: return mutableMapOf()
        return bonus.mapValues { (_, p) ->
            val showComplete = p.timesCompleted > 0
            TaskProgress(
                status = if (showComplete) "complete" else "incomplete",
                correct = p.correct,
                incorrect = p.incorrect,
                questions = p.questionsAnswered,
                stars = null,
                showdays = p.showdays,
                hidedays = p.hidedays,
                displayDays = p.displayDays,
                disable = p.disable
            )
        }.toMutableMap()
    }
    
    /**
     * Gets the practice tasks map in new format (task names → TaskProgress)
     */
    fun getPracticeTasksMap(): Map<String, TaskProgress> {
        return getPracticeTasks()
    }

    /** No-op: progress is in session data only; task completion uses merge + onSyncSingleItemToDb (RPC). */
    private fun saveRequiredTasks(requiredTasks: Map<String, TaskProgress>, progressData: DbUserData? = null) {
        invalidateCompletedTasksMapCache()
    }

    /** No-op: progress is in session data only; task completion uses merge + onSyncSingleItemToDb (RPC). */
    private fun savePracticeTasks(practiceTasks: Map<String, TaskProgress>, taskNameJustCompleted: String? = null) {
        invalidateCompletedTasksMapCache()
    }

    /**
     * Generates a unique task ID that includes section information
     * This allows the same task (same launch ID) to be tracked separately in different sections
     */
    fun getUniqueTaskId(taskId: String, sectionId: String?): String {
        return if (sectionId != null && sectionId != "required") {
            // For optional/bonus sections, include section ID to make it unique
            // Required tasks use just the taskId for backward compatibility and progress tracking
            "${sectionId}_$taskId"
        } else {
            taskId
        }
    }

    /**
     * Canonical identifier for completion tracking: section + title.
     * Falls back to section + taskId when title is missing.
     */
    fun getUniqueTaskId(taskId: String, taskTitle: String?, sectionId: String?): String {
        val normalizedTitle = taskTitle?.trim()?.takeIf { it.isNotEmpty() }
        return if (sectionId != null && normalizedTitle != null) {
            "$sectionId::$normalizedTitle"
        } else {
            getUniqueTaskId(taskId, sectionId)
        }
    }

    /**
     * Marks a task complete: RPC(s) then **one** DB refetch. Session is only updated from the DB row.
     * [preRpcUpdates] runs first (e.g. [SingleItemUpdate.GameIndex] with game completion index).
     */
    suspend fun markTaskCompletedWithName(
        taskId: String,
        taskName: String,
        stars: Int,
        sectionId: String? = null,
        correctAnswers: Int? = null,
        incorrectAnswers: Int? = null,
        questionsAnswered: Int? = null,
        preRpcUpdates: List<SingleItemUpdate> = emptyList()
    ): Result<Int> {
        Log.d("DailyProgressManager", "markTaskCompletedWithName called: taskId=$taskId, taskName=$taskName, stars=$stars, sectionId=$sectionId")
        val profile = getCurrentKid()
        val normalizedSection = sectionId?.takeIf { it.isNotBlank() } ?: "optional"
        val cloudProfile = toCloudProfile(profile)

        for (u in preRpcUpdates) {
            val step = invokeSingleItemRpcOnly(u)
            if (step.isFailure) return Result.failure(step.exceptionOrNull() ?: Exception("Could not save pre-completion update"))
        }

        val completion = syncService.invokeAfUpdateTaskCompletion(
            profile = cloudProfile,
            taskTitle = taskName,
            sectionId = normalizedSection,
            stars = stars,
            correct = correctAnswers,
            incorrect = incorrectAnswers,
            questionsAnswered = questionsAnswered
        )
        if (completion.isFailure) {
            return Result.failure(completion.exceptionOrNull() ?: Exception("Could not save task to server"))
        }

        val refetch = refetchSessionFromDb(cloudProfile)
        if (refetch.isFailure) {
            return Result.failure(refetch.exceptionOrNull() ?: Exception("Could not refresh task state from server"))
        }

        val earnedStars = completion.getOrNull() ?: 0
        Log.d("DailyProgressManager", "Completion RPC applied for $taskId ($taskName), section=$normalizedSection, earnedStars=$earnedStars")
        return Result.success(earnedStars)
    }
    
    /**
     * Checks if a task is completed today
     * NEW: Looks up by task name instead of task ID
     */
    fun isTaskCompleted(taskId: String): Boolean {
        val requiredTasks = getRequiredTasks()
        val isCompleted = requiredTasks[taskId]?.status == "complete"
        Log.d("DailyProgressManager", "isTaskCompleted: taskId=$taskId, isCompleted=$isCompleted")
        return isCompleted
    }

    /**
     * Gets current progress with totals for display (fallback when config not available)
     * Earned coins/stars are computed from required_tasks so coins update after task completion.
     */
    fun getCurrentProgressWithTotals(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val totalStars = getPossibleStarsFromSession()

        // Coins come only from Chores 4 $$; no total from tasks
        val earnedCoins = getCoinsEarned()
        val totalCoins = 0
        val displayEarnedStars = getEarnedBerries()

        return Pair(Pair(earnedCoins, totalCoins), Pair(displayEarnedStars, totalStars))
    }

    /**
     * Gets cached total possible stars for the current profile (for when config isn't available).
     * Prefers DB-backed cache when available.
     */
    /** Reads from prefs (updated when we apply cloud data). No cache. */
    fun getCachedTotalPossibleStars(): Int {
        return getPossibleStarsFromSession()
    }

    /**
     * Gets the date of the last reset for debugging
     */
    fun getLastResetDate(): String {
        return dataForProfile(getCurrentKid(), null)?.lastReset ?: "Never"
    }

    /**
     * Battle Berry Management - tracks berries spent on battles
     */
    
    /** Earned berries from session data (last DB fetch). No prefs. */
    fun getEarnedBerries(): Int {
        val data = dataForProfile(getCurrentKid(), null)
        return data?.berriesEarned ?: 0
    }

    /** user_data.possible_stars from last DB fetch (session). */
    fun getPossibleStarsFromSession(): Int {
        return dataForProfile(getCurrentKid(), null)?.possibleStars ?: 0
    }

    /**
     * True when every **visible today** required task and every **visible** checklist item (with stars) is done.
     * Uses `required_tasks` / `checklist_items` from the last DB fetch only — no GitHub config.
     */
    fun areAllVisibleRequiredAndChecklistCompleteFromDb(): Boolean {
        val data = dataForProfile(getCurrentKid(), null) ?: return false
        var anyVisible = false

        data.requiredTasks.forEach { (_, tp) ->
            if (!TaskVisibilityChecker.isTaskVisible(tp.showdays, tp.hidedays, tp.displayDays, tp.disable)) return@forEach
            anyVisible = true
            if (tp.status != "complete") return false
        }

        data.checklistItems.forEach { (_, cp) ->
            if (cp.stars <= 0) return@forEach
            if (!TaskVisibilityChecker.isTaskVisible(null, null, cp.displayDays, null)) return@forEach
            anyVisible = true
            if (!cp.done) return false
        }

        // Nothing visible today: only treat as "all done" if there is literally nothing in DB.
        if (!anyVisible) {
            return data.requiredTasks.isEmpty() && data.checklistItems.isEmpty()
        }
        return true
    }

    /**
     * Sum of star values for visible required tasks + visible checklist items that are complete/done.
     * Uses only [DbUserData] from the last DB fetch (same visibility rules as [areAllVisibleRequiredAndChecklistCompleteFromDb]).
     */
    fun getEarnedRequiredStarsFromSession(): Int {
        val data = dataForProfile(getCurrentKid(), null) ?: return 0
        var sum = 0
        data.requiredTasks.forEach { (_, tp) ->
            if (!TaskVisibilityChecker.isTaskVisible(tp.showdays, tp.hidedays, tp.displayDays, tp.disable)) return@forEach
            if (tp.status == "complete") sum += (tp.stars ?: 0)
        }
        data.checklistItems.forEach { (_, cp) ->
            if (cp.stars <= 0) return@forEach
            if (!TaskVisibilityChecker.isTaskVisible(null, null, cp.displayDays, null)) return@forEach
            if (cp.done) sum += cp.stars
        }
        return sum
    }

    /** Updates session data with earned berries (display only). DB berries are updated only by task/checklist RPCs when status=complete or done=true. */
    fun setEarnedBerries(amount: Int) {
        val profile = getCurrentKid()
        val current = getCurrentSessionData(profile) ?: return
        currentSessionData = current.copy(berriesEarned = amount)
        Log.d("DailyProgressManager", "Set earned berries: $amount for profile: $profile (session only; DB updated by task RPCs)")
    }
    
    /**
     * Resets earned berries to 0 (when battle happens)
     */
    fun resetEarnedBerries() {
        setEarnedBerries(0)
    }
    
    /**
     * Gets the earned stars count when battle ended (to detect new task completions)
     */
    fun getEarnedStarsAtBattleEnd(): Int {
        return earnedStarsAtBattleEndMemory
    }
    
    /**
     * Sets the earned stars count when battle ended
     */
    fun setEarnedStarsAtBattleEnd(amount: Int) {
        earnedStarsAtBattleEndMemory = amount
    }
    
    /**
     * Resets battle end tracking (called when new required tasks are completed)
     */
    fun resetBattleEndTracking() {
        earnedStarsAtBattleEndMemory = 0
    }
    
    /**
     * Pokemon Collection Management
     */

    /**
     * Converts local profile (A/B) to cloud profile format (AM/BM)
     * If already in cloud format, returns as-is
     */
    private fun toCloudProfile(profile: String?): String {
        return when (profile) {
            "A" -> "AM"
            "B" -> "BM"
            else -> profile ?: "AM" // Default to AM if null, or return as-is if already AM/BM
        }
    }

    /**
     * Gets the current number of unlocked Pokemon for the current kid
     * Prefers DB-backed cache when available.
     */
    /** Prefers last DB-backed session; then prefs. */
    fun getUnlockedPokemonCount(): Int {
        val kid = getCurrentKid()
        val cloud = toCloudProfile(kid)
        currentSessionData?.takeIf { it.profile == cloud }?.let { return it.pokemonUnlocked }
        return 0
    }

    /** Pokemon count: RPC then DB refetch (prefs/session from row). */
    suspend fun setUnlockedPokemonCount(count: Int): Result<Unit> {
        val kid = getCurrentKid()
        val cloudProfile = toCloudProfile(kid)
        return applyRpcChainThenRefetch(listOf(SingleItemUpdate.PokemonUnlocked(cloudProfile, count)))
    }

    /**
     * Gets the current kid identifier (A or B, or AM/BM if already converted)
     */
    fun getCurrentKid(): String {
        return SettingsManager.readProfile(context) ?: "AM"
    }

    /**
     * Coins earned (Chores 4 $$) - never reset. Battle Hub displays this.
     * Prefers DB-backed cache when available.
     */
    /** Chores 4 $$ total coins: prefers last DB fetch session, then prefs. */
    fun getCoinsEarned(profile: String? = null): Int {
        val p = profile ?: getCurrentKid()
        dataForProfile(p, null)?.coinsEarned?.let { return it }
        return 0
    }

    /**
     * Kids virtual bank balance (parent adds/subtracts). Never reset.
     * This is read from the last DB fetch session data so app UI can display it,
     * while uploads/payloads omit it when unknown (nullable in DbUserData).
     */
    fun getKidBankBalance(profile: String? = null): BigDecimal {
        val p = profile ?: getCurrentKid()
        return dataForProfile(p, null)?.kidBankBalance ?: BigDecimal.ZERO
    }

    /**
     * Add (or subtract) coins and return new total. Used when checking/unchecking chores.
     */
    fun addCoinsEarned(profile: String? = null, delta: Int): Int {
        val p = profile ?: getCurrentKid()
        val current = dataForProfile(p, null)?.coinsEarned ?: 0
        val newVal = (current + delta).coerceAtLeast(0)
        val session = dataForProfile(p, null)
        if (session != null) {
            currentSessionData = session.copy(coinsEarned = newVal)
        }
        return newVal
    }

    /**
     * Chores list (Chores 4 $$) for the profile. Prefers session data (from last DB fetch), then prefs.
     * Call loadChoresFromJsonIfNeeded() first if empty.
     */
    fun getChores(profile: String? = null): List<ChoreProgress> {
        val p = profile ?: getCurrentKid()
        val fromSession = currentSessionData?.takeIf { it.profile == toCloudProfile(p) }?.chores
        if (!fromSession.isNullOrEmpty()) return fromSession
        // No local/prefs fallback: chores must come from the last DB fetch/reset.
        Log.w("DailyProgressManager", "getChores: returning empty (no DB/session chores) for profile: $p")
        return emptyList()
    }

    /** Writes to prefs; caller must sync to DB. */
    fun saveChores(profile: String? = null, chores: List<ChoreProgress>) {
        val p = profile ?: getCurrentKid()
        val session = dataForProfile(p, null)
        if (session != null) {
            currentSessionData = session.copy(chores = chores)
        }
        Log.d("DailyProgressManager", "Chores updated for profile: $p (${chores.size} items); persist via DB write")
    }

    /**
     * Load chores from config/chores.json (GitHub first, then cache, then assets — same as other config).
     * Merge with stored (by chore_id). If stored is empty, populate with file list (done=false).
     * Call from getContentFromJson and Chores screen.
     */
    fun loadChoresFromJsonIfNeeded(profile: String? = null) {
        // Intentionally disabled: chores must be DB-backed. If the DB/restore fails,
        // we want the UI to show empty + error (not silently repopulate from local GitHub/assets).
        Log.w("DailyProgressManager", "loadChoresFromJsonIfNeeded: ignored; chores must come from DB for profile=${profile ?: getCurrentKid()}")
    }

    private data class ChoreJsonItem(val id: Int, val description: String, val coins: Int)

    /**
     * Unlocks additional Pokemon (admin function). RPC + refetch on success.
     */
    suspend fun unlockPokemon(count: Int): Result<Unit> {
        val currentCount = getUnlockedPokemonCount()
        return setUnlockedPokemonCount(currentCount + count)
    }

    /**
     * Gets the last unlocked Pokemon (for display)
     */
    fun getLastUnlockedPokemon(): String? {
        // This would need to be implemented based on how Pokemon are organized
        // For now, return null - will be implemented when we have Pokemon data structure
        return null
    }

    /**
     * Checks if a Pokemon was already unlocked today
     */
    fun wasPokemonUnlockedToday(): Boolean {
        val lastUnlockDate = lastPokemonUnlockDateMemory
        val currentDate = getCurrentDateString()
        return lastUnlockDate == currentDate
    }

    /**
     * Records that a Pokemon was unlocked today
     */
    fun recordPokemonUnlockToday() {
        lastPokemonUnlockDateMemory = getCurrentDateString()
    }

    /**
     * Whether to show the Pokemon unlock button. Coins are no longer tied to tasks; always false.
     */
    fun shouldShowPokemonUnlockButton(): Boolean {
        return false
    }

    /** Banked minutes from session data (last DB fetch). No prefs. */
    fun getBankedRewardMinutes(): Int {
        val data = dataForProfile(getCurrentKid(), null)
        return data?.bankedMins ?: 0
    }

    fun setBankedRewardMinutes(minutes: Int) {
        setBankedRewardMinutesForProfile(getCurrentKid(), minutes)
    }

    /** Updates session data with banked minutes (display only). DB banked_mins is updated only by task/checklist RPCs when status=complete or done=true. */
    fun setBankedRewardMinutesForProfile(profile: String, minutes: Int) {
        val validatedMinutes = when {
            minutes < 0 -> 0
            minutes > 1000 -> 1000
            else -> minutes
        }
        val current = getCurrentSessionData(profile) ?: return
        currentSessionData = current.copy(bankedMins = validatedMinutes)
        Log.d("DailyProgressManager", "Set banked minutes: $validatedMinutes for profile: $profile (session only; DB updated by task RPCs)")
    }

    /**
     * Adds minutes directly to the banked reward minutes
     */
    fun addRewardMinutes(minutes: Int): Int {
        val currentMinutes = getBankedRewardMinutes()
        val newMinutes = currentMinutes + minutes
        setBankedRewardMinutes(newMinutes)
        Log.d("DailyProgressManager", "Added $minutes minutes to reward bank. New total: $newMinutes minutes")
        return newMinutes
    }

    /**
     * Uses all banked reward minutes and resets them to 0
     * Returns the minutes that were used
     */
    fun useAllRewardMinutes(): Int {
        val currentMinutes = getBankedRewardMinutes()
        if (currentMinutes > 0) {
            setBankedRewardMinutes(0)
            return currentMinutes
        }
        return 0
    }

}
