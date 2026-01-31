package com.talq2me.baerened

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class TrainingMapActivity : AppCompatActivity() {
    
    private lateinit var mapContainer: RelativeLayout
    private lateinit var progressInfo: TextView
    private var mapType: String = "required"
    private lateinit var progressManager: DailyProgressManager
    private var currentContent: MainContent? = null
    private var lastLaunchedGameSectionId: String? = null // Store section ID of last launched game
    private var loadingDialog: android.app.ProgressDialog? = null
    
    data class IconConfig(
        val starsIcon: String = "🍍",
        val coinsIcon: String = "🪙"
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get map type from intent
        mapType = intent.getStringExtra("mapType") ?: "required"
        
        progressManager = DailyProgressManager(this)
        val profile = SettingsManager.readProfile(this) ?: "AM"
        
        // Run daily_reset_process() and then cloud_sync() according to requirements
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Show loading spinner on main thread
                withContext(Dispatchers.Main) {
                    showLoadingSpinner("Syncing progress...")
                }
                
                // Perform daily reset process and cloud sync
                val resetAndSyncManager = DailyResetAndSyncManager(this@TrainingMapActivity)
                resetAndSyncManager.dailyResetProcessAndSync(profile)
                
                android.util.Log.d("TrainingMapActivity", "Daily reset and sync completed for profile: $profile")
                
                // Hide loading spinner and finish loading
                withContext(Dispatchers.Main) {
                    hideLoadingSpinner()
                    finishLoadingTrainingMap()
                }
            } catch (e: Exception) {
                android.util.Log.e("TrainingMapActivity", "Error during daily reset and sync", e)
                // Hide loading spinner even on error
                withContext(Dispatchers.Main) {
                    hideLoadingSpinner()
                    finishLoadingTrainingMap()
                }
            }
        }
    }
    
    private fun showLoadingSpinner(message: String) {
        runOnUiThread {
            loadingDialog = android.app.ProgressDialog(this@TrainingMapActivity).apply {
                setMessage(message)
                setCancelable(false)
                show()
            }
        }
    }
    
    private fun hideLoadingSpinner() {
        runOnUiThread {
            loadingDialog?.dismiss()
            loadingDialog = null
        }
    }
    
    private fun finishLoadingTrainingMap() {
        // Create the map view
        createMapView()
    }
    
    private fun createMapView() {
        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
        
        // Main container
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#2c3e50"))
                cornerRadius = (15 * density)
            }
        }
        
        // Header with back button and title
        val headerLayout = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        
        // Back button
        val backButton = Button(this).apply {
            text = "← Back to Battle Hub"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            background = getDrawable(R.drawable.button_rounded)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
            }
            setOnClickListener {
                // Set result to indicate we're returning (tasks may have been completed)
                setResult(RESULT_OK)
                finish()
            }
        }
        
        // Title
        val titleView = TextView(this).apply {
            text = when (mapType) {
                "optional" -> "🗺️ Extra Practice Map 🗺️"
                "bonus" -> "🎮 Bonus Training Map 🎮"
                else -> "🗺️ Training Map 🗺️"
            }
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        headerLayout.addView(backButton)
        headerLayout.addView(titleView)
        container.addView(headerLayout)
        
        // Progress info
        progressInfo = TextView(this).apply {
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        container.addView(progressInfo)
        
        // Map container (RelativeLayout for absolute positioning of gyms)
        mapContainer = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.6).toInt() // 60% of screen height
            ).apply {
                setMargins(0, (16 * density).toInt(), 0, (16 * density).toInt())
            }
            // Map background image
            try {
                val bitmap = android.graphics.BitmapFactory.decodeStream(assets.open("images/map1.png"))
                val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
                background = drawable
            } catch (e: Exception) {
                // Fallback to brown if image not found
                android.util.Log.e("TrainingMapActivity", "Could not load map1.png", e)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#8b6914"))
                    cornerRadius = (10 * density)
                }
            }
            minimumHeight = (400 * density).toInt()
        }
        
        container.addView(mapContainer)
        
        // Wrap in ScrollView for smaller screens
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(container)
        }
        
        setContentView(scrollView)
        
        // Load tasks into map after view is laid out
        mapContainer.post {
            loadTasksIntoMap()
        }
    }
    
    private fun loadTasksIntoMap() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentUpdateService = ContentUpdateService()
                val cloudStorageManager = CloudStorageManager(this@TrainingMapActivity)
                val profile = SettingsManager.readProfile(this@TrainingMapActivity) ?: "AM"
                
                // Step 1: Try to fetch latest from GitHub
                var jsonString: String? = null
                var contentFromGitHub: MainContent? = null
                
                try {
                    contentFromGitHub = contentUpdateService.fetchMainContent(this@TrainingMapActivity)
                    if (contentFromGitHub != null) {
                        // Successfully fetched from GitHub - fetchMainContent already saved to cache
                        jsonString = Gson().toJson(contentFromGitHub)
                        android.util.Log.d("TrainingMapActivity", "Successfully fetched config from GitHub")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TrainingMapActivity", "Could not fetch from GitHub: ${e.message}, falling back to cache")
                }
                
                // Step 2: If GitHub failed, use cache/local storage
                if (jsonString == null || contentFromGitHub == null) {
                    android.util.Log.d("TrainingMapActivity", "Using cached/local config")
                    jsonString = contentUpdateService.getCachedMainContent(this@TrainingMapActivity)
                }
                
                currentContent = if (jsonString != null && jsonString.isNotEmpty()) {
                    Gson().fromJson(jsonString, MainContent::class.java)
                } else null
                
                if (currentContent == null) {
                    withContext(Dispatchers.Main) {
                        progressInfo.text = "Unable to load tasks"
                    }
                    return@launch
                }
                
                // CRITICAL: Pass mapType to get only the relevant tasks (required or practice)
                // This prevents collisions when the same task name exists in both
                val completedTasksMap = progressManager.getCompletedTasksMap(mapType).toMutableMap()
                
                // Get tasks for this map type
                val section = currentContent!!.sections?.find { it.id == mapType }
                val baseTasks = section?.tasks?.filter { task ->
                    task.title != null && 
                    task.launch != null && 
                    isTaskVisible(task.showdays, task.hidedays, task.displayDays, task.disable)
                } ?: emptyList()
                
                // For required map, also include checklist items as required tasks
                val checklistTasks = if (mapType == "required") {
                    val checklistSection = currentContent!!.sections?.find { it.id == "checklist" }
                    checklistSection?.items?.filter { item ->
                        item.label != null &&
                        isTaskVisible(item.showdays, item.hidedays, item.displayDays, null)
                    }?.map { item ->
                        // Convert ChecklistItem to Task-like structure for display
                        Task(
                            title = item.label,
                            launch = "checklist_${item.id ?: item.label}",
                            stars = item.stars ?: 0,
                            showdays = item.showdays,
                            hidedays = item.hidedays,
                            displayDays = item.displayDays
                        )
                    } ?: emptyList()
                } else {
                    emptyList()
                }
                
                // Combine regular tasks and checklist tasks
                val tasks = baseTasks + checklistTasks
                
                if (tasks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressInfo.text = "No ${mapType} tasks available"
                    }
                    return@launch
                }
                
                // For optional tasks, check if all are completed - if so, reset them all
                if (mapType == "optional") {
                    val allCompleted = tasks.all { task ->
                        val baseTaskId = task.launch ?: ""
                        val taskTitle = task.title ?: ""
                        
                        // For diagramLabeler, use final URL to get correct diagram parameter
                        if (baseTaskId == "diagramLabeler" && !task.url.isNullOrEmpty()) {
                            val finalUrl = getGameModeUrl(task.url, task.easydays, task.harddays, task.extremedays)
                            if (finalUrl.contains("diagram=")) {
                                val diagramParam = finalUrl.substringAfter("diagram=").substringBefore("&").substringBefore("#")
                                if (diagramParam.isNotEmpty()) {
                                    val taskId = "${mapType}_${baseTaskId}_$diagramParam"
                                    return@all completedTasksMap[taskId] == true
                                }
                            }
                        }
                        
                        // Use task title as key (matches how tasks are stored in required_tasks)
                        completedTasksMap[taskTitle] == true
                    }
                    
                    // If all optional tasks are completed, reset them all (never ending)
                    if (allCompleted && tasks.isNotEmpty()) {
                        val allCompletedTasks = progressManager.getCompletedTasksMap(mapType).toMutableMap()
                        tasks.forEach { task ->
                            val baseTaskId = task.launch ?: ""
                            val taskTitle = task.title ?: ""
                            
                            // For diagramLabeler, use final URL to get correct diagram parameter
                            if (baseTaskId == "diagramLabeler" && !task.url.isNullOrEmpty()) {
                                val finalUrl = getGameModeUrl(task.url, task.easydays, task.harddays, task.extremedays)
                                if (finalUrl.contains("diagram=")) {
                                    val diagramParam = finalUrl.substringAfter("diagram=").substringBefore("&").substringBefore("#")
                                    if (diagramParam.isNotEmpty()) {
                                        val taskId = "${mapType}_${baseTaskId}_$diagramParam"
                                        // Clear completion status by removing from map
                                        allCompletedTasks.remove(taskId)
                                    }
                                }
                            } else {
                                // Use task title as key (matches how tasks are stored in required_tasks)
                                allCompletedTasks.remove(taskTitle)
                            }
                        }
                        // Save the updated map
                        // CRITICAL: Use the same SharedPreferences name as DailyProgressManager
                        val profile = SettingsManager.readProfile(this@TrainingMapActivity) ?: "AM"
                        val prefs = getSharedPreferences("daily_progress_prefs", android.content.Context.MODE_PRIVATE)
                        val gson = Gson()
                        val completedTasksKey = "${profile}_completed_tasks"
                        prefs.edit()
                            .putString(completedTasksKey, gson.toJson(allCompletedTasks))
                            .apply()
                        // Refresh the completed tasks map after resetting
                        completedTasksMap.clear()
                        completedTasksMap.putAll(progressManager.getCompletedTasksMap(mapType))
                    }
                }
                
                // Calculate progress
                val completedCount = tasks.count { task ->
                    val baseTaskId = task.launch ?: ""
                    val taskTitle = task.title ?: ""
                    
                    // Check if this is a checklist item
                    if (baseTaskId.startsWith("checklist_")) {
                        // For checklist items, use the label as the key (matches how they're stored)
                        return@count completedTasksMap[taskTitle] == true
                    }
                    
                    // For diagramLabeler, we need to get the final URL (after getGameModeUrl processing)
                    // to extract the correct diagram parameter for tracking
                    if (baseTaskId == "diagramLabeler" && !task.url.isNullOrEmpty()) {
                        // Get the final URL with mode parameter processed
                        val finalUrl = getGameModeUrl(task.url, task.easydays, task.harddays, task.extremedays)
                        if (finalUrl.contains("diagram=")) {
                            val diagramParam = finalUrl.substringAfter("diagram=").substringBefore("&").substringBefore("#")
                            if (diagramParam.isNotEmpty()) {
                                val uniqueTaskId = if (mapType == "required") {
                                    "${baseTaskId}_$diagramParam"
                                } else {
                                    "${mapType}_${baseTaskId}_$diagramParam"
                                }
                                // Only check the unique ID, not the base ID, to track each diagram separately
                                if (completedTasksMap[uniqueTaskId] == true) {
                                    return@count true
                                }
                            }
                        }
                    }
                    
                    // For non-diagramLabeler tasks, use task title as key (matches how tasks are stored in required_tasks)
                    completedTasksMap[taskTitle] == true
                }
                
                withContext(Dispatchers.Main) {
                    progressInfo.text = "${mapType.capitalize()} Training: $completedCount / ${tasks.size} completed"

                    // Ensure the container is measured before we place buttons.
                    // This avoids the first-load "fallback width/height" which caused positions to shift on next reload.
                    mapContainer.post {
                        // Generate random positions for gyms
                        val density = resources.displayMetrics.density
                        val mapWidth = mapContainer.width.coerceAtLeast(1)
                        val mapHeight = mapContainer.height.coerceAtLeast(1)
                        val buttonSize = (80 * density).toInt()
                        val buttonRadius = (40 * density).toInt()
                        val padding = (20 * density).toInt() // Padding from edges

                        // Load icon config
                        val icons = try {
                            val inputStream = assets.open("config/icon_config.json")
                            val configJson = inputStream.bufferedReader().use { it.readText() }
                            inputStream.close()
                            Gson().fromJson(configJson, IconConfig::class.java) ?: IconConfig()
                        } catch (e: Exception) {
                            IconConfig()
                        }

                        // Deterministic seed so gym positions don't reshuffle on each map reload.
                        // Include URL because some tasks share the same launch id (e.g., diagramLabeler).
                        val seedInput = buildString {
                            append(mapType)
                            tasks.forEach { t ->
                                append('|')
                                append(t.launch ?: "")
                                append('|')
                                append(t.url ?: "")
                                append('|')
                                append(t.title ?: "")
                            }
                        }
                        val seedHash = seedInput.hashCode()
                        val random = kotlin.random.Random(seedHash)

                        // Generate positions for gyms (like points of interest on a real map)
                        // But ensure they don't overlap
                        val positions = mutableListOf<Pair<Int, Int>>()
                        val actualButtonSize = buttonSize
                        val actualButtonRadius = buttonRadius

                        // Minimum distance between button centers (to prevent overlaps)
                        // Increased to 2.2x for better spacing, especially with many tasks
                        val minDistance = (buttonSize * 2.2).toInt()

                        // Always use grid-based layout to guarantee no overlaps
                        // Grid layout ensures reliable positioning without any overlapping tasks
                        val useGridLayout = true
                        
                        // Create gym buttons with positions
                        val gymButtons = mutableListOf<Pair<View, Pair<Int, Int>>>()

                        tasks.forEachIndexed { index, task ->
                        val baseTaskId = task.launch ?: ""
                        val taskTitle = task.title ?: ""
                        
                        // For diagramLabeler, we need to get the final URL (after getGameModeUrl processing)
                        // to extract the correct diagram parameter for tracking
                        var isCompleted = false
                        if (mapType == "bonus") {
                            // Bonus tasks are never completed
                            isCompleted = false
                        } else if (baseTaskId.startsWith("checklist_")) {
                            // For checklist items, use the label as the key (matches how they're stored)
                            isCompleted = completedTasksMap[taskTitle] == true
                        } else if (baseTaskId == "diagramLabeler" && !task.url.isNullOrEmpty()) {
                            // Get the final URL with mode parameter processed
                            val finalUrl = getGameModeUrl(task.url, task.easydays, task.harddays, task.extremedays)
                            if (finalUrl.contains("diagram=")) {
                                val diagramParam = finalUrl.substringAfter("diagram=").substringBefore("&").substringBefore("#")
                                if (diagramParam.isNotEmpty()) {
                                    val uniqueTaskId = if (mapType == "required") {
                                        "${baseTaskId}_$diagramParam"
                                    } else {
                                        "${mapType}_${baseTaskId}_$diagramParam"
                                    }
                                    // Only check the unique ID to track each diagram separately
                                    isCompleted = completedTasksMap[uniqueTaskId] == true
                                } else {
                                    // No diagram parameter value, use task title as key
                                    isCompleted = completedTasksMap[taskTitle] == true
                                }
                            } else {
                                // No diagram parameter found, use task title as key
                                isCompleted = completedTasksMap[taskTitle] == true
                            }
                        } else {
                            // For non-diagramLabeler tasks, use task title as key (matches how tasks are stored in required_tasks)
                            isCompleted = completedTasksMap[taskTitle] == true
                            // CRITICAL: Log task completion check
                            if (taskTitle.isNotEmpty()) {
                                Log.d("TrainingMapActivity", "CRITICAL: Checking completion for task: '$taskTitle' (taskId: $baseTaskId)")
                                Log.d("TrainingMapActivity", "CRITICAL: completedTasksMap['$taskTitle'] = ${completedTasksMap[taskTitle]}")
                                Log.d("TrainingMapActivity", "CRITICAL: isCompleted = $isCompleted")
                                Log.d("TrainingMapActivity", "CRITICAL: All keys in completedTasksMap: ${completedTasksMap.keys}")
                            }
                        }
                        
                        // Generate position that doesn't overlap with existing buttons
                        var x: Int = padding
                        var y: Int = padding
                        
                        if (useGridLayout) {
                            // Grid-based layout for many tasks - guarantees no overlaps
                            val availableWidth = mapWidth - 2 * padding
                            val availableHeight = mapHeight - 2 * padding
                            
                            // Calculate grid dimensions to fit all tasks
                            val cols = Math.ceil(Math.sqrt(tasks.size.toDouble())).toInt()
                            val rows = Math.ceil(tasks.size.toDouble() / cols).toInt()
                            
                            val cellWidth = availableWidth / cols
                            val cellHeight = availableHeight / rows
                            
                            // Ensure cells are large enough for buttons with spacing
                            val minCellSize = minDistance
                            if (cellWidth < minCellSize || cellHeight < minCellSize) {
                                // Fallback: recalculate with minimum cell size
                                val maxCols = availableWidth / minCellSize
                                val maxRows = availableHeight / minCellSize
                                val adjustedCols = Math.min(maxCols, Math.ceil(Math.sqrt(tasks.size.toDouble())).toInt())
                                val adjustedRows = Math.ceil(tasks.size.toDouble() / adjustedCols).toInt()
                                
                                val col = index % adjustedCols
                                val row = index / adjustedCols
                                
                                val adjustedCellWidth = if (adjustedCols > 0) availableWidth / adjustedCols else minCellSize
                                val adjustedCellHeight = if (adjustedRows > 0) availableHeight / adjustedRows else minCellSize
                                
                                // Center button within cell
                                x = padding + (col * adjustedCellWidth) + (adjustedCellWidth - actualButtonSize) / 2
                                y = padding + (row * adjustedCellHeight) + (adjustedCellHeight - actualButtonSize) / 2
                            } else {
                                val col = index % cols
                                val row = index / cols
                                
                                // Center button within cell
                                x = padding + (col * cellWidth) + (cellWidth - actualButtonSize) / 2
                                y = padding + (row * cellHeight) + (cellHeight - actualButtonSize) / 2
                            }
                            
                            val centerX = x + actualButtonRadius
                            val centerY = y + actualButtonRadius
                            positions.add(Pair(centerX, centerY))
                        } else {
                            // Random placement for fewer tasks (more natural look)
                            var attempts = 0
                            val maxAttempts = 500 // Increased attempts for better distribution
                            var positionFound = false
                            
                            // Try to find a non-overlapping position
                            while (attempts < maxAttempts && !positionFound) {
                                // Random position within bounds (with padding)
                                val xRange = (mapWidth - 2 * padding - actualButtonSize).coerceAtLeast(1)
                                val yRange = (mapHeight - 2 * padding - actualButtonSize).coerceAtLeast(1)
                                x = padding + random.nextInt(xRange)
                                y = padding + random.nextInt(yRange)
                                
                                val centerX = x + actualButtonRadius
                                val centerY = y + actualButtonRadius
                                
                                // Check if this position overlaps with any existing button
                                val overlaps = positions.any { (existingX, existingY) ->
                                    val dx = existingX - centerX
                                    val dy = existingY - centerY
                                    val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                                    distance < minDistance
                                }
                                
                                if (!overlaps) {
                                    positionFound = true
                                    positions.add(Pair(centerX, centerY)) // Store center position
                                } else {
                                    attempts++
                                }
                            }
                            
                            // If we couldn't find a non-overlapping position after maxAttempts, use a fallback position
                            if (!positionFound) {
                                // Fallback: space them out in a spiral pattern from center
                                val angle = (index * 2 * Math.PI / tasks.size) + (random.nextDouble() * 0.5)
                                val radius = Math.min(mapWidth, mapHeight) * 0.3 * (1.0 + index * 0.1)
                                val fallbackX = (mapWidth / 2 + radius * Math.cos(angle) - actualButtonRadius).toInt()
                                val fallbackY = (mapHeight / 2 + radius * Math.sin(angle) - actualButtonRadius).toInt()
                                x = fallbackX.coerceIn(padding, mapWidth - padding - actualButtonSize)
                                y = fallbackY.coerceIn(padding, mapHeight - padding - actualButtonSize)
                                val centerX = x + actualButtonRadius
                                val centerY = y + actualButtonRadius
                                positions.add(Pair(centerX, centerY))
                            }
                        }
                        
                        // Create gym button container (FrameLayout to layer emoji and text)
                        val gymButtonContainer = FrameLayout(this@TrainingMapActivity).apply {
                            layoutParams = RelativeLayout.LayoutParams(
                                actualButtonSize,
                                actualButtonSize
                            ).apply {
                                leftMargin = x
                                topMargin = y
                            }
                            
                            // Background circle
                            background = if (isCompleted) {
                                android.graphics.drawable.GradientDrawable().apply {
                                    setColor(android.graphics.Color.parseColor("#4CAF50"))
                                    cornerRadius = actualButtonRadius.toFloat()
                                    setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#2e7d32"))
                                }
                            } else {
                                android.graphics.drawable.GradientDrawable().apply {
                                    setColor(android.graphics.Color.parseColor("#ffd700"))
                                    cornerRadius = actualButtonRadius.toFloat()
                                    setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#ff8c00"))
                                }
                            }
                            
                            if (!isCompleted) {
                                setOnClickListener {
                                    // Check if this is a checklist item
                                    if (baseTaskId.startsWith("checklist_")) {
                                        // For checklist items, show completion dialog and mark as complete
                                        handleChecklistTaskCompletion(task)
                                    } else {
                                        // Launch task using Layout's launch logic
                                        launchTask(task, mapType)
                                    }
                                }
                            }
                        }
                        
                        // Gym emoji - sized to fit within button (smaller to ensure it fits)
                        val gymEmoji = TextView(this@TrainingMapActivity).apply {
                            text = "🏟️"
                            textSize = (actualButtonSize * 0.35f) // 35% of button size to ensure it fits comfortably
                            gravity = android.view.Gravity.CENTER
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                        
                        // Text overlay (title and stars icon) - smaller text
                        val textOverlay = TextView(this@TrainingMapActivity).apply {
                            val starsCount = task.stars ?: 1
                            text = "${task.title}\n$starsCount ${icons.starsIcon}"
                            textSize = 8f // Smaller text to prevent wrapping
                            setTextColor(if (isCompleted) android.graphics.Color.GRAY else android.graphics.Color.BLACK)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            gravity = android.view.Gravity.CENTER
                            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                            // Add semi-transparent background for better text readability
                            setBackgroundColor(android.graphics.Color.argb(180, 255, 255, 255))
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = android.view.Gravity.CENTER
                            }
                        }
                        
                        gymButtonContainer.addView(gymEmoji)
                        gymButtonContainer.addView(textOverlay)
                        
                        gymButtons.add(Pair(gymButtonContainer, Pair(x + buttonRadius, y + buttonRadius)))
                    }
                    
                    // Create custom view to draw dashed lines connecting the gyms
                    // Use random connections to create an interesting network effect
                    val pathView = object : View(this@TrainingMapActivity) {
                        override fun onDraw(canvas: android.graphics.Canvas) {
                            super.onDraw(canvas)
                            
                            if (positions.size < 2) return
                            
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#ffffff")
                                strokeWidth = (3 * density)
                                style = android.graphics.Paint.Style.STROKE
                                pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f * density, 8f * density), 0f)
                                alpha = 150 // Semi-transparent so lines don't obscure buttons
                            }
                            
                            // Create random connections between tasks
                            // Each task connects to 1-3 other random tasks to create a network
                            val connections = mutableSetOf<Pair<Int, Int>>()
                            val connectionRandom = kotlin.random.Random(seedHash)
                            
                            // Ensure each task has at least one connection
                            for (i in positions.indices) {
                                val numConnections = connectionRandom.nextInt(1, 4) // 1-3 connections per task
                                var connectionsMade = 0
                                var attempts = 0
                                
                                while (connectionsMade < numConnections && attempts < 20) {
                                    val targetIndex = connectionRandom.nextInt(positions.size)
                                    if (targetIndex != i) {
                                        val connection = if (i < targetIndex) Pair(i, targetIndex) else Pair(targetIndex, i)
                                        if (connections.add(connection)) {
                                            connectionsMade++
                                        }
                                    }
                                    attempts++
                                }
                            }
                            
                            // Draw all connections
                            connections.forEach { (startIdx, endIdx) ->
                                val start = positions[startIdx]
                                val end = positions[endIdx]
                                canvas.drawLine(
                                    start.first.toFloat(),
                                    start.second.toFloat(),
                                    end.first.toFloat(),
                                    end.second.toFloat(),
                                    paint
                                )
                            }
                        }
                    }.apply {
                        layoutParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    
                    // Add path view first (so it appears behind the buttons)
                    mapContainer.addView(pathView)
                    
                    // Add all gym buttons
                    gymButtons.forEach { (button, _) ->
                        mapContainer.addView(button)
                    }
                        }
                    }
            } catch (e: Exception) {
                android.util.Log.e("TrainingMapActivity", "Error loading map", e)
                withContext(Dispatchers.Main) {
                    progressInfo.text = "Error loading tasks"
                }
            }
        }
    }
    
    private fun launchTask(task: Task, sectionId: String) {
        // Use the same launch logic as Layout.kt
        val gameType = task.launch ?: "unknown"
        val gameTitle = task.title ?: "Task"
        
        // Check if this is a video sequence task FIRST (before game content)
        // Videos should be handled before trying to fetch game content from data/ folder
        if (task.videoSequence != null) {
            handleVideoSequenceTask(task, sectionId)
            return
        }
        
        // Check if this is a Chrome page task
        if (task.chromePage == true) {
            val pageUrl = task.url
            if (pageUrl.isNullOrBlank()) {
                android.widget.Toast.makeText(this, "No page configured for this task.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, ChromePageActivity::class.java).apply {
                    putExtra(ChromePageActivity.EXTRA_URL, pageUrl)
                    putExtra(ChromePageActivity.EXTRA_TASK_ID, gameType)
                    putExtra(ChromePageActivity.EXTRA_STARS, task.stars ?: 0)
                    putExtra(ChromePageActivity.EXTRA_TASK_TITLE, gameTitle)
                    putExtra(ChromePageActivity.EXTRA_SECTION_ID, sectionId)
                }
                startActivityForResult(intent, 1001)
            }
            return
        }
        
        // Check if this is a web game task
        if (task.webGame == true && !task.url.isNullOrEmpty()) {
            // Get final URL with mode parameter processed (for easydays/harddays/extremedays)
            var finalGameUrl = getGameModeUrl(task.url, task.easydays, task.harddays, task.extremedays)
            
            // Append totalQuestions parameter if specified in config
            if (task.totalQuestions != null) {
                val separator = if (finalGameUrl.contains("?")) "&" else "?"
                finalGameUrl = "$finalGameUrl${separator}totalQuestions=${task.totalQuestions}"
                android.util.Log.d("TrainingMapActivity", "Added totalQuestions=${task.totalQuestions} to URL: $finalGameUrl")
            }
            
            // IMPORTANT: Always use GitHub Pages URL first - never convert to local assets
            // GitHub is the source of truth. WebView will naturally fall back to local cache if needed.
            // Only local assets are used if GitHub URL is not available (offline/no network).
            android.util.Log.d("TrainingMapActivity", "Using GitHub Pages URL (will load from GitHub first): $finalGameUrl")
            
            // For diagramLabeler, make taskId unique if diagram parameter exists
            // Use the final URL (after mode processing) to extract diagram parameter
            // Note: Don't include section prefix here - WebGameActivity will add it via getUniqueTaskId
            var taskIdToPass = gameType
            if (gameType == "diagramLabeler" && finalGameUrl.contains("diagram=")) {
                val diagramParam = finalGameUrl.substringAfter("diagram=").substringBefore("&").substringBefore("#")
                if (diagramParam.isNotEmpty()) {
                    // Include diagram parameter but not section prefix (WebGameActivity will add section prefix)
                    taskIdToPass = "${gameType}_$diagramParam"
                }
            }
            
            lastLaunchedGameSectionId = sectionId
            val intent = Intent(this, WebGameActivity::class.java).apply {
                putExtra(WebGameActivity.EXTRA_GAME_URL, finalGameUrl)
                putExtra(WebGameActivity.EXTRA_TASK_ID, taskIdToPass) // WebGameActivity will add section prefix via getUniqueTaskId
                putExtra(WebGameActivity.EXTRA_SECTION_ID, sectionId)
                putExtra(WebGameActivity.EXTRA_STARS, task.stars ?: 0)
                putExtra(WebGameActivity.EXTRA_TASK_TITLE, gameTitle)
            }
            startActivityForResult(intent, 1002)
            return
        }
        
        // Check if this is a direct playlist task (playlistId without videoSequence)
        if (task.playlistId != null) {
            val intent = Intent(this, YouTubePlayerActivity::class.java).apply {
                putExtra(YouTubePlayerActivity.EXTRA_PLAYLIST_ID, task.playlistId)
                putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, gameTitle)
            }
            startActivityForResult(intent, 1003)
            return
        }
        
        // Check for Google Read Along
        if (task.launch == "googleReadAlong") {
            // Launch MainActivity to handle Google Read Along (it has the launcher setup)
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("launchTask", gameType)
                putExtra("taskTitle", gameTitle)
                putExtra("sectionId", sectionId)
                putExtra("taskStars", task.stars ?: 0)
            }
            startActivity(intent)
            return
        }
        
        // Check for Boukili
        if (task.launch == "boukili") {
            // Launch MainActivity to handle Boukili (it has the launcher setup)
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("launchTask", gameType)
                putExtra("taskTitle", gameTitle)
                putExtra("sectionId", sectionId)
                putExtra("taskStars", task.stars ?: 0)
            }
            startActivity(intent)
            return
        }
        
        // Check for French Book Reader
        if (task.launch == "frenchBookReader") {
            val intent = Intent(this, FrenchBookReaderActivity::class.java)
            startActivity(intent)
            return
        }
        
        // Check for Printing Game (doesn't need JSON)
        if (gameType == "printing") {
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
            launchPrintingGame(game, sectionId)
            return
        }
        
        // Check for Spelling OCR Game (doesn't need JSON)
        if (gameType == "spellingOCR") {
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
            launchSpellingOCRGame(game, sectionId, task)
            return
        }
        
        // Handle regular game content - use same logic as Layout.kt
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentUpdateService = ContentUpdateService()
                val gameContent = contentUpdateService.fetchGameContent(this@TrainingMapActivity, gameType)

                withContext(Dispatchers.Main) {
                    android.util.Log.d("TrainingMapActivity", "Launching game: $gameTitle, totalQuestions=${task.totalQuestions}, gameType=$gameType")
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
                    
                    if (gameContent != null) {
                        launchGameActivity(game, gameContent, sectionId)
                    } else {
                        android.widget.Toast.makeText(this@TrainingMapActivity, "$gameType content not available", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TrainingMapActivity", "Error fetching game content for $gameType", e)
                
                // Fallback to starting game without content
                withContext(Dispatchers.Main) {
                    android.util.Log.d("TrainingMapActivity", "Launching game (no content): $gameTitle, totalQuestions=${task.totalQuestions}, gameType=$gameType")
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
                    android.widget.Toast.makeText(this@TrainingMapActivity, "$gameType content not available", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun launchPrintingGame(game: Game, sectionId: String) {
        val gameType = game.type
        val gameTitle = game.title
        val stars = game.estimatedTime
        val isRequired = sectionId == "required"
        
        val intent = Intent(this, PrintingGameActivity::class.java).apply {
            putExtra("GAME_TYPE", gameType)
            putExtra("GAME_TITLE", gameTitle)
            putExtra("GAME_STARS", stars)
            putExtra("IS_REQUIRED_GAME", isRequired)
            putExtra("SECTION_ID", sectionId)
        }
        
        startActivityForResult(intent, 1005) // Use 1005 for PrintingGameActivity
    }
    
    private fun launchSpellingOCRGame(game: Game, sectionId: String, task: Task) {
        val gameType = game.type
        val gameTitle = game.title
        val stars = game.estimatedTime
        val isRequired = sectionId == "required"
        
        // Extract word file from task URL, default to englishWordsGr1.json
        val wordFile = when {
            task.url == null -> "englishWordsGr1.json"
            task.url.contains("file=") -> {
                val extracted = task.url.substringAfter("file=").substringBefore("&").trim()
                if (extracted.isNotEmpty() && extracted.endsWith(".json")) extracted else "englishWordsGr1.json"
            }
            task.url.endsWith(".json") -> task.url
            else -> "englishWordsGr1.json"
        }
        
        android.util.Log.d("TrainingMapActivity", "Launching Spelling OCR Game with word file: $wordFile")
        
        val intent = Intent(this, SpellingOCRActivity::class.java).apply {
            putExtra("GAME_TYPE", gameType)
            putExtra("GAME_TITLE", gameTitle)
            putExtra("GAME_STARS", stars)
            putExtra("IS_REQUIRED_GAME", isRequired)
            putExtra("SECTION_ID", sectionId)
            putExtra("WORD_FILE", wordFile)
        }
        
        startActivityForResult(intent, 1006) // Use 1006 for SpellingOCRActivity
    }
    
    private fun launchGameActivity(game: Game, gameContent: String, sectionId: String) {
        try {
            val gson = Gson()
            val questions = gson.fromJson(gameContent, Array<GameData>::class.java)
            
            // Calculate total questions (same logic as MainActivity)
            val totalQuestions = when {
                game.totalQuestions != null -> {
                    android.util.Log.d("TrainingMapActivity", "Using totalQuestions from config: ${game.totalQuestions}")
                    game.totalQuestions!!
                }
                questions.size > 0 -> {
                    android.util.Log.d("TrainingMapActivity", "No totalQuestions in config, using questions.size: ${questions.size}")
                    questions.size
                }
                else -> {
                    android.util.Log.d("TrainingMapActivity", "No totalQuestions in config and no questions, using default: 5")
                    5
                }
            }
            
            // Determine if game is required
            val isRequired = currentContent?.sections?.any { section ->
                section.id == "required" && section.tasks?.any { it.launch == game.type } == true
            } ?: false

            // Store section ID for use in onActivityResult
            lastLaunchedGameSectionId = sectionId
            
            val intent = Intent(this, GameActivity::class.java).apply {
                putExtra("GAME_CONTENT", gameContent)
                putExtra("GAME_TYPE", game.type)
                putExtra("GAME_TITLE", game.title)
                putExtra("TOTAL_QUESTIONS", totalQuestions)
                putExtra("GAME_STARS", game.estimatedTime)
                putExtra("IS_REQUIRED_GAME", isRequired)
                putExtra("BLOCK_OUTLINES", game.blockOutlines)
                putExtra("SECTION_ID", sectionId)
            }
            
            startActivityForResult(intent, 1004) // Use 1004 for GameActivity
        } catch (e: Exception) {
            android.util.Log.e("TrainingMapActivity", "Error parsing game content for ${game.type}", e)
            android.widget.Toast.makeText(this, "Error loading game content", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // Handle ChromePageActivity result (requestCode 1001)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val chromeTaskId = data.getStringExtra(ChromePageActivity.EXTRA_TASK_ID)
            val chromeSectionId = data.getStringExtra(ChromePageActivity.EXTRA_SECTION_ID)
            val chromeStars = data.getIntExtra(ChromePageActivity.EXTRA_STARS, 0)
            val chromeTaskTitle = data.getStringExtra(ChromePageActivity.EXTRA_TASK_TITLE)
            
            if (!chromeTaskId.isNullOrEmpty()) {
                val progressManager = DailyProgressManager(this)
                val displayTitle = chromeTaskTitle ?: "Chrome Page: $chromeTaskId"
                val isRequiredTask = chromeSectionId == "required"
                
                // Mark task as completed and get earned stars
                val earnedStars = progressManager.markTaskCompletedWithName(
                    chromeTaskId,
                    displayTitle,
                    chromeStars,
                    isRequiredTask,
                    currentContent,
                    chromeSectionId
                )
                
                if (earnedStars > 0) {
                    progressManager.grantRewardsForTaskCompletion(earnedStars, chromeSectionId)
                }
                android.util.Log.d("TrainingMapActivity", "Marked Chrome page task as completed: taskId=$chromeTaskId, sectionId=$chromeSectionId, stars=$chromeStars, earnedStars=$earnedStars")
            }
        }
        
        // Handle WebGameActivity result (requestCode 1002)
        if (requestCode == 1002 && resultCode == RESULT_OK && data != null) {
            val taskId = data.getStringExtra(WebGameActivity.EXTRA_TASK_ID)
            val sectionId = data.getStringExtra(WebGameActivity.EXTRA_SECTION_ID) ?: lastLaunchedGameSectionId ?: mapType
            val stars = data.getIntExtra(WebGameActivity.EXTRA_STARS, 0)
            val taskTitle = data.getStringExtra(WebGameActivity.EXTRA_TASK_TITLE)
            
            // Mark task as completed (same logic as Layout.handleWebGameCompletion)
            if (!taskId.isNullOrEmpty()) {
                val progressManager = DailyProgressManager(this)
                
                // Use task title from parameter or create default
                val displayTitle = taskTitle ?: "Web Game: $taskId"
                
                // Determine if task is required based on section ID
                val isRequiredTask = sectionId == "required"
                
                // Mark task as completed and get earned stars
                val earnedStars = progressManager.markTaskCompletedWithName(
                    taskId,
                    displayTitle,
                    stars,
                    isRequiredTask,
                    currentContent,  // Pass config to verify section
                    sectionId  // Pass section ID to create unique task IDs
                )
                
                if (earnedStars > 0) {
                    progressManager.grantRewardsForTaskCompletion(earnedStars, sectionId)
                }
                android.util.Log.d("TrainingMapActivity", "Marked web game task as completed: taskId=$taskId, sectionId=$sectionId, stars=$stars, earnedStars=$earnedStars")
            }
        }
        
        // Handle PrintingGameActivity result (same format as GameActivity)
        if (requestCode == 1005 && resultCode == RESULT_OK && data != null) {
            val gameType = data.getStringExtra("GAME_TYPE")
            val gameStars = data.getIntExtra("GAME_STARS", 0)
            
            // Use the stored section ID from when we launched the game
            val gameSectionId = lastLaunchedGameSectionId ?: mapType
            
            if (!gameType.isNullOrEmpty()) {
                // Find the task in currentContent to get its title
                var gameTitle = "Game: $gameType"
                currentContent?.sections?.forEach { section ->
                    section.tasks?.find { it.launch == gameType }?.let { task ->
                        gameTitle = task.title ?: gameTitle
                    }
                }
                
                val progressManager = DailyProgressManager(this)
                val isRequiredTask = gameSectionId == "required"
                
                // Mark task as completed and get earned stars
                val earnedStars = progressManager.markTaskCompletedWithName(
                    gameType,
                    gameTitle,
                    gameStars,
                    isRequiredTask,
                    currentContent,
                    gameSectionId
                )
                
                if (earnedStars > 0) {
                    progressManager.grantRewardsForTaskCompletion(earnedStars, gameSectionId)
                }
                android.util.Log.d("TrainingMapActivity", "Marked printing game task as completed: taskId=$gameType, sectionId=$gameSectionId, stars=$gameStars, earnedStars=$earnedStars")
            }
        }
        
        // Handle SpellingOCRActivity result (same format as PrintingGameActivity)
        if (requestCode == 1006 && resultCode == RESULT_OK && data != null) {
            val rewardsAlreadyApplied = data.getBooleanExtra("REWARDS_APPLIED", false)
            if (!rewardsAlreadyApplied) {
                val gameType = data.getStringExtra("GAME_TYPE")
                val gameStars = data.getIntExtra("GAME_STARS", 0)
                val gameSectionId = data.getStringExtra("SECTION_ID") ?: lastLaunchedGameSectionId ?: mapType
                if (!gameType.isNullOrEmpty()) {
                    var gameTitle = "Game: $gameType"
                    currentContent?.sections?.forEach { section ->
                        section.tasks?.find { it.launch == gameType }?.let { task ->
                            gameTitle = task.title ?: gameTitle
                        }
                    }
                    val progressManager = DailyProgressManager(this)
                    val earnedStars = progressManager.markTaskCompletedWithName(
                        gameType, gameTitle, gameStars, gameSectionId == "required", currentContent, gameSectionId
                    )
                    if (earnedStars > 0) {
                        progressManager.grantRewardsForTaskCompletion(earnedStars, gameSectionId)
                    }
                }
            }
        }
        
        // Handle GameActivity result
        if (requestCode == 1004 && resultCode == RESULT_OK && data != null) {
            val rewardsAlreadyApplied = data.getBooleanExtra("REWARDS_APPLIED", false)
            if (!rewardsAlreadyApplied) {
                val gameType = data.getStringExtra("GAME_TYPE")
                val gameStars = data.getIntExtra("GAME_STARS", 0)
                val gameSectionId = data.getStringExtra("SECTION_ID") ?: lastLaunchedGameSectionId ?: mapType
                if (!gameType.isNullOrEmpty()) {
                    var gameTitle = "Game: $gameType"
                    currentContent?.sections?.forEach { section ->
                        section.tasks?.find { it.launch == gameType }?.let { task ->
                            gameTitle = task.title ?: gameTitle
                        }
                    }
                    val progressManager = DailyProgressManager(this)
                    val earnedStars = progressManager.markTaskCompletedWithName(
                        gameType, gameTitle, gameStars, gameSectionId == "required", currentContent, gameSectionId
                    )
                    if (earnedStars > 0) {
                        progressManager.grantRewardsForTaskCompletion(earnedStars, gameSectionId)
                    }
                }
            }
        }
        
        // Handle YouTubePlayerActivity result (videos)
        if (requestCode == 1003 && resultCode == RESULT_OK && data != null) {
            val videoTaskId = data.getStringExtra("TASK_ID")
            val videoSectionId = data.getStringExtra("SECTION_ID")
            val videoStars = data.getIntExtra("TASK_STARS", 0)
            val videoTaskTitle = data.getStringExtra("TASK_TITLE")
            
            if (!videoTaskId.isNullOrEmpty()) {
                val progressManager = DailyProgressManager(this)
                val displayTitle = videoTaskTitle ?: "Video: $videoTaskId"
                val isRequiredTask = videoSectionId == "required"
                
                // Mark task as completed and get earned stars
                val earnedStars = progressManager.markTaskCompletedWithName(
                    videoTaskId,
                    displayTitle,
                    videoStars,
                    isRequiredTask,
                    currentContent,
                    videoSectionId
                )
                
                if (earnedStars > 0) {
                    progressManager.grantRewardsForTaskCompletion(earnedStars, videoSectionId)
                }
                android.util.Log.d("TrainingMapActivity", "Marked video task as completed: taskId=$videoTaskId, sectionId=$videoSectionId, stars=$videoStars, earnedStars=$earnedStars")
            }
        }
        
        // Refresh the map when returning from any task to show updated completion status
        // Only refresh if the task was completed (RESULT_OK) to avoid unnecessary refreshes
        if ((requestCode == 1001 || requestCode == 1002 || requestCode == 1003 || requestCode == 1004 || requestCode == 1005 || requestCode == 1006) && resultCode == RESULT_OK) {
            val currentProfile = SettingsManager.readProfile(this) ?: "AM"
            // Advance local last_updated synchronously so cloudSync (e.g. on BattleHub return)
            // sees local newer than cloud and uploads instead of applying stale cloud over berries/tasks.
            DailyResetAndSyncManager(this).advanceLocalTimestampForProfile(currentProfile)
            // Blocking: finish sync before redraw so onResume (e.g. on BattleHub) has nothing to do.
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    DailyResetAndSyncManager(this@TrainingMapActivity).updateLocalTimestampAndSyncToCloud(currentProfile)
                    android.util.Log.d("TrainingMapActivity", "Task completion sync finished, redrawing map")
                } catch (e: Exception) {
                    android.util.Log.e("TrainingMapActivity", "Error syncing after task completion", e)
                }
                withContext(Dispatchers.Main) {
                    mapContainer.removeAllViews()
                    loadTasksIntoMap()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        android.util.Log.d("TrainingMapActivity", "onResume called")
        
        // Only sync and refresh if map is already initialized (after reset check completes)
        if (!::mapContainer.isInitialized) {
            android.util.Log.d("TrainingMapActivity", "onResume: Map not initialized yet, skipping refresh")
            return
        }
        
        // CRITICAL: Add a small delay before syncing to allow any pending task completion syncs to finish
        // This prevents onResume from overwriting data that was just saved
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Run daily_reset_process() and then cloud_sync() when resuming
            val profile = SettingsManager.readProfile(this) ?: "AM"
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val resetAndSyncManager = DailyResetAndSyncManager(this@TrainingMapActivity)
                    resetAndSyncManager.dailyResetProcessAndSync(profile)
                    android.util.Log.d("TrainingMapActivity", "Daily reset and sync completed in onResume for profile $profile")
                } catch (e: Exception) {
                    android.util.Log.e("TrainingMapActivity", "Error during daily reset and sync in onResume", e)
                }
                withContext(Dispatchers.Main) {
                    // Refresh the map after sync to show updated completion status
                    // This ensures the map shows updated completion status after sync
                    createMapView()
                    
                    // Check for Google Read Along completion after map refresh
                    checkGoogleReadAlongCompletion()
                    
                    // Check for Boukili completion after map refresh
                    checkBoukiliCompletion()
                }
            }
        }, 1000) // 1 second delay to allow task completion syncs to finish
    }
    
    /**
     * Checks if Google Read Along was completed when returning to this activity.
     * Similar logic to MainActivity.checkGoogleReadAlongCompletion()
     */
    private fun checkGoogleReadAlongCompletion() {
        val readAlongPrefs = getSharedPreferences("read_along_session", android.content.Context.MODE_PRIVATE)
        val startTimeMs = readAlongPrefs.getLong("read_along_start_time", -1L)
        
        if (startTimeMs <= 0L) {
            // No pending Read Along session
            android.util.Log.d("TrainingMapActivity", "No pending Google Read Along session")
            return
        }
        
        val taskId = readAlongPrefs.getString("read_along_task_id", null) ?: return
        val taskTitle = readAlongPrefs.getString("read_along_task_title", taskId) ?: taskId
        val stars = readAlongPrefs.getInt("read_along_stars", 0)
        val sectionId = readAlongPrefs.getString("read_along_section_id", null)?.takeIf { it.isNotBlank() }
        
        val now = System.currentTimeMillis()
        val timeElapsedMs = now - startTimeMs
        val timeElapsedSeconds = timeElapsedMs / 1000
        val MIN_READ_ALONG_DURATION_SECONDS = 30L
        
        android.util.Log.d("TrainingMapActivity", "Checking Google Read Along completion: elapsed=${timeElapsedSeconds}s, required=${MIN_READ_ALONG_DURATION_SECONDS}s")
        
        // Only check if we've been away for at least a few seconds (to avoid checking immediately after launch)
        if (timeElapsedMs < 2000L) {
            android.util.Log.d("TrainingMapActivity", "Just launched Read Along (< 2s ago), skipping check")
            return
        }
        
        if (timeElapsedSeconds >= MIN_READ_ALONG_DURATION_SECONDS) {
            // Enough time has passed - mark as complete
            android.util.Log.d("TrainingMapActivity", "✅ Google Read Along completed! Time spent: ${timeElapsedSeconds}s")
            
            // Find the task in current content and mark it complete
            if (currentContent != null) {
                var foundTask: Task? = null
                var foundSectionId: String? = null
                
                currentContent!!.sections?.forEach { section ->
                    section.tasks?.forEach { task ->
                        if (task.launch == "googleReadAlong" || task.launch == taskId) {
                            foundTask = task
                            foundSectionId = section.id
                            return@forEach
                        }
                    }
                    if (foundTask != null) {
                        return@forEach
                    }
                }
                
                if (foundTask != null) {
                    val finalTaskId = foundTask!!.launch ?: taskId
                    val finalTaskTitle = foundTask!!.title ?: taskTitle
                    val finalStars = foundTask!!.stars ?: stars
                    val finalSectionId = foundSectionId ?: sectionId ?: mapType
                    
                    // Mark task as completed
                    val isRequiredTask = finalSectionId == "required"
                    val earnedStars = progressManager.markTaskCompletedWithName(
                        finalTaskId,
                        finalTaskTitle,
                        finalStars,
                        isRequiredTask,
                        currentContent,
                        finalSectionId
                    )
                    
                    // Add stars to reward bank
                    if (earnedStars > 0) {
                        progressManager.grantRewardsForTaskCompletion(earnedStars, finalSectionId)
                    }
                    
                    // Sync progress to cloud
                    val currentProfile = SettingsManager.readProfile(this) ?: "AM"
                    val cloudStorageManager = CloudStorageManager(this)
                    lifecycleScope.launch {
                        cloudStorageManager.saveIfEnabled(currentProfile)
                    }
                    
                    // Refresh the map to show updated completion status
                    mapContainer.removeAllViews()
                    loadTasksIntoMap()
                    
                    android.widget.Toast.makeText(
                        this,
                        "Great reading! You spent ${timeElapsedSeconds}s in Read Along.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.util.Log.w("TrainingMapActivity", "Google Read Along task not found in current content")
                }
            } else {
                android.util.Log.w("TrainingMapActivity", "No current content available for Read Along completion")
            }
            
            // Clear the start time
            readAlongPrefs.edit()
                .remove("read_along_start_time")
                .remove("read_along_task_id")
                .remove("read_along_task_title")
                .remove("read_along_stars")
                .remove("read_along_section_id")
                .apply()
        } else {
            // Not enough time - show message but keep the start time (they can try again)
            val remainingSeconds = MIN_READ_ALONG_DURATION_SECONDS - timeElapsedSeconds
            android.widget.Toast.makeText(
                this,
                "Stay in Google Read Along for at least ${MIN_READ_ALONG_DURATION_SECONDS}s to earn rewards (only ${timeElapsedSeconds}s). Need ${remainingSeconds}s more.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            android.util.Log.d("TrainingMapActivity", "⚠️ Google Read Along not completed yet: ${timeElapsedSeconds}s < ${MIN_READ_ALONG_DURATION_SECONDS}s")
        }
    }
    
    /**
     * Checks if Boukili was completed when returning to this activity.
     * Similar logic to MainActivity.checkBoukiliCompletion()
     */
    private fun checkBoukiliCompletion() {
        val boukiliPrefs = getSharedPreferences("boukili_session", android.content.Context.MODE_PRIVATE)
        val startTimeMs = boukiliPrefs.getLong("boukili_start_time", -1L)
        
        if (startTimeMs <= 0L) {
            // No pending Boukili session
            android.util.Log.d("TrainingMapActivity", "No pending Boukili session")
            return
        }
        
        val taskId = boukiliPrefs.getString("boukili_task_id", null) ?: return
        val taskTitle = boukiliPrefs.getString("boukili_task_title", taskId) ?: taskId
        val stars = boukiliPrefs.getInt("boukili_stars", 0)
        val sectionId = boukiliPrefs.getString("boukili_section_id", null)?.takeIf { it.isNotBlank() }
        
        val now = System.currentTimeMillis()
        val timeElapsedMs = now - startTimeMs
        val timeElapsedSeconds = timeElapsedMs / 1000
        val MIN_BOUKILI_DURATION_SECONDS = 30L
        
        android.util.Log.d("TrainingMapActivity", "Checking Boukili completion: elapsed=${timeElapsedSeconds}s, required=${MIN_BOUKILI_DURATION_SECONDS}s")
        
        // Only check if we've been away for at least a few seconds (to avoid checking immediately after launch)
        if (timeElapsedMs < 2000L) {
            android.util.Log.d("TrainingMapActivity", "Just launched Boukili (< 2s ago), skipping check")
            return
        }
        
        if (timeElapsedSeconds >= MIN_BOUKILI_DURATION_SECONDS) {
            // Enough time has passed - mark as complete
            android.util.Log.d("TrainingMapActivity", "✅ Boukili completed! Time spent: ${timeElapsedSeconds}s")
            
            // Find the task in current content and mark it complete
            if (currentContent != null) {
                var foundTask: Task? = null
                var foundSectionId: String? = null
                
                currentContent!!.sections?.forEach { section ->
                    section.tasks?.forEach { task ->
                        if (task.launch == "boukili" || task.launch == taskId) {
                            foundTask = task
                            foundSectionId = section.id
                            return@forEach
                        }
                    }
                    if (foundTask != null) {
                        return@forEach
                    }
                }
                
                if (foundTask != null) {
                    val finalTaskId = foundTask!!.launch ?: taskId
                    val finalTaskTitle = foundTask!!.title ?: taskTitle
                    val finalStars = foundTask!!.stars ?: stars
                    val finalSectionId = foundSectionId ?: sectionId ?: mapType
                    
                    // Mark task as completed
                    val isRequiredTask = finalSectionId == "required"
                    val earnedStars = progressManager.markTaskCompletedWithName(
                        finalTaskId,
                        finalTaskTitle,
                        finalStars,
                        isRequiredTask,
                        currentContent,
                        finalSectionId
                    )
                    
                    if (earnedStars > 0) {
                        progressManager.grantRewardsForTaskCompletion(earnedStars, finalSectionId)
                    }
                    
                    // Sync progress to cloud
                    val currentProfile = SettingsManager.readProfile(this) ?: "AM"
                    val cloudStorageManager = CloudStorageManager(this)
                    lifecycleScope.launch {
                        cloudStorageManager.saveIfEnabled(currentProfile)
                    }
                    
                    // Refresh the map to show updated completion status
                    mapContainer.removeAllViews()
                    loadTasksIntoMap()
                    
                    android.widget.Toast.makeText(
                        this,
                        "Great reading! You spent ${timeElapsedSeconds}s in Boukili.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.util.Log.w("TrainingMapActivity", "Boukili task not found in current content")
                }
            } else {
                android.util.Log.w("TrainingMapActivity", "No current content available for Boukili completion")
            }
            
            // Clear the start time
            boukiliPrefs.edit()
                .remove("boukili_start_time")
                .remove("boukili_task_id")
                .remove("boukili_task_title")
                .remove("boukili_stars")
                .remove("boukili_section_id")
                .apply()
        } else {
            // Not enough time - show message but keep the start time (they can try again)
            val remainingSeconds = MIN_BOUKILI_DURATION_SECONDS - timeElapsedSeconds
            android.widget.Toast.makeText(
                this,
                "Stay in Boukili for at least ${MIN_BOUKILI_DURATION_SECONDS}s to earn rewards (only ${timeElapsedSeconds}s). Need ${remainingSeconds}s more.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            android.util.Log.d("TrainingMapActivity", "⚠️ Boukili not completed yet: ${timeElapsedSeconds}s < ${MIN_BOUKILI_DURATION_SECONDS}s")
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
        builder.appendQueryParameter("mode", mode)
        return builder.build().toString()
    }
    
    private fun parseDisableDate(dateString: String?): Calendar? {
        if (dateString.isNullOrEmpty()) return null
        
        return try {
            // Try parsing format like "Jan 15, 2027" or "Nov 24, 2025"
            val formatter = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
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
            android.util.Log.e("TrainingMapActivity", "Error parsing disable date: $dateString", e)
            null
        }
    }
    
    private fun isTaskVisible(showdays: String?, hidedays: String?, displayDays: String?, disable: String?): Boolean {
        // Use the same visibility logic as DailyProgressManager
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
            } else {
                // If disable is not a date, treat it as a boolean-like string
                if (disable.equals("true", ignoreCase = true)) {
                    return false
                }
            }
        }
        
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val dayNames = arrayOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
        val todayName = dayNames[today - 1]
        
        if (hidedays != null && hidedays.contains(todayName, ignoreCase = true)) {
            return false
        }
        
        if (displayDays != null && !displayDays.contains(todayName, ignoreCase = true)) {
            return false
        }
        
        if (showdays != null && !showdays.contains(todayName, ignoreCase = true)) {
            return false
        }
        
        return true
    }
    
    private fun handleVideoSequenceTask(task: Task, sectionId: String) {
        val videoSequence = task.videoSequence ?: return

        when (videoSequence) {
            "playlist" -> {
                // Play YouTube playlist directly (no JSON file needed)
                val playlistId = task.playlistId ?: task.launch
                android.util.Log.d("TrainingMapActivity", "Playlist task detected, playlistId: $playlistId")
                if (playlistId != null) {
                    val playlistTitle = task.title ?: "Playlist"
                    playYouTubePlaylist(playlistId, playlistTitle)
                } else {
                    android.util.Log.e("TrainingMapActivity", "No playlist ID found for playlist task")
                }
            }
            else -> {
                // Handle video sequences that need JSON files (sequential, exact)
                val videoFile = task.launch ?: return

                lifecycleScope.launch(Dispatchers.IO) {
                    var videoJson: String? = null
                    try {
                        // Load the video JSON file (fetch from GitHub first, then cache, then assets)
                        val contentUpdateService = ContentUpdateService()
                        videoJson = contentUpdateService.fetchVideoContent(this@TrainingMapActivity, videoFile)
                        android.util.Log.d("TrainingMapActivity", "Fetched video content for $videoFile: ${if (videoJson != null) "success" else "null"}")
                    } catch (e: Exception) {
                        android.util.Log.e("TrainingMapActivity", "Error fetching video content for $videoFile", e)
                    }

                    // If fetchVideoContent failed, try loading directly from assets as fallback
                    if (videoJson == null) {
                        try {
                            android.util.Log.d("TrainingMapActivity", "Trying to load video content directly from assets")
                            videoJson = assets.open("videos/$videoFile.json").bufferedReader().use { it.readText() }
                            android.util.Log.d("TrainingMapActivity", "Successfully loaded video content from assets")
                        } catch (e: Exception) {
                            android.util.Log.e("TrainingMapActivity", "Error loading video content from assets for $videoFile", e)
                        }
                    }

                    if (videoJson == null) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(this@TrainingMapActivity, "Error loading video content", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
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
                                    android.widget.Toast.makeText(this@TrainingMapActivity, "Unknown video sequence type", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TrainingMapActivity", "Error playing video", e)
                            android.widget.Toast.makeText(this@TrainingMapActivity, "Error playing video", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
    
    private fun playYouTubeVideo(videoJson: String, videoName: String, task: Task, sectionId: String) {
        try {
            val gson = Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoId = videoMap[videoName]

            android.util.Log.d("TrainingMapActivity", "Looking for video: $videoName in JSON file, found videoId: $videoId")

            if (videoId != null) {
                val intent = Intent(this, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, videoName)
                    putExtra("TASK_ID", task.launch ?: "unknown")
                    putExtra("TASK_TITLE", task.title ?: "Video Task")
                    putExtra("TASK_STARS", task.stars ?: 0)
                    putExtra("SECTION_ID", sectionId)
                }
                android.util.Log.d("TrainingMapActivity", "Starting YouTube player with videoId: $videoId, videoName: $videoName, sectionId: $sectionId")
                startActivityForResult(intent, 1003)
            } else {
                android.util.Log.e("TrainingMapActivity", "Video not found: $videoName in JSON: $videoJson")
                android.widget.Toast.makeText(this, "Video not found: $videoName", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("TrainingMapActivity", "Error parsing video JSON", e)
            android.widget.Toast.makeText(this, "Error playing video", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun playNextVideoInSequence(videoJson: String, videoFile: String, task: Task, sectionId: String) {
        try {
            val gson = Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoList = videoMap.keys.toList()

            if (videoList.isEmpty()) {
                android.util.Log.e("TrainingMapActivity", "No videos found in $videoFile.json")
                android.widget.Toast.makeText(this, "No videos available", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            // Get the last played video index for this kid and video file
            val prefs = getSharedPreferences("video_progress", android.content.Context.MODE_PRIVATE)
            val currentKid = SettingsManager.readProfile(this) ?: "AM"
            var lastVideoIndex = prefs.getInt("${currentKid}_${videoFile}_index", -1)
            
            if (lastVideoIndex >= videoList.size) {
                android.util.Log.d("TrainingMapActivity", "Saved index $lastVideoIndex is out of bounds for list size ${videoList.size}, resetting to 0")
                lastVideoIndex = -1
                prefs.edit().putInt("${currentKid}_${videoFile}_index", -1).apply()
            }
            
            // Determine which video to play - always advance to next video
            val videoIndexToPlay: Int = if (lastVideoIndex == -1) {
                0
            } else {
                (lastVideoIndex + 1) % videoList.size
            }
            
            android.util.Log.d("TrainingMapActivity", "Sequential video selection for ${currentKid}_${videoFile} - Last index: $lastVideoIndex, Playing index: $videoIndexToPlay, List size: ${videoList.size}")
            
            val videoNameToPlay = videoList[videoIndexToPlay]
            playYouTubeVideo(videoJson, videoNameToPlay, task, sectionId)

        } catch (e: Exception) {
            android.util.Log.e("TrainingMapActivity", "Error handling sequential video", e)
            android.widget.Toast.makeText(this, "Error playing video sequence", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun playYouTubePlaylist(playlistId: String, playlistTitle: String) {
        android.util.Log.d("TrainingMapActivity", "Launching playlist player with ID: $playlistId, title: $playlistTitle")
        try {
            val intent = Intent(this, YouTubePlayerActivity::class.java).apply {
                putExtra(YouTubePlayerActivity.EXTRA_PLAYLIST_ID, playlistId)
                putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, playlistTitle)
            }
            android.util.Log.d("TrainingMapActivity", "Starting YouTubePlayerActivity for playlistId: $playlistId")
            startActivityForResult(intent, 1003)
        } catch (e: Exception) {
            android.util.Log.e("TrainingMapActivity", "Error launching playlist player", e)
            android.widget.Toast.makeText(this, "Error playing playlist", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Handles checklist task completion - shows dialog and marks task as complete with rewards
     */
    private fun handleChecklistTaskCompletion(task: Task) {
        val taskName = task.title ?: "Checklist Task"
        val taskId = task.launch ?: ""
        val stars = task.stars ?: 0
        
        // Extract checklist item ID from launch ID (format: "checklist_<itemId>")
        val itemId = if (taskId.startsWith("checklist_")) {
            taskId.substringAfter("checklist_")
        } else {
            taskId
        }
        
        // Load icon config for stars display
        val icons = try {
            val inputStream = assets.open("config/icon_config.json")
            val configJson = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            Gson().fromJson(configJson, IconConfig::class.java) ?: IconConfig()
        } catch (e: Exception) {
            IconConfig()
        }
        
        // Build message with stars if applicable
        val message = if (stars > 0) {
            "Yay! You completed $taskName!\n\nEarned $stars ${icons.starsIcon}"
        } else {
            "Yay! You completed $taskName!"
        }
        
        // Show completion dialog
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("✨ Task Completed! ✨")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                
                // Mark task as completed and grant rewards
                if (stars > 0) {
                    // Award stars when task is completed
                    // CRITICAL: Pass sectionId = "required" so completion is saved to required_tasks.
                    // Checklist items are in config section "checklist", so isTaskFromRequiredSection would be false
                    // and we'd wrongly save to practice_tasks. Collector and map both read checklist from required_tasks.
                    val earnedStars = progressManager.markTaskCompletedWithName(
                        itemId,
                        taskName,
                        stars,
                        true,  // isRequired - checklist items behave like required tasks
                        currentContent,
                        sectionId = "required"
                    )
                    if (earnedStars > 0) {
                        progressManager.grantRewardsForTaskCompletion(earnedStars, "required")
                    }
                } else {
                    // Items with 0 stars should still be marked as completed for tracking
                    progressManager.markTaskCompletedWithName(
                        itemId,
                        taskName,
                        0,
                        true,  // isRequired - checklist items behave like required tasks
                        currentContent,
                        sectionId = "required"
                    )
                }
                
                // Sync checklist completion to cloud first, then redraw (blocking so onResume has nothing to do).
                val profile = SettingsManager.readProfile(this@TrainingMapActivity) ?: "AM"
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        DailyResetAndSyncManager(this@TrainingMapActivity).updateLocalTimestampAndSyncToCloud(profile)
                        android.util.Log.d("TrainingMapActivity", "Synced checklist completion to cloud for profile: $profile")
                    } catch (e: Exception) {
                        android.util.Log.e("TrainingMapActivity", "Error syncing checklist to cloud", e)
                    }
                    withContext(Dispatchers.Main) {
                        loadTasksIntoMap()
                    }
                }
            }
            .setCancelable(true)
            .show()
    }
}

