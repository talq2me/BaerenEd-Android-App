package com.talq2me.baerened

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import com.google.gson.Gson
import com.talq2me.baerened.ProfileButton
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Layout manager class responsible for creating and managing UI layouts
 * for the MainActivity. This separates layout concerns from business logic.
 */
class Layout(private val activity: MainActivity) {

    private val TAG = "Layout"

    // UI elements from MainActivity (accessed through the activity reference)
    private val headerLayout: LinearLayout get() = activity.headerLayout
    private val titleText: TextView get() = activity.titleText
    private val progressLayout: LinearLayout get() = activity.progressLayout
    private val progressText: TextView get() = activity.progressText
    private val progressBar: ProgressBar get() = activity.progressBar
    private val sectionsContainer: LinearLayout get() = activity.sectionsContainer

    // Progress management
    private val progressManager = DailyProgressManager(activity)
    
    // Runnable for pending section setup to prevent duplicates
    private var pendingSectionsSetup: Runnable? = null

    // Icon configuration
    private var iconConfig: IconConfig? = null
    
    data class IconConfig(
        val starsIcon: String = "🍍",
        val coinsIcon: String = "🪙"
    )
    
    private fun loadIconConfig(): IconConfig {
        if (iconConfig == null) {
            try {
                val inputStream = activity.assets.open("config/icon_config.json")
                val configJson = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                iconConfig = Gson().fromJson(configJson, IconConfig::class.java) ?: IconConfig()
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error loading icon config", e)
                iconConfig = IconConfig() // Use defaults
            }
        }
        return iconConfig!!
    }

    /**
     * Display the main content by setting up all UI components
     */
    fun displayContent(content: MainContent) {
        android.util.Log.d("Layout", "displayContent called with content: ${content.title}")
        android.util.Log.d("Layout", "Content sections count: ${content.sections?.size ?: 0}")

        // Display header buttons - use config buttons if available, otherwise use default
        headerLayout.visibility = View.VISIBLE
        if (content.header?.buttons != null && content.header.buttons.isNotEmpty()) {
            setupHeaderButtons(content.header.buttons)
        } else {
            setupDefaultHeaderButtons()
        }

        // Display title with version and profile
        val baseTitle = content.title ?: "BaerenEd"
        val version = activity.getAppVersion()
        val profile = SettingsManager.readProfile(activity) ?: "A"
        val profileDisplay = if (profile == "A") "AM" else "BM"
        
        // Format: "Daily Homework Dashboard v# - AM" (or BM)
        // Extract base title if it already includes profile, otherwise use as-is
        val titleWithoutProfile = if (baseTitle.endsWith(" AM") || baseTitle.endsWith(" BM")) {
            baseTitle.substring(0, baseTitle.length - 3).trim()
        } else {
            baseTitle
        }
        
        titleText.text = "$titleWithoutProfile v$version - $profileDisplay"
        titleText.visibility = View.VISIBLE

        // Setup progress with dynamic calculation
        if (content.progress != null) {
            progressLayout.visibility = View.VISIBLE
            setupProgress(content.progress, content)
        } else {
            progressLayout.visibility = View.GONE
            // Always calculate totals from config to ensure correct caching
            progressManager.calculateTotalsFromConfig(content)
        }

        // Display battle hub at the top (embedded WebView)
        setupBattleHub()
        setupGymMap()

        // Display sections (tasks and buttons) - defer heavy view creation to avoid blocking
        sectionsContainer.visibility = View.VISIBLE
        // Cancel any pending section setup to prevent duplicate sections
        pendingSectionsSetup?.let { sectionsContainer.removeCallbacks(it) }
        // Create new runnable and store it
        pendingSectionsSetup = Runnable {
            setupSections(content.sections ?: emptyList())
            pendingSectionsSetup = null
        }
        // Use post() to defer view creation to next frame, allowing UI to render first
        sectionsContainer.post(pendingSectionsSetup!!)

        android.util.Log.d("Layout", "displayContent completed")
    }

