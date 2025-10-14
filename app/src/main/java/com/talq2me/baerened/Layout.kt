package com.talq2me.baerened

import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
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

    /**
     * Display the main content by setting up all UI components
     */
    fun displayContent(content: MainContent) {
        // Display header buttons
        if (content.header?.buttons?.isNotEmpty() == true) {
            headerLayout.visibility = View.VISIBLE
            setupHeaderButtons(content.header.buttons)
        } else {
            headerLayout.visibility = View.GONE
        }

        // Display title
        titleText.text = content.title ?: "BaerenEd"
        titleText.visibility = View.VISIBLE

        // Display progress
        if (content.progress != null) {
            progressLayout.visibility = View.VISIBLE
            setupProgress(content.progress)
        } else {
            progressLayout.visibility = View.GONE
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
                setOnClickListener {
                    activity.handleHeaderButtonClick(button.action)
                }
            }
            headerLayout.addView(btn)
        }
    }

    private fun setupProgress(progress: Progress) {
        progressText.text = "${progress.starsEarned ?: 0}/${progress.starsGoal ?: 0} ⭐ - ${progress.message ?: "Complete tasks to earn stars!"}"
        progressBar.max = progress.starsGoal ?: 10
        progressBar.progress = progress.starsEarned ?: 0
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
            setPadding(0, 16, 0, 16)
        }

        // Section title and description
        val titleView = TextView(activity).apply {
            text = section.title ?: "Section"
            textSize = 18f
            setTextColor(android.graphics.Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }

        val descriptionView = TextView(activity).apply {
            text = section.description ?: "Section description"
            textSize = 14f
            setTextColor(android.graphics.Color.DKGRAY)
            setPadding(0, 0, 0, 16)
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
        val buttonSize = (120 * density).toInt() // 120dp square

        // Use a RelativeLayout for more precise positioning of children
        return RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                weight = 1f
            }
            background = activity.getDrawable(R.drawable.game_item_background)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            // Ensure consistent minimum height
            minimumHeight = (120 * density).toInt()

            // Create the stars indicator first, so we can position the text above it
            val starsView = TextView(activity).apply {
                id = View.generateViewId() // Important for RelativeLayout rules
                text = "⭐".repeat(task.stars ?: 1)
                textSize = 14f
                // Set text color to yellow if needed, but the emoji has color
                gravity = android.view.Gravity.CENTER
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                    bottomMargin = (4 * density).toInt()
                }
            }

            // Create the main text label for the task
            val titleView = TextView(activity).apply {
                text = task.title ?: "Task"
                textSize = 16f
                setTextColor(android.graphics.Color.BLACK) // FIX: Changed text color to black for readability
                gravity = android.view.Gravity.CENTER
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                // Ensure consistent single line behavior
                setSingleLine(false)
                maxLines = 2

                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    addRule(RelativeLayout.ABOVE, starsView.id) // Position text above the stars
                }
            }

            // Set the click listener on the parent layout
            setOnClickListener {
                val gameType = task.launch ?: "unknown"
                val gameTitle = task.title ?: "Task"

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
                                totalQuestions = task.totalQuestions
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
                                totalQuestions = task.totalQuestions
                            )
                            activity.startGame(game, null)
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

            val checkbox = android.widget.CheckBox(activity).apply {
                isChecked = item.done == true
                isEnabled = false // Read-only for now
            }

            val labelView = TextView(activity).apply {
                text = item.label ?: "Checklist Item"
                textSize = 16f
                setTextColor(if (item.done == true) android.graphics.Color.GRAY else android.graphics.Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            addView(checkbox)
            addView(labelView)
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
