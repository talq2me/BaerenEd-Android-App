package com.talq2me.baerened

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
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

    /**
     * Stems (`*.json` without `.json`) under `assets/ufliWordChains/`, sorted.
     * Prefers the small GitHub Contents API for that folder (reliable on device); falls back to the
     * recursive tree API, then to filenames bundled under `assets/ufliWordChains/` in the APK.
     */
    suspend fun fetchUfliWordChainStemNamesSorted(context: Context): List<String> = withContext(Dispatchers.IO) {
        fetchUfliStemsFromContentsApi()?.takeIf { it.isNotEmpty() }?.let {
            Log.d(TAG, "fetchUfliWordChainStemNamesSorted: ${it.size} stems via Contents API")
            return@withContext it
        }
        fetchUfliStemsFromTreeApi()?.takeIf { it.isNotEmpty() }?.let {
            Log.d(TAG, "fetchUfliWordChainStemNamesSorted: ${it.size} stems via tree API")
            return@withContext it
        }
        val bundled = stemsFromBundledUfliAssets(context)
        if (bundled.isNotEmpty()) {
            Log.w(TAG, "fetchUfliWordChainStemNamesSorted: using ${bundled.size} bundled stems (GitHub listing failed)")
        } else {
            Log.w(TAG, "fetchUfliWordChainStemNamesSorted: no stems from API or assets")
        }
        bundled
    }

    private fun fetchUfliStemsFromContentsApi(): List<String>? {
        return try {
            val request = Request.Builder()
                .url(GITHUB_UFLI_CHAINS_CONTENTS_API)
                .header("User-Agent", "BaerenEd-Android-App")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchUfliStemsFromContentsApi: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val arr = JsonParser.parseString(body).asJsonArray
                val stems = mutableListOf<String>()
                arr.forEach { el ->
                    val obj = el.asJsonObject
                    if (obj.get("type")?.asString != "file") return@forEach
                    val name = obj.get("name")?.asString ?: return@forEach
                    if (!name.endsWith(".json", ignoreCase = true)) return@forEach
                    stems.add(name.dropLast(5))
                }
                stems.filter { it.isNotBlank() }.sorted()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchUfliStemsFromContentsApi exception", e)
            null
        }
    }

    private fun fetchUfliStemsFromTreeApi(): List<String>? {
        return try {
            val request = Request.Builder()
                .url(GITHUB_TREE_API)
                .header("User-Agent", "BaerenEd-Android-App")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchUfliStemsFromTreeApi: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val root = JsonParser.parseString(body).asJsonObject
                val tree = root.getAsJsonArray("tree") ?: return null
                val stems = mutableListOf<String>()
                tree.forEach { node ->
                    val obj = node.asJsonObject
                    if (obj.get("type")?.asString != "blob") return@forEach
                    val fullPath = obj.get("path")?.asString ?: return@forEach
                    if (!fullPath.startsWith(UFLI_PREFIX)) return@forEach
                    if (!fullPath.endsWith(".json", ignoreCase = true)) return@forEach
                    stems.add(fullPath.removePrefix(UFLI_PREFIX).removeSuffix(".json"))
                }
                stems.filter { it.isNotBlank() }.sorted()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchUfliStemsFromTreeApi exception", e)
            null
        }
    }

    private fun stemsFromBundledUfliAssets(context: Context): List<String> {
        return try {
            context.assets.list("ufliWordChains")
                ?.filter { it.endsWith(".json", ignoreCase = true) }
                ?.map { it.dropLast(5) }
                ?.filter { it.isNotBlank() }
                ?.sorted()
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "stemsFromBundledUfliAssets", e)
            emptyList()
        }
    }

    suspend fun fetchGameContent(context: Context, gameType: String): String? = withContext(Dispatchers.IO) {
        val gameFileName = "${gameType}.json"
        val candidatePaths = listOf(
            "data/$gameFileName",
            "ufliWordChains/$gameFileName"
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
        internal const val GITHUB_TREE_API =
            "https://api.github.com/repos/talq2me/BaerenEd-Android-App/git/trees/V3?recursive=1"
        /** Directory listing for `ufliWordChains/` only (small JSON vs. ~1MB recursive tree). */
        internal const val GITHUB_UFLI_CHAINS_CONTENTS_API =
            "https://api.github.com/repos/talq2me/BaerenEd-Android-App/contents/app/src/main/assets/ufliWordChains?ref=V3"
        internal const val UFLI_PREFIX = "app/src/main/assets/ufliWordChains/"
    }
}
