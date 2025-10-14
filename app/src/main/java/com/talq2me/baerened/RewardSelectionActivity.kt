package com.talq2me.baerened

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RewardSelectionActivity : AppCompatActivity() {

    private lateinit var gridLayout: GridLayout
    private lateinit var timerText: TextView
    private lateinit var backButton: Button

    private val rewardApps = mutableListOf<RewardApp>()
    private var selectedApp: RewardApp? = null
    private var remainingMinutes: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reward_selection)

        // Get reward time from intent
        remainingMinutes = intent.getIntExtra("reward_minutes", 0)

        if (remainingMinutes <= 0) {
            Toast.makeText(this, "No reward time available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Initialize views
        gridLayout = findViewById(R.id.rewardAppsGrid)
        timerText = findViewById(R.id.timerText)
        backButton = findViewById(R.id.backButton)

        // Setup timer display
        timerText.text = "Reward Time: $remainingMinutes minutes"

        // Load available reward apps
        loadRewardApps()

        // Setup back button
        backButton.setOnClickListener {
            finish()
        }
    }

    private fun loadRewardApps() {
        try {
            // Get reward apps from shared preferences (set by parent)
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val rewardAppsSet = prefs.getStringSet("reward_apps", emptySet())

            if (rewardAppsSet.isNullOrEmpty()) {
                showNoRewardApps()
                return
            }

            val pm = packageManager
            var appsFound = 0

            for (packageName in rewardAppsSet) {
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)

                    val rewardApp = RewardApp(packageName, label, icon, remainingMinutes)
                    rewardApps.add(rewardApp)
                    appsFound++

                } catch (e: Exception) {
                    Log.w(TAG, "Reward app not found: $packageName")
                }
            }

            if (appsFound == 0) {
                showNoRewardApps()
            } else {
                setupRewardGrid()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading reward apps", e)
            showNoRewardApps()
        }
    }

    private fun setupRewardGrid() {
        gridLayout.removeAllViews()
        gridLayout.columnCount = 2
        gridLayout.rowCount = (rewardApps.size + 1) / 2

        for (rewardApp in rewardApps) {
            val appView = createAppView(rewardApp)
            gridLayout.addView(appView)
        }
    }

    private fun createAppView(rewardApp: RewardApp): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            background = getDrawable(R.drawable.game_item_background)
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(8, 8, 8, 8)
            }
        }

        val iconView = ImageView(this).apply {
            setImageDrawable(rewardApp.icon)
            layoutParams = LinearLayout.LayoutParams(120, 120).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }

        val nameView = TextView(this).apply {
            text = rewardApp.name
            textSize = 14f
            setTextColor(android.graphics.Color.BLACK)
            gravity = android.view.Gravity.CENTER
            maxLines = 2
        }

        val timeView = TextView(this).apply {
            text = "${rewardApp.allowedTime} minutes"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            gravity = android.view.Gravity.CENTER
        }

        container.addView(iconView)
        container.addView(nameView)
        container.addView(timeView)

        container.setOnClickListener {
            selectRewardApp(rewardApp)
        }

        return container
    }

    private fun selectRewardApp(rewardApp: RewardApp) {
        selectedApp = rewardApp

        // Check if we have enough reward time
        if (remainingMinutes < rewardApp.allowedTime) {
            Toast.makeText(this,
                "Not enough reward time. You have $remainingMinutes minutes, need ${rewardApp.allowedTime} minutes.",
                Toast.LENGTH_LONG).show()
            return
        }

        // Launch the reward app
        try {
            val intent = packageManager.getLaunchIntentForPackage(rewardApp.packageName)
            if (intent != null) {
                // Grant access for the specified time
                grantRewardAccess(rewardApp.packageName, rewardApp.allowedTime)

                // Launch the app
                startActivity(intent)

                // Close this activity
                finish()
            } else {
                Toast.makeText(this, "Cannot launch ${rewardApp.name}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching reward app", e)
            Toast.makeText(this, "Error launching ${rewardApp.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun grantRewardAccess(packageName: String, minutes: Int) {
        // This would integrate with the RewardManager from BaerenLock
        // For now, we'll just log it
        Log.d(TAG, "Granting $minutes minutes access to $packageName")

        // In a real implementation, this would call:
        // RewardManager.grantAccess(this, packageName, minutes)
    }

    private fun showNoRewardApps() {
        val noAppsText = TextView(this).apply {
            text = "No reward apps configured.\nPlease ask a parent to set up reward apps in settings."
            textSize = 16f
            setTextColor(android.graphics.Color.BLACK)
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        gridLayout.addView(noAppsText)
    }

    companion object {
        private const val TAG = "RewardSelectionActivity"
    }
}

data class RewardApp(
    val packageName: String,
    val name: String,
    val icon: android.graphics.drawable.Drawable,
    val allowedTime: Int
)

