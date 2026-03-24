package com.talq2me.baerened

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class RewardSelectionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RewardSelectionActivity"
        // Intent extra key for reward minutes - must match what BaerenLock expects
        const val EXTRA_REWARD_MINUTES = "reward_minutes"
    }

    private var remainingMinutes: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get reward time from intent
        remainingMinutes = intent.getIntExtra(EXTRA_REWARD_MINUTES, 0)
        Log.d(TAG, "RewardSelectionActivity: Received $remainingMinutes minutes from Intent (should match report value)")

        if (remainingMinutes <= 0) {
            Toast.makeText(this, "No reward time available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val rawProfile = SettingsManager.readProfile(this@RewardSelectionActivity) ?: "AM"
                val profile = when (rawProfile) {
                    "A" -> "AM"
                    "B" -> "BM"
                    else -> rawProfile
                }
                val syncService = CloudSyncService()
                // Must use use_reward_time (not add_reward_time): moves banked_mins → reward_time_expiry so BaerenLock shows reward apps.
                val result = syncService.invokeUseRewardTime(profile)
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message ?: "unknown error"
                    Log.e(TAG, "Failed use_reward_time for profile $profile (raw=$rawProfile): $message")
                    Toast.makeText(this@RewardSelectionActivity, "Could not start reward time: $message", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                Log.d(TAG, "Activated reward session via use_reward_time for profile $profile (raw=$rawProfile), had $remainingMinutes banked locally")
                DailyProgressManager(this@RewardSelectionActivity).useAllRewardMinutes()
                launchBaerenLock()
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error launching BaerenLock", e)
                Toast.makeText(this@RewardSelectionActivity, "Error launching reward app. Please try again.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Launches BaerenLock without sending reward minutes via Intent/Broadcast.
     * BaerenLock will read the reward minutes from the cloud database instead.
     */
    private fun launchBaerenLock() {
        Log.d(TAG, "Launching BaerenLock (it will read reward minutes from cloud database)")

        // Try to launch BaerenLock's LauncherActivity directly
        try {
            val launcherIntent = Intent().apply {
                setClassName("com.talq2me.baerenlock", "com.talq2me.baerenlock.LauncherActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // No reward minutes in Intent - BaerenLock will read from cloud database
            }
            startActivity(launcherIntent)
            Log.d(TAG, "Launched BaerenLock LauncherActivity (no Intent extras - will read from cloud)")
        } catch (e: Exception) {
            // Fallback: Use HOME intent if direct launch fails
            Log.w(TAG, "Failed to launch BaerenLock directly, trying HOME intent: ${e.message}")
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    // No reward minutes in Intent - BaerenLock will read from cloud database
                }
                startActivity(homeIntent)
                Log.d(TAG, "Launched BaerenLock via Home Intent (no Intent extras - will read from cloud)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch BaerenLock via HOME intent: ${e2.message}")
                throw e2 // Re-throw so caller can handle it
            }
        }
    }
}

