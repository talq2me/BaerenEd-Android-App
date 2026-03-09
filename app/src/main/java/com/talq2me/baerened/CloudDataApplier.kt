package com.talq2me.baerened

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Applies DB data to prefs so the app can display it. Prefs are the only copy; DB is source of truth.
 */
class CloudDataApplier(
    private val context: Context,
    private val onTimestampSet: ((profile: String, timestamp: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CloudDataApplier"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
    }

    private val gson = Gson()

    /**
     * Applies DB data to prefs so UI can read it. No separate "local" copy; prefs hold what we last got from DB (or will push).
     */
    fun applyDbDataToPrefs(data: CloudUserData) {
        try {
            Log.d(TAG, "Applying DB data: profile=${data.profile} (progress in session only, no prefs)")

            SettingsManager.writeProfile(context, data.profile)
            applyAppListsToBaerenLock(data)
            if (!data.lastUpdated.isNullOrEmpty()) {
                onTimestampSet?.invoke(data.profile, data.lastUpdated)
            }
            Log.d(TAG, "Applied profile and timestamp for: ${data.profile}")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying cloud data to local", e)
            throw e
        }
    }

    /**
     * ONLINE-ONLY: game_indices are read/written from DB only. No prefs write.
     */
    private fun applyGameIndicesToLocal(gameIndices: Map<String, Int>, profile: String) {
        // No-op: game indices are stored in DB only
    }

    /**
     * Applies app lists from cloud data to BaerenLock SharedPreferences
     */
    private fun applyAppListsToBaerenLock(data: CloudUserData) {
        try {
            val baerenLockContext = context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
            
            // Apply reward apps
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
            
            // Apply blacklisted apps
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
            
            // Apply whitelisted apps
            data.whiteListedApps?.let { json ->
                try {
                    val appList = gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        val prefs = baerenLockContext.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("allowed", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} whitelisted apps to BaerenLock")
                        // Also refresh RewardManager if accessible
                        try {
                            val rewardManagerClass = Class.forName("com.talq2me.baerenlock.RewardManager")
                            val refreshMethod = rewardManagerClass.getMethod("refreshRewardEligibleApps", Context::class.java)
                            refreshMethod.invoke(null, baerenLockContext)
                        } catch (e: Exception) {
                            // RewardManager not accessible, that's okay
                            Log.d(TAG, "Could not refresh RewardManager: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying whitelisted apps to BaerenLock", e)
                }
            }
        } catch (e: Exception) {
            // BaerenLock not installed or not accessible - that's okay
            Log.d(TAG, "BaerenLock not accessible, skipping app list sync: ${e.message}")
        }
    }

    /**
     * Applies app lists from DB to BaerenLock if BaerenLock doesn't have them yet
     */
    fun applyAppListsFromCloudIfLocalEmpty(data: CloudUserData) {
        try {
            val baerenLockContext = context.createPackageContext("com.talq2me.baerenlock", Context.CONTEXT_IGNORE_SECURITY)
            
            // Check if local has these lists
            val rewardPrefs = baerenLockContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val blacklistPrefs = baerenLockContext.getSharedPreferences("blacklist_prefs", Context.MODE_PRIVATE)
            val whitelistPrefs = baerenLockContext.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            
            val localHasReward = rewardPrefs.getStringSet("reward_apps", null)?.isNotEmpty() == true
            val localHasBlacklist = blacklistPrefs.getStringSet("packages", null)?.isNotEmpty() == true
            val localHasWhitelist = whitelistPrefs.getStringSet("allowed", null)?.isNotEmpty() == true
            
            // Only apply if DB has data and BaerenLock doesn't
            if (!localHasReward && !data.rewardApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.rewardApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        rewardPrefs.edit().putStringSet("reward_apps", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} reward apps from cloud (local was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying reward apps from cloud", e)
                }
            }
            
            if (!localHasBlacklist && !data.blacklistedApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.blacklistedApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        blacklistPrefs.edit().putStringSet("packages", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} blacklisted apps from DB (BaerenLock was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying blacklisted apps from cloud", e)
                }
            }
            
            if (!localHasWhitelist && !data.whiteListedApps.isNullOrBlank()) {
                try {
                    val appList = gson.fromJson<List<String>>(data.whiteListedApps, object : TypeToken<List<String>>() {}.type)
                    if (appList != null && appList.isNotEmpty()) {
                        whitelistPrefs.edit().putStringSet("allowed", appList.toSet()).apply()
                        Log.d(TAG, "Applied ${appList.size} whitelisted apps from DB (BaerenLock was empty)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying whitelisted apps from cloud", e)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "BaerenLock not accessible for app list sync: ${e.message}")
        }
    }

    /**
     * Gets all tasks from a specific config section, filtered by visibility for today
     * Only includes tasks that are visible on the current day
     */
    private fun getConfigTasksForSection(sectionId: String): List<Task> {
        try {
            // Load config content
            val contentUpdateService = ContentUpdateService()
            val jsonString = contentUpdateService.getCachedMainContent(context)

            if (jsonString.isNullOrEmpty()) {
                Log.w(TAG, "No cached main content available for config tasks")
                return emptyList()
            }

            val mainContent = gson.fromJson(jsonString, MainContent::class.java)
            val section = mainContent?.sections?.find { it.id == sectionId }

            // Get all tasks from section
            val allTasks = section?.tasks?.filterNotNull() ?: emptyList()
            
            // Filter tasks by visibility - only include tasks visible today
            val visibleTasks = allTasks.filter { task ->
                TaskVisibilityChecker.isTaskVisible(task)
            }

            return visibleTasks
        } catch (e: Exception) {
            Log.e(TAG, "Error loading config tasks for section: $sectionId", e)
            return emptyList()
        }
    }

    /**
     * Gets checklist item labels that are visible today (for applying cloud required_tasks).
     * Ensures checklist completions from cloud are not dropped when syncing.
     */
    private fun getChecklistItemLabelsVisibleToday(): Set<String> {
        return getConfigChecklistItemsVisibleToday().mapNotNull { it.label }.toSet()
    }

    /**
     * Gets checklist items from all sections that are visible today.
     */
    private fun getConfigChecklistItemsVisibleToday(): List<ChecklistItem> {
        try {
            val contentUpdateService = ContentUpdateService()
            val jsonString = contentUpdateService.getCachedMainContent(context)
            if (jsonString.isNullOrEmpty()) return emptyList()
            val mainContent = gson.fromJson(jsonString, MainContent::class.java) ?: return emptyList()
            val allItems = mainContent.sections?.flatMap { section ->
                section.items?.filterNotNull() ?: emptyList()
            } ?: emptyList()
            return allItems.filter { TaskVisibilityChecker.isItemVisible(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading checklist items from config", e)
            return emptyList()
        }
    }
}
