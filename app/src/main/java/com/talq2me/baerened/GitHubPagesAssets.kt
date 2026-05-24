package com.talq2me.baerened

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Fetches files published under `app/src/main/assets/` on GitHub Pages.
 * APK copies of these paths are not required at runtime.
 */
object GitHubPagesAssets {

    private const val TAG = "GitHubPagesAssets"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun normalizeAssetPath(raw: String): String {
        var path = raw.trim().replace('\\', '/')
        while (path.startsWith("../")) {
            path = path.removePrefix("../")
        }
        if (path.startsWith("/")) {
            path = path.removePrefix("/")
        }
        val repoPrefix = "app/src/main/assets/"
        if (path.startsWith(repoPrefix)) {
            path = path.removePrefix(repoPrefix)
        }
        return path
    }

    fun assetUrl(assetPath: String, bustCache: Boolean = false): String {
        val path = normalizeAssetPath(assetPath)
        val base = "${GitHubGameContentService.GITHUB_PAGES_ASSETS_ROOT}/$path"
        return if (bustCache) "$base?nocache=${System.currentTimeMillis()}" else base
    }

    fun fetchBytes(assetPath: String): ByteArray? {
        return try {
            val request = Request.Builder()
                .url(assetUrl(assetPath, bustCache = true))
                .header("User-Agent", "BaerenEd-Android-App")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchBytes failed [$assetPath]: HTTP ${response.code}")
                    return null
                }
                response.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchBytes exception [$assetPath]", e)
            null
        }
    }

    fun fetchText(assetPath: String): String? =
        fetchBytes(assetPath)?.toString(Charsets.UTF_8)

    fun fetchBitmap(assetPath: String): Bitmap? {
        val bytes = fetchBytes(assetPath) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Prefer `.webp` when the JSON path ends with `.png`. */
    fun fetchBitmapWithWebpFallback(assetPath: String): Bitmap? {
        val normalized = normalizeAssetPath(assetPath)
        if (normalized.endsWith(".png", ignoreCase = true)) {
            val webpPath = normalized.replace(Regex("\\.png$", RegexOption.IGNORE_CASE), ".webp")
            fetchBitmap(webpPath)?.let { return it }
        }
        return fetchBitmap(normalized)
    }

    /** e.g. `images/arena` tries `images/arena.webp` then `images/arena.png`. */
    fun fetchBitmapPreferWebp(baseWithoutExtension: String): Bitmap? {
        val base = normalizeAssetPath(baseWithoutExtension)
            .removeSuffix(".webp")
            .removeSuffix(".png")
        fetchBitmap("$base.webp")?.let { return it }
        return fetchBitmap("$base.png")
    }

    fun fetchPokemonManifestFilenames(): List<String> {
        val json = fetchText("images/pokeSprites/sprites/pokemon/pokedex_manifest.json")
            ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i) }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPokemonManifestFilenames parse error", e)
            emptyList()
        }
    }

    fun pokemonImageBase64DataUrl(filename: String): String {
        val bytes = fetchBytes("images/pokeSprites/sprites/pokemon/$filename") ?: return ""
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/png;base64,$base64"
    }
}
