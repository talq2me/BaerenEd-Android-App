package com.talq2me.baerened

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub-only fetcher for game/video JSON assets used by task launchers.
 * No main dashboard config fetching and no local storage cache.
 */
class GitHubGameContentService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchGameContent(context: Context, gameType: String): String? {
        val gameFileName = "${gameType}.json"
        return try {
            val url = "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/data/$gameFileName?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "Fetched game content from GitHub: $gameType")
                        return body
                    }
                }
                Log.w(TAG, "fetchGameContent failed or empty: $gameType")
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchGameContent network error: ${e.message}")
            null
        }
    }

    suspend fun fetchVideoContent(context: Context, videoFile: String): String? {
        val videoFileName = "${videoFile}.json"
        return try {
            val url = "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/videos/$videoFileName?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "Fetched video content from GitHub: $videoFile")
                        return body
                    }
                }
                Log.w(TAG, "fetchVideoContent failed or empty: $videoFile")
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchVideoContent network error: ${e.message}")
            null
        }
    }

    fun clearCache(context: Context) {
        Log.d(TAG, "No local cache to clear (GitHubGameContentService)")
    }

    companion object {
        private const val TAG = "GitHubGameContent"
    }
}