    private fun setupHeaderButtons(buttons: List<Button>) {
        headerLayout.removeAllViews()

        buttons.forEach { button ->
            val btn = android.widget.Button(activity).apply {
                text = button.label ?: "Button"
                textSize = 18f // Match game screen button text size
                setOnClickListener {
                    activity.handleHeaderButtonClick(button.action)
                }

                // Style buttons to match game screen - simple rounded style
                layoutParams = LinearLayout.LayoutParams(
                    140.dpToPx(), // 140dp width like game screen
                    70.dpToPx()  // 70dp height like game screen
                ).apply {
                    marginEnd = 12.dpToPx() // 12dp margin like game screen
                }

                background = activity.resources.getDrawable(R.drawable.button_rounded)
                setTextColor(activity.resources.getColor(android.R.color.white))
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())

                // Set text with consistent symbols for standard actions
                when (button.action) {
                    "settings" -> {
                        text = "⚙ Settings"
                    }
                    "openPokedex" -> {
                        text = "📱 My Pokedex"
                    }
                    "askForTime" -> {
                        text = "⏰ Ask for Time"
                    }
                    // Otherwise use the label from config
                }
            }
            headerLayout.addView(btn)
        }
    }

    private fun setupDefaultHeaderButtons() {
        headerLayout.removeAllViews()

        // Create default navigation buttons
        val defaultButtons = listOf(
            Button("⚙ Settings", "settings")
        )

        defaultButtons.forEach { button ->
            val btn = android.widget.Button(activity).apply {
                text = button.label ?: "Button"
                textSize = 18f // Match game screen button text size
                setOnClickListener {
                    activity.handleHeaderButtonClick(button.action)
                }

                // Style buttons to match game screen - simple rounded style
                layoutParams = LinearLayout.LayoutParams(
                    140.dpToPx(), // 140dp width like game screen
                    70.dpToPx()  // 70dp height like game screen
                ).apply {
                    marginEnd = 12.dpToPx() // 12dp margin like game screen
                }

                background = activity.resources.getDrawable(R.drawable.button_rounded)
                setTextColor(activity.resources.getColor(android.R.color.white))
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            }
            headerLayout.addView(btn)
        }
    }

    private fun addMyPokedexButton() {
        val pokedexButton = android.widget.Button(activity).apply {
            text = "📱 My Pokedex"
            textSize = 16f
            setTextColor(activity.resources.getColor(android.R.color.white))
            background = activity.resources.getDrawable(R.drawable.button_rounded)
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                70.dpToPx()  // Same height as nav buttons
            ).apply {
                setMargins(8.dpToPx(), 0, 0, 0)
            }

            setOnClickListener {
                activity.openPokemonCollection()
            }
        }

        headerLayout.addView(pokedexButton)
    }

    private fun setupProgress(progress: Progress, content: MainContent) {
        // Use the provided content for accurate calculation
        val progressData = progressManager.getCurrentProgressWithTotals(content)
        val earnedCoins = progressData.first.first
        val totalCoins = progressData.first.second
        val earnedStars = progressData.second.first
        val totalStars = progressData.second.second

        // Get actual banked reward minutes (not recalculated from stars)
        val rewardMinutes = progressManager.getBankedRewardMinutes()

        val icons = loadIconConfig()
        progressText.text = "$earnedCoins/$totalCoins ${icons.coinsIcon} + $earnedStars ${icons.starsIcon} = $rewardMinutes mins - ${progress.message ?: "Complete tasks to earn coins and stars!"}"
        progressBar.max = totalCoins
        progressBar.progress = earnedCoins

        // Add Pokemon button if all coins earned AND no Pokemon unlocked today
        if (progressManager.shouldShowPokemonUnlockButton(content)) {
            addPokemonButtonToProgressLayout()
        } else {
            removePokemonButtonFromProgressLayout()
        }

        // Add/Remove Use Rewards button based on banked minutes
        if (rewardMinutes > 0) {
            addUseRewardsButtonToProgressLayout(rewardMinutes)
        } else {
            removeUseRewardsButtonFromProgressLayout()
        }

        // Force refresh of progress display to ensure it shows correct values
        refreshProgressDisplay()
    }

    private fun updateProgressDisplay() {
        val currentContent = activity.getCurrentMainContent()
        if (currentContent != null) {
            // Get current progress with accurate calculation
            val progressData = progressManager.getCurrentProgressWithTotals(currentContent)
            val earnedCoins = progressData.first.first
            val totalCoins = progressData.first.second
            val earnedStars = progressData.second.first
            val totalStars = progressData.second.second

            // Get actual banked reward minutes (not recalculated from stars)
            val rewardMinutes = progressManager.getBankedRewardMinutes()

            val icons = loadIconConfig()
            progressText.text = "$earnedCoins/$totalCoins ${icons.coinsIcon} + $earnedStars ${icons.starsIcon} = $rewardMinutes mins - Complete tasks to earn coins and stars!"
            progressBar.max = totalCoins
            progressBar.progress = earnedCoins

            // Update Pokemon button visibility
            if (progressManager.shouldShowPokemonUnlockButton(currentContent)) {
                addPokemonButtonToProgressLayout()
            } else {
                removePokemonButtonFromProgressLayout()
            }

            // Update Use Rewards button visibility
            if (rewardMinutes > 0) {
                addUseRewardsButtonToProgressLayout(rewardMinutes)
            } else {
                removeUseRewardsButtonFromProgressLayout()
            }
        } else {
            // Fallback - use cached values but ensure they're calculated correctly
            val progressData = progressManager.getCurrentProgressWithTotals()
            val earnedCoins = progressData.first.first
            val totalCoins = progressData.first.second
            val earnedStars = progressData.second.first
            val totalStars = progressData.second.second

            // Get actual banked reward minutes (not recalculated from stars)
            val rewardMinutes = progressManager.getBankedRewardMinutes()

            val icons = loadIconConfig()
            progressText.text = "$earnedCoins/$totalCoins ${icons.coinsIcon} + $earnedStars ${icons.starsIcon} = $rewardMinutes mins - Complete tasks to earn coins and stars!"
            progressBar.max = totalCoins
            progressBar.progress = earnedCoins
        }
    }

    /**
     * Refreshes the progress display (can be called from other activities)
     */
    fun refreshProgressDisplay() {
        updateProgressDisplay()
    }

    private suspend fun fetchJsonFromUrl(urlString: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var reader: BufferedReader? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connect()

            val stream = connection.inputStream
            reader = BufferedReader(InputStreamReader(stream))
            val buffer = StringBuffer()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                buffer.append(line)
            }
            buffer.toString()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error fetching JSON from URL: $urlString", e)
            null
        } finally {
            connection?.disconnect()
            reader?.close()
        }
    }

    /**
     * Refreshes the header buttons (for when Pokemon are unlocked)
     */
    fun refreshHeaderButtons() {
        // Re-setup default header buttons to include My Pokedex if needed
        setupDefaultHeaderButtons()
    }

    /**
     * Refreshes the sections to update task completion visual state
     */
    fun refreshSections() {
        val currentContent = activity.getCurrentMainContent()
        if (currentContent != null) {
            // Cancel any pending section setup to prevent duplicate sections
            pendingSectionsSetup?.let { sectionsContainer.removeCallbacks(it) }
            pendingSectionsSetup = null
            // Re-setup sections to update task completion states
            setupSections(currentContent.sections ?: emptyList())
        }
    }

    /**
     * Handles video completion and grants rewards if the video was watched to completion
     */
    fun handleVideoCompletion(taskId: String, taskTitle: String, stars: Int, videoFile: String? = null, videoIndex: Int? = null) {
        android.util.Log.d("Layout", "handleVideoCompletion called: taskId=$taskId, taskTitle=$taskTitle, stars=$stars, videoFile=$videoFile, videoIndex=$videoIndex")

        // If this is a sequential video that completed, save the video index
        // This allows the next play from this video list to advance to the next video
        if (videoFile != null && videoIndex != null) {
            val prefs = activity.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val currentKid = SettingsManager.readProfile(activity) ?: "A"
            prefs.edit()
                .putInt("${currentKid}_${videoFile}_index", videoIndex)
                .apply()
            android.util.Log.d("Layout", "Saved sequential video progress: ${currentKid}_${videoFile}_index = $videoIndex")
        }

        // Only grant rewards if this is a legitimate completion (not manual exit)
        // The video activities will only call this on actual completion
        if (stars > 0) {
            val progressManager = DailyProgressManager(activity)
            val currentContent = activity.getCurrentMainContent()
            // Videos are typically in required section, but pass config to verify
            val earnedStars = progressManager.markTaskCompletedWithName(
                taskId, 
                taskTitle, 
                stars, 
                false,  // Default to optional, config will verify
                currentContent  // Pass config to verify section
            )

            android.util.Log.d("Layout", "markTaskCompletedWithName returned: $earnedStars stars")

            if (earnedStars > 0) {
                // Add stars to reward bank and convert to minutes
                val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)

                android.util.Log.d("Layout", "Video task $taskId ($taskTitle) completed, earned $earnedStars stars = ${progressManager.convertStarsToMinutes(earnedStars)} minutes, total bank: $totalRewardMinutes minutes")

                // Show completion message first
                Toast.makeText(activity, "🎥 Video completed! Earned $earnedStars stars!", Toast.LENGTH_LONG).show()

                // Add berries to battle hub if task is from required or optional section
                val currentContent = activity.getCurrentMainContent()
                val isFromRequiredOrOptional = currentContent?.sections?.any { section ->
                    (section.id == "required" || section.id == "optional") && 
                    section.tasks?.any { it.launch == taskId } == true
                } ?: false
                
                if (isFromRequiredOrOptional) {
                    val berriesToAdd = earnedStars // 1 star = 1 berry
                    val savedBerries = activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                        .getInt("pendingBerries", 0)
                    activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putInt("pendingBerries", savedBerries + berriesToAdd)
                        .apply()
                    android.util.Log.d("Layout", "Added $berriesToAdd berries to battle hub from main page video task completion")
                    // Refresh battle hub to show updated berries
                    refreshBattleHub()
                }

                // Force refresh the entire content to ensure task completion is reflected
                if (currentContent != null) {
                    android.util.Log.d("Layout", "Re-displaying content to update task completion state")
                    displayContent(currentContent)
                    android.util.Log.d("Layout", "Content re-displayed, checking if task completion is now reflected")
                } else {
                    android.util.Log.e("Layout", "Current content is null, cannot refresh UI")
                }

                // Also refresh progress display
                refreshProgressDisplay()

                android.util.Log.d("Layout", "Task completion UI refresh completed")
            } else {
                android.util.Log.d("Layout", "Task $taskId was already completed today, no additional stars awarded")
                Toast.makeText(activity, "Task already completed today", Toast.LENGTH_SHORT).show()
            }
        } else {
            android.util.Log.d("Layout", "No stars to award for task $taskId")
        }
    }

    suspend fun handleWebGameCompletion(taskId: String?, sectionId: String?, stars: Int, taskTitle: String?) {
        android.util.Log.d(TAG, "handleWebGameCompletion called with taskId: $taskId, sectionId: $sectionId, stars: $stars")

        if (!taskId.isNullOrEmpty()) {
            val progressManager = DailyProgressManager(activity)
            val currentContent = activity.getCurrentMainContent()
            
            // Extract original gameId if taskId starts with "battleHub_"
            val actualTaskId = if (taskId.startsWith("battleHub_")) {
                taskId.substringAfter("battleHub_")
            } else {
                taskId
            }
            
            // Use task title from parameter or create default
            val displayTitle = taskTitle ?: "Web Game: $actualTaskId"
            
            // Determine if task is required based on section ID
            val isRequiredTask = sectionId == "required"
            
            // Pass config and sectionId to markTaskCompletedWithName so it can track tasks separately per section
            // Use actualTaskId (without battleHub_ prefix) for completion tracking
            val earnedStars = progressManager.markTaskCompletedWithName(
                actualTaskId, 
                displayTitle, 
                stars, 
                isRequiredTask,
                currentContent,  // Pass config to verify section
                sectionId  // Pass section ID to create unique task IDs
            )

            if (earnedStars > 0) {
                val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)

                android.util.Log.d("Layout", "Web game completed (taskId=$taskId, section=$sectionId), earned $earnedStars stars = ${progressManager.convertStarsToMinutes(earnedStars)} minutes, total bank: $totalRewardMinutes minutes")

                Toast.makeText(activity, "🎮 Web game completed! Earned $earnedStars stars!", Toast.LENGTH_LONG).show()

                // Add berries to battle hub if task is from required or optional section
                if (sectionId == "required" || sectionId == "optional") {
                    val berriesToAdd = earnedStars // 1 star = 1 berry
                    val savedBerries = activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                        .getInt("pendingBerries", 0)
                    activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putInt("pendingBerries", savedBerries + berriesToAdd)
                        .apply()
                    android.util.Log.d("Layout", "Added $berriesToAdd berries to battle hub from main page task completion")
                    // Refresh battle hub and gym map to show updated state
                    refreshBattleHub()
                    refreshGymMap()
                }

                if (currentContent != null) {
                    android.util.Log.d("Layout", "Re-displaying content to update task completion state")
                    displayContent(currentContent)
                } else {
                    android.util.Log.e("Layout", "Current content is null, cannot refresh UI")
                }
                refreshProgressDisplay()
            } else {
                android.util.Log.d("Layout", "Web game task $taskId was already completed today, no additional stars awarded")
                Toast.makeText(activity, "Web game already completed today", Toast.LENGTH_SHORT).show()
            }
        } else {
            android.util.Log.d("Layout", "No taskId provided for web game completion.")
            Toast.makeText(activity, "Web game completed, but no reward was issued.", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleChromePageCompletion(taskId: String, taskTitle: String, stars: Int, sectionId: String?) {
        android.util.Log.d(TAG, "handleChromePageCompletion: taskId=$taskId, taskTitle=$taskTitle, stars=$stars, sectionId=$sectionId")

        val progressManager = DailyProgressManager(activity)
        val currentContent = activity.getCurrentMainContent()
        
        // Determine if task is required based on section ID
        val isRequiredTask = sectionId == "required"
        
        // Pass config and sectionId to markTaskCompletedWithName so it can track tasks separately per section
        val earnedStars = progressManager.markTaskCompletedWithName(
            taskId, 
            taskTitle, 
            stars, 
            isRequiredTask,
            currentContent,  // Pass config to verify section
            sectionId  // Pass section ID to create unique task IDs
        )

        if (earnedStars > 0) {
            val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)
            android.util.Log.d(TAG, "Chrome page completed (taskId=$taskId, section=$sectionId), earned $earnedStars stars, total reward minutes: $totalRewardMinutes")
            Toast.makeText(activity, "🌐 Task completed! Earned $earnedStars stars!", Toast.LENGTH_LONG).show()
            
            // Add berries to battle hub if task is from required or optional section
            if (sectionId == "required" || sectionId == "optional") {
                val berriesToAdd = earnedStars // 1 star = 1 berry
                val savedBerries = activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                    .getInt("pendingBerries", 0)
                activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt("pendingBerries", savedBerries + berriesToAdd)
                    .apply()
                android.util.Log.d(TAG, "Added $berriesToAdd berries to battle hub from main page task completion")
                // Refresh battle hub to show updated berries
                refreshBattleHub()
            }
        } else {
            android.util.Log.d(TAG, "Chrome page task $taskId already completed today or no stars to award")
            Toast.makeText(activity, "Task already completed today", Toast.LENGTH_SHORT).show()
        }

        if (currentContent != null) {
            android.util.Log.d(TAG, "Refreshing content after Chrome page completion")
            displayContent(currentContent)
        }
        refreshProgressDisplay()
    }

    fun handleManualTaskCompletion(
        taskId: String,
        taskTitle: String,
        stars: Int,
        sectionId: String?,
        completionMessage: String = "Task completed!"
    ) {
        android.util.Log.d(TAG, "handleManualTaskCompletion: taskId=$taskId, sectionId=$sectionId, stars=$stars")
        val currentContent = activity.getCurrentMainContent()
        val isRequiredTask = sectionId == "required"
        val earnedStars = progressManager.markTaskCompletedWithName(
            taskId,
            taskTitle,
            stars,
            isRequiredTask,
            currentContent,
            sectionId
        )

        if (earnedStars > 0) {
            val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)
            android.util.Log.d(
                TAG,
                "Manual task completion awarded $earnedStars stars (${progressManager.convertStarsToMinutes(earnedStars)} mins). Total bank: $totalRewardMinutes"
            )
            Toast.makeText(activity, completionMessage, Toast.LENGTH_LONG).show()
        } else {
            android.util.Log.d(TAG, "Manual task $taskId already completed today")
            Toast.makeText(activity, "Task already completed today", Toast.LENGTH_SHORT).show()
        }

        currentContent?.let { displayContent(it) }
        refreshProgressDisplay()
    }

    private fun addPokemonButtonToProgressLayout() {
        // Check if Pokemon button already exists
        if (progressLayout.findViewWithTag<View>("pokemon_button") != null) {
            return
        }

        // Create Pokemon button
        val pokemonButton = android.widget.Button(activity).apply {
            tag = "pokemon_button"
            text = "Unlock Pokemon"
            textSize = 16f
            setTextColor(activity.resources.getColor(android.R.color.white))
            background = activity.resources.getDrawable(R.drawable.button_rounded)
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                70.dpToPx()  // Same height as nav buttons
            ).apply {
                setMargins(16.dpToPx(), 0, 0, 0)
            }

            setOnClickListener {
                // Auto-unlock one Pokemon and record the unlock
                val currentUnlocked = progressManager.getUnlockedPokemonCount()
                progressManager.setUnlockedPokemonCount(currentUnlocked + 1)
                progressManager.recordPokemonUnlockToday()

                // Refresh progress display to hide the button (since we unlocked a Pokemon today)
                refreshProgressDisplay()

                // Refresh header buttons to show My Pokedex if this is the first Pokemon
                refreshHeaderButtons()

                // Open Pokemon collection
                activity.openPokemonCollection()
            }
        }

        progressLayout.addView(pokemonButton)
    }

    private fun removePokemonButtonFromProgressLayout() {
        val pokemonButton = progressLayout.findViewWithTag<View>("pokemon_button")
        pokemonButton?.let {
            progressLayout.removeView(it)
        }
    }

    private fun addUseRewardsButtonToProgressLayout(rewardMinutes: Int) {
        // Check if Use Rewards button already exists
        var useRewardsButton = progressLayout.findViewWithTag<android.widget.Button>("use_rewards_button")

        if (useRewardsButton != null) {
            // If it exists, just update its text
            useRewardsButton.text = "🎮 Use Rewards (${rewardMinutes} mins)"
            return
        }

        // Create Use Rewards button if it doesn't exist
        useRewardsButton = android.widget.Button(activity).apply {
            tag = "use_rewards_button"
            text = "🎮 Use Rewards (${rewardMinutes} mins)"
            textSize = 16f
            setTextColor(activity.resources.getColor(android.R.color.white))
            background = activity.resources.getDrawable(R.drawable.button_rounded)
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                70.dpToPx()  // Same height as nav buttons
            ).apply {
                setMargins(16.dpToPx(), 0, 0, 0)
            }

            setOnClickListener {
                useRewardMinutes()
            }
        }

        progressLayout.addView(useRewardsButton)
    }

    private fun removeUseRewardsButtonFromProgressLayout() {
        val useRewardsButton = progressLayout.findViewWithTag<View>("use_rewards_button")
        useRewardsButton?.let {
            progressLayout.removeView(it)
        }
    }

    private fun useRewardMinutes() {
        val rewardMinutes = progressManager.getBankedRewardMinutes() // Use banked minutes directly
        android.util.Log.d("Layout", "useRewardMinutes: Retrieved $rewardMinutes banked reward minutes")
        if (rewardMinutes > 0) {
            // Send progress report using the launcher - this will show the email app first,
            // and then launch reward selection after the user returns from email
            try {
                if (activity is MainActivity) {
                    android.util.Log.d("Layout", "Sending report with $rewardMinutes minutes (will match BaerenLock Intent)")
                    (activity as MainActivity).sendProgressReportForRewardTime(rewardMinutes.toInt())
                } else {
                    // Fallback if not MainActivity (shouldn't happen, but just in case)
                    val intent = Intent(activity, RewardSelectionActivity::class.java).apply {
                        putExtra(RewardSelectionActivity.EXTRA_REWARD_MINUTES, rewardMinutes.toInt())
                    }
                    activity.startActivity(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error sending progress report when using reward time", e)
                // If there's an error, still try to launch reward selection
                try {
                    val intent = Intent(activity, RewardSelectionActivity::class.java).apply {
                        putExtra(RewardSelectionActivity.EXTRA_REWARD_MINUTES, rewardMinutes.toInt())
                    }
                    activity.startActivity(intent)
                } catch (e2: Exception) {
                    android.util.Log.e("Layout", "Error launching reward selection", e2)
                }
            }

            // Refresh progress display to update the UI
            refreshProgressDisplay()

            // Show confirmation message
            android.widget.Toast.makeText(activity, "📧 Sending progress report, then select a reward app! You have ${rewardMinutes.toInt()} minutes!", android.widget.Toast.LENGTH_LONG).show()

        } else {
            android.widget.Toast.makeText(activity, "No reward minutes to use!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun getGameModeUrl(originalUrl: String, easydays: String?, harddays: String?, extremedays: String?): String {
        // Parse the original URL first to get the existing mode if present
        val uri = android.net.Uri.parse(originalUrl)
        val originalMode = uri.getQueryParameter("mode")
        
        // If no day fields are specified, preserve the original mode from the URL
        val hasDayFields = !easydays.isNullOrEmpty() || !harddays.isNullOrEmpty() || !extremedays.isNullOrEmpty()
        if (!hasDayFields) {
            // No day fields specified, preserve original URL mode or default to "hard"
            val mode = originalMode ?: "hard"
            val builder = uri.buildUpon()
            builder.clearQuery()
            uri.queryParameterNames.forEach { name ->
                if (name != "mode") {
                    builder.appendQueryParameter(name, uri.getQueryParameter(name))
                }
            }
            builder.appendQueryParameter("mode", mode)
            return builder.build().toString()
        }

        // Day fields are specified, determine mode based on today
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val todayShort = when (today) {
            Calendar.MONDAY -> "mon"
            Calendar.TUESDAY -> "tue"
            Calendar.WEDNESDAY -> "wed"
            Calendar.THURSDAY -> "thu"
            Calendar.FRIDAY -> "fri"
            Calendar.SATURDAY -> "sat"
            Calendar.SUNDAY -> "sun"
            else -> ""
        }

        var mode = originalMode ?: "hard" // Use original mode as default if day doesn't match

        if (!easydays.isNullOrEmpty() && easydays.split(",").contains(todayShort)) {
            mode = "easy"
        } else if (!harddays.isNullOrEmpty() && harddays.split(",").contains(todayShort)) {
            mode = "hard"
        } else if (!extremedays.isNullOrEmpty() && extremedays.split(",").contains(todayShort)) {
            mode = "extreme"
        }

        // Parse the URL and add/update the 'mode' parameter
        val builder = uri.buildUpon()
        builder.clearQuery() // Clear existing query parameters

        // Re-add all original query parameters except 'mode'
        uri.queryParameterNames.forEach { name ->
            if (name != "mode") {
                builder.appendQueryParameter(name, uri.getQueryParameter(name))
            }
        }
        builder.appendQueryParameter("mode", mode) // Add or update 'mode'

        return builder.build().toString()
    }

    /**
     * Parses a date string in format "Nov 24, 2025" and returns a Calendar instance
     * Returns null if parsing fails
     */
    private fun parseDisableDate(dateString: String?): Calendar? {
        if (dateString.isNullOrEmpty()) return null
        
        return try {
            // Try parsing format like "Nov 24, 2025"
            val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.US)
            val date = formatter.parse(dateString.trim())
            if (date != null) {
                Calendar.getInstance().apply {
                    time = date
                    // Set time to start of day for accurate comparison
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            } else null
        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error parsing disable date: $dateString", e)
            null
        }
    }

    private fun isTaskVisible(showdays: String?, hidedays: String?, displayDays: String? = null, disable: String? = null): Boolean {
        // Check disable date first - if current date is before disable date, hide the task
        if (!disable.isNullOrEmpty()) {
            val disableDate = parseDisableDate(disable)
            if (disableDate != null) {
                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // If today is before the disable date, task is disabled (not visible)
                if (today.before(disableDate)) {
                    return false
                }
            }
        }

        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val todayShort = when (today) {
            Calendar.MONDAY -> "mon"
            Calendar.TUESDAY -> "tue"
            Calendar.WEDNESDAY -> "wed"
            Calendar.THURSDAY -> "thu"
            Calendar.FRIDAY -> "fri"
            Calendar.SATURDAY -> "sat"
            Calendar.SUNDAY -> "sun"
            else -> ""
        }

        if (!hidedays.isNullOrEmpty()) {
            if (hidedays.split(",").contains(todayShort)) {
                return false // Hide if today is in hidedays
            }
        }

        // Check displayDays first (if set, only show on those days)
        if (!displayDays.isNullOrEmpty()) {
            return displayDays.split(",").contains(todayShort) // Show only if today is in displayDays
        }

        if (!showdays.isNullOrEmpty()) {
            return showdays.split(",").contains(todayShort) // Show only if today is in showdays
        }

        return true // Visible by default if no restrictions
    }

    private fun handleVideoSequenceTask(task: Task, sectionId: String?) {
        val videoSequence = task.videoSequence ?: return

        when (videoSequence) {
            "playlist" -> {
                // Play YouTube playlist directly (no JSON file needed)
                // For playlist tasks, the playlist ID should be in the playlistId field,
                // but support launch field for backward compatibility
                val playlistId = task.playlistId ?: task.launch
                android.util.Log.d("Layout", "Playlist task detected, playlistId: $playlistId, task.launch: ${task.launch}, task.playlistId: ${task.playlistId}")
                if (playlistId != null) {
                    val playlistTitle = task.title ?: "Playlist"
                    playYouTubePlaylist(playlistId, playlistTitle)
                } else {
                    android.util.Log.e("Layout", "No playlist ID found for playlist task")
                }
            }
            else -> {
                // Handle video sequences that need JSON files
                val videoFile = task.launch ?: return

                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var videoJson: String? = null
                    try {
                        // Load the video JSON file (fetch from GitHub first, then cache, then assets)
                        videoJson = activity.contentUpdateService.fetchVideoContent(activity, videoFile)
                        android.util.Log.d("Layout", "Fetched video content for $videoFile: ${if (videoJson != null) "success" else "null"}")
                    } catch (e: Exception) {
                        android.util.Log.e("Layout", "Error fetching video content for $videoFile", e)
                    }

                    // If fetchVideoContent failed, try loading directly from assets as fallback
                    if (videoJson == null) {
                        try {
                            android.util.Log.d("Layout", "Trying to load video content directly from assets")
                            videoJson = activity.assets.open("videos/$videoFile.json").bufferedReader().use { it.readText() }
                            android.util.Log.d("Layout", "Successfully loaded video content from assets")
                        } catch (e: Exception) {
                            android.util.Log.e("Layout", "Error loading video content from assets for $videoFile", e)
                        }
                    }

                    if (videoJson == null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(activity, "Error loading video content", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        try {
                            when (videoSequence) {
                                "exact" -> {
                                    // Play specific video
                                    val videoName = task.video ?: return@withContext
                                    playYouTubeVideo(videoJson, videoName, task, sectionId)
                                }
                                "sequential" -> {
                                    // Play next video in sequence
                                    playNextVideoInSequence(videoJson, videoFile, task, sectionId)
                                }
                                else -> {
                                    // Unknown video sequence type
                                    Toast.makeText(activity, "Unknown video sequence type", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Layout", "Error playing video", e)
                            Toast.makeText(activity, "Error playing video", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        
        // Function to launch a task from battle hub
        fun launchTaskFromBattleHub(task: Task, sectionId: String) {
            val gameType = task.launch ?: "unknown"
            val gameTitle = task.title ?: "Task"
            
            // Check if this is a Chrome page task
            if (task.chromePage == true) {
                val pageUrl = task.url
                if (pageUrl.isNullOrBlank()) {
                    android.widget.Toast.makeText(activity, "No page configured for this task.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val taskLaunchId = task.launch ?: "unknown"
                    val intent = android.content.Intent(activity, ChromePageActivity::class.java).apply {
                        putExtra(ChromePageActivity.EXTRA_URL, pageUrl)
                        putExtra(ChromePageActivity.EXTRA_TASK_ID, taskLaunchId)
                        putExtra(ChromePageActivity.EXTRA_STARS, task.stars ?: 0)
                        putExtra(ChromePageActivity.EXTRA_TASK_TITLE, task.title ?: taskLaunchId)
                        putExtra(ChromePageActivity.EXTRA_SECTION_ID, sectionId)
                    }
                    activity.chromePageLauncher.launch(intent)
                }
            }
            // Check if this is a web game task
            else if (task.webGame == true && !task.url.isNullOrEmpty()) {
                android.util.Log.d("Layout", "Handling web game task from battle hub: ${task.title}, URL: ${task.url}")
                val taskLaunchId = task.launch ?: "unknown"
                val intent = android.content.Intent(activity, WebGameActivity::class.java).apply {
                    var gameUrl = getGameModeUrl(task.url, task.easydays, task.harddays, task.extremedays)
                    // Convert GitHub Pages URL to local asset URL for Android
                    if (gameUrl.contains("talq2me.github.io") && gameUrl.contains("/html/")) {
                        val fileName = gameUrl.substringAfterLast("/")
                        gameUrl = "file:///android_asset/html/$fileName"
                        android.util.Log.d("Layout", "Converted to local asset URL: $gameUrl")
                    }
                    putExtra(WebGameActivity.EXTRA_GAME_URL, gameUrl)
                    putExtra(WebGameActivity.EXTRA_TASK_ID, taskLaunchId)
                    putExtra(WebGameActivity.EXTRA_SECTION_ID, sectionId)
                    putExtra(WebGameActivity.EXTRA_STARS, task.stars ?: 0)
                    putExtra(WebGameActivity.EXTRA_TASK_TITLE, task.title ?: taskLaunchId)
                }
                activity.webGameCompletionLauncher.launch(intent)
            }
            else if (task.videoSequence != null) {
                android.util.Log.d("Layout", "Handling video sequence task from battle hub: ${task.videoSequence}, launch: ${task.launch}")
                handleVideoSequenceTask(task, sectionId)
            }
            else if (task.playlistId != null) {
                android.util.Log.d("Layout", "Handling direct playlist task from battle hub with playlistId: ${task.playlistId}")
                playYouTubePlaylist(task.playlistId, task.title ?: "Playlist")
            }
            else if (task.launch == "googleReadAlong") {
                android.util.Log.d("Layout", "Launching Google Read Along from battle hub")
                activity.launchGoogleReadAlong(task, sectionId)
            }
            else if (task.launch == "frenchBookReader") {
                android.util.Log.d("Layout", "Launching French Book Reader from battle hub")
                val intent = android.content.Intent(activity, FrenchBookReaderActivity::class.java)
                activity.startActivity(intent)
            }
            else {
                // Handle regular game content
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val gameContent = activity.contentUpdateService.fetchGameContent(activity, gameType)
                        
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val game = Game(
                                id = gameType,
                                title = gameTitle,
                                description = "Educational activity",
                                type = gameType,
                                iconUrl = "",
                                requiresRewardTime = false,
                                difficulty = "Easy",
                                estimatedTime = task.stars ?: 1,
                                totalQuestions = task.totalQuestions,
                                blockOutlines = task.blockOutlines ?: false
                            )
                            activity.startGame(game, gameContent, sectionId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Layout", "Error fetching game content for $gameType", e)
                        
                        // Fallback to starting game without content
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val game = Game(
                                id = gameType,
                                title = gameTitle,
                                description = "Educational activity",
                                type = gameType,
                                iconUrl = "",
                                requiresRewardTime = false,
                                difficulty = "Easy",
                                estimatedTime = task.stars ?: 1,
                                totalQuestions = task.totalQuestions,
                                blockOutlines = task.blockOutlines ?: false
                            )
                            activity.startGame(game, null, sectionId)
                        }
                    }
                }
            }
        }
    }

    private fun playYouTubeVideo(videoJson: String, videoName: String, task: Task, sectionId: String? = null) {
        try {
            val gson = com.google.gson.Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoId = videoMap[videoName]

            android.util.Log.d("Layout", "Looking for video: $videoName in JSON file, found videoId: $videoId")

            if (videoId != null) {
                // Launch our custom YouTube player activity for kid-safe viewing
                val intent = Intent(activity, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, videoName)
                    putExtra("TASK_ID", task.launch ?: "unknown")
                    putExtra("TASK_TITLE", task.title ?: "Video Task")
                    putExtra("TASK_STARS", task.stars ?: 0)
                    sectionId?.let { putExtra("SECTION_ID", it) }
                }
                android.util.Log.d("Layout", "Starting YouTube player with videoId: $videoId, videoName: $videoName, sectionId: $sectionId")
                activity.videoCompletionLauncher.launch(intent)
            } else {
                android.util.Log.e("Layout", "Video not found: $videoName in JSON: $videoJson")
                Toast.makeText(activity, "Video not found: $videoName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error parsing video JSON", e)
            Toast.makeText(activity, "Error playing video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playNextVideoInSequence(videoJson: String, videoFile: String, task: Task, sectionId: String? = null) {
        try {
            val gson = com.google.gson.Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoList = videoMap.keys.toList()

            // Safety check: ensure we have videos to play
            if (videoList.isEmpty()) {
                android.util.Log.e("Layout", "No videos found in $videoFile.json")
                Toast.makeText(activity, "No videos available", Toast.LENGTH_SHORT).show()
                return
            }

            // Get the last played video index for this kid and video file
            val prefs = activity.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val currentKid = SettingsManager.readProfile(activity) ?: "A"
            var lastVideoIndex = prefs.getInt("${currentKid}_${videoFile}_index", -1)
            
            // Validate that the saved index is still valid for the current video list
            // If videos were removed, the saved index might be out of bounds or point to wrong video
            if (lastVideoIndex >= videoList.size) {
                android.util.Log.d("Layout", "Saved index $lastVideoIndex is out of bounds for list size ${videoList.size}, resetting to 0")
                lastVideoIndex = -1 // Reset to start from beginning
                // Clear the invalid saved index
                prefs.edit().putInt("${currentKid}_${videoFile}_index", -1).apply()
            }
            
            // Determine which video to play - always advance to next video
            val videoIndexToPlay: Int = if (lastVideoIndex == -1) {
                // First time playing - start at beginning
                0
            } else {
                // Play next video in sequence (wraps around if at end)
                (lastVideoIndex + 1) % videoList.size
            }
            
            android.util.Log.d("Layout", "Sequential video selection for ${currentKid}_${videoFile} - Last index: $lastVideoIndex, Playing index: $videoIndexToPlay, List size: ${videoList.size}")
            
            val videoNameToPlay = videoList[videoIndexToPlay]

            // Play the video using the activity result launcher
            // Pass the video file and index info through the intent so we can save after completion
            playYouTubeVideoForSequence(videoJson, videoNameToPlay, task, videoFile, videoIndexToPlay, sectionId)

        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error handling sequential video", e)
            Toast.makeText(activity, "Error playing video sequence", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun playYouTubeVideoForSequence(videoJson: String, videoName: String, task: Task, videoFile: String, videoIndex: Int, sectionId: String? = null) {
        try {
            val gson = com.google.gson.Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoId = videoMap[videoName]

            android.util.Log.d("Layout", "Playing sequential video: $videoName (index: $videoIndex) from file: $videoFile")

            if (videoId != null) {
                // Launch YouTube player activity with sequence tracking info
                val intent = Intent(activity, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, videoName)
                    putExtra("TASK_ID", task.launch ?: "unknown")
                    putExtra("TASK_TITLE", task.title ?: "Video Task")
                    putExtra("TASK_STARS", task.stars ?: 0)
                    sectionId?.let { putExtra("SECTION_ID", it) }
                    putExtra("VIDEO_FILE", videoFile) // Store for index update after completion
                    putExtra("VIDEO_INDEX", videoIndex) // Store for index update after completion
                    putExtra("IS_SEQUENTIAL", true) // Flag to indicate this is a sequential video
                }
                android.util.Log.d("Layout", "Starting YouTube player with videoId: $videoId, videoName: $videoName, index: $videoIndex")
                activity.videoCompletionLauncher.launch(intent)
            } else {
                android.util.Log.e("Layout", "Video not found: $videoName in JSON")
                Toast.makeText(activity, "Video not found: $videoName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error parsing video JSON for sequence", e)
            Toast.makeText(activity, "Error playing video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playYouTubePlaylist(playlistId: String, playlistTitle: String) {
        android.util.Log.d("Layout", "Launching playlist player with ID: $playlistId, title: $playlistTitle")
        try {
            // Launch the unified YouTube player activity (no rewards for playlists)
            val intent = Intent(activity, YouTubePlayerActivity::class.java).apply {
                putExtra(YouTubePlayerActivity.EXTRA_PLAYLIST_ID, playlistId)
                putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, playlistTitle) // Use video title for consistency
            }
            android.util.Log.d("Layout", "Starting YouTubePlayerActivity for playlistId: $playlistId")
            activity.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error launching playlist player", e)
            Toast.makeText(activity, "Error playing playlist", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSections(sections: List<Section>) {
        android.util.Log.d("Layout", "setupSections called with ${sections.size} sections")
        // Remove all views except the battle hub and gym map WebViews
        val battleHubView = battleHubWebView
        val gymMapView = gymMapWebView
        sectionsContainer.removeAllViews()
        // Re-add battle hub and gym map if they exist
        battleHubView?.let {
            sectionsContainer.addView(it, 0)
        }
        gymMapView?.let {
            sectionsContainer.addView(it, 1)
        }

        // Pre-load all completed task statuses in one batch to avoid multiple SharedPreferences reads
        // This is a light operation as it just reads from memory (DailyProgressManager caches the map)
        val completedTasksMap = progressManager.getCompletedTasksMap()

        // Create sections incrementally across multiple frames to avoid blocking the main thread
        // This prevents Choreographer warnings about skipped frames
        createSectionsIncrementally(sections, completedTasksMap, 0)
    }

    private fun createSectionsIncrementally(
        sections: List<Section>,
        completedTasksMap: Map<String, Boolean>,
        index: Int
    ) {
        if (index >= sections.size) {
            android.util.Log.d("Layout", "setupSections completed")
            return
        }

        // Create one section per frame to avoid blocking
        sectionsContainer.post {
            val section = sections[index]
            android.util.Log.d("Layout", "Creating section ${index + 1}/${sections.size}: ${section.title}, tasks count: ${section.tasks?.size ?: 0}")
            val sectionView = createSectionView(section, completedTasksMap)
            sectionsContainer.addView(sectionView)
            
            // Continue with next section on next frame
            createSectionsIncrementally(sections, completedTasksMap, index + 1)
        }
    }

    private fun createTaskRow(
        visibleTasks: List<Task>,
        rowIndex: Int,
        buttonsPerRow: Int,
        sectionId: String,
        completedTasksMap: Map<String, Boolean>,
        density: Float
    ): LinearLayout {
        val rowContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 0)
            weightSum = buttonsPerRow.toFloat()
        }

        val startIndex = rowIndex * buttonsPerRow
        val endIndex = minOf(startIndex + buttonsPerRow, visibleTasks.size)

        for (i in startIndex until endIndex) {
            val task = visibleTasks[i]
            android.util.Log.d("Layout", "Creating task view: ${task.title}, launch=${task.launch}, stars=${task.stars}")
            val taskView = createTaskView(task, sectionId, completedTasksMap)
            rowContainer.addView(taskView)
        }

        // Add empty views to fill remaining spots if needed
        while (rowContainer.childCount < buttonsPerRow) {
            val emptyView = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (70 * density).toInt(),
                    1f
                ).apply {
                    setMargins((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                }
            }
            rowContainer.addView(emptyView)
        }

        return rowContainer
    }

    private fun createTaskRowsIncrementally(
        visibleTasks: List<Task>,
        sectionId: String,
        completedTasksMap: Map<String, Boolean>,
        tasksContainer: LinearLayout,
        buttonsPerRow: Int,
        density: Float,
        rowIndex: Int
    ) {
        val rows = (visibleTasks.size + buttonsPerRow - 1) / buttonsPerRow
        if (rowIndex >= rows) {
            return
        }

        // Create one row per frame
        tasksContainer.post {
            val rowContainer = createTaskRow(
                visibleTasks,
                rowIndex,
                buttonsPerRow,
                sectionId,
                completedTasksMap,
                density
            )
            tasksContainer.addView(rowContainer)
            
            // Continue with next row on next frame
            createTaskRowsIncrementally(
                visibleTasks,
                sectionId,
                completedTasksMap,
                tasksContainer,
                buttonsPerRow,
                density,
                rowIndex + 1
            )
        }
    }

    private fun createSectionView(section: Section, completedTasksMap: Map<String, Boolean>): View {
        val sectionLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12) // Reduced for compact layout
        }

        // Section title and description - Larger for tablet readability
        val titleView = TextView(activity).apply {
            text = section.title ?: "Section"
            textSize = 24f // Larger like game screen
            setTextColor(android.graphics.Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }

        val descriptionView = TextView(activity).apply {
            text = section.description ?: "Section description"
            textSize = 18f // Larger like game screen
            setTextColor(android.graphics.Color.DKGRAY)
            setPadding(0, 0, 0, 20)
        }

        sectionLayout.addView(titleView)
        sectionLayout.addView(descriptionView)

            // Add tasks or checklist items in a flexbox-like layout
            section.tasks?.let { tasks ->
                if (tasks.isEmpty()) return@let

                android.util.Log.d("Layout", "Processing ${tasks.size} tasks for section ${section.title}")

                // Calculate how many buttons can fit per row based on screen width
                val displayMetrics = activity.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val density = displayMetrics.density

                // Button parameters
                val minButtonWidthDp = 120f // Minimum button width in dp
                val maxButtonWidthDp = 200f // Maximum button width in dp
                val horizontalMarginDp = 6f // Margin on each side of button
                val containerPaddingDp = 0f // Container padding (can be adjusted)

                val minButtonWidthPx = (minButtonWidthDp * density).toInt()
                val horizontalMarginPx = (horizontalMarginDp * density * 2).toInt() // Left + right margins
                val containerPaddingPx = (containerPaddingDp * density * 2).toInt() // Left + right padding

                val availableWidth = screenWidth - containerPaddingPx

                // Calculate optimal buttons per row
                val maxPossibleButtons = (availableWidth + horizontalMarginPx) / (minButtonWidthPx + horizontalMarginPx)
                val buttonsPerRow = maxOf(1, minOf(maxPossibleButtons.toInt(), 6)) // Between 1 and 6 buttons

                // Create a container for tasks that wraps to multiple lines
                val tasksContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 0, 0, 0)
                }

                // Filter visible tasks and defer to next frame to avoid blocking
                sectionLayout.post {
                    val visibleTasks = tasks.filter { task -> 
                        isTaskVisible(task.showdays, task.hidedays, task.displayDays, task.disable) 
                    }
                    
                    // Always create incrementally, one row per frame
                    createTaskRowsIncrementally(
                        visibleTasks, 
                        section.id ?: "unknown", 
                        completedTasksMap, 
                        tasksContainer, 
                        buttonsPerRow, 
                        density, 
                        0
                    )
                }

                sectionLayout.addView(tasksContainer)
            }

        section.items?.let { items ->
            items.forEach { item ->
                // Only add checklist item view if it's visible today
                if (isTaskVisible(item.showdays, item.hidedays)) {
                    val itemView = createChecklistItemView(item, completedTasksMap)
                    sectionLayout.addView(itemView)
                }
            }
        }

        return sectionLayout
    }

    private fun createTaskView(task: Task, sectionId: String, completedTasksMap: Map<String, Boolean>): View {
        val density = activity.resources.displayMetrics.density
        // Use 70dp height to match nav buttons for compact layout
        val tileHeight = (70 * density).toInt()

        // Check if this task is completed today (using pre-loaded map to avoid SharedPreferences read)
        // Use unique task ID that includes section for optional/bonus tasks
        val taskLaunchId = task.launch ?: "unknown_task"
        val taskIdForCheck = if (sectionId != "required") {
            // For optional/bonus sections, use section-prefixed ID to track separately
            "${sectionId}_$taskLaunchId"
        } else {
            // For required tasks, use just the launch ID
            taskLaunchId
        }
        // For optional/bonus tasks, don't grey them out - they can be played multiple times
        val isCompleted = if (sectionId != "required") {
            false  // Optional/bonus tasks are never "completed" in UI sense - always available
        } else {
            completedTasksMap[taskIdForCheck] == true
        }
        android.util.Log.d("Layout", "Task UI check: taskId=$taskIdForCheck, taskLaunchId=$taskLaunchId, sectionId=$sectionId, taskTitle=${task.title}, isCompleted=$isCompleted")

        // Use a RelativeLayout for more precise positioning of children
        return RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, // Use weight for width instead of fixed width
                tileHeight // Fixed 70dp height to match nav buttons
            ).apply {
                weight = 1f // Each task takes equal width in the row
                setMargins((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            }

            // Grey out background if completed
            background = if (isCompleted) {
                activity.getDrawable(R.drawable.game_item_background_grey)
            } else {
                activity.getDrawable(R.drawable.game_item_background)
            }

            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            // Ensure consistent height - match nav button height for compact layout
            minimumHeight = tileHeight

            // Create the stars indicator first, so we can position the text above it
            val icons = loadIconConfig()
            val starsView = TextView(activity).apply {
                id = View.generateViewId() // Important for RelativeLayout rules
                text = icons.starsIcon.repeat(task.stars ?: 1)
                textSize = 16f // Slightly smaller for compact layout
                setTextColor(if (isCompleted) android.graphics.Color.GRAY else android.graphics.Color.parseColor("#FFD700")) // Gold for active, grey for completed
                gravity = android.view.Gravity.CENTER
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                    bottomMargin = (6 * density).toInt()
                }
            }

            // Create the main text label for the task
            val titleView = TextView(activity).apply {
                text = task.title ?: "Task"
                textSize = 14f // Smaller for more text
                setTextColor(if (isCompleted) android.graphics.Color.GRAY else android.graphics.Color.BLACK)
                gravity = android.view.Gravity.CENTER
                setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                // Ensure consistent single line behavior
                setSingleLine(false)
                maxLines = 3 // Allow more lines for longer titles

                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    addRule(RelativeLayout.ABOVE, starsView.id) // Position text above the stars
                }
            }

            // Set the click listener on the parent layout (only if not completed)
            if (!isCompleted) {
                setOnClickListener {
                    val gameType = task.launch ?: "unknown"
                    val gameTitle = task.title ?: "Task"

                    // Check if this is a Chrome page task
                    if (task.chromePage == true) {
                        val pageUrl = task.url
                        if (pageUrl.isNullOrBlank()) {
                            Toast.makeText(activity, "No page configured for this task.", Toast.LENGTH_SHORT).show()
                        } else {
                            val taskLaunchId = task.launch ?: "unknown"
                            val intent = Intent(activity, ChromePageActivity::class.java).apply {
                                putExtra(ChromePageActivity.EXTRA_URL, pageUrl)
                                putExtra(ChromePageActivity.EXTRA_TASK_ID, taskLaunchId)  // Use launch ID instead of rewardId
                                putExtra(ChromePageActivity.EXTRA_STARS, task.stars ?: 0)
                                putExtra(ChromePageActivity.EXTRA_TASK_TITLE, task.title ?: taskLaunchId)
                                putExtra(ChromePageActivity.EXTRA_SECTION_ID, sectionId)  // Pass section ID
                            }
                            activity.chromePageLauncher.launch(intent)
                        }
                    // Check if this is a web game task
                    } else if (task.webGame == true && !task.url.isNullOrEmpty()) {
                        android.util.Log.d("Layout", "Handling web game task: ${task.title}, URL: ${task.url}")
                        val taskLaunchId = task.launch ?: "unknown"
                        val intent = Intent(activity, WebGameActivity::class.java).apply {
                            var gameUrl = getGameModeUrl(task.url, task.easydays, task.harddays, task.extremedays)
                            // Convert GitHub Pages URL to local asset URL for Android
                            if (gameUrl.contains("talq2me.github.io") && gameUrl.contains("/html/")) {
                                val fileName = gameUrl.substringAfterLast("/")
                                gameUrl = "file:///android_asset/html/$fileName"
                                android.util.Log.d("Layout", "Converted to local asset URL: $gameUrl")
                            }
                            putExtra(WebGameActivity.EXTRA_GAME_URL, gameUrl)
                            putExtra(WebGameActivity.EXTRA_TASK_ID, taskLaunchId)  // Use launch ID instead of rewardId
                            putExtra(WebGameActivity.EXTRA_SECTION_ID, sectionId)  // Pass section ID
                            putExtra(WebGameActivity.EXTRA_STARS, task.stars ?: 0)  // Pass stars from config
                            putExtra(WebGameActivity.EXTRA_TASK_TITLE, task.title ?: taskLaunchId)
                        }
                        activity.webGameCompletionLauncher.launch(intent)
                    } else if (task.videoSequence != null) {
                        android.util.Log.d("Layout", "Handling video sequence task: ${task.videoSequence}, launch: ${task.launch}, playlistId: ${task.playlistId}")
                        handleVideoSequenceTask(task, sectionId)
                    } else if (task.playlistId != null) {
                        // Handle playlist task with playlistId field (no rewards for playlists)
                        android.util.Log.d("Layout", "Handling direct playlist task with playlistId: ${task.playlistId}")
                        playYouTubePlaylist(task.playlistId, task.title ?: "Playlist")
                    } else if (task.launch == "googleReadAlong") {
                        android.util.Log.d("Layout", "Launching Google Read Along with tracking")
                        activity.launchGoogleReadAlong(task, sectionId)
                    } else if (task.launch == "frenchBookReader") {
                        android.util.Log.d("Layout", "Launching French Book Reader")
                        val intent = Intent(activity, FrenchBookReaderActivity::class.java)
                        activity.startActivity(intent)
                    } else {
                        // Handle regular game content - use shared launchTask function
                        launchTask(task, sectionId, null)
                    }
                }
            }

            // Add views to the RelativeLayout
            addView(titleView)
            addView(starsView)
        }
    }

    private fun createChecklistItemView(item: ChecklistItem, completedTasksMap: Map<String, Boolean>): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            background = activity.getDrawable(R.drawable.game_item_background)

            // Check if this item is completed (using pre-loaded map to avoid SharedPreferences read)
            val itemId = item.id ?: "checkbox_${item.label}"
            val isCompleted = completedTasksMap[itemId] == true

            // Create a container for label + stars
            val labelStarsContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Create label (without stars in text)
            val labelView = TextView(activity).apply {
                text = item.label ?: "Checklist Item"
                textSize = 18f // Larger for tablet readability
                setTextColor(if (isCompleted) android.graphics.Color.GRAY else android.graphics.Color.BLACK)
                alpha = if (isCompleted) 0.6f else 1.0f // Make completed items slightly faded
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Create stars view (right next to label)
            val icons = loadIconConfig()
            val starsView = TextView(activity).apply {
                text = if (item.stars != null && item.stars!! > 0) icons.starsIcon.repeat(item.stars!!) else ""
                textSize = 16f
                setTextColor(if (isCompleted) android.graphics.Color.GRAY else android.graphics.Color.parseColor("#FFD700")) // Gold for active, grey for completed
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Add label and stars to container
            labelStarsContainer.addView(labelView)
            labelStarsContainer.addView(starsView)

            // Create checkbox
            val checkbox = android.widget.CheckBox(activity).apply {
                isChecked = isCompleted
                isEnabled = !isCompleted // Disable if already completed

                // Style checkbox based on completion state
                setTextColor(if (isChecked) android.graphics.Color.GRAY else android.graphics.Color.BLACK)
                alpha = if (isChecked) 0.6f else 1.0f // Make completed items slightly faded

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && !isCompleted && item.stars != null && item.stars!! > 0) {
                        // Award stars to reward bank when checkbox is checked (only if not already completed)
                        // ALL checklist items give coins and should only be completed once per day
                        val currentContent = activity.getCurrentMainContent()
                        val earnedStars = progressManager.markTaskCompletedWithName(
                            itemId,
                            item.label ?: itemId,
                            item.stars!!,
                            true,  // isRequired parameter - checklist items behave like required tasks
                            currentContent  // Pass config to verify
                        )
                        if (earnedStars > 0) {
                            // Add stars to reward bank
                            progressManager.addStarsToRewardBank(earnedStars)
                            updateProgressDisplay()
                            // Update visual state - disable and grey out
                            post {
                                isEnabled = false
                                setTextColor(android.graphics.Color.GRAY)
                                alpha = 0.6f
                                // Also update the label and stars
                                labelView.setTextColor(android.graphics.Color.GRAY)
                                labelView.alpha = 0.6f
                                starsView.setTextColor(android.graphics.Color.GRAY)
                            }
                        }
                    } else if (isChecked && isCompleted) {
                        // If already completed but checkbox was checked again, ensure visual state
                        post {
                            isEnabled = false
                            setTextColor(android.graphics.Color.GRAY)
                            alpha = 0.6f
                            // Also update the label and stars
                            labelView.setTextColor(android.graphics.Color.GRAY)
                            labelView.alpha = 0.6f
                            starsView.setTextColor(android.graphics.Color.GRAY)
                        }
                    }
                }
            }

            // Add views in order: checkbox, label+stars container
            addView(checkbox)
            addView(labelStarsContainer)
        }
    }

    /**
     * Display profile selection screen
     */
    fun displayProfileSelection(content: MainContent) {
        Log.d(TAG, "Displaying profile selection screen")

        // Hide AM/BM content elements
        headerLayout.visibility = View.GONE
        progressLayout.visibility = View.GONE
        sectionsContainer.visibility = View.GONE

        // Show title
        titleText.text = content.title ?: "Select Your Profile"
        titleText.visibility = View.VISIBLE

        // For profile selection, we'll show the buttons in the sections container
        sectionsContainer.visibility = View.VISIBLE
        setupProfileSelectionButtons(content)

        Log.d(TAG, "Profile selection screen displayed")
    }

    private fun setupProfileSelectionButtons(content: MainContent) {
        sectionsContainer.removeAllViews()

        content.buttons?.forEach { profileButton ->
            val buttonView = createProfileButtonView(profileButton)
            sectionsContainer.addView(buttonView)
        } ?: run {
            // Fallback if no buttons in content
            val fallbackButtons = listOf(
                ProfileButton("AM Tasks", "https://via.placeholder.com/100", "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/AM_config.json"),
                ProfileButton("BM Tasks", "https://via.placeholder.com/100", "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/BM_config.json")
            )

            fallbackButtons.forEach { profileButton ->
                val buttonView = createProfileButtonView(profileButton)
                sectionsContainer.addView(buttonView)
            }
        }
    }

    private fun createProfileButtonView(profileButton: ProfileButton): View {
        return android.widget.Button(activity).apply {
            text = profileButton.title ?: "Profile"
            textSize = 18f
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            setOnClickListener {
                activity.selectProfile(profileButton.configUrl)
            }
        }
    }
    
    // Shared function to launch a task (used by both main page and battle hub/gym map)
    private fun launchTask(task: Task, sectionId: String, sourceTaskId: String? = null) {
        val gameType = task.launch ?: "unknown"
        val gameTitle = task.title ?: "Task"
        
        // Check if this is a Chrome page task
        if (task.chromePage == true) {
            val pageUrl = task.url
            if (pageUrl.isNullOrBlank()) {
                android.widget.Toast.makeText(activity, "No page configured for this task.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val taskLaunchId = task.launch ?: "unknown"
                val intent = android.content.Intent(activity, ChromePageActivity::class.java).apply {
                    putExtra(ChromePageActivity.EXTRA_URL, pageUrl)
                    putExtra(ChromePageActivity.EXTRA_TASK_ID, taskLaunchId)
                    putExtra(ChromePageActivity.EXTRA_STARS, task.stars ?: 0)
                    putExtra(ChromePageActivity.EXTRA_TASK_TITLE, task.title ?: taskLaunchId)
                    putExtra(ChromePageActivity.EXTRA_SECTION_ID, sectionId)
                }
                activity.chromePageLauncher.launch(intent)
            }
        }
        // Check if this is a web game task
        else if (task.webGame == true && !task.url.isNullOrEmpty()) {
            android.util.Log.d("Layout", "Handling web game task: ${task.title}, URL: ${task.url}")
            val taskLaunchId = task.launch ?: "unknown"
            val intent = android.content.Intent(activity, WebGameActivity::class.java).apply {
                var gameUrl = getGameModeUrl(task.url, task.easydays, task.harddays, task.extremedays)
                // Convert GitHub Pages URL to local asset URL for Android
                if (gameUrl.contains("talq2me.github.io") && gameUrl.contains("/html/")) {
                    val fileName = gameUrl.substringAfterLast("/")
                    gameUrl = "file:///android_asset/html/$fileName"
                    android.util.Log.d("Layout", "Converted to local asset URL: $gameUrl")
                }
                putExtra(WebGameActivity.EXTRA_GAME_URL, gameUrl)
                putExtra(WebGameActivity.EXTRA_TASK_ID, sourceTaskId ?: taskLaunchId)
                putExtra(WebGameActivity.EXTRA_SECTION_ID, sectionId)
                putExtra(WebGameActivity.EXTRA_STARS, task.stars ?: 0)
                putExtra(WebGameActivity.EXTRA_TASK_TITLE, task.title ?: taskLaunchId)
            }
            activity.webGameCompletionLauncher.launch(intent)
        }
        else if (task.videoSequence != null) {
            android.util.Log.d("Layout", "Handling video sequence task: ${task.videoSequence}, launch: ${task.launch}")
            handleVideoSequenceTask(task, sectionId)
        }
        else if (task.playlistId != null) {
            android.util.Log.d("Layout", "Handling direct playlist task with playlistId: ${task.playlistId}")
            playYouTubePlaylist(task.playlistId, task.title ?: "Playlist")
        }
        else if (task.launch == "googleReadAlong") {
            android.util.Log.d("Layout", "Launching Google Read Along")
            activity.launchGoogleReadAlong(task, sectionId)
        }
        else if (task.launch == "frenchBookReader") {
            android.util.Log.d("Layout", "Launching French Book Reader")
            val intent = android.content.Intent(activity, FrenchBookReaderActivity::class.java)
            activity.startActivity(intent)
        }
        else {
            // Handle regular game content - use exact same logic as main page
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val gameContent = activity.contentUpdateService.fetchGameContent(activity, gameType)

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.util.Log.d("Layout", "Launching game: ${task.title}, totalQuestions=${task.totalQuestions}, gameType=$gameType, sourceTaskId=$sourceTaskId")
                        val game = Game(
                            id = gameType,
                            title = gameTitle,
                            description = "Educational activity",
                            type = gameType,
                            iconUrl = "",
                            requiresRewardTime = false,
                            difficulty = "Easy",
                            estimatedTime = task.stars ?: 1,
                            totalQuestions = task.totalQuestions,
                            blockOutlines = task.blockOutlines ?: false
                        )
                        android.util.Log.d("Layout", "Created Game object: totalQuestions=${game.totalQuestions}")
                        // Pass sourceTaskId to startGame so it can track completion properly
                        activity.startGame(game, gameContent, sectionId, sourceTaskId)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Layout", "Error fetching game content for $gameType", e)

                    // Fallback to starting game without content
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.util.Log.d("Layout", "Launching game (no content): ${task.title}, totalQuestions=${task.totalQuestions}, gameType=$gameType, sourceTaskId=$sourceTaskId")
                        val game = Game(
                            id = gameType,
                            title = gameTitle,
                            description = "Educational activity",
                            type = gameType,
                            iconUrl = "",
                            requiresRewardTime = false,
                            difficulty = "Easy",
                            estimatedTime = task.stars ?: 1,
                            totalQuestions = task.totalQuestions,
                            blockOutlines = task.blockOutlines ?: false
                        )
                        android.util.Log.d("Layout", "Created Game object (no content): totalQuestions=${game.totalQuestions}")
                        // Pass sourceTaskId to startGame so it can track completion properly
                        activity.startGame(game, null, sectionId, sourceTaskId)
                    }
                }
            }
        }
    }
    
    // Function to launch a task from battle hub or gym map
    fun launchTaskFromBattleHub(task: Task, sectionId: String, battleHubTaskId: String? = null) {
        launchTask(task, sectionId, battleHubTaskId)
    }
    
    // Battle Hub WebView (embedded at top of main screen)
    private var battleHubWebView: android.webkit.WebView? = null
    
    private fun setupBattleHub() {
        // Remove existing battle hub if it exists
        battleHubWebView?.let {
            sectionsContainer.removeView(it)
            battleHubWebView = null
        }
        
        // Create WebView for battle hub
        val webView = android.webkit.WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Increase height to show full battle hub including berry meter
                // Use screen height as reference - take about 50% of screen height for battle hub
                // This ensures the berry meter and all controls are visible
                val screenHeight = activity.resources.displayMetrics.heightPixels
                val density = activity.resources.displayMetrics.density
                // Use 50% of screen or minimum 800dp, whichever is larger
                val minHeightDp = 800
                val minHeightPx = (density * minHeightDp).toInt()
                height = maxOf((screenHeight * 0.5).toInt(), minHeightPx)
                setMargins(0, 0, 0, 16) // Bottom margin
            }
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
            }
            
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("Layout", "Battle hub page loaded")
                }
            }
            
            // Add JavaScript interface (same as WebGameActivity)
            addJavascriptInterface(BattleHubInterface(), "Android")
        }
        
        // Insert at the beginning of sectionsContainer (before sections)
        sectionsContainer.addView(webView, 0)
        battleHubWebView = webView
        
        // Load the battle hub HTML
        webView.loadUrl("file:///android_asset/html/pokemonBattleHub.html")
        
        android.util.Log.d("Layout", "Battle hub WebView created and added to main screen")
    }
    
    fun refreshBattleHub() {
        // Reload the battle hub to update berries and Pokemon
        battleHubWebView?.reload()
    }
    
    // Gym Map WebView (embedded on main screen)
    private var gymMapWebView: android.webkit.WebView? = null
    
    private fun setupGymMap() {
        // Remove existing gym map if it exists
        gymMapWebView?.let {
            sectionsContainer.removeView(it)
            gymMapWebView = null
        }
        
        // Create WebView for gym map
        val webView = android.webkit.WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val screenHeight = activity.resources.displayMetrics.heightPixels
                val density = activity.resources.displayMetrics.density
                // Use 55% of screen or minimum 700dp, whichever is larger
                val minHeightDp = 700
                val minHeightPx = (density * minHeightDp).toInt()
                height = maxOf((screenHeight * 0.55).toInt(), minHeightPx)
                setMargins(0, 0, 0, 16) // Bottom margin
            }
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
            }
            
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("Layout", "Gym map page loaded")
                }
            }
            
            // Add JavaScript interface
            addJavascriptInterface(GymMapInterface(), "Android")
        }
        
        // Insert after battle hub (at index 1)
        sectionsContainer.addView(webView, 1)
        gymMapWebView = webView
        
        // Load the gym map HTML
        webView.loadUrl("file:///android_asset/html/gymMap.html")
        
        android.util.Log.d("Layout", "Gym map WebView created and added to main screen")
    }
    
    fun refreshGymMap() {
        // Reload the gym map to update completed gyms
        gymMapWebView?.reload()
    }
    
    // JavaScript interface for gym map
    inner class GymMapInterface {
        @android.webkit.JavascriptInterface
        fun getConfigJson(): String {
            return try {
                val contentUpdateService = com.talq2me.baerened.ContentUpdateService()
                val cachedContent = contentUpdateService.getCachedMainContent(activity)
                if (cachedContent != null) {
                    android.util.Log.d("Layout", "Returning cached config for gym map")
                    cachedContent
                } else {
                    val profile = com.talq2me.baerened.SettingsManager.readProfile(activity) ?: "A"
                    val configFileName = when (profile) {
                        "A" -> "AM_config.json"
                        "B" -> "BM_config.json"
                        else -> "Main_config.json"
                    }
                    activity.assets.open("config/$configFileName").bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error getting config JSON for gym map", e)
                "{}"
            }
        }
        
        @android.webkit.JavascriptInterface
        fun getCompletedTasks(): String {
            return try {
                val completedTasksMap = progressManager.getCompletedTasksMap()
                val completedTasks = completedTasksMap.filter { it.value }.keys.toList()
                com.google.gson.Gson().toJson(completedTasks)
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error getting completed tasks", e)
                "[]"
            }
        }
        
        @android.webkit.JavascriptInterface
        fun launchGame(gameId: String) {
            android.util.Log.d("Layout", "JavaScript requested to launch game from gym map: $gameId")
            activity.runOnUiThread {
                // Launch game from gym map (same logic as battle hub)
                val currentContent = activity.getCurrentMainContent() ?: return@runOnUiThread
                
                var taskToLaunch: com.talq2me.baerened.Task? = null
                var sectionId: String? = null
                
                currentContent.sections?.forEach { section ->
                    section.tasks?.forEach { task ->
                        if (task.launch == gameId) {
                            taskToLaunch = task
                            sectionId = section.id
                            return@forEach
                        }
                    }
                }
                
                if (taskToLaunch != null && sectionId != null) {
                    android.util.Log.d("Layout", "Found task for gym map: ${taskToLaunch.title}, totalQuestions=${taskToLaunch.totalQuestions}, stars=${taskToLaunch.stars}")
                    // Use battleHubTaskId prefix to track that it came from gym map
                    val gymMapTaskId = "gymMap_$gameId"
                    launchTaskFromBattleHub(taskToLaunch, sectionId, gymMapTaskId)
                } else {
                    android.util.Log.w("Layout", "Task not found for gameId: $gameId")
                }
            }
        }
    }
    
    // JavaScript interface for battle hub (embedded version)
    inner class BattleHubInterface {
        @android.webkit.JavascriptInterface
        fun getUnlockedPokemonCount(): Int {
            return DailyProgressManager(activity).getUnlockedPokemonCount()
        }
        
        @android.webkit.JavascriptInterface
        fun getPokemonFileList(): String {
            return try {
                val fileList = activity.assets.list("images/pokeSprites/sprites/pokemon")
                    ?.filter { it.endsWith(".png") }
                    ?: emptyList()
                com.google.gson.Gson().toJson(fileList)
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error getting Pokemon file list", e)
                "[]"
            }
        }
        
        @android.webkit.JavascriptInterface
        fun unlockPokemon(count: Int) {
            DailyProgressManager(activity).unlockPokemon(count)
        }
        
        @android.webkit.JavascriptInterface
        fun launchGame(gameId: String) {
            android.util.Log.d("Layout", "JavaScript requested to launch game: $gameId")
            activity.runOnUiThread {
                // Launch game from battle hub (same logic as before)
                val currentContent = activity.getCurrentMainContent() ?: return@runOnUiThread
                
                var taskToLaunch: com.talq2me.baerened.Task? = null
                var sectionId: String? = null
                
                currentContent.sections?.forEach { section ->
                    section.tasks?.forEach { task ->
                        if (task.launch == gameId) {
                            taskToLaunch = task
                            sectionId = section.id
                            return@forEach
                        }
                    }
                }
                
                if (taskToLaunch != null && sectionId != null) {
                    launchTaskFromBattleHub(taskToLaunch!!, sectionId, "battleHub_$gameId")
                } else {
                    android.widget.Toast.makeText(activity, "Game not found: $gameId", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        @android.webkit.JavascriptInterface
        fun getConfigJson(): String {
            return try {
                val contentUpdateService = com.talq2me.baerened.ContentUpdateService()
                val cachedContent = contentUpdateService.getCachedMainContent(activity)
                if (cachedContent != null) {
                    android.util.Log.d("Layout", "Returning cached config")
                    cachedContent
                } else {
                    val profile = com.talq2me.baerened.SettingsManager.readProfile(activity) ?: "A"
                    val configFileName = when (profile) {
                        "A" -> "AM_config.json"
                        "B" -> "BM_config.json"
                        else -> "Main_config.json"
                    }
                    activity.assets.open("config/$configFileName").bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error getting config JSON", e)
                "{}"
            }
        }
        
        @android.webkit.JavascriptInterface
        fun setPendingBerries(amount: Int) {
            try {
                activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt("pendingBerries", amount)
                    .apply()
                android.util.Log.d("Layout", "Set pending berries to $amount")
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error setting pending berries", e)
            }
        }
        
        @android.webkit.JavascriptInterface
        fun getPendingBerries(): Int {
            return try {
                activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                    .getInt("pendingBerries", 0)
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error getting pending berries", e)
                0
            }
        }
        
        @android.webkit.JavascriptInterface
        fun clearPendingBerries() {
            try {
                activity.getSharedPreferences("pokemonBattleHub", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .remove("pendingBerries")
                    .apply()
                android.util.Log.d("Layout", "Cleared pending berries")
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error clearing pending berries", e)
            }
        }
        
        @android.webkit.JavascriptInterface
        fun loadPokemonImage(filename: String): String {
            return try {
                val inputStream = activity.assets.open("images/pokeSprites/sprites/pokemon/$filename")
                val bytes = inputStream.readBytes()
                inputStream.close()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                "data:image/png;base64,$base64"
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error loading Pokemon image: $filename", e)
                ""
            }
        }
        
        @android.webkit.JavascriptInterface
        fun loadPokemonManifest(): String {
            return try {
                val inputStream = activity.assets.open("images/pokeSprites/sprites/pokemon/pokedex_manifest.json")
                val size = inputStream.available()
                val buffer = ByteArray(size)
                inputStream.read(buffer)
                inputStream.close()
                String(buffer)
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error loading Pokemon manifest", e)
                "[]"
            }
        }
        
        @android.webkit.JavascriptInterface
        fun getTotalStars(): Int {
            return try {
                val currentContent = activity.getCurrentMainContent()
                if (currentContent != null) {
                    val (_, totalStars) = DailyProgressManager(activity).calculateTotalsFromConfig(currentContent)
                    totalStars
                } else {
                    100 // Fallback default
                }
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error getting total stars", e)
                100 // Fallback default
            }
        }
        
        @android.webkit.JavascriptInterface
        fun getIconConfig(): String {
            return try {
                val inputStream = activity.assets.open("config/icon_config.json")
                val configJson = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                configJson
            } catch (e: Exception) {
                android.util.Log.e("Layout", "Error loading icon config", e)
                // Return default config
                """{"starsIcon":"🍍","coinsIcon":"🪙"}"""
            }
        }
    }
}

private fun Int.dpToPx(): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        Resources.getSystem().displayMetrics
    ).toInt()
}