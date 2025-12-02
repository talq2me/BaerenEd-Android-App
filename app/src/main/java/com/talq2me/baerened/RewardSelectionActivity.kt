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

        // Primary method: Create an Intent to launch the home screen (BaerenLock)
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

        // Add reward minutes and transaction ID as extras to the Intent
        homeIntent.putExtra(EXTRA_REWARD_MINUTES, minutes)
        homeIntent.putExtra("reward_transaction_id", transactionId)
        startActivity(homeIntent)
        Log.d(TAG, "Launched BaerenLock via Home Intent with $minutes minutes (transaction ID: $transactionId).")

        // Fallback method: Send broadcast in case Intent delivery fails
        // This ensures BaerenLock receives the reward time even if it's already running
        // Use the same transaction ID so BaerenLock knows it's the same reward
        sendRewardTimeBroadcast(minutes, transactionId)

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

