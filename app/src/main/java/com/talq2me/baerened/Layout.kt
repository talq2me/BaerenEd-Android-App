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
import kotlinx.coroutines.*

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

    /**
     * Display the main content by setting up all UI components
     */
    fun displayContent(content: MainContent) {
        android.util.Log.d("Layout", "displayContent called with content: ${content.title}")
        android.util.Log.d("Layout", "Content sections count: ${content.sections?.size ?: 0}")

        // Display header buttons - always show default navigation buttons
        headerLayout.visibility = View.VISIBLE
        setupDefaultHeaderButtons()

        // Display title
        titleText.text = content.title ?: "BaerenEd"
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

        // Display sections (tasks and buttons)
        sectionsContainer.visibility = View.VISIBLE
        setupSections(content.sections ?: emptyList())

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

                // Set text with consistent symbols
                when (button.action) {
                    "back" -> {
                        text = "< Back"
                    }
                    "home" -> {
                        text = "⌂ Home"
                    }
                    "refresh" -> {
                        text = "⟳ Refresh"
                    }
                }
            }
            headerLayout.addView(btn)
        }

        // Add "My Pokedex" button if user has unlocked Pokemon
        if (progressManager.getUnlockedPokemonCount() > 0) {
            addMyPokedexButton()
        }
    }

    private fun setupDefaultHeaderButtons() {
        headerLayout.removeAllViews()

        // Create default navigation buttons
        val defaultButtons = listOf(
            Button("< Back", "back"),
            Button("⌂ Home", "home"),
            Button("⟳ Refresh", "refresh"),
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

        // Add "My Pokedex" button if user has unlocked Pokemon
        if (progressManager.getUnlockedPokemonCount() > 0) {
            addMyPokedexButton()
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

        progressText.text = "$earnedCoins/$totalCoins 🪙 + $earnedStars ⭐ = $rewardMinutes mins - ${progress.message ?: "Complete tasks to earn coins and stars!"}"
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

            progressText.text = "$earnedCoins/$totalCoins 🪙 + $earnedStars ⭐ = $rewardMinutes mins - Complete tasks to earn coins and stars!"
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

            progressText.text = "$earnedCoins/$totalCoins 🪙 + $earnedStars ⭐ = $rewardMinutes mins - Complete tasks to earn coins and stars!"
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
            // Re-setup sections to update task completion states
            setupSections(currentContent.sections ?: emptyList())
        }
    }

    /**
     * Handles video completion and grants rewards if the video was watched to completion
     */
    fun handleVideoCompletion(taskId: String, taskTitle: String, stars: Int) {
        android.util.Log.d("Layout", "handleVideoCompletion called: taskId=$taskId, taskTitle=$taskTitle, stars=$stars")

        // Only grant rewards if this is a legitimate completion (not manual exit)
        // The video activities will only call this on actual completion
        if (stars > 0) {
            val progressManager = DailyProgressManager(activity)
            val earnedStars = progressManager.markTaskCompletedWithName(taskId, taskTitle, stars, false)

            android.util.Log.d("Layout", "markTaskCompletedWithName returned: $earnedStars stars")

            if (earnedStars > 0) {
                // Add stars to reward bank and convert to minutes
                val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)

                android.util.Log.d("Layout", "Video task $taskId ($taskTitle) completed, earned $earnedStars stars = ${progressManager.convertStarsToMinutes(earnedStars)} minutes, total bank: $totalRewardMinutes minutes")

                // Show completion message first
                Toast.makeText(activity, "🎥 Video completed! Earned $earnedStars stars!", Toast.LENGTH_LONG).show()

                // Force refresh the entire content to ensure task completion is reflected
                val currentContent = activity.getCurrentMainContent()
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

    fun handleWebGameCompletion(rewardId: String?) {
        android.util.Log.d("Layout", "handleWebGameCompletion called with rewardId: $rewardId")

        if (!rewardId.isNullOrEmpty()) {
            // In a real application, you would use the rewardId to look up the actual reward (e.g., stars, coins)
            // For now, let's assume the rewardId directly corresponds to a task ID and we award a fixed number of stars.
            // You'll need to adapt this logic to your specific reward system.

            val progressManager = DailyProgressManager(activity)
            // For demonstration, let's assume a web game always awards 5 stars if a rewardId is present
            val starsToAward = 5
            val taskTitle = "Web Game: $rewardId"

            val earnedStars = progressManager.markTaskCompletedWithName(rewardId, taskTitle, starsToAward, false)

            if (earnedStars > 0) {
                val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)

                android.util.Log.d("Layout", "Web game completed ($rewardId), earned $earnedStars stars = ${progressManager.convertStarsToMinutes(earnedStars)} minutes, total bank: $totalRewardMinutes minutes")

                Toast.makeText(activity, "🎮 Web game completed! Earned $earnedStars stars!", Toast.LENGTH_LONG).show()

                val currentContent = activity.getCurrentMainContent()
                if (currentContent != null) {
                    android.util.Log.d("Layout", "Re-displaying content to update task completion state")
                    displayContent(currentContent)
                } else {
                    android.util.Log.e("Layout", "Current content is null, cannot refresh UI")
                }
                refreshProgressDisplay()
            } else {
                android.util.Log.d("Layout", "Web game task $rewardId was already completed today, no additional stars awarded")
                Toast.makeText(activity, "Web game already completed today", Toast.LENGTH_SHORT).show()
            }
        } else {
            android.util.Log.d("Layout", "No rewardId provided for web game completion.")
            Toast.makeText(activity, "Web game completed, but no reward was issued.", Toast.LENGTH_SHORT).show()
        }
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
        val rewardMinutes = progressManager.useAllRewardMinutes()
        if (rewardMinutes > 0) {
            // Send reward time to BaerenLock app via shared storage
            sendRewardTimeToBaerenLock(rewardMinutes.toInt())

            // Clear the pending reward data since we've consumed it
            progressManager.clearPendingRewardData()

            // Refresh progress display to update the UI
            refreshProgressDisplay()

            // Show confirmation message
            android.widget.Toast.makeText(activity, "🎮 Started ${rewardMinutes.toInt()} minutes of reward time!", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun sendRewardTimeToBaerenLock(minutes: Int) {
        try {
            val progressManager = DailyProgressManager(activity)

            // First check if there's already pending reward data
            val existingReward = progressManager.getPendingRewardData()

            if (existingReward != null) {
                // Add to existing minutes
                val (existingMinutes, timestamp) = existingReward
                val totalMinutes = existingMinutes + minutes

                // Update the shared file with new total
                val sharedFile = progressManager.getSharedRewardFile()
                sharedFile.writeText("$totalMinutes\n$timestamp")

                android.util.Log.d("Layout", "Added $minutes minutes to existing $existingMinutes minutes, total: $totalMinutes minutes")
            } else {
                // Create new reward data
                val currentTime = System.currentTimeMillis()
                val sharedFile = progressManager.getSharedRewardFile()
                sharedFile.writeText("$minutes\n$currentTime")

                android.util.Log.d("Layout", "Created new reward data: $minutes minutes")
            }

        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error sending reward time to BaerenLock", e)
            android.widget.Toast.makeText(activity, "Error starting reward time", android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    private fun handleVideoSequenceTask(task: Task) {
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
                    try {
                        // Load the video JSON file from assets
                        val videoJson = activity.assets.open("videos/$videoFile.json").bufferedReader().use { it.readText() }

                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            when (videoSequence) {
                                "exact" -> {
                                    // Play specific video
                                    val videoName = task.video ?: return@withContext
                                    playYouTubeVideo(videoJson, videoName, task)
                                }
                                "sequential" -> {
                                    // Play next video in sequence
                                    playNextVideoInSequence(videoJson, videoFile, task)
                                }
                                else -> {
                                    // Unknown video sequence type
                                    Toast.makeText(activity, "Unknown video sequence type", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Layout", "Error loading video content for $videoFile", e)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(activity, "Error loading video content", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun playYouTubeVideo(videoJson: String, videoName: String, task: Task) {
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
                }
                android.util.Log.d("Layout", "Starting YouTube player with videoId: $videoId, videoName: $videoName")
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

    private fun playNextVideoInSequence(videoJson: String, videoFile: String, task: Task) {
        try {
            val gson = com.google.gson.Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoList = videoMap.keys.toList()

            // Get the last played video index for this file and profile
            val prefs = activity.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val currentKid = activity.getSharedPreferences("child_profile", Context.MODE_PRIVATE)
                .getString("profile", "A") ?: "A"
            val lastVideoIndex = prefs.getInt("${currentKid}_${videoFile}_index", 0)

            // Get next video (wrap around if at end)
            val nextIndex = (lastVideoIndex + 1) % videoList.size
            val nextVideoName = videoList[nextIndex]

            // Play the video using the activity result launcher
            playYouTubeVideo(videoJson, nextVideoName, task)

            // Update the last played index
            prefs.edit().putInt("${currentKid}_${videoFile}_index", nextIndex).apply()

        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error handling sequential video", e)
            Toast.makeText(activity, "Error playing video sequence", Toast.LENGTH_SHORT).show()
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
        sectionsContainer.removeAllViews()

        sections.forEach { section ->
            android.util.Log.d("Layout", "Creating section: ${section.title}, tasks count: ${section.tasks?.size ?: 0}")
            val sectionView = createSectionView(section)
            sectionsContainer.addView(sectionView)
        }

        android.util.Log.d("Layout", "setupSections completed")
    }

    private fun createSectionView(section: Section): View {
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

                // Create rows of tasks
                val rows = (tasks.size + buttonsPerRow - 1) / buttonsPerRow // Ceiling division

                for (rowIndex in 0 until rows) {
                    // Create a horizontal row container
                    val rowContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(0, 0, 0, 0)
                        weightSum = buttonsPerRow.toFloat()
                    }

                    // Add tasks to this row
                    val startIndex = rowIndex * buttonsPerRow
                    val endIndex = minOf(startIndex + buttonsPerRow, tasks.size)

                    for (i in startIndex until endIndex) {
                        val task = tasks[i]
                        android.util.Log.d("Layout", "Creating task view: ${task.title}, launch=${task.launch}, stars=${task.stars}")
                        val taskView = createTaskView(task)
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

                    tasksContainer.addView(rowContainer)
                }

                sectionLayout.addView(tasksContainer)
            }

        section.items?.let { items ->
            items.forEach { item ->
                val itemView = createChecklistItemView(item)
                sectionLayout.addView(itemView)
            }
        }

        return sectionLayout
    }

    private fun createTaskView(task: Task): View {
        val density = activity.resources.displayMetrics.density
        // Use 70dp height to match nav buttons for compact layout
        val tileHeight = (70 * density).toInt()

        // Check if this task is completed today
        val taskIdForCheck = task.launch ?: "unknown_task"
        val isCompleted = progressManager.isTaskCompleted(taskIdForCheck)
        android.util.Log.d("Layout", "Task UI check: taskId=$taskIdForCheck, taskTitle=${task.title}, isCompleted=$isCompleted")

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
            val starsView = TextView(activity).apply {
                id = View.generateViewId() // Important for RelativeLayout rules
                text = "⭐".repeat(task.stars ?: 1)
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

                    // Check if this is a web game task
                    if (task.webGame == true && !task.url.isNullOrEmpty()) {
                        android.util.Log.d("Layout", "Handling web game task: ${task.title}, URL: ${task.url}")
                        val intent = Intent(activity, WebGameActivity::class.java).apply {
                            putExtra(WebGameActivity.EXTRA_GAME_URL, task.url)
                            putExtra(WebGameActivity.EXTRA_REWARD_ID, task.rewardId ?: task.launch)
                        }
                        activity.webGameCompletionLauncher.launch(intent)
                    } else if (task.videoSequence != null) {
                        android.util.Log.d("Layout", "Handling video sequence task: ${task.videoSequence}, launch: ${task.launch}, playlistId: ${task.playlistId}")
                        handleVideoSequenceTask(task)
                    } else if (task.playlistId != null) {
                        // Handle playlist task with playlistId field (no rewards for playlists)
                        android.util.Log.d("Layout", "Handling direct playlist task with playlistId: ${task.playlistId}")
                        playYouTubePlaylist(task.playlistId, task.title ?: "Playlist")
                    } else {
                        // Handle regular game content
                        // Fetch game content asynchronously
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
                                    activity.startGame(game, gameContent)
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
                                    activity.startGame(game, null)
                                }
                            }
                        }
                    }
                }
            }

            // Add views to the RelativeLayout
            addView(titleView)
            addView(starsView)
        }
    }

    private fun createChecklistItemView(item: ChecklistItem): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            background = activity.getDrawable(R.drawable.game_item_background)

            // Check if this item is completed
            val itemId = item.id ?: "checkbox_${item.label}"
            val isCompleted = progressManager.isTaskCompleted(itemId)

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
            val starsView = TextView(activity).apply {
                text = if (item.stars != null && item.stars!! > 0) "⭐".repeat(item.stars!!) else ""
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
                        val earnedStars = progressManager.markTaskCompletedWithName(
                            itemId,
                            item.label ?: itemId,
                            item.stars!!,
                            true  // isRequired parameter - checklist items behave like required tasks
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
}

// Extension function to convert dp to pixels
private fun Int.dpToPx(): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        Resources.getSystem().displayMetrics
    ).toInt()
}
