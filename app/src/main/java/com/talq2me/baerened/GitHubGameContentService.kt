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
        val candidatePaths = listOf(
            "data/$gameFileName",
            "ufliWordChains/$gameFileName"
        )

        candidatePaths.forEach { assetPath ->
            fetchFromGitHub(assetPath)?.let {
                Log.d(TAG, "Fetched game content from GitHub path: $assetPath")
                return it
            }
        }

        Log.w(TAG, "fetchGameContent failed for all candidate paths: $gameType")
        return null
    }

    private fun fetchFromGitHub(assetPath: String): String? {
        return try {
            val url = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/$assetPath?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: IOException) {
            null
        }
    }

    suspend fun fetchVideoContent(context: Context, videoFile: String): String? {
        val videoFileName = "${videoFile}.json"
        return try {
            val url = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/videos/$videoFileName?nocache=${System.currentTimeMillis()}"
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
