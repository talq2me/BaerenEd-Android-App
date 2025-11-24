package com.talq2me.baerened

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RewardSelectionActivity : AppCompatActivity() {

    private var remainingMinutes: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get reward time from intent
        remainingMinutes = intent.getIntExtra("reward_minutes", 0)

        if (remainingMinutes <= 0) {
            Toast.makeText(this, "No reward time available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            // Grant access for the specified time, which also launches BaerenLock
            grantRewardAccess("com.talq2me.baerenlock", remainingMinutes) // Package name is arbitrary here as it's not used by BaerenLock for app-specific rewards

            // Close this activity after granting access and launching BaerenLock
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Error granting reward access or launching BaerenLock", e)
            Toast.makeText(this, "Error granting reward. Please try again.", Toast.LENGTH_SHORT).show()
            finish() // Ensure the activity finishes even on error
        }
    }

    private fun grantRewardAccess(packageName: String, minutes: Int) {
        Log.d(TAG, "Granting $minutes minutes to BaerenLock via Intent.")

        // Create an Intent to launch the home screen (BaerenLock)
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

        // Add reward minutes as an extra to the Intent
        homeIntent.putExtra("reward_minutes", minutes)
        startActivity(homeIntent)
        Log.d(TAG, "Launched BaerenLock via Home Intent with $minutes minutes.")

        // Now that BaerenLock has been launched with the reward minutes, clear them from BaerenEd
        DailyProgressManager(this).clearBankedRewardMinutes()
    }

    companion object {
        private const val TAG = "RewardSelectionActivity"
    }
}

