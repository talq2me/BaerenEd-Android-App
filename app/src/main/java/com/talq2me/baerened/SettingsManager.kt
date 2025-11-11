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
}