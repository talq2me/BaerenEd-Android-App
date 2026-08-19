package com.talq2me.baerened

import android.net.Uri
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trainer maps: DB-first path uses stable `af_get_*_tasks` RPCs (see [prepareFromDbStrict]) so tasks,
 * completion, and launch metadata come from Postgres.
 *
 * Optional / practice map: completion is **only** [DbRow.completionStatus] from `af_get_tasks_practice`
 * (see [isCompletedOnMap] for `mapType == "optional"`). Do not seed client completion maps for optional.
 */
object TrainerMapTaskMerge {

    data class DbRow(
        val taskName: String,
        val completionStatus: String,
        val berryValue: Int,
        val minsValue: Int,
        val launch: String?,
        val url: String?,
        val webGame: Boolean,
        val chromePage: Boolean,
        val videoSequence: String?,
        val playlistId: String?,
        val totalQuestions: Int?,
        val rewardId: String?,
        val easydays: String?,
        val harddays: String?,
        val extremedays: String?,
        val easy: Boolean,
        val blockOutlines: Boolean,
        val isChecklist: Boolean,
        val choreId: String? = null,
        val description: String? = null,
        val rewardCash: Double? = null
    )

    fun parseRows(arr: JsonArray): List<DbRow> {
        val out = ArrayList<DbRow>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val name = o.stringOrNull("task_name") ?: continue
            val status = o.stringOrNull("completion_status") ?: "incomplete"
            val berries = o.intOrNull("berry_value") ?: 0
            val mins = o.intOrNull("mins_value") ?: 0
            out.add(
                DbRow(
                    taskName = name,
                    completionStatus = status,
                    berryValue = berries,
                    minsValue = mins,
                    launch = o.stringOrNull("launch"),
                    url = o.stringOrNull("url"),
                    webGame = o.boolOrDefault("web_game", false),
                    chromePage = o.boolOrDefault("chrome_page", false),
                    videoSequence = o.stringOrNull("video_sequence"),
                    playlistId = o.stringOrNull("playlist_id"),
                    totalQuestions = o.intOrNull("total_questions"),
                    rewardId = o.stringOrNull("reward_id"),
                    easydays = o.stringOrNull("easydays"),
                    harddays = o.stringOrNull("harddays"),
                    extremedays = o.stringOrNull("extremedays"),
                    easy = o.boolOrDefault("easy", false),
                    blockOutlines = o.boolOrDefault("block_outlines", false),
                    isChecklist = o.boolOrDefault("is_checklist", false),
                    choreId = o.stringOrNull("chore_id"),
                    description = o.stringOrNull("description"),
                    rewardCash = o.doubleOrNull("reward_cash")
                )
            )
        }
        return out
    }

    /** Maps a trainer RPC row to [Task] for in-app launches; null if launch is missing. */
    fun taskFromDbRow(row: DbRow): Task? {
        val launch = row.launch?.trim().orEmpty()
        if (launch.isEmpty()) return null
        return Task(
            title = row.taskName,
            launch = launch,
            stars = row.berryValue,
            totalQuestions = row.totalQuestions,
            videoSequence = row.videoSequence,
            playlistId = row.playlistId,
            blockOutlines = row.blockOutlines,
            webGame = row.webGame,
            chromePage = row.chromePage,
            url = row.url,
            rewardId = row.rewardId,
            easydays = row.easydays,
            harddays = row.harddays,
            extremedays = row.extremedays,
            easy = row.easy,
            choreId = row.choreId,
            description = row.description,
            rewardCash = row.rewardCash
        )
    }

    /** Maps a checklist RPC row to [Task] for checklist map UI. */
    private fun checklistTaskFromDbRow(row: DbRow): Task {
        val checklistId = row.launch?.trim().takeUnless { it.isNullOrEmpty() } ?: row.taskName
        return Task(
            title = row.taskName,
            launch = "checklist_$checklistId",
            stars = row.berryValue
        )
    }

    /**
     * Finds a task by `launch` across required / practice / bonus RPCs (DB order).
     * Returns [Task] and section id (`required` | `optional` | `bonus`) for launch routing.
     */
    suspend fun findTaskAndSectionByLaunch(
        profile: String,
        launchId: String,
        supabase: SupabaseInterface
    ): Pair<Task, String>? {
        val id = launchId.trim()
        if (id.isEmpty()) return null
        fun taskForLaunch(arr: JsonArray?): Task? {
            if (arr == null) return null
            val row = parseRows(arr).find { it.launch?.trim() == id } ?: return null
            return taskFromDbRow(row)
        }
        taskForLaunch(supabase.invokeAfGetRequiredTasksRows(profile).getOrNull())?.let { return it to "required" }
        taskForLaunch(supabase.invokeAfGetPracticeTasksRows(profile).getOrNull())?.let { return it to "optional" }
        taskForLaunch(supabase.invokeAfGetBonusTasksRows(profile).getOrNull())?.let { return it to "bonus" }
        taskForLaunch(supabase.invokeAfGetPhotoChoreTasksRows(profile).getOrNull())?.let { return it to "chores" }
        return null
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val v = get(key) ?: return null
        if (v.isJsonNull) return null
        return v.asString
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val v = get(key) ?: return null
        if (v.isJsonNull) return null
        return try {
            v.asInt
        } catch (_: Exception) {
            v.asString.toIntOrNull()
        }
    }

    private fun JsonObject.boolOrDefault(key: String, default: Boolean): Boolean {
        val v = get(key) ?: return default
        if (v.isJsonNull) return default
        return try {
            v.asBoolean
        } catch (_: Exception) {
            when (v.asString.lowercase()) {
                "1", "true", "t", "yes", "y" -> true
                "0", "false", "f", "no", "n" -> false
                else -> default
            }
        }
    }

    private fun JsonObject.doubleOrNull(key: String): Double? {
        val v = get(key) ?: return null
        if (v.isJsonNull) return null
        return try {
            v.asDouble
        } catch (_: Exception) {
            v.asString.toDoubleOrNull()
        }
    }

    private const val TAG = "TrainerMapTaskMerge"

    /** Outcome of resolving which tasks to show on a trainer map and how completion is determined. */
    sealed class PrepareTrainerMapResult {
        data class Ready(
            val tasks: List<Task>,
            val sessionCompletionMap: MutableMap<String, Boolean>,
            val dbCompletionByTitle: Map<String, Boolean>?
        ) : PrepareTrainerMapResult()

        data class NoTasks(val message: String) : PrepareTrainerMapResult()
        data class Failed(val error: Throwable) : PrepareTrainerMapResult()
    }

    /**
     * DB-first (dumb UI): task list order and completion come from Postgres row RPCs.
     * Launch metadata and completion come from DB RPC rows only. No fallback to config-derived task lists.
     */
    suspend fun prepareFromDbStrict(
        mapType: String,
        profile: String,
        sessionCompletionMap: MutableMap<String, Boolean>,
        supabase: SupabaseInterface
    ): PrepareTrainerMapResult = withContext(Dispatchers.IO) {
        if (!supabase.isConfigured()) {
            return@withContext PrepareTrainerMapResult.Failed(
                IllegalStateException("Supabase is not configured.")
            )
        }
        val rowsResult = when (mapType) {
            "required", "checklist" -> supabase.invokeAfGetRequiredTasksRows(profile)
            "optional" -> supabase.invokeAfGetPracticeTasksRows(profile)
            "bonus" -> supabase.invokeAfGetBonusTasksRows(profile)
            "chores" -> supabase.invokeAfGetPhotoChoreTasksRows(profile)
            else -> Result.failure(IllegalArgumentException("Unknown mapType: $mapType"))
        }
        if (rowsResult.isFailure) {
            val ex = rowsResult.exceptionOrNull() ?: Exception("Trainer map RPC failed")
            Log.e(TAG, "Trainer map RPC failed", ex)
            return@withContext PrepareTrainerMapResult.Failed(ex)
        }
        val parsed = parseRows(rowsResult.getOrNull()!!).let { rows ->
            when (mapType) {
                "required" -> rows.filter { !it.isChecklist }
                "checklist" -> rows.filter { it.isChecklist }
                else -> rows
            }
        }
        if (parsed.isEmpty()) {
            return@withContext PrepareTrainerMapResult.NoTasks("No ${mapType} tasks available")
        }
        val tasks = when (mapType) {
            "checklist" -> parsed.map { checklistTaskFromDbRow(it) }
            else -> parsed.mapNotNull { taskFromDbRow(it) }
        }
        if (tasks.isEmpty()) {
            return@withContext PrepareTrainerMapResult.Failed(
                IllegalStateException(
                    "Server returned trainer rows without launch metadata."
                )
            )
        }
        val dbCompletion = parsed.associate { row ->
            val isDone = row.completionStatus.equals("complete", ignoreCase = true) ||
                row.completionStatus.equals("done", ignoreCase = true)
            row.taskName to isDone
        }.filterKeys { it.isNotEmpty() }
        PrepareTrainerMapResult.Ready(tasks, sessionCompletionMap, dbCompletion)
    }

    /**
     * @param dbCompletionByTitle from `af_get_*_tasks` RPCs ([prepareFromDbStrict]). For **optional** maps this is the
     *   only source of truth for complete/incomplete (matches Postgres). [completedTasksMap] is not used for optional.
     */
    fun isCompletedOnMap(
        task: Task,
        mapType: String,
        completedTasksMap: Map<String, Boolean>,
        dbCompletionByTitle: Map<String, Boolean>?
    ): Boolean {
        val taskTitle = task.title ?: ""
        val baseTaskId = task.launch ?: ""
        // Bonus tasks: af_get_tasks_bonus pins completion_status='incomplete' on the server, so the generic
        // DB lookup below always returns false. No client-side bonus override needed.
        // Extra practice map: af_get_tasks_practice completion_status only (no session/prefs logic).
        if (mapType == "optional") {
            return taskTitle.isNotEmpty() && dbCompletionByTitle?.get(taskTitle) == true
        }
        if (dbCompletionByTitle != null && taskTitle.isNotEmpty() && dbCompletionByTitle.containsKey(taskTitle)) {
            return dbCompletionByTitle[taskTitle] == true
        }
        if (baseTaskId.startsWith("checklist_")) {
            return completedTasksMap[taskTitle] == true
        }
        if (baseTaskId == "diagramLabeler" && !task.url.isNullOrEmpty()) {
            val finalUrl = gameModeUrlForDiagramCheck(
                task.url,
                task.easydays,
                task.harddays,
                task.extremedays
            )
            if (finalUrl.contains("diagram=")) {
                val diagramParam = finalUrl.substringAfter("diagram=").substringBefore("&").substringBefore("#")
                if (diagramParam.isNotEmpty()) {
                    val uniqueTaskId = if (mapType == "required") {
                        "${baseTaskId}_$diagramParam"
                    } else {
                        "${mapType}_${baseTaskId}_$diagramParam"
                    }
                    if (completedTasksMap[uniqueTaskId] == true) return true
                }
            }
        }
        return completedTasksMap[taskTitle] == true
    }

    /** Same rules as [TrainingMapActivity.getGameModeUrl] for diagram= parameter extraction. */
    private fun gameModeUrlForDiagramCheck(
        originalUrl: String,
        easydays: String?,
        harddays: String?,
        extremedays: String?
    ): String {
        val uri = Uri.parse(originalUrl)
        val originalMode = uri.getQueryParameter("mode")
        val hasDayFields = !easydays.isNullOrEmpty() || !harddays.isNullOrEmpty() || !extremedays.isNullOrEmpty()
        if (!hasDayFields) {
            val mode = originalMode ?: "hard"
            val builder = uri.buildUpon()
            builder.clearQuery()
            uri.queryParameterNames.forEach { name ->
                if (name != "mode") {
                    builder.appendQueryParameter(name, uri.getQueryParameter(name))
                }
            }
            builder.appendQueryParameter("mode", mode)
            return builder.build().toString()
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
        var mode = originalMode ?: "hard"
        if (!easydays.isNullOrEmpty() && easydays.split(",").contains(todayShort)) {
            mode = "easy"
        } else if (!harddays.isNullOrEmpty() && harddays.split(",").contains(todayShort)) {
            mode = "hard"
        } else if (!extremedays.isNullOrEmpty() && extremedays.split(",").contains(todayShort)) {
            mode = "extreme"
        }
        val builder = uri.buildUpon()
        builder.clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name != "mode") {
                builder.appendQueryParameter(name, uri.getQueryParameter(name))
            }
        }
        builder.appendQueryParameter("mode", mode)
        return builder.build().toString()
    }
}
