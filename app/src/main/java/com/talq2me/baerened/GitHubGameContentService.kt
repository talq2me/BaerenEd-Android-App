package com.talq2me.baerened

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun fetchGameContent(context: Context, gameType: String): String? = withContext(Dispatchers.IO) {
        val gameFileName = "${gameType}.json"
        val candidatePaths = listOf(
            "data/$gameFileName",
            "ufliWordChains/$gameFileName",
            "ufliIrregularWords/$gameFileName"
        )

        candidatePaths.forEach { assetPath ->
            fetchFromGitHub(assetPath)?.let {
                Log.d(TAG, "Fetched game content from GitHub path: $assetPath")
                return@withContext it
            }
        }

        Log.w(TAG, "fetchGameContent failed for all candidate paths: $gameType")
        null
    }

    private fun fetchFromGitHub(assetPath: String): String? {
        return try {
            val url = "$GITHUB_PAGES_ASSETS_ROOT/$assetPath?nocache=${System.currentTimeMillis()}"
            Log.d(TAG, "Fetching from GitHub: $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BaerenEd-Android-App")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchFromGitHub failed for $assetPath: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Log.w(TAG, "fetchFromGitHub returned empty body for $assetPath")
                    return null
                }
                body
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchFromGitHub exception for $assetPath: ${e.message}")
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

        internal const val GITHUB_PAGES_ASSETS_ROOT =
            "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets"

        /** GitHub Pages URL for a bundled-path HTML game (never file:///android_asset). */
        fun githubHtmlUrl(fileName: String): String =
            "$GITHUB_PAGES_ASSETS_ROOT/html/$fileName"
    }
}
