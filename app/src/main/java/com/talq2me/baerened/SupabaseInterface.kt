package com.talq2me.baerened

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

/**
 * PostgREST RPC client for Supabase (OkHttp): user_data fetch and column upserts, daily reset, task and settings
 * RPCs, devices, image uploads. Used from activities and managers; DB is source of truth via RPCs.
 */
open class SupabaseInterface {

    data class RewardSpinnerItem(
        val id: Int,
        val name: String,
        val percent: Int
    )

    data class DailyPrizeResult(
        val prizeUnlocked: String?,
        val newlyUnlocked: Boolean,
        val eligible: Boolean,
        /** Present when SQL returns an `error` field (e.g. no reward rows). */
        val serverError: String? = null
    )

    companion object {
        private const val TAG = "SupabaseInterface"
    }

    private val gson = Gson()
    private val client = OkHttpClient()
    
    /** OkHttpClient for shared HTTP stack when needed. */
    fun getClient(): OkHttpClient = client

    /**
     * Gets Supabase URL from BuildConfig
     */
    fun getSupabaseUrl(): String {
        return try {
            BuildConfig.SUPABASE_URL.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase URL from BuildConfig", e)
            ""
        }
    }

    /**
     * Gets Supabase API key from BuildConfig
     */
    fun getSupabaseKey(): String {
        return try {
            BuildConfig.SUPABASE_KEY.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase key from BuildConfig", e)
            ""
        }
    }

    /**
     * Checks if Supabase is configured
     */
    fun isConfigured(): Boolean {
        val url = getSupabaseUrl()
        val key = getSupabaseKey()
        return url.isNotBlank() && key.isNotBlank()
    }

    private fun rpcCandidates(rpcName: String): List<String> = listOf(rpcName)

