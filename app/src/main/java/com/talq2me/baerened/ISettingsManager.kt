package com.talq2me.baerened

import android.content.Context

/**
 * Interface for settings management
 * This allows for easier testing and dependency injection
 */
interface ISettingsManager {
    fun readProfile(context: Context): String?
    fun writeProfile(context: Context, profile: String)
    
    fun readPin(context: Context): String?
    fun writePin(context: Context, pin: String)
    
    fun readEmail(context: Context): String?
    fun writeEmail(context: Context, email: String)
    
    fun getAvailableProfiles(context: Context): List<String>
    fun getProfileDisplayNames(): Map<String, String>
    
    fun preloadSettings(context: Context)
}

/**
 * Settings manager implementation
 * Note: For now, this wraps the existing SettingsManager object to maintain compatibility
 * Eventually, SettingsManager object can be removed and this can be used directly
 */
class SettingsManagerImpl(
    private val context: Context,
    private val cloudStorageManager: ICloudStorageManager? = null
) : ISettingsManager {
    
    override fun readProfile(context: Context): String? {
        // For now, delegate to existing object
        // Eventually can use ProgressRepository or other storage
        return SettingsManager.readProfile(context)
    }
    
    override fun writeProfile(context: Context, profile: String) {
        SettingsManager.writeProfile(context, profile)
    }
    
    override fun readPin(context: Context): String? {
        return SettingsManager.readPin(context)
    }
    
    override fun writePin(context: Context, pin: String) {
        SettingsManager.writePin(context, pin)
    }
    
    override fun readEmail(context: Context): String? {
        return SettingsManager.readEmail(context)
    }
    
    override fun writeEmail(context: Context, email: String) {
        SettingsManager.writeEmail(context, email)
    }
    
    override fun getAvailableProfiles(context: Context): List<String> {
        return SettingsManager.getAvailableProfiles(context)
    }
    
    override fun getProfileDisplayNames(): Map<String, String> {
        return SettingsManager.getProfileDisplayNames()
    }
    
    override fun preloadSettings(context: Context) {
        SettingsManager.preloadSettings(context)
    }
}
