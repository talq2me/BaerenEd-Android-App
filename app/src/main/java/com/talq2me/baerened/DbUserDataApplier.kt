package com.talq2me.baerened

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Applies selected fields from a [DbUserData] fetch into prefs (profile, BaerenLock app lists, last_updated callback).
 * Task progress and metrics live in session / DB paths, not here.
 */
class DbUserDataApplier(
    private val context: Context,
    private val onTimestampSet: ((profile: String, timestamp: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "DbUserDataApplier"
    }

    private val gson = Gson()

    fun applyDbDataToPrefs(data: DbUserData) {
        try {
            Log.d(TAG, "Applying DB data: profile=${data.profile} (progress in session only, no prefs)")

            SettingsManager.writeProfile(context, data.profile)
            applyAppListsToBaerenLock(data)
            if (!data.lastUpdated.isNullOrEmpty()) {
                onTimestampSet?.invoke(data.profile, data.lastUpdated)
            }
            Log.d(TAG, "Applied profile and timestamp for: ${data.profile}")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying DB data to local", e)
            throw e
        }
    }

    private fun applyAppListsToBaerenLock(data: DbUserData) {
        try {
            val baerenLockContext = context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)

            data.rewardApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("reward_apps", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} reward apps to BaerenLock")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying reward apps to BaerenLock", e)
                }
            }

            data.blacklistedApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("packages", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} blacklisted apps to BaerenLock")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying blacklisted apps to BaerenLock", e)
                }
            }

            data.whiteListedApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("allowed", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} whitelisted apps to BaerenLock")
                        try {
                            val rewardManagerClass = Class.forName("com.talq2me.baerenlock.RewardManager")
                            val refreshMethod = rewardManagerClass.getMethod("refreshRewardEligibleApps", Context::class.java)
                            refreshMethod.invoke(null, baerenLockContext)
                        } catch (e: Exception) {
                            Log.d(TAG, "Could not refresh RewardManager: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying whitelisted apps to BaerenLock", e)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "BaerenLock not accessible, skipping app list sync: ${e.message}")
        }
    }
}
