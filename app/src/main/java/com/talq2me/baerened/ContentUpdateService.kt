package com.talq2me.baerened

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ONLINE-ONLY: No local disk cache of GitHub content. Fetches from network; in-memory cache only for the session.
 */
class ContentUpdateService : Service() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("content_cache", MODE_PRIVATE)
        Log.d(TAG, "ContentUpdateService created (online-only: no disk cache)")
    }

    private fun getContentUrlForChild(context: Context): String {
        val child = SettingsManager.readProfile(context) ?: "AM"

        return "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/${child}_config.json"
    }

    private fun getChoresUrl(): String {
        return "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/chores.json"
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ContentUpdateService started for background update.")

        // Update content in background. Fetch main config and chores from GitHub, update cache.
        CoroutineScope(Dispatchers.IO).launch {
            fetchMainContent(this@ContentUpdateService)
            fetchChores(this@ContentUpdateService)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** ONLINE-ONLY: Store in memory only; no disk or SharedPreferences. */
    fun saveMainContentToCache(context: Context, json: String) {
        try {
            inMemoryMainContent = json
            val content = Gson().fromJson(json, MainContent::class.java)
            inMemoryMainContentVersion = content.version
            Log.d(TAG, "Main content stored in memory: ${content.version}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving main content to memory", e)
        }
    }

    /**
     * ONLINE-ONLY: Fetch from GitHub only. No fallback to cache or asset — if network fails, returns null
     * so the UI can show that content is unavailable (no network).
     */
    fun getCachedMainContent(context: Context): String? {
        return try {
            val baseUrl = getContentUrlForChild(context)
            val urlWithBust = "$baseUrl?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder().url(urlWithBust).build()
            val quickClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val response = quickClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    Log.d(TAG, "getCachedMainContent: Fetched from GitHub (length=${body.length})")
                    inMemoryMainContent = body
                    return body
                }
            }
            response.close()
            Log.w(TAG, "getCachedMainContent: Network failed or empty response — no fallback")
            null
        } catch (e: Exception) {
            Log.w(TAG, "getCachedMainContent: Network error — no fallback: ${e.message}")
            null
        }
    }

    fun getCachedMainContentVersion(): String? {
        return inMemoryMainContentVersion ?: (if (::prefs.isInitialized) prefs.getString("main_content_version", null) else null)
    }

    /** ONLINE-ONLY: Fetch from network only. No fallback — returns null if network fails. */
    suspend fun fetchGameContent(context: Context, gameType: String): String? {
        val gameFileName = "${gameType}.json"
        return try {
            val url = "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/data/$gameFileName?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "Fetched game content from network: $gameType")
                        return body
                    }
                }
                Log.w(TAG, "fetchGameContent: Network failed or empty — no fallback")
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchGameContent: Network error — no fallback: ${e.message}")
            null
        }
    }

    /** ONLINE-ONLY: Fetch from network only. No fallback — returns null if network fails. */
    suspend fun fetchVideoContent(context: Context, videoFile: String): String? {
        val videoFileName = "${videoFile}.json"
        return try {
            val url = "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/videos/$videoFileName"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "Fetched video content from network: $videoFile")
                        return body
                    }
                }
                Log.w(TAG, "fetchVideoContent: Network failed or empty — no fallback")
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchVideoContent: Network error — no fallback: ${e.message}")
            null
        }
    }

    /** ONLINE-ONLY: Fetch from network only. No fallback — returns null if network fails. */
    suspend fun fetchMainContent(context: Context): MainContent? {
        val baseUrl = getContentUrlForChild(context)
        val urlWithBust = "$baseUrl?nocache=${System.currentTimeMillis()}"
        return try {
            val request = Request.Builder().url(urlWithBust).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "Fetched main content from network.")
                        inMemoryMainContent = body
                        val content = Gson().fromJson(body, MainContent::class.java)
                        inMemoryMainContentVersion = content.version
                        return content
                    }
                }
                Log.w(TAG, "fetchMainContent: Network failed or empty — no fallback")
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchMainContent: Network error — no fallback: ${e.message}")
            null
        }
    }

    /** ONLINE-ONLY: Fetch from GitHub only. No fallback — returns null if network fails. */
    fun getCachedChores(context: Context): String? {
        return try {
            val urlWithBust = "${getChoresUrl()}?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder().url(urlWithBust).build()
            val quickClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val response = quickClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    Log.d(TAG, "getCachedChores: Fetched from GitHub")
                    inMemoryChores = body
                    response.close()
                    return body
                }
            }
            response.close()
            Log.w(TAG, "getCachedChores: Network failed or empty — no fallback")
            null
        } catch (e: Exception) {
            Log.w(TAG, "getCachedChores: Network error — no fallback: ${e.message}")
            null
        }
    }

    /** ONLINE-ONLY: Fetch from GitHub only. No fallback — returns null if network fails. */
    suspend fun fetchChores(context: Context): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val urlWithBust = "${getChoresUrl()}?nocache=${System.currentTimeMillis()}"
        try {
            val request = Request.Builder().url(urlWithBust).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "fetchChores: Fetched from GitHub")
                        inMemoryChores = body
                        return@withContext body
                    }
                }
                Log.w(TAG, "fetchChores: Network failed or empty — no fallback")
                return@withContext null
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchChores: Network error — no fallback: ${e.message}")
            null
        }
    }

    /** ONLINE-ONLY: Clear in-memory cache only. */
    fun clearCache(context: Context) {
        try {
            inMemoryMainContent = null
            inMemoryMainContentVersion = null
            inMemoryChores = null
            if (::prefs.isInitialized) prefs.edit().clear().apply()
            Log.d(TAG, "In-memory cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    fun isContentStale(context: Context, maxAgeHours: Long = 24): Boolean {
        return inMemoryMainContent == null
    }

    companion object {
        private const val TAG = "ContentUpdateService"
        @Volatile private var inMemoryMainContent: String? = null
        @Volatile private var inMemoryMainContentVersion: String? = null
        @Volatile private var inMemoryChores: String? = null
    }
}
