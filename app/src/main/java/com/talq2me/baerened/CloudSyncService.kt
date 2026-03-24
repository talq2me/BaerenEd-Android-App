package com.talq2me.baerened

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles HTTP sync operations with Supabase
 * Extracted from CloudStorageManager for better separation of concerns
 * 
 * CRITICAL REQUIREMENT: ALL sync operations MUST compare cloud and local timestamps before updating.
 * Never update cloud data without first checking if cloud timestamp is newer than local timestamp.
 * This prevents overwriting newer cloud data with older local data.
 * 
 * See CLOUD_SYNC_TIMESTAMP_REQUIREMENT.md for full documentation.
 */
class CloudSyncService {
    
    companion object {
        private const val TAG = "CloudSyncService"
    }

    private val gson = Gson()
    private val client = OkHttpClient()
    
    /**
     * Gets the OkHttpClient instance (for use by CloudStorageManager)
     */
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
     * Uploads user data to cloud
     */
    /**
     * Uploads user data to cloud
     * CRITICAL: Checks cloud timestamp before updating - only updates if local is newer
     */
    suspend fun uploadUserData(data: CloudUserData): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            // CRITICAL: Check cloud timestamp first before updating
            val checkUrl = "${getSupabaseUrl()}/rest/v1/user_data?profile=eq.${data.profile}&select=last_updated"
            val checkRequest = Request.Builder()
                .url(checkUrl)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()

            val checkResponse = client.newCall(checkRequest).execute()
            val checkResponseBody = checkResponse.body?.string() ?: "[]"
            checkResponse.close()

