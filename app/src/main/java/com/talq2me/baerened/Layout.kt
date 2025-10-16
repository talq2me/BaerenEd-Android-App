package com.talq2me.baerened

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
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
    private val rewardLayout: LinearLayout get() = activity.rewardLayout
    private val rewardTitle: TextView get() = activity.rewardTitle
    private val rewardDescription: TextView get() = activity.rewardDescription

    // Progress management
    private val progressManager = DailyProgressManager(activity)

    /**
     * Display the main content by setting up all UI components
     */
    fun displayContent(content: MainContent) {
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

        // Display reward
        if (content.reward != null) {
            rewardLayout.visibility = View.VISIBLE
            setupReward(content.reward)
        } else {
            rewardLayout.visibility = View.GONE
        }
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
            Button("⟳ Refresh", "refresh")
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

        progressText.text = "$earnedCoins/$totalCoins 🪙 + $earnedStars/$totalStars ⭐ - ${progress.message ?: "Complete tasks to earn coins and stars!"}"
        progressBar.max = totalStars
        progressBar.progress = earnedStars

        // Add Pokemon button if all coins earned AND no Pokemon unlocked today
        if (progressManager.shouldShowPokemonUnlockButton(content)) {
            addPokemonButtonToProgressLayout()
        } else {
            removePokemonButtonFromProgressLayout()
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

            progressText.text = "$earnedCoins/$totalCoins 🪙 + $earnedStars/$totalStars ⭐ - Complete tasks to earn coins and stars!"
            progressBar.max = totalStars
            progressBar.progress = earnedStars

            // Update Pokemon button visibility
            if (progressManager.shouldShowPokemonUnlockButton(currentContent)) {
                addPokemonButtonToProgressLayout()
            } else {
                removePokemonButtonFromProgressLayout()
            }
        } else {
            // Fallback - use cached values but ensure they're calculated correctly
            val progressData = progressManager.getCurrentProgressWithTotals()
            val earnedCoins = progressData.first.first
            val totalCoins = progressData.first.second
            val earnedStars = progressData.second.first
            val totalStars = progressData.second.second

            progressText.text = "$earnedCoins/$totalCoins 🪙 + $earnedStars/$totalStars ⭐ - Complete tasks to earn coins and stars!"
            progressBar.max = totalStars
            progressBar.progress = earnedStars
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

    private fun handleVideoSequenceTask(task: Task) {
        val videoSequence = task.videoSequence ?: return
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
                            playYouTubeVideo(videoJson, videoName)
                        }
                        "sequential" -> {
                            // Play next video in sequence
                            playNextVideoInSequence(videoJson, videoFile)
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

    private fun playYouTubeVideo(videoJson: String, videoName: String) {
        try {
            val gson = com.google.gson.Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoId = videoMap[videoName]

            if (videoId != null) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } else {
                Toast.makeText(activity, "Video not found: $videoName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error parsing video JSON", e)
            Toast.makeText(activity, "Error playing video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playNextVideoInSequence(videoJson: String, videoFile: String) {
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

            // Play the video
            playYouTubeVideo(videoJson, nextVideoName)

            // Update the last played index
            prefs.edit().putInt("${currentKid}_${videoFile}_index", nextIndex).apply()

        } catch (e: Exception) {
            android.util.Log.e("Layout", "Error handling sequential video", e)
            Toast.makeText(activity, "Error playing video sequence", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSections(sections: List<Section>) {
        sectionsContainer.removeAllViews()

        sections.forEach { section ->
            val sectionView = createSectionView(section)
            sectionsContainer.addView(sectionView)
        }
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
            // Create a container for tasks that can wrap horizontally
            val tasksContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // Ensure baseline alignment for consistent button heights
                setBaselineAligned(false)
                // Make container wider to accommodate longer text
                weightSum = tasks.size.toFloat()
            }

            tasks.forEach { task ->
                val taskView = createTaskView(task)
                tasksContainer.addView(taskView)
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
        val isCompleted = progressManager.isTaskCompleted(task.launch ?: "unknown_task")

        // Use a RelativeLayout for more precise positioning of children
        return RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, // Use weight for width
                tileHeight // Fixed 70dp height to match nav buttons
            ).apply {
                setMargins((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                weight = 1f
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

                    // Check if this is a video sequence task
                    if (task.videoSequence != null) {
                        handleVideoSequenceTask(task)
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
                        // Award stars when checkbox is checked (only if not already completed)
                        // ALL checklist items give coins and should only be completed once per day
                        val earnedStars = progressManager.markTaskCompleted(
                            itemId,
                            item.stars!!,
                            true  // isRequired parameter - checklist items behave like required tasks
                        )
                        if (earnedStars > 0) {
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

    private fun setupReward(reward: Reward) {
        rewardTitle.text = reward.title ?: "Reward"
        rewardDescription.text = reward.description ?: "Complete tasks to unlock rewards"
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
        rewardLayout.visibility = View.GONE

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
