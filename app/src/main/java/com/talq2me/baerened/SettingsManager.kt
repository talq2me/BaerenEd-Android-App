package com.talq2me.baerened

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.talq2me.contract.SettingsContract

object SettingsManager {

    private var currentProfile: String? = null
    private var currentPin: String? = null
    private var currentEmail: String? = null

    fun readProfile(context: Context): String? {
        if (currentProfile != null) {
            return currentProfile
        }

        try {
            context.contentResolver.query(SettingsContract.CONTENT_URI, arrayOf(SettingsContract.KEY_PROFILE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    currentProfile = cursor.getString(cursor.getColumnIndexOrThrow(SettingsContract.KEY_PROFILE))
                    return currentProfile
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to read profile from provider.", e)
        }
        return null
    }

    fun writeProfile(context: Context, newProfile: String) {
        val values = ContentValues().apply {
            put(SettingsContract.KEY_PROFILE, newProfile)
        }
        try {
            context.contentResolver.update(SettingsContract.CONTENT_URI, values, null, null)
            currentProfile = newProfile
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to write profile to provider.", e)
        }
    }

    fun readPin(context: Context): String? {
        if (currentPin != null) {
            return currentPin
        }

        try {
            context.contentResolver.query(SettingsContract.CONTENT_URI, arrayOf(SettingsContract.KEY_PIN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    currentPin = cursor.getString(cursor.getColumnIndexOrThrow(SettingsContract.KEY_PIN))
                    return currentPin
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to read PIN from provider.", e)
        }
        return null
    }

    fun writePin(context: Context, newPin: String) {
        val values = ContentValues().apply {
            put(SettingsContract.KEY_PIN, newPin)
        }
        try {
            context.contentResolver.update(SettingsContract.CONTENT_URI, values, null, null)
            currentPin = newPin
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to write PIN to provider.", e)
        }
    }

    fun readEmail(context: Context): String? {
        if (currentEmail != null) {
            return currentEmail
        }

        try {
            context.contentResolver.query(SettingsContract.CONTENT_URI, arrayOf(SettingsContract.KEY_PARENT_EMAIL), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    currentEmail = cursor.getString(cursor.getColumnIndexOrThrow(SettingsContract.KEY_PARENT_EMAIL))
                    return currentEmail
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to read email from provider.", e)
        }
        return null
    }

    fun writeEmail(context: Context, newEmail: String) {
        val values = ContentValues().apply {
            put(SettingsContract.KEY_PARENT_EMAIL, newEmail)
        }
        try {
            context.contentResolver.update(SettingsContract.CONTENT_URI, values, null, null)
            currentEmail = newEmail
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to write email to provider.", e)
        }
    }

    /**
     * Gets available profiles based on config files in assets
     * Returns a list of profile IDs (e.g., ["A", "B"]) for profiles that have config files
     */
    fun getAvailableProfiles(context: Context): List<String> {
        val availableProfiles = mutableListOf<String>()

        try {
            // Check for config files in assets/config/ directory
            val assetManager = context.assets
            val configFiles = assetManager.list("config") ?: emptyArray()

            // Look for files matching the pattern: {ProfileId}_config.json
            configFiles.forEach { filename ->
                if (filename.endsWith("_config.json")) {
                    // Extract profile ID from filename (e.g., "AM_config.json" -> "AM")
                    val profileId = filename.removeSuffix("_config.json")
                    if (profileId.isNotEmpty()) {
                        availableProfiles.add(profileId)
                    }
                }
            }

            // Sort profiles for consistent ordering
            availableProfiles.sort()

        } catch (e: Exception) {
            Log.e("SettingsManager", "Error detecting available profiles", e)
            // Fallback to basic profiles if detection fails
            return listOf("AM", "BM")
        }

        return availableProfiles
    }

    /**
     * Gets display names for profiles
     * Returns a map of profile ID to display name
     */
    fun getProfileDisplayNames(): Map<String, String> {
        return mapOf(
            "AM" to "AM Profile",
            "BM" to "BM Profile"
        )
    }
}