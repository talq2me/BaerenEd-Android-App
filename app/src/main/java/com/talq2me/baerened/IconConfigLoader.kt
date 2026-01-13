package com.talq2me.baerened

import android.content.Context
import android.content.res.AssetManager
import com.google.gson.Gson

/**
 * Loads and caches icon configuration from assets
 * Extracted from Layout class for reusability
 */
object IconConfigLoader {
    
    data class IconConfig(
        val starsIcon: String = "🍍",
        val coinsIcon: String = "🪙"
    )
    
    private var cachedConfig: IconConfig? = null
    
    /**
     * Loads icon configuration from assets/config/icon_config.json
     * Caches the result for subsequent calls
     */
    fun loadIconConfig(context: Context): IconConfig {
        if (cachedConfig == null) {
            try {
                val inputStream = context.assets.open("config/icon_config.json")
                val configJson = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                cachedConfig = Gson().fromJson(configJson, IconConfig::class.java) ?: IconConfig()
            } catch (e: Exception) {
                android.util.Log.e("IconConfigLoader", "Error loading icon config", e)
                cachedConfig = IconConfig() // Use defaults
            }
        }
        return cachedConfig!!
    }
    
    /**
     * Clears the cached configuration (useful for testing)
     */
    fun clearCache() {
        cachedConfig = null
    }
}
