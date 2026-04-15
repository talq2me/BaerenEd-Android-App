package com.talq2me.baerened

import android.content.Context

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
            cachedConfig = IconConfig()
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
