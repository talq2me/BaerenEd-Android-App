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
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ContentUpdateService : Service() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("content_cache", MODE_PRIVATE)
        Log.d(TAG, "ContentUpdateService created")
    }

    private fun getContentUrlForChild(context: Context): String {
        val child = SettingsManager.readProfile(context) ?: "A"

        return when (child) {
            "A" -> "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/AM_config.json"
            "B" -> "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/BM_config.json"
            else -> "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/Main_config.json"
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ContentUpdateService started for background update.")

        // Update content in background. This will attempt to fetch from network and update cache.
        CoroutineScope(Dispatchers.IO).launch {
            fetchMainContent(this@ContentUpdateService)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun saveMainContentToCache(context: Context, json: String) {
        try {
            val file = File(context.filesDir, "main_content.json")
            FileWriter(file).use { writer ->
                writer.write(json)
            }

            // Save version for comparison
            val content = Gson().fromJson(json, MainContent::class.java)
            prefs.edit().putString("main_content_version", content.version).apply()

            Log.d(TAG, "Main content cached: ${content.version}")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving main content to cache", e)
        }
    }

    fun getCachedMainContent(context: Context): String? {
        return try {
            val file = File(context.filesDir, "main_content.json")
            if (file.exists()) {
                file.readText()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached main content", e)
            null
        }
    }

    fun getCachedMainContentVersion(): String? {
        return prefs.getString("main_content_version", null)
    }

    /**
     * Fetch game content with comprehensive fallback strategy:
     * 1. Try network first and cache result
     * 2. If network fails, try cache
     * 3. If cache fails, try assets as final fallback
     */
    suspend fun fetchGameContent(context: Context, gameType: String): String? {
        val gameFileName = "${gameType}.json"
        val cacheFile = File(context.cacheDir, gameFileName)

        // 1. Try fetching from Network
        try {
            val url = "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/data/$gameFileName"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "Successfully fetched game content from network: $gameType")
                        // Save to cache for offline use
                        try {
                            FileOutputStream(cacheFile).use { it.write(body.toByteArray()) }
                            Log.d(TAG, "Saved game content to cache: $gameFileName")
                        } catch (e: IOException) {
                            Log.e(TAG, "Error writing game content to cache", e)
                        }
                        return body
                    }
                }
                Log.w(TAG, "Game content fetch failed with code: ${response.code}. Trying cache.")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error fetching game content: ${e.message}. Trying cache.")
        }

        // 2. Network failed, try loading from Cache
        if (cacheFile.exists()) {
            try {
                val cachedContent = cacheFile.readText()
                Log.d(TAG, "Successfully loaded game content from cache: $gameFileName")
                return cachedContent
            } catch (e: Exception) {
                Log.e(TAG, "Error reading game content from cache. Trying assets.", e)
            }
        }

        // 3. Cache failed or doesn't exist, fall back to bundled Assets
        try {
            Log.d(TAG, "Loading game content from bundled asset: $gameFileName")
            context.assets.open("data/$gameFileName").use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    return reader.readText()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not find or read game asset file: data/$gameFileName", e)
            return null // Final failure
        }
    }

    /**
     * Fetch main content with comprehensive fallback strategy:
     * 1. Try network first and cache result
     * 2. If network fails, try cache
     * 3. If cache fails, try assets as final fallback
     */
    suspend fun fetchMainContent(context: Context): MainContent? {
        val url = getContentUrlForChild(context)
        val cacheFileName = url.substring(url.lastIndexOf('/') + 1) // e.g., "AM_config.json"
        val cacheFile = File(context.cacheDir, cacheFileName)

        // 1. Try fetching from Network
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "Successfully fetched from network.")
                        // Save to cache for offline use
                        try {
                            FileOutputStream(cacheFile).use { it.write(body.toByteArray()) }
                            Log.d(TAG, "Saved content to cache: ${cacheFile.name}")
                        } catch (e: IOException) {
                            Log.e(TAG, "Error writing to cache", e)
                        }
                        return Gson().fromJson(body, MainContent::class.java)
                    }
                }
                Log.w(TAG, "GitHub fetch failed with code: ${response.code}. Trying cache.")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error fetching content: ${e.message}. Trying cache.")
        }

        // 2. Network failed, try loading from Cache
        if (cacheFile.exists()) {
            try {
                val cachedContent = cacheFile.readText()
                Log.d(TAG, "Successfully loaded from cache: ${cacheFile.name}")
                return Gson().fromJson(cachedContent, MainContent::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading from cache. Trying assets.", e)
            }
        }

        // 3. Cache failed or doesn't exist, fall back to bundled Assets
        try {
            val assetPath = "config/$cacheFileName"
            Log.d(TAG, "Loading from bundled asset: $assetPath")
            context.assets.open(assetPath).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    return Gson().fromJson(reader, MainContent::class.java)
                }
            }
        } catch (e: IOException) {
            val assetPath = "config/$cacheFileName"
            Log.e(TAG, "Could not find or read asset file: $assetPath", e)
            return null // Final failure
        }
    }


    fun clearCache(context: Context) {
        try {
            val filesDir = context.filesDir
            filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("main_content.json")) {
                    file.delete()
                }
            }

            prefs.edit().clear().apply()

            Log.d(TAG, "Cache cleared")

        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    fun isContentStale(context: Context, maxAgeHours: Long = 24): Boolean {
        return try {
            val file = File(context.filesDir, "main_content.json")
            if (!file.exists()) return true

            val lastModified = file.lastModified()
            val age = System.currentTimeMillis() - lastModified
            val maxAge = TimeUnit.HOURS.toMillis(maxAgeHours)

            age > maxAge

        } catch (e: Exception) {
            Log.e(TAG, "Error checking content staleness", e)
            true
        }
    }

    companion object {
        private const val TAG = "ContentUpdateService"
    }
}