    /** POST RPC and return response body (for jsonb/scalar results). */
    private suspend fun invokeRpcPostReadBody(rpcName: String, jsonBody: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Supabase not configured"))
        var lastError: Exception? = null
        for (candidate in rpcCandidates(rpcName)) {
            try {
                val url = "${getSupabaseUrl()}/rest/v1/rpc/$candidate"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .addHeader("Content-Type", "application/json")
                    .build()
                val response = client.newCall(request).execute()
                val raw = response.body?.string() ?: ""
                response.close()
                if (!response.isSuccessful) {
                    lastError = Exception("$candidate failed: ${response.code} $raw")
                    continue
                }
                return@withContext Result.success(raw.trim())
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(lastError ?: Exception("$rpcName failed"))
    }

    /** Postgres `RETURNS text` via PostgREST is a JSON string literal. */
    private fun decodeRpcJsonText(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t == "null") return null
        return try {
            val el = JsonParser.parseString(t)
            when {
                el.isJsonNull -> null
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Current time as now() converted to America/Toronto. System now() is UTC; we format in America/Toronto for the DB.
     * Format: yyyy-MM-dd HH:mm:ss.SSS (no timezone suffix).
     */
    fun generateESTTimestamp(): String {
        val estTimeZone = TimeZone.getTimeZone("America/Toronto")
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        dateFormat.timeZone = estTimeZone
        return dateFormat.format(now)
    }

    /**
     * Fetches user_data row via RPC (af_get_user_data).
     */
    suspend fun downloadUserData(profile: String): Result<DbUserData?> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }
        try {
            val body = """{"p_profile":"${profile.escapeJson()}"}"""
            val rawResult = invokeRpcPostReadBody("af_get_user_data", body)
            if (rawResult.isFailure) {
                return@withContext Result.failure(rawResult.exceptionOrNull() ?: Exception("RPC failed"))
            }
            val responseBody = rawResult.getOrNull()?.trim() ?: "null"
            if (responseBody == "null" || responseBody.isEmpty()) {
                return@withContext Result.success(null)
            }
            val fixedResponseBody = try {
                fixJsonbStringFields(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Error fixing JSONB fields, using original response: ${e.message}")
                responseBody
            }
            val userData = try {
                gson.fromJson(fixedResponseBody, DbUserData::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing af_get_user_data response (first 500): ${fixedResponseBody.take(500)}")
                return@withContext Result.failure(e)
            }
            Result.success(userData)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading from cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Calls Postgres function af_daily_reset(p_profile) which, for the given profile's user_data row
     * where last_reset date (EST) is not today (EST), blanks required_tasks, checklist_items,
     * practice_tasks, berries_earned, banked_mins, chores and sets last_reset/last_updated to now() EST.
     * Call before fetching user_data so screens always see reset-applied data.
     */
    suspend fun invokeAfDailyReset(profile: String): Result<Unit> =
        invokeRpc("af_daily_reset", """{"p_profile":"$profile"}""")

    /** POST to an RPC with body {"p_profile":"<profile>"}. Logs and returns failure on non-2xx. */
    private suspend fun invokeRpcProfileOnly(rpcName: String, profile: String): Result<Unit> =
        invokeRpc(rpcName, """{"p_profile":"$profile"}""")

    /**
     * Fetches profile config JSON from GitHub Pages (source of truth).
     * Returns parsed JsonObject to pass directly into DB RPCs as p_config_json.
     */
    private suspend fun fetchProfileConfigFromGitHubPages(profile: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        if (profile.isBlank()) {
            return@withContext Result.failure(Exception("Profile is blank"))
        }
        try {
            val url = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/config/${profile}_config.json?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(url)
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("GitHub Pages config fetch failed for $profile: ${response.code}")
                    )
                }
                val parsed = JsonParser.parseString(raw)
                if (!parsed.isJsonObject) {
                    return@withContext Result.failure(Exception("GitHub Pages config is not a JSON object for $profile"))
                }
                Result.success(parsed.asJsonObject)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Calls config-update RPC with explicit GitHub Pages JSON payload.
     * This avoids server-side silent no-op when DB http_get returns warnings.
     */
    private suspend fun invokeRpcProfileWithConfigJson(rpcName: String, profile: String): Result<Unit> {
        val config = fetchProfileConfigFromGitHubPages(profile).getOrElse { err ->
            return Result.failure(Exception("Could not fetch GitHub Pages config for $profile: ${err.message}"))
        }
        val body = JsonObject().apply {
            addProperty("p_profile", profile)
            add("p_config_json", config)
        }
        return invokeRpc(rpcName, gson.toJson(body))
    }

    /**
     * Fetches chores JSON array from GitHub Pages.
     */
    private suspend fun fetchChoresJsonFromGitHubPages(): Result<com.google.gson.JsonElement> = withContext(Dispatchers.IO) {
        try {
            val url = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/config/chores.json?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(url)
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("GitHub Pages chores fetch failed: ${response.code}"))
                }
                val parsed = JsonParser.parseString(raw)
                if (!parsed.isJsonArray) {
                    return@withContext Result.failure(Exception("GitHub Pages chores payload is not a JSON array"))
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Canonical dbMaster RPC names (see sql/af_update_*_from_config.sql on Supabase). */
    suspend fun invokeAfUpdateRequiredTasksFromConfig(profile: String): Result<Unit> =
        invokeRpcProfileWithConfigJson("af_update_tasks_from_config_required", profile)

    suspend fun invokeAfUpdatePracticeTasksFromConfig(profile: String): Result<Unit> =
        invokeRpcProfileWithConfigJson("af_update_tasks_from_config_practice", profile)

    suspend fun invokeAfUpdateBonusTasksFromConfig(profile: String): Result<Unit> =
        invokeRpcProfileWithConfigJson("af_update_tasks_from_config_bonus", profile)

    suspend fun invokeAfUpdateChecklistItemsFromConfig(profile: String): Result<Unit> =
        invokeRpcProfileWithConfigJson("af_update_tasks_from_config_checklist_items", profile)

    suspend fun invokeAfUpdatePhotoChoresFromConfig(profile: String): Result<Unit> =
        invokeRpcProfileWithConfigJson("af_update_tasks_from_config_photo_chores", profile)

    suspend fun invokeAfUpdateChoresFromGitHub(profile: String): Result<Unit> {
        val choresJson = fetchChoresJsonFromGitHubPages().getOrElse { err ->
            return Result.failure(Exception("Could not fetch GitHub Pages chores.json: ${err.message}"))
        }
        val body = JsonObject().apply {
            addProperty("p_profile", profile)
            add("p_chores_json", choresJson)
        }
        return invokeRpc("af_update_tasks_from_config_chores", gson.toJson(body))
    }

    /** @deprecated Prefer [invokeAfUpdateRequiredTasksFromConfig]; delegates to the same RPC. */
    suspend fun invokeAfRequiredTasksFromConfig(profile: String): Result<Unit> =
        invokeAfUpdateRequiredTasksFromConfig(profile)

    suspend fun invokeAfPracticeTasksFromConfig(profile: String): Result<Unit> =
        invokeAfUpdatePracticeTasksFromConfig(profile)

    suspend fun invokeAfBonusTasksFromConfig(profile: String): Result<Unit> =
        invokeAfUpdateBonusTasksFromConfig(profile)

    suspend fun invokeAfChecklistItemsFromConfig(profile: String): Result<Unit> =
        invokeAfUpdateChecklistItemsFromConfig(profile)

    suspend fun invokeAfChoresFromGitHub(profile: String): Result<Unit> =
        invokeAfUpdateChoresFromGitHub(profile)

    /**
     * Battle Hub summary from DB only (see sql/af_get_battle_hub_counts.sql).
     * Keys: possible_stars, berries_earned, banked_mins, coins_earned, pokemon_unlocked,
     * kid_bank_balance, reward_time_expiry (nullable), prize_unlocked (nullable).
     */
    suspend fun invokeAfGetBattleHubCounts(profile: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        val raw = invokeRpcPostReadBody("af_get_battle_hub_counts", """{"p_profile":"$profile"}""")
            .getOrElse { return@withContext Result.failure(it) }
        val el = JsonParser.parseString(raw.trim())
        if (el.isJsonObject) Result.success(el.asJsonObject)
        else Result.failure(Exception("af_get_battle_hub_counts: expected JSON object"))
    }

    /** Stable required+checklist rows (current impl routed in SQL). */
    suspend fun invokeAfGetRequiredTasksRows(profile: String): Result<JsonArray> =
        invokeRpcProfileReturningJsonArray("af_get_tasks_required", profile)

    /** Stable optional/practice rows (current impl routed in SQL). */
    suspend fun invokeAfGetPracticeTasksRows(profile: String): Result<JsonArray> =
        invokeRpcProfileReturningJsonArray("af_get_tasks_practice", profile)

    /** Stable bonus rows (current impl routed in SQL). */
    suspend fun invokeAfGetBonusTasksRows(profile: String): Result<JsonArray> =
        invokeRpcProfileReturningJsonArray("af_get_tasks_bonus", profile)

    /** Today's photo-chore gym rows (current impl routed in SQL). */
    suspend fun invokeAfGetPhotoChoreTasksRows(profile: String): Result<JsonArray> =
        invokeRpcProfileReturningJsonArray("af_get_tasks_photo_chores", profile)

    /**
     * Today's required-task progress. `allDone` is true iff every visible-today required task AND every visible-today
     * checklist item with stars>0 is complete/done (matches legacy [DailyProgressManager.areAllVisibleRequiredAndChecklistCompleteFromDb]).
     * `earnedBerries` is the sum of `berry_value` for those visible rows that are currently complete/done.
     * Source of truth: `af_get_required_progress_today` SQL — no Android logic.
     */
    data class RequiredProgressToday(val allDone: Boolean, val earnedBerries: Int)

    suspend fun invokeAfGetRequiredProgressToday(profile: String): Result<RequiredProgressToday> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Supabase not configured"))
        val body = """{"p_profile":"${profile.escapeJson()}"}"""
        val raw = invokeRpcPostReadBody("af_get_required_progress_today", body)
            .getOrElse { return@withContext Result.failure(it) }
        val parsed = JsonParser.parseString(raw.trim())
        if (!parsed.isJsonObject) {
            return@withContext Result.failure(Exception("af_get_required_progress_today: expected JSON object"))
        }
        val obj = parsed.asJsonObject
        val allDone = obj.get("all_done")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val earnedBerries = obj.get("earned_berries")?.takeUnless { it.isJsonNull }?.asInt ?: 0
        Result.success(RequiredProgressToday(allDone = allDone, earnedBerries = earnedBerries))
    }

    /**
     * Whether the Extra Practice (optional) map is unlocked right now (all visible required tasks complete).
     * Source of truth: `af_can_show_optional_map` SQL — no Android logic.
     */
    suspend fun invokeAfCanShowOptionalMap(profile: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Supabase not configured"))
        val body = """{"p_profile":"${profile.escapeJson()}"}"""
        val raw = invokeRpcPostReadBody("af_can_show_optional_map", body)
            .getOrElse { return@withContext Result.failure(it) }
        val parsed = JsonParser.parseString(raw.trim())
        return@withContext when {
            parsed.isJsonPrimitive -> Result.success(parsed.asJsonPrimitive.asBoolean)
            else -> Result.failure(Exception("af_can_show_optional_map: expected boolean"))
        }
    }

    /** Reads reward spinner rows from `reward_spinner` table. */
    suspend fun getRewardSpinnerItems(): Result<List<RewardSpinnerItem>> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Supabase not configured"))
        return@withContext try {
            val raw = invokeRpcPostReadBody("af_get_reward_spinner", "{}")
                .getOrElse { return@withContext Result.failure(it) }
            val parsed = JsonParser.parseString(raw)
            if (!parsed.isJsonArray) {
                return@withContext Result.failure(Exception("af_get_reward_spinner: expected JSON array"))
            }
            val arr = parsed.asJsonArray
            val items = arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val id = o.get("id")?.takeUnless { it.isJsonNull }?.asInt ?: return@mapNotNull null
                val name = o.get("name")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                val percent = o.get("percent")?.takeUnless { it.isJsonNull }?.asInt ?: return@mapNotNull null
                RewardSpinnerItem(id = id, name = name, percent = percent)
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Returns today's prize if already unlocked, otherwise unlocks one when profile is eligible. */
    suspend fun invokeAfGetOrUnlockDailyPrize(profile: String): Result<DailyPrizeResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Supabase not configured"))
        return@withContext try {
            val raw = invokeRpcPostReadBody(
                "af_get_or_unlock_daily_prize",
                """{"p_profile":"${profile.escapeJson()}"}"""
            ).getOrElse { return@withContext Result.failure(it) }
            val parsed = JsonParser.parseString(raw.trim())
            if (!parsed.isJsonObject) {
                return@withContext Result.failure(Exception("af_get_or_unlock_daily_prize: expected JSON object"))
            }
            val obj = parsed.asJsonObject
            val prize = obj.get("prize_unlocked")?.takeUnless { it.isJsonNull }?.asString
            val newly = obj.get("newly_unlocked")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
            val eligible = obj.get("eligible")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
            val serverError = obj.get("error")?.takeUnless { it.isJsonNull }?.asString
            Result.success(
                DailyPrizeResult(
                    prizeUnlocked = prize,
                    newlyUnlocked = newly,
                    eligible = eligible,
                    serverError = serverError
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun invokeRpcProfileReturningJsonArray(rpcName: String, profile: String): Result<JsonArray> =
        withContext(Dispatchers.IO) {
            val raw = invokeRpcPostReadBody(rpcName, """{"p_profile":"$profile"}""")
                .getOrElse { return@withContext Result.failure(it) }
            val el = JsonParser.parseString(raw.trim())
            if (el.isJsonArray) Result.success(el.asJsonArray)
            else Result.failure(Exception("$rpcName: expected JSON array"))
        }

    /** POST to an RPC with arbitrary JSON body. Logs and returns failure on non-2xx. */
    private suspend fun invokeRpc(rpcName: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Supabase not configured"))
        var lastError: Exception? = null
        for (candidate in rpcCandidates(rpcName)) {
            try {
                val url = "${getSupabaseUrl()}/rest/v1/rpc/$candidate"
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.close()
                    Log.d(TAG, "$candidate() invoked successfully")
                    return@withContext Result.success(Unit)
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    response.close()
                    lastError = Exception("$candidate failed: ${response.code} - $errorBody")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(lastError ?: Exception("$rpcName failed"))
    }

    /**
     * Unified completion RPC (`af_update_task_completion`). Routes to required / practice / bonus updaters in SQL
     * and returns earned stars. No client-side fallbacks: if the RPC fails, the failure propagates so the bug is
     * visible.
     */
    suspend fun invokeAfUpdateTaskCompletion(
        profile: String,
        taskTitle: String,
        sectionId: String?,
        stars: Int?,
        correct: Int? = null,
        incorrect: Int? = null,
        questionsAnswered: Int? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        val normalizedSection =
            sectionId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: "optional"
        val obj = JsonObject().apply {
            addProperty("p_profile", profile)
            addProperty("p_task_title", taskTitle)
            addProperty("p_section_id", normalizedSection)
            if (stars != null) addProperty("p_stars", stars)
            if (correct != null) addProperty("p_correct", correct)
            if (incorrect != null) addProperty("p_incorrect", incorrect)
            if (questionsAnswered != null) addProperty("p_questions_answered", questionsAnswered)
        }
        fun parseEarnedStarsBody(rawBody: String): Result<Int> = try {
            val t = rawBody.trim()
            if (t.isEmpty() || t == "null") Result.success(0)
            else {
                val parsed = JsonParser.parseString(t)
                if (parsed.isJsonNull) Result.success(0)
                else Result.success(parsed.asInt)
            }
        } catch (e: Exception) {
            Result.failure(Exception("af_update_task_completion: invalid return payload: ${e.message}"))
        }

        invokeRpcPostReadBody("af_update_task_completion", gson.toJson(obj)).fold(
            onSuccess = { raw -> parseEarnedStarsBody(raw) },
            onFailure = { err -> Result.failure(err) }
        )
    }

    /** Calls af_update_tasks_checklist_items; DB updates item and berries_earned/banked_mins. */
    suspend fun invokeAfUpdateChecklistItem(profile: String, itemLabel: String, done: Boolean): Result<Unit> {
        val body = """{"p_profile":"${profile.escapeJson()}", "p_item_label":"${itemLabel.escapeJson()}", "p_done":$done}"""
        return invokeRpc("af_update_tasks_checklist_items", body)
    }

    /** Calls af_update_tasks_photo_chores; marks a photo chore complete (no cash/berries). */
    suspend fun invokeAfUpdatePhotoChore(profile: String, choreId: String): Result<Unit> {
        val body = """{"p_profile":"${profile.escapeJson()}", "p_chore_id":"${choreId.escapeJson()}"}"""
        return invokeRpc("af_update_tasks_photo_chores", body)
    }

    /** Calls af_update_tasks_chores; DB updates chore and coins_earned. */
    suspend fun invokeAfUpdateChore(profile: String, choreId: Int, done: Boolean): Result<Unit> {
        val body = """{"p_profile":"${profile.escapeJson()}", "p_chore_id":$choreId, "p_done":$done}"""
        return invokeRpc("af_update_tasks_chores", body)
    }

    /**
     * Calls af_update_berries_banked — single DB path for berries_earned / banked_mins / last_updated.
     * If [bankedMins] is null, omits p_banked_mins so the DB leaves banked_mins unchanged (see SQL).
     */
    suspend fun invokeAfUpdateBerriesBanked(
        profile: String,
        berriesEarned: Int,
        bankedMins: Int? = null
    ): Result<Unit> {
        val ensureResult = ensureUserDataProfileExists(profile)
        if (ensureResult.isFailure) return ensureResult
        val obj = JsonObject().apply {
            addProperty("p_profile", profile)
            addProperty("p_berries_earned", berriesEarned)
            if (bankedMins != null) {
                addProperty("p_banked_mins", bankedMins)
            } else {
                add("p_banked_mins", JsonNull.INSTANCE)
            }
        }
        return invokeRpc("af_update_berries_banked", Gson().toJson(obj))
    }

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    /** Calls af_update_game_index; params only (no JSON). */
    suspend fun invokeAfUpdateGameIndex(profile: String, gameKey: String, index: Int): Result<Unit> {
        val body = """{"p_profile":"${profile.escapeJson()}", "p_game_key":"${gameKey.escapeJson()}", "p_index":$index}"""
        return invokeRpc("af_update_game_index", body)
    }

    /** Calls af_update_pokemon_unlocked; params only (no JSON). */
    suspend fun invokeAfUpdatePokemonUnlocked(profile: String, pokemonUnlocked: Int): Result<Unit> {
        val body = """{"p_profile":"${profile.escapeJson()}", "p_pokemon_unlocked":$pokemonUnlocked}"""
        return invokeRpc("af_update_pokemon_unlocked", body)
    }

    /**
     * Activates banked reward time: calls af_reward_time_use(p_profile).
     * DB sets reward_time_expiry = now + banked_mins and banked_mins = 0 (see 000Requirements.md).
     * Use this when the child presses "Use Reward Time" — not af_reward_time_add.
     */
    suspend fun invokeUseRewardTime(profile: String): Result<Unit> {
        val ensureResult = ensureUserDataProfileExists(profile)
        if (ensureResult.isFailure) return ensureResult
        val body = """{"p_profile":"${profile.escapeJson()}"}"""
        return invokeRpc("af_reward_time_use", body)
    }

    /** Adds reward minutes using DB function af_reward_time_add(p_profile, p_minutes). Parent/add-time path. */
    suspend fun invokeAddRewardTime(profile: String, minutes: Int): Result<Unit> {
        val ensureResult = ensureUserDataProfileExists(profile)
        if (ensureResult.isFailure) return ensureResult

        val body = """{"p_profile":"${profile.escapeJson()}", "p_minutes":$minutes}"""
        val rpcResult = invokeRpc("af_reward_time_add", body)
        if (rpcResult.isSuccess) return rpcResult

        // Fallback path for environments where af_reward_time_add RPC has not been deployed yet.
        return withContext(Dispatchers.IO) {
            try {
                val hub = invokeAfGetBattleHubCounts(profile).getOrElse { err ->
                    return@withContext Result.failure(Exception("af_reward_time_add RPC failed and fallback read failed: ${err.message}"))
                }
                val current = hub.get("banked_mins")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val updated = (current + minutes).coerceAtLeast(0)
                val upsertResult = upsertUserDataColumns(
                    profile,
                    mapOf(
                        "banked_mins" to updated,
                        "last_updated" to generateESTTimestamp()
                    )
                )
                if (upsertResult.isSuccess) {
                    Log.w(TAG, "Used fallback af_reward_time_add path for profile=$profile, minutes=$minutes")
                }
                upsertResult
            } catch (e: Exception) {
                Result.failure(Exception("af_reward_time_add RPC failed and fallback failed: ${e.message}"))
            }
        }
    }

    /**
     * Ensures there is a user_data row for the profile so RPC calls do not silently affect 0 rows.
     */
    private suspend fun ensureUserDataProfileExists(profile: String): Result<Unit> =
        invokeRpc("af_insert_user_data_profile", """{"p_profile":"${profile.escapeJson()}"}""")

    /**
     * ONLINE-ONLY: upsert selected user_data columns for the profile (partial row update via JSON map).
     * Used for authoritative writes (e.g. restore chores from GitHub, update single task completion).
     * No timestamp conflict check.
     */
    suspend fun upsertUserDataColumns(profile: String, columns: Map<String, Any>): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        try {
            val wrap = JsonObject().apply {
                addProperty("p_profile", profile)
                add("p_columns", gson.toJsonTree(columns))
            }
            invokeRpc("af_upsert_user_data_columns", gson.toJson(wrap))
        } catch (e: Exception) {
            Log.e(TAG, "upsertUserDataColumns error", e)
            Result.failure(e)
        }
    }

    suspend fun invokeAfGetSettingsRow(): Result<JsonObject?> = withContext(Dispatchers.IO) {
        val raw = invokeRpcPostReadBody("af_get_settings_row", "{}").getOrElse { return@withContext Result.failure(it) }
        if (raw == "null" || raw.isBlank()) return@withContext Result.success(null)
        val el = try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        if (!el.isJsonObject) return@withContext Result.failure(Exception("af_get_settings_row: expected object"))
        Result.success(el.asJsonObject)
    }

    suspend fun invokeAfUpsertSettingsRow(parentEmail: String?, pin: String?, aggressiveCleanup: Boolean? = null): Result<Unit> {
        val obj = JsonObject().apply {
            if (parentEmail != null) addProperty("p_parent_email", parentEmail) else add("p_parent_email", JsonNull.INSTANCE)
            if (pin != null) addProperty("p_pin", pin) else add("p_pin", JsonNull.INSTANCE)
            if (aggressiveCleanup != null) addProperty("p_aggressive_cleanup", aggressiveCleanup)
            else add("p_aggressive_cleanup", JsonNull.INSTANCE)
        }
        return invokeRpc("af_upsert_settings_row", gson.toJson(obj))
    }

    suspend fun invokeAfGetDeviceRow(deviceId: String): Result<JsonObject?> = withContext(Dispatchers.IO) {
        val body = """{"p_device_id":"${deviceId.escapeJson()}"}"""
        val raw = invokeRpcPostReadBody("af_get_device_row", body).getOrElse { return@withContext Result.failure(it) }
        if (raw == "null" || raw.isBlank()) return@withContext Result.success(null)
        val el = try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        if (!el.isJsonObject) return@withContext Result.failure(Exception("af_get_device_row: expected object"))
        Result.success(el.asJsonObject)
    }

    suspend fun invokeAfUpsertDevice(deviceId: String, deviceName: String, activeProfile: String, lastUpdated: String? = null): Result<Unit> {
        val obj = JsonObject().apply {
            addProperty("p_device_id", deviceId)
            addProperty("p_device_name", deviceName)
            addProperty("p_active_profile", activeProfile)
            if (lastUpdated != null) addProperty("p_last_updated", lastUpdated) else add("p_last_updated", JsonNull.INSTANCE)
        }
        return invokeRpc("af_upsert_device", gson.toJson(obj))
    }

    suspend fun invokeAfUpsertImageUpload(profile: String, task: String, imageBase64: String): Result<Unit> {
        val obj = JsonObject().apply {
            addProperty("p_profile", profile)
            addProperty("p_task", task)
            addProperty("p_image", imageBase64)
        }
        return invokeRpc("af_upsert_image_upload", gson.toJson(obj))
    }

    suspend fun invokeAfDeleteImageUploadsIlike(profile: String, taskPattern: String): Result<Int> {
        val obj = JsonObject().apply {
            addProperty("p_profile", profile)
            addProperty("p_task_pattern", taskPattern)
        }
        val raw = invokeRpcPostReadBody("af_delete_image_uploads_ilike", gson.toJson(obj)).getOrElse { return Result.failure(it) }
        return try {
            val n = JsonParser.parseString(raw).asJsonPrimitive.asInt
            Result.success(n)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun invokeAfGetImageUploadId(profile: String, taskPattern: String): Result<Long?> {
        val obj = JsonObject().apply {
            addProperty("p_profile", profile)
            addProperty("p_task_pattern", taskPattern)
        }
        val raw = invokeRpcPostReadBody("af_get_image_upload_id", gson.toJson(obj)).getOrElse { return Result.failure(it) }
        if (raw == "null" || raw.isBlank()) return Result.success(null)
        return try {
            val id = JsonParser.parseString(raw).asJsonPrimitive.asLong
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun invokeAfDeleteImageUploadById(id: Long): Result<Unit> {
        val body = """{"p_id":$id}"""
        return invokeRpc("af_delete_image_upload_by_id", body)
    }
    
    /**
     * Fixes JSONB fields that are returned as JSON strings instead of objects.
     * Supabase sometimes returns JSONB columns as escaped JSON strings, which causes
     * Gson parsing errors. This function converts those string fields back to objects.
     * 
     * @param jsonResponse The raw JSON response from Supabase
     * @return Fixed JSON string with JSONB fields converted from strings to objects
     */
    private fun fixJsonbStringFields(jsonResponse: String): String {
        try {
            val jsonElement = JsonParser.parseString(jsonResponse)
            
            // If it's an array (list of user_data records)
            if (jsonElement.isJsonArray) {
                val jsonArray = jsonElement.asJsonArray
                jsonArray.forEach { element ->
                    if (element.isJsonObject) {
                        fixJsonbFieldsInObject(element.asJsonObject)
                    }
                }
            } else if (jsonElement.isJsonObject) {
                // If it's a single object
                fixJsonbFieldsInObject(jsonElement.asJsonObject)
            }
            
            return gson.toJson(jsonElement)
        } catch (e: Exception) {
            Log.w(TAG, "Error fixing JSONB string fields, returning original response: ${e.message}")
            return jsonResponse
        }
    }
    
    /**
     * Recursively fixes JSONB fields in a JsonObject that are stored as strings.
     * Fields that should be objects: required_tasks, practice_tasks, checklist_items, game_indices
     * Fields that should be arrays: chores
     */
    private fun fixJsonbFieldsInObject(obj: JsonObject) {
        // JSONB fields that should be objects (not strings)
        val jsonbObjectFields = listOf("required_tasks", "practice_tasks", "bonus_tasks", "checklist_items", "game_indices", "photo_chores")
        jsonbObjectFields.forEach { fieldName ->
            val field = obj.get(fieldName)
            if (field != null) {
                if (field.isJsonPrimitive && field.asJsonPrimitive.isString) {
                    try {
                        val stringValue = field.asString
                        if (stringValue.isNotBlank() && stringValue != "null") {
                            val parsedJson = JsonParser.parseString(stringValue)
                            obj.add(fieldName, parsedJson)
                            Log.d(TAG, "Fixed JSONB field '$fieldName' from string to object")
                        } else {
                            obj.add(fieldName, JsonObject())
                            Log.d(TAG, "Fixed JSONB field '$fieldName' from empty/null string to empty object")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing JSONB field '$fieldName' as JSON, setting to empty object: ${e.message}")
                        obj.add(fieldName, JsonObject())
                    }
                } else if (field.isJsonNull) {
                    obj.add(fieldName, JsonObject())
                    Log.d(TAG, "Fixed JSONB field '$fieldName' from null to empty object")
                }
            }
        }
        // JSONB fields that should be arrays (chores)
        val jsonbArrayFields = listOf("chores")
        jsonbArrayFields.forEach { fieldName ->
            val field = obj.get(fieldName)
            if (field != null) {
                if (field.isJsonPrimitive && field.asJsonPrimitive.isString) {
                    try {
                        val stringValue = field.asString
                        if (stringValue.isNotBlank() && stringValue != "null") {
                            val parsedJson = JsonParser.parseString(stringValue)
                            obj.add(fieldName, parsedJson)
                            Log.d(TAG, "Fixed JSONB field '$fieldName' from string to array")
                        } else {
                            obj.add(fieldName, JsonArray())
                            Log.d(TAG, "Fixed JSONB field '$fieldName' from empty/null string to empty array")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing JSONB field '$fieldName' as JSON, setting to empty array: ${e.message}")
                        obj.add(fieldName, JsonArray())
                    }
                } else if (field.isJsonNull) {
                    obj.add(fieldName, JsonArray())
                    Log.d(TAG, "Fixed JSONB field '$fieldName' from null to empty array")
                }
            }
        }
        sanitizeGameIndicesForGson(obj)
    }

    /**
     * [DbUserData.gameIndices] is Map<String, Int>. Postgres may store metadata strings in the same
     * JSONB object (e.g. `_spellingPoolAdvancedOn` from af_maybe_advance_spelling_pools).
     */
    private fun sanitizeGameIndicesForGson(obj: JsonObject) {
        val field = obj.get("game_indices") ?: return
        if (!field.isJsonObject) return
        val indices = field.asJsonObject
        val sanitized = JsonObject()
        indices.entrySet().forEach { (key, value) ->
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber ->
                    sanitized.add(key, value)
                value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                    value.asString.toIntOrNull()?.let { sanitized.addProperty(key, it) }
            }
        }
        if (sanitized.size() != indices.size()) {
            Log.d(TAG, "Sanitized game_indices: kept ${sanitized.size()} of ${indices.size()} entries")
        }
        obj.add("game_indices", sanitized)
    }
}
