package com.talq2me.baerened

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

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

        try {
            val progressManager = DailyProgressManager(this)
            val currentMinutes = progressManager.getBankedRewardMinutes()
            
            if (currentMinutes > 0) {
                // Sync reward minutes to cloud first (so BaerenLock can read them from the database)
                // BaerenLock will read the minutes from the cloud database instead of receiving them via Intent
                // Banked minutes sync to cloud via full update_cloud_with_local on next BattleHub/Trainer Map load or game completion (per requirements)
                progressManager.setBankedRewardMinutes(currentMinutes)
                Log.d(TAG, "Synced $currentMinutes minutes to cloud for BaerenLock to read")
                
                // Launch BaerenLock without sending minutes via Intent/Broadcast
                // BaerenLock will read the minutes from the cloud database when it starts
                launchBaerenLock()
                
                // Clear the banked minutes from BaerenEd local storage
                // Note: We don't sync 0 to cloud here - let BaerenLock manage clearing from cloud when it uses the time
                // This prevents a race condition where BaerenLock might read 0 before it has a chance to read the actual minutes
                val profile = progressManager.getCurrentKid()
                val key = "${profile}_banked_reward_minutes"
                getSharedPreferences("daily_progress_prefs", MODE_PRIVATE)
                    .edit()
                    .putInt(key, 0)
                    .apply()
                Log.d(TAG, "Cleared banked reward minutes from BaerenEd local storage (cloud still has minutes for BaerenLock)")
            } else {
                Toast.makeText(this, "No reward time available", Toast.LENGTH_SHORT).show()
            }

            // Close this activity after launching BaerenLock
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Error launching BaerenLock", e)
            Toast.makeText(this, "Error launching reward app. Please try again.", Toast.LENGTH_SHORT).show()
            finish() // Ensure the activity finishes even on error
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