            if (checkResponseBody != "[]" && checkResponseBody.isNotBlank()) {
                try {
                    val dataList = gson.fromJson(checkResponseBody, object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type) as? List<Map<String, Any>>
                    val userData = dataList?.firstOrNull()
                    val cloudTimestamp = userData?.get("last_updated") as? String
                    
                    if (cloudTimestamp != null && data.lastUpdated != null) {
                        val cloudTime = parseTimestamp(cloudTimestamp)
                        val localTime = parseTimestamp(data.lastUpdated)
                        val timeDiff = cloudTime - localTime // Positive = cloud newer, negative = local newer
                        val cloudIsNewer = timeDiff > 0
                        Log.d(TAG, "CRITICAL: uploadUserData timestamp check: cloudIsNewer=$cloudIsNewer, timeDiff=${timeDiff}ms (cloud=$cloudTimestamp, local=${data.lastUpdated})")
                        // CRITICAL: Newest dataset always wins - if cloud is newer, don't overwrite
                        if (cloudIsNewer) {
                            Log.d(TAG, "CRITICAL: Cloud user_data timestamp is newer (${timeDiff}ms), not updating cloud - this means upload is being blocked!")
                            return@withContext Result.success(Unit) // Cloud is newer, don't overwrite
                        } else {
                            Log.d(TAG, "CRITICAL: Local timestamp is newer or equal (${-timeDiff}ms), proceeding with upload")
                        }
                    } else if (cloudTimestamp != null && data.lastUpdated == null) {
                        Log.d(TAG, "CRITICAL: Cloud has timestamp but local doesn't, not updating cloud")
                        return@withContext Result.success(Unit) // Cloud has timestamp, don't overwrite
                    } else {
                        Log.d(TAG, "CRITICAL: No cloud timestamp or no local timestamp, proceeding with upload")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing cloud timestamp, proceeding with upload", e)
                }
            }
            
            // CRITICAL: Do NOT send last_reset on progress uploads. last_reset is only set by runDailyResetInDb (or "Reset all progress").
            // Sending prefs' profile_last_reset (often null or stale) would overwrite the DB and cause the next load to run a full reset.
            val tree = gson.toJsonTree(data)
            if (tree.isJsonObject) {
                tree.asJsonObject.remove("last_reset")
            }
            val json = gson.toJson(tree)
            
            // CRITICAL: Log what we're about to upload
            Log.d(TAG, "CRITICAL: About to upload data for profile: ${data.profile}")
            Log.d(TAG, "  requiredTasks size: ${data.requiredTasks.size}")
            Log.d(TAG, "  practiceTasks size: ${data.practiceTasks.size}")
            Log.d(TAG, "  checklistItems size: ${data.checklistItems.size}")
            Log.d(TAG, "  gameIndices size: ${data.gameIndices.size}")
            Log.d(TAG, "  berriesEarned: ${data.berriesEarned}, bankedMins: ${data.bankedMins}")
            Log.d(TAG, "  lastUpdated: ${data.lastUpdated}")
            
            // CRITICAL: Log Math task specifically
            val mathTask = data.requiredTasks["Math"]
            if (mathTask != null) {
                Log.d(TAG, "CRITICAL: Math task in upload data - status: ${mathTask.status}, correct: ${mathTask.correct}, incorrect: ${mathTask.incorrect}, questions: ${mathTask.questions}")
            } else {
                Log.d(TAG, "CRITICAL: Math task NOT FOUND in upload requiredTasks! Available tasks: ${data.requiredTasks.keys.take(10).joinToString()}")
            }
            
            // Log a sample of the JSON (first 2000 chars to see the structure)
            val jsonPreview = if (json.length > 2000) json.take(2000) + "..." else json
            Log.d(TAG, "  JSON preview: $jsonPreview")
            
            val baseUrl = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            // First check if record exists
            val recordCheckUrl = "$baseUrl?profile=eq.${data.profile}&select=profile"
            val recordCheckRequest = Request.Builder()
                .url(recordCheckUrl)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()

            val recordCheckResponse = client.newCall(recordCheckRequest).execute()
            val recordCheckResponseBody = recordCheckResponse.body?.string() ?: "[]"
            recordCheckResponse.close()

            val recordExists = recordCheckResponseBody != "[]" && recordCheckResponseBody.isNotBlank()

            if (recordExists) {
                // Record exists, try UPDATE (PATCH)
                val patchUrl = "$baseUrl?profile=eq.${data.profile}"
                val patchRequest = Request.Builder()
                    .url(patchUrl)
                    .patch(requestBody)
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                val patchResponse = client.newCall(patchRequest).execute()

                if (patchResponse.isSuccessful) {
                    patchResponse.close()
                    return@withContext Result.success(Unit)
                } else {
                    val patchErrorBody = patchResponse.body?.string()
                    Log.e(TAG, "Upload failed (PATCH): ${patchResponse.code} - $patchErrorBody")
                    patchResponse.close()
                    return@withContext Result.failure(Exception("PATCH failed with code: ${patchResponse.code}"))
                }
            } else {
                // Record doesn't exist, try INSERT (POST)
                val postRequest = Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                val postResponse = client.newCall(postRequest).execute()

                if (postResponse.isSuccessful) {
                    postResponse.close()
                    return@withContext Result.success(Unit)
                } else {
                    val postErrorBody = postResponse.body?.string()
                    Log.e(TAG, "Upload failed (POST): ${postResponse.code} - $postErrorBody")
                    postResponse.close()
                    return@withContext Result.failure(Exception("POST failed with code: ${postResponse.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to cloud", e)
            Result.failure(e)
        }
    }
    
    /**
     * Compares two timestamps and returns:
     * - Positive value if timestamp1 is newer than timestamp2
     * - Negative value if timestamp1 is older than timestamp2
     * - Zero if they are equal
     */
    private fun compareTimestamps(timestamp1: String, timestamp2: String): Int {
        val time1 = parseTimestamp(timestamp1)
        val time2 = parseTimestamp(timestamp2)
        return time1.compareTo(time2)
    }
    
    /**
     * Parses timestamp string to milliseconds for comparison
     * All timestamps are stored in EST without timezone suffixes
     * Strips any timezone suffix (Z, +00:00, -05:00) and parses the base time as EST
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            // All timestamps should be EST without timezone suffix, but accept ISO variants defensively.
            // Strip timezone suffixes and normalize 'T' to space.
            var baseTimestamp = when {
                timestamp.endsWith("Z") -> timestamp.substring(0, timestamp.length - 1)
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
            Log.w(TAG, "Error parsing timestamp for comparison: $timestamp", e)
            0L
        }
    }

    /**
     * Downloads user data from cloud
     */
    suspend fun downloadUserData(profile: String): Result<CloudUserData?> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            val url = "${getSupabaseUrl()}/rest/v1/user_data?profile=eq.$profile&select=*"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "[]"
                
                // CRITICAL: Handle JSONB fields that might be returned as strings (double-encoded)
                // Supabase sometimes returns JSONB columns as JSON strings instead of objects
                Log.d(TAG, "Raw response body length: ${responseBody.length}")
                val fixedResponseBody = try {
                    fixJsonbStringFields(responseBody)
                } catch (e: Exception) {
                    Log.e(TAG, "Error fixing JSONB fields, using original response: ${e.message}")
                    responseBody
                }
                
                val dataList: List<CloudUserData> = try {
                    gson.fromJson(
                        fixedResponseBody,
                        object : TypeToken<List<CloudUserData>>() {}.type
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing response after fixing JSONB fields")
                    Log.e(TAG, "Response body (first 500 chars): ${fixedResponseBody.take(500)}")
                    throw e
                }

                val userData = dataList.firstOrNull()
                response.close()
                Result.success(userData)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to download from cloud: ${response.code} - $errorBody")
                response.close()
                Result.failure(Exception("Download failed: ${response.code} - $errorBody"))
            }
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
    suspend fun invokeAfDailyReset(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        try {
            val url = "${getSupabaseUrl()}/rest/v1/rpc/af_daily_reset"
            val body = """{"p_profile":"$profile"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.close()
                Log.d(TAG, "af_daily_reset() invoked successfully")
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "af_daily_reset() failed: ${response.code} - $errorBody")
                response.close()
                Result.failure(Exception("af_daily_reset failed: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "invokeAfDailyReset error", e)
            Result.failure(e)
        }
    }

    /** POST to an RPC with body {"p_profile":"<profile>"}. Logs and returns failure on non-2xx. */
    private suspend fun invokeRpcProfileOnly(rpcName: String, profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Supabase not configured"))
        try {
            val url = "${getSupabaseUrl()}/rest/v1/rpc/$rpcName"
            val body = """{"p_profile":"$profile"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.close()
                Log.d(TAG, "$rpcName() invoked successfully")
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.w(TAG, "$rpcName() failed: ${response.code} - $errorBody")
                response.close()
                Result.failure(Exception("$rpcName failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "$rpcName error", e)
            Result.failure(e)
        }
    }

    /** Calls af_required_tasks_from_config(p_profile); DB fetches config from GitHub and merges with existing. */
    suspend fun invokeAfRequiredTasksFromConfig(profile: String): Result<Unit> = invokeRpcProfileOnly("af_required_tasks_from_config", profile)

    /** Calls af_practice_tasks_from_config(p_profile); DB fetches config from GitHub and merges optional section into practice_tasks. */
    suspend fun invokeAfPracticeTasksFromConfig(profile: String): Result<Unit> = invokeRpcProfileOnly("af_practice_tasks_from_config", profile)

    /** Calls af_bonus_tasks_from_config(p_profile); DB fetches config from GitHub and merges bonus section into bonus_tasks. */
    suspend fun invokeAfBonusTasksFromConfig(profile: String): Result<Unit> = invokeRpcProfileOnly("af_bonus_tasks_from_config", profile)

    /** Calls af_checklist_items_from_config(p_profile); DB fetches config from GitHub and merges with existing. */
    suspend fun invokeAfChecklistItemsFromConfig(profile: String): Result<Unit> = invokeRpcProfileOnly("af_checklist_items_from_config", profile)

    /** Calls af_chores_from_github(p_profile); DB fetches chores.json from GitHub and merges with existing. */
    suspend fun invokeAfChoresFromGitHub(profile: String): Result<Unit> = invokeRpcProfileOnly("af_chores_from_github", profile)

    /** POST to an RPC with arbitrary JSON body. Logs and returns failure on non-2xx. */
    private suspend fun invokeRpc(rpcName: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Supabase not configured"))
        try {
            val url = "${getSupabaseUrl()}/rest/v1/rpc/$rpcName"
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
                Log.d(TAG, "$rpcName() invoked successfully")
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.w(TAG, "$rpcName() failed: ${response.code} - $errorBody")
                response.close()
                Result.failure(Exception("$rpcName failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "$rpcName error", e)
            Result.failure(e)
        }
    }

    /** Calls af_update_required_task; DB updates task and berries_earned/banked_mins. correct/incorrect/questions always sent (0 when null) so DB stores them. */
    suspend fun invokeAfUpdateRequiredTask(
        profile: String,
        taskTitle: String,
        status: String? = null,
        correct: Int? = null,
        incorrect: Int? = null,
        questions: Int? = null
    ): Result<Unit> {
        val obj = JsonObject().apply {
            addProperty("p_profile", profile)
            addProperty("p_task_title", taskTitle)
            status?.let { addProperty("p_status", it) }
            addProperty("p_correct", correct ?: 0)
            addProperty("p_incorrect", incorrect ?: 0)
            addProperty("p_questions", questions ?: 0)
        }
        return invokeRpc("af_update_required_task", Gson().toJson(obj))
    }

    /** Calls af_update_practice_task; DB updates task and berries_earned/banked_mins when p_stars provided. correct/incorrect/questions_answered always sent (0 when null). */
    suspend fun invokeAfUpdatePracticeTask(
        profile: String,
        taskTitle: String,
        timesCompleted: Int? = null,
        stars: Int? = null,
        correct: Int? = null,
        incorrect: Int? = null,
        questionsAnswered: Int? = null
    ): Result<Unit> {
        val obj = JsonObject().apply {
            addProperty("p_profile", profile)
            addProperty("p_task_title", taskTitle)
            timesCompleted?.let { addProperty("p_times_completed", it) }
            stars?.let { addProperty("p_stars", it) }
            addProperty("p_correct", correct ?: 0)
            addProperty("p_incorrect", incorrect ?: 0)
            addProperty("p_questions_answered", questionsAnswered ?: 0)
        }
        return invokeRpc("af_update_practice_task", Gson().toJson(obj))
    }

    /** Calls af_update_bonus_task; same as practice task but updates bonus_tasks column. */
    suspend fun invokeAfUpdateBonusTask(
        profile: String,
        taskTitle: String,
        timesCompleted: Int? = null,
        stars: Int? = null,
        correct: Int? = null,
        incorrect: Int? = null,
        questionsAnswered: Int? = null
    ): Result<Unit> {
        val obj = JsonObject().apply {
            addProperty("p_profile", profile)
            addProperty("p_task_title", taskTitle)
            timesCompleted?.let { addProperty("p_times_completed", it) }
            stars?.let { addProperty("p_stars", it) }
            addProperty("p_correct", correct ?: 0)
            addProperty("p_incorrect", incorrect ?: 0)
            addProperty("p_questions_answered", questionsAnswered ?: 0)
        }
        return invokeRpc("af_update_bonus_task", Gson().toJson(obj))
    }

    /** Calls af_update_checklist_item; DB updates item and berries_earned/banked_mins. */
    suspend fun invokeAfUpdateChecklistItem(profile: String, itemLabel: String, done: Boolean): Result<Unit> {
        val body = """{"p_profile":"${profile.escapeJson()}", "p_item_label":"${itemLabel.escapeJson()}", "p_done":$done}"""
        return invokeRpc("af_update_checklist_item", body)
    }

    /** Calls af_update_chore; DB updates chore and coins_earned. */
    suspend fun invokeAfUpdateChore(profile: String, choreId: Int, done: Boolean): Result<Unit> {
        val body = """{"p_profile":"${profile.escapeJson()}", "p_chore_id":$choreId, "p_done":$done}"""
        return invokeRpc("af_update_chore", body)
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
     * Activates banked reward time: calls use_reward_time(p_profile).
     * DB sets reward_time_expiry = now + banked_mins and banked_mins = 0 (see 000Requirements.md).
     * Use this when the child presses "Use Reward Time" — not add_reward_time.
     */
    suspend fun invokeUseRewardTime(profile: String): Result<Unit> {
        val ensureResult = ensureUserDataProfileExists(profile)
        if (ensureResult.isFailure) return ensureResult
        val body = """{"p_profile":"${profile.escapeJson()}"}"""
        return invokeRpc("use_reward_time", body)
    }

    /** Adds reward minutes using DB function add_reward_time(p_profile, p_minutes). Parent/add-time path. */
    suspend fun invokeAddRewardTime(profile: String, minutes: Int): Result<Unit> {
        val ensureResult = ensureUserDataProfileExists(profile)
        if (ensureResult.isFailure) return ensureResult

        val body = """{"p_profile":"${profile.escapeJson()}", "p_minutes":$minutes}"""
        val rpcResult = invokeRpc("add_reward_time", body)
        if (rpcResult.isSuccess) return rpcResult

        // Fallback path for environments where add_reward_time RPC has not been deployed yet.
        return withContext(Dispatchers.IO) {
            try {
                val url = "${getSupabaseUrl()}/rest/v1/user_data?profile=eq.${profile.escapeJson()}&select=banked_mins"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("apikey", getSupabaseKey())
                    .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "Unknown error"
                    response.close()
                    return@withContext Result.failure(Exception("add_reward_time RPC failed and fallback read failed: $err"))
                }
                val bodyText = response.body?.string() ?: "[]"
                response.close()
                val rows = gson.fromJson(bodyText, object : TypeToken<List<Map<String, Any?>>>() {}.type) as? List<Map<String, Any?>>
                val row = rows?.firstOrNull()
                val current = (row?.get("banked_mins") as? Number)?.toInt() ?: 0
                val updated = (current + minutes).coerceAtLeast(0)
                val patchResult = patchUserDataColumns(
                    profile,
                    mapOf(
                        "banked_mins" to updated,
                        "last_updated" to generateESTTimestamp()
                    )
                )
                if (patchResult.isSuccess) {
                    Log.w(TAG, "Used fallback add_reward_time path for profile=$profile, minutes=$minutes")
                }
                patchResult
            } catch (e: Exception) {
                Result.failure(Exception("add_reward_time RPC failed and fallback failed: ${e.message}"))
            }
        }
    }

    /**
     * Ensures there is a user_data row for the profile so RPC/PATCH calls do not silently affect 0 rows.
     */
    private suspend fun ensureUserDataProfileExists(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        try {
            val checkUrl = "${getSupabaseUrl()}/rest/v1/user_data?profile=eq.${profile.escapeJson()}&select=profile"
            val checkRequest = Request.Builder()
                .url(checkUrl)
                .get()
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .build()
            val checkResponse = client.newCall(checkRequest).execute()
            if (!checkResponse.isSuccessful) {
                val err = checkResponse.body?.string() ?: "Unknown error"
                checkResponse.close()
                return@withContext Result.failure(Exception("Profile existence check failed: $err"))
            }
            val checkBody = checkResponse.body?.string() ?: "[]"
            checkResponse.close()
            val exists = checkBody != "[]" && checkBody.isNotBlank()
            if (exists) return@withContext Result.success(Unit)

            val upsertJson = gson.toJson(
                mapOf(
                    "profile" to profile,
                    "banked_mins" to 0,
                    "last_updated" to generateESTTimestamp()
                )
            )
            val upsertRequest = Request.Builder()
                .url("${getSupabaseUrl()}/rest/v1/user_data")
                .post(upsertJson.toRequestBody("application/json".toMediaType()))
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .build()
            val upsertResponse = client.newCall(upsertRequest).execute()
            return@withContext if (upsertResponse.isSuccessful) {
                upsertResponse.close()
                Log.d(TAG, "Created missing user_data row for profile=$profile")
                Result.success(Unit)
            } else {
                val err = upsertResponse.body?.string() ?: "Unknown error"
                upsertResponse.close()
                Result.failure(Exception("Failed creating profile row ($profile): $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * ONLINE-ONLY: Runs daily reset in the DB. Blanks required_tasks, checklist_items, practice_tasks,
     * berries_earned, banked_mins, chores. Sets last_reset and last_updated to now() EST.
     * Does NOT change coins_earned, pokemon_unlocked, or game_indices.
     * Prefer invoking af_daily_reset() RPC before fetch; this remains for one-off reset (e.g. "Reset all progress").
     */
    suspend fun runDailyResetInDb(profile: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        try {
            val now = generateESTTimestamp()
            val payload = mapOf(
                "last_reset" to now,
                "last_updated" to now,
                "required_tasks" to emptyMap<String, Any>(),
                "checklist_items" to emptyMap<String, Any>(),
                "practice_tasks" to emptyMap<String, Any>(),
                "bonus_tasks" to emptyMap<String, Any>(),
                "berries_earned" to 0,
                "banked_mins" to 0,
                "chores" to emptyList<Any>()
            )
            val result = patchUserDataColumns(profile, payload)
            if (result.isSuccess) {
                Log.d(TAG, "runDailyResetInDb: success for profile $profile")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "runDailyResetInDb failed", e)
            Result.failure(e)
        }
    }

    /**
     * ONLINE-ONLY: PATCH only the given columns for the profile. Used for authoritative writes
     * (e.g. restore chores from GitHub, update single task completion). No timestamp conflict check.
     */
    suspend fun patchUserDataColumns(profile: String, columns: Map<String, Any>): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured"))
        }
        try {
            val json = gson.toJson(columns)
            val baseUrl = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())
            val updateUrl = "$baseUrl?profile=eq.$profile"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            val response = client.newCall(patchRequest).execute()
            if (response.isSuccessful) {
                response.close()
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "patchUserDataColumns failed: ${response.code} - $errorBody")
                response.close()
                Result.failure(Exception("PATCH failed: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "patchUserDataColumns error", e)
            Result.failure(e)
        }
    }

    /**
     * Resets progress in cloud
     */
    suspend fun resetProgressInCloud(profile: String, resetData: Map<String, Any>): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Supabase not configured in BuildConfig"))
        }

        try {
            val json = gson.toJson(resetData)
            val baseUrl = "${getSupabaseUrl()}/rest/v1/user_data"
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val updateUrl = "$baseUrl?profile=eq.$profile"
            val patchRequest = Request.Builder()
                .url(updateUrl)
                .patch(requestBody)
                .addHeader("apikey", getSupabaseKey())
                .addHeader("Authorization", "Bearer ${getSupabaseKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            val patchResponse = client.newCall(patchRequest).execute()
            if (patchResponse.isSuccessful) {
                patchResponse.close()
                Log.d(TAG, "Successfully reset progress in cloud for profile: $profile")
                Result.success(Unit)
            } else {
                val errorBody = patchResponse.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to reset progress in cloud: ${patchResponse.code} - $errorBody")
                patchResponse.close()
                Result.failure(Exception("Reset failed: ${patchResponse.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting progress in cloud", e)
            Result.failure(e)
        }
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
        val jsonbObjectFields = listOf("required_tasks", "practice_tasks", "bonus_tasks", "checklist_items", "game_indices")
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
    }
}
