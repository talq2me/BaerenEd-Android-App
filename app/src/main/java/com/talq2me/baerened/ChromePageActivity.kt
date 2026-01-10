package com.talq2me.baerened

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import java.util.UUID

class ChromePageActivity : AppCompatActivity() {

    private var startTime: Long = 0
    private var url: String? = null
    private var taskId: String? = null  // The task launch ID for completion tracking
    private var sectionId: String? = null  // The section ID (required/optional)
    private var stars: Int = 0
    private var taskTitle: String? = null
    private var hasLaunchedCustomTab = false
    private var hasReportedResult = false
    private var hasBeenPaused = false  // Track if we've been paused (Chrome is active)
    private var customTabsSession: CustomTabsSession? = null
    private var customTabsClient: CustomTabsClient? = null
    private var customTabsServiceConnection: CustomTabsServiceConnection? = null
    private lateinit var timeTracker: TimeTracker
    private var pageVisited = false
    private lateinit var webGameProgress: WebGameProgress
    private var isJeLisRewardTimeSetup = false
    private var targetUri: Uri? = null

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TASK_ID = "task_id"  // The task launch ID for completion tracking
        const val EXTRA_SECTION_ID = "section_id"  // The section ID (required/optional)
        const val EXTRA_STARS = "stars"
        const val EXTRA_TASK_TITLE = "task_title"
        private const val MIN_TIME_SECONDS = 60
        private const val TAG = "ChromePageActivity"
        private const val CHROME_PACKAGE = "com.android.chrome"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        url = intent.getStringExtra(EXTRA_URL)
        taskId = intent.getStringExtra(EXTRA_TASK_ID)
        sectionId = intent.getStringExtra(EXTRA_SECTION_ID)
        stars = intent.getIntExtra(EXTRA_STARS, 0)
        taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)

        if (url.isNullOrBlank()) {
            Toast.makeText(this, "No page specified.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // For Je Lis, ensure reward time is available before launching Chrome
        // This prevents the confusing flow where Chrome gets blocked, then redirects to BaerenLock
        if (taskId == "jeLis") {
            ensureRewardTimeForJeLis()
        }

        // Initialize time tracker
        timeTracker = TimeTracker(this)

        // Initialize web game progress tracker
        val gameId = taskId ?: url ?: "unknown"
        webGameProgress = WebGameProgress(this, gameId)
        
        // Use unique task ID that includes section info to track separately for required vs optional
        val progressManager = DailyProgressManager(this)
        val currentTaskId = taskId
        val currentSectionId = sectionId
        val uniqueTaskId = if (currentTaskId != null && currentSectionId != null) {
            progressManager.getUniqueTaskId(currentTaskId, currentSectionId)
        } else {
            currentTaskId ?: "chromepage"
        }
        
        // Start tracking time for this page visit
        val pageName = taskTitle ?: taskId ?: url ?: "Web Page"
        timeTracker.startActivity(uniqueTaskId, "chromepage", pageName)

        targetUri = Uri.parse(url)

        // For Je Lis, wait for reward time setup before launching Chrome
        if (taskId == "jeLis" && !isJeLisRewardTimeSetup) {
            // Wait for reward time to be set up, then launch Chrome
            // Give BaerenLock a moment to process the reward time broadcast
            Handler(Looper.getMainLooper()).postDelayed({
                launchChromeAfterRewardTimeSetup()
            }, 800) // Give BaerenLock 800ms to process reward time from broadcast/cloud
            return
        }

        // Connect to Custom Tabs service to better track timing
        connectAndLaunchChrome()
    }

    private fun ensureRewardTimeForJeLis() {
        try {
            val progressManager = DailyProgressManager(this)
            val currentMinutes = progressManager.getBankedRewardMinutes()
            
            if (currentMinutes > 0) {
                Log.d(TAG, "Je Lis detected: Found $currentMinutes banked reward minutes, syncing to cloud")
                
                // Sync reward minutes to cloud (so BaerenLock can read them)
                // This is the same pattern used by RewardSelectionActivity
                progressManager.setBankedRewardMinutes(currentMinutes)
                
                // Send broadcast to BaerenLock to start reward time (background operation)
                // This doesn't bring BaerenLock to foreground
                try {
                    val transactionId = UUID.randomUUID().mostSignificantBits
                    val broadcastIntent = Intent("com.talq2me.baerenlock.ACTION_ADD_REWARD_TIME").apply {
                        setPackage("com.talq2me.baerenlock")
                        putExtra("reward_minutes", currentMinutes)
                        putExtra("reward_transaction_id", transactionId)
                    }
                    sendBroadcast(broadcastIntent)
                    Log.d(TAG, "Sent broadcast to BaerenLock to start reward time for Je Lis ($currentMinutes minutes)")
                    
                    // Clear the banked minutes from BaerenEd local storage AFTER successful sync to cloud
                    // This matches the pattern used in RewardSelectionActivity
                    // BaerenLock will manage clearing from cloud when it uses the time
                    // Only clear if we successfully synced to cloud and sent the broadcast
                    val profile = progressManager.getCurrentKid()
                    val key = "${profile}_banked_reward_minutes"
                    getSharedPreferences("daily_progress_prefs", MODE_PRIVATE)
                        .edit()
                        .putInt(key, 0)
                        .apply()
                    Log.d(TAG, "Cleared banked reward minutes from BaerenEd local storage (cloud still has minutes for BaerenLock)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send broadcast to BaerenLock", e)
                    // Don't clear banked minutes if broadcast failed - BaerenLock will read from cloud
                    // The minutes are already synced to cloud, so BaerenLock can still use them
                    Log.d(TAG, "Banked minutes remain in BaerenEd (already synced to cloud for BaerenLock)")
                }
            } else {
                Log.d(TAG, "Je Lis detected: No banked reward minutes available")
                // BaerenLock might already have reward time available
            }
            
            // Mark as setup so Chrome can launch
            // We give BaerenLock a moment to process the broadcast, then launch Chrome
            isJeLisRewardTimeSetup = true
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring reward time for Je Lis", e)
            // Continue anyway - launch Chrome and let BaerenLock handle blocking if needed
            // Don't clear any banked minutes on error - preserve existing reward management
            isJeLisRewardTimeSetup = true
        }
    }

    private fun launchChromeAfterRewardTimeSetup() {
        if (targetUri == null) {
            Log.e(TAG, "targetUri is null, cannot launch Chrome")
            finish()
            return
        }
        connectAndLaunchChrome()
    }

    private fun connectAndLaunchChrome() {
        if (targetUri == null) {
            Log.e(TAG, "targetUri is null, cannot launch Chrome")
            finish()
            return
        }

        // Connect to Custom Tabs service to better track timing
        customTabsServiceConnection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                customTabsClient = client
                customTabsClient?.warmup(0L)
                customTabsSession = customTabsClient?.newSession(null)
                launchCustomTab(targetUri!!)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                customTabsClient = null
                customTabsSession = null
            }
        }

        val connected = CustomTabsClient.bindCustomTabsService(
            this,
            CHROME_PACKAGE,
            customTabsServiceConnection!!
        )

        if (!connected) {
            // Fallback: launch without service connection
            Log.w(TAG, "Could not bind to Custom Tabs service, launching directly")
            launchCustomTab(targetUri!!)
        }
    }

    private fun launchCustomTab(uri: Uri) {
        val builder = CustomTabsIntent.Builder(customTabsSession).apply {
            setShowTitle(false)
            setUrlBarHidingEnabled(true)
            setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            setInstantAppsEnabled(false)
            // Try to minimize menu options
            setShowTitle(false)
            val toolbarColor = ContextCompat.getColor(this@ChromePageActivity, android.R.color.black)
            setToolbarColor(toolbarColor)
        }

        val customTabsIntent = builder.build().apply {
            // Force Chrome package
            intent.setPackage(CHROME_PACKAGE)
        }

        runCatching {
            hasLaunchedCustomTab = true
            customTabsIntent.launchUrl(this, uri)
            Log.d(TAG, "Custom tab launched, waiting for onPause to start timing")
        }.onFailure {
            Log.e(TAG, "Failed to launch custom tab", it)
            Toast.makeText(this, "Unable to open Chrome.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // Mark that we've been paused (Chrome is now active)
        if (hasLaunchedCustomTab) {
            hasBeenPaused = true
            // If we haven't set start time yet, set it now
            if (startTime == 0L) {
                startTime = SystemClock.elapsedRealtime()
                Log.d(TAG, "Start time set in onPause: $startTime")
            } else {
                Log.d(TAG, "Activity paused (Chrome is active), startTime was: $startTime")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Ignore onResume if we haven't launched the tab yet, already reported result, or haven't been paused yet
        // (onResume is called immediately after launch, but we only want to check timing when user returns from Chrome)
        if (!hasLaunchedCustomTab || hasReportedResult || !hasBeenPaused) {
            if (!hasBeenPaused) {
                Log.d(TAG, "onResume called but not yet paused (Chrome not active yet), ignoring")
            }
            return
        }

        // If startTime is still 0, something went wrong - set it now as fallback
        if (startTime == 0L) {
            Log.w(TAG, "onResume called but startTime is 0, setting now as fallback")
            startTime = SystemClock.elapsedRealtime()
            return
        }

        // User has returned from Chrome - check timing
        hasReportedResult = true
        val elapsedTime = SystemClock.elapsedRealtime() - startTime
        val elapsedSeconds = elapsedTime / 1000
        
        Log.d(TAG, "User returned from Chrome. Elapsed time: ${elapsedSeconds} seconds (required: $MIN_TIME_SECONDS)")

        if (elapsedSeconds >= MIN_TIME_SECONDS) {
            // Mark page as visited and end time tracking
            pageVisited = true
            timeTracker.endActivity("chromepage")
            // Update stars earned for the completed session
            timeTracker.updateStarsEarned("chromepage", stars)
            
            val resultIntent = Intent().apply {
                taskId?.let { putExtra(EXTRA_TASK_ID, it) }  // Pass task ID for completion tracking
                sectionId?.let { putExtra(EXTRA_SECTION_ID, it) }  // Pass section ID
                putExtra(EXTRA_STARS, stars)
                taskTitle?.let { putExtra(EXTRA_TASK_TITLE, it) }
            }
            setResult(RESULT_OK, resultIntent)
            Log.d(TAG, "Task completed successfully, granting reward")
        } else {
            // End time tracking even if not completed (user didn't spend enough time)
            timeTracker.endActivity("chromepage")
            
            val remainingSeconds = MIN_TIME_SECONDS - elapsedSeconds.toInt()
            Toast.makeText(
                this, 
                "You must spend at least $MIN_TIME_SECONDS seconds on this task. You spent ${elapsedSeconds}s. Need ${remainingSeconds}s more.", 
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_CANCELED)
            Log.d(TAG, "Task not completed - insufficient time")
        }
        finish()
    }

    override fun onDestroy() {
        // End time tracking if not already ended
        if (!pageVisited) {
            timeTracker.endActivity("chromepage")
        }
        
        super.onDestroy()
        customTabsServiceConnection?.let {
            unbindService(it)
        }
        customTabsServiceConnection = null
        customTabsClient = null
        customTabsSession = null
    }
}
