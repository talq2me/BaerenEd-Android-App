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
            // Grant access for the specified time, which also launches BaerenLock
            Log.d(TAG, "Sending $remainingMinutes minutes to BaerenLock via Intent (should match report)")
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

        // Generate a unique transaction ID to prevent double-counting
        // This ID is used by both Intent and Broadcast, so BaerenLock can deduplicate
        val transactionId = System.currentTimeMillis()

        // Primary method: Send broadcast first (most reliable)
        // This ensures BaerenLock receives the reward time even if it's already running
        sendRewardTimeBroadcast(minutes, transactionId)

        // Secondary method: Try to launch BaerenLock's LauncherActivity directly with explicit Intent
        // This is more reliable than using ACTION_MAIN + CATEGORY_HOME which may not preserve extras
        try {
            val launcherIntent = Intent().apply {
                setClassName("com.talq2me.baerenlock", "com.talq2me.baerenlock.LauncherActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REWARD_MINUTES, minutes)
                putExtra("reward_transaction_id", transactionId)
            }
            startActivity(launcherIntent)
            Log.d(TAG, "Launched BaerenLock LauncherActivity directly with $minutes minutes (transaction ID: $transactionId).")
        } catch (e: Exception) {
            // Fallback: Use HOME intent if direct launch fails
            Log.w(TAG, "Failed to launch BaerenLock directly, trying HOME intent: ${e.message}")
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REWARD_MINUTES, minutes)
                putExtra("reward_transaction_id", transactionId)
            }
            startActivity(homeIntent)
            Log.d(TAG, "Launched BaerenLock via Home Intent with $minutes minutes (transaction ID: $transactionId).")
        }

        // Now that BaerenLock has been launched with the reward minutes, clear them from BaerenEd
        DailyProgressManager(this).clearBankedRewardMinutes()
    }

    /**
     * Sends a broadcast to BaerenLock with reward time as a fallback mechanism.
     * This ensures BaerenLock receives reward time even if Intent delivery via HOME action fails.
     * The transaction ID prevents double-counting if both Intent and Broadcast are received.
     */
    private fun sendRewardTimeBroadcast(minutes: Int, transactionId: Long) {
        try {
            val broadcastIntent = Intent("com.talq2me.baerenlock.ACTION_ADD_REWARD_TIME").apply {
                putExtra("reward_minutes", minutes)
                putExtra("reward_transaction_id", transactionId)
                setPackage("com.talq2me.baerenlock") // Explicitly target BaerenLock
            }
            sendBroadcast(broadcastIntent)
            Log.d(TAG, "Sent broadcast to BaerenLock with $minutes minutes (transaction ID: $transactionId, fallback mechanism)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending reward time broadcast (fallback)", e)
            // Don't fail the whole operation if broadcast fails
        }
    }
}

