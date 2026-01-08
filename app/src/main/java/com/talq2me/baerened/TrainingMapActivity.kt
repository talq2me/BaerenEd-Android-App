package com.talq2me.baerened

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
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
    
    data class IconConfig(
        val starsIcon: String = "🍍",
        val coinsIcon: String = "🪙"
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get map type from intent
        mapType = intent.getStringExtra("mapType") ?: "required"
        
        progressManager = DailyProgressManager(this)
        
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
                        android.util.Log.d("TrainingMapActivity", "Successfully fetched config from GitHub, updating user_data table in cloud")
                        
                        // Update user_data table in cloud with latest config data
                        // This will collect tasks from the latest config (which is now in cache) and upload to cloud
                        try {
                            val uploadResult = cloudStorageManager.uploadToCloud(profile)
                            if (uploadResult.isSuccess) {
                                android.util.Log.d("TrainingMapActivity", "Successfully synced latest config to cloud user_data table")
                            } else {
                                android.util.Log.w("TrainingMapActivity", "Failed to sync config to cloud: ${uploadResult.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TrainingMapActivity", "Error syncing config to cloud", e)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TrainingMapActivity", "Could not fetch from GitHub: ${e.message}, falling back to cache")
                }
                
                // Step 2: If GitHub failed, use cache/local storage
                if (jsonString == null || contentFromGitHub == null) {
                    android.util.Log.d("TrainingMapActivity", "Using cached/local config and attempting to push to cloud")
                    jsonString = contentUpdateService.getCachedMainContent(this@TrainingMapActivity)
                    
                    // Try to push cached data to cloud
                    if (jsonString != null && jsonString.isNotEmpty()) {
                        try {
                            val uploadResult = cloudStorageManager.uploadToCloud(profile)
                            if (uploadResult.isSuccess) {
                                android.util.Log.d("TrainingMapActivity", "Successfully pushed cached config to cloud user_data table")
                            } else {
                                android.util.Log.w("TrainingMapActivity", "Failed to push cached config to cloud: ${uploadResult.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TrainingMapActivity", "Error pushing cached config to cloud", e)
                        }
                    }
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
                
                val completedTasksMap = progressManager.getCompletedTasksMap().toMutableMap()
                
                // Get tasks for this map type
                val section = currentContent!!.sections?.find { it.id == mapType }
                val tasks = section?.tasks?.filter { task ->
                    task.title != null && 
                    task.launch != null && 
                    isTaskVisible(task.showdays, task.hidedays, task.displayDays, task.disable)
                } ?: emptyList()
                
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
                        
                        val taskId = "${mapType}_$baseTaskId"
                        completedTasksMap[taskId] == true
                    }
                    
                    // If all optional tasks are completed, reset them all (never ending)
                    if (allCompleted && tasks.isNotEmpty()) {
                        val allCompletedTasks = progressManager.getCompletedTasksMap().toMutableMap()
                        tasks.forEach { task ->
                            val baseTaskId = task.launch ?: ""
                            
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
                                val taskId = "${mapType}_$baseTaskId"
                                // Clear completion status by removing from map
                                allCompletedTasks.remove(taskId)
                            }
                        }
                        // Save the updated map
                        val prefs = getSharedPreferences("daily_progress", android.content.Context.MODE_PRIVATE)
                        val gson = Gson()
                        prefs.edit()
                            .putString("completed_tasks", gson.toJson(allCompletedTasks))
                            .apply()
                        // Refresh the completed tasks map after resetting
                        completedTasksMap.clear()
                        completedTasksMap.putAll(progressManager.getCompletedTasksMap())
                    }
                }
                
                // Calculate progress
                val completedCount = tasks.count { task ->
                    val baseTaskId = task.launch ?: ""
                    
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
                    
                    // For non-diagramLabeler tasks, use standard tracking
                    val taskId = if (mapType == "required") {
                        baseTaskId
                    } else {
                        "${mapType}_$baseTaskId"
                    }
                    
                    completedTasksMap[taskId] == true
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
                        val random = kotlin.random.Random(seedInput.hashCode())

                        // Generate random positions for gyms (like points of interest on a real map)
                        // But ensure they don't overlap
                        val positions = mutableListOf<Pair<Int, Int>>()
                        val actualButtonSize = buttonSize
                        val actualButtonRadius = buttonRadius

                        // Minimum distance between button centers (to prevent overlaps)
                        val minDistance = (buttonSize * 1.5).toInt() // 1.5x button size ensures no overlap

                        // Create gym buttons with random positions
                        val gymButtons = mutableListOf<Pair<View, Pair<Int, Int>>>()

                        tasks.forEachIndexed { index, task ->
                        val baseTaskId = task.launch ?: ""
                        
                        // For diagramLabeler, we need to get the final URL (after getGameModeUrl processing)
                        // to extract the correct diagram parameter for tracking
                        var isCompleted = false
                        if (mapType == "bonus") {
                            // Bonus tasks are never completed
                            isCompleted = false
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
                                    // No diagram parameter value, use standard tracking
                                    val taskId = if (mapType == "required") {
                                        baseTaskId
                                    } else {
                                        "${mapType}_$baseTaskId"
                                    }
                                    isCompleted = completedTasksMap[taskId] == true
                                }
                            } else {
                                // No diagram parameter found, use standard tracking
                                val taskId = if (mapType == "required") {
                                    baseTaskId
                                } else {
                                    "${mapType}_$baseTaskId"
                                }
                                isCompleted = completedTasksMap[taskId] == true
                            }
                        } else {
                            // For non-diagramLabeler tasks, use standard tracking
                            val taskId = if (mapType == "required") {
                                baseTaskId
                            } else {
                                "${mapType}_$baseTaskId"
                            }
                            isCompleted = completedTasksMap[taskId] == true
                        }
                        
                        // Generate random position that doesn't overlap with existing buttons
                        var x: Int = padding
                        var y: Int = padding
                        var attempts = 0
                        val maxAttempts = 200 // Increased attempts for better distribution
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
                            positions.add(Pair(x + actualButtonRadius, y + actualButtonRadius))
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
                                    // Launch task using Layout's launch logic
                                    launchTask(task, mapType)
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
                    val pathView = object : View(this@TrainingMapActivity) {
                        override fun onDraw(canvas: android.graphics.Canvas) {
                            super.onDraw(canvas)
                            
                            if (positions.size < 2) return
                            
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#ffffff")
                                strokeWidth = (4 * density)
                                style = android.graphics.Paint.Style.STROKE
                                pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f * density, 10f * density), 0f)
                                alpha = 180 // Semi-transparent
                            }
                            
                            // Draw lines connecting each gym to the next one (creating a pathway)
                            for (i in 0 until positions.size - 1) {
                                val start = positions[i]
                                val end = positions[i + 1]
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
            
            // Convert GitHub Pages URL to local asset URL for Android (only if file exists in assets)
            if (finalGameUrl.contains("talq2me.github.io") && finalGameUrl.contains("/html/")) {
                val uri = android.net.Uri.parse(finalGameUrl)
                val fileName = finalGameUrl.substringAfterLast("/").substringBefore("?")
                
                // Check if file exists in assets first
                try {
                    val assetManager = assets
                    val assetPath = "html/$fileName"
                    val inputStream = assetManager.open(assetPath)
                    inputStream.close()
                    
                    // File exists in assets, convert to local asset URL
                    // Preserve all query parameters including diagram and mode
                    val queryParams = uri.query
                    finalGameUrl = if (queryParams != null) {
                        "file:///android_asset/html/$fileName?$queryParams"
                    } else {
                        "file:///android_asset/html/$fileName"
                    }
                } catch (e: java.io.IOException) {
                    // File doesn't exist in assets, keep GitHub Pages URL
                }
            }
            
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
        
        // Check for French Book Reader
        if (task.launch == "frenchBookReader") {
            val intent = Intent(this, FrenchBookReaderActivity::class.java)
            startActivity(intent)
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
        // Handle task completion and refresh the map
        if (resultCode == RESULT_OK && data != null) {
            val taskId = data.getStringExtra(WebGameActivity.EXTRA_TASK_ID)
            val sectionId = data.getStringExtra(WebGameActivity.EXTRA_SECTION_ID)
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
                    
                    // Add stars to reward bank (converts to minutes using convertStarsToMinutes)
                    if (earnedStars > 0) {
                        progressManager.addStarsToRewardBank(earnedStars)
                        android.util.Log.d("TrainingMapActivity", "Added $earnedStars stars to reward bank = ${progressManager.convertStarsToMinutes(earnedStars)} minutes")
                        
                        // Add berries to battle hub if task is from required or optional section
                        if (sectionId == "required" || sectionId == "optional") {
                            progressManager.addEarnedBerries(earnedStars)
                            android.util.Log.d("TrainingMapActivity", "Added $earnedStars berries to battle hub from task completion")
                        }
                    }
                    
                    android.util.Log.d("TrainingMapActivity", "Marked task as completed: taskId=$taskId, sectionId=$sectionId, stars=$stars, earnedStars=$earnedStars")
                }
            
            // Also handle ChromePageActivity result
            if (requestCode == 1001) {
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
                    
                    // Add stars to reward bank (converts to minutes using convertStarsToMinutes)
                    if (earnedStars > 0) {
                        progressManager.addStarsToRewardBank(earnedStars)
                        
                        // Add berries to battle hub if task is from required or optional section
                        if (chromeSectionId == "required" || chromeSectionId == "optional") {
                            progressManager.addEarnedBerries(earnedStars)
                            android.util.Log.d("TrainingMapActivity", "Added $earnedStars berries to battle hub from Chrome page completion")
                        }
                    }
                }
            }
        }
        
        // Handle GameActivity result
        if (requestCode == 1004 && resultCode == RESULT_OK && data != null) {
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
                
                // Add stars to reward bank (converts to minutes using convertStarsToMinutes)
                if (earnedStars > 0) {
                    progressManager.addStarsToRewardBank(earnedStars)
                    android.util.Log.d("TrainingMapActivity", "Added $earnedStars stars to reward bank = ${progressManager.convertStarsToMinutes(earnedStars)} minutes")
                    
                    // Add berries to battle hub if task is from required or optional section
                    if (gameSectionId == "required" || gameSectionId == "optional") {
                        progressManager.addEarnedBerries(earnedStars)
                        android.util.Log.d("TrainingMapActivity", "Added $earnedStars berries to battle hub from game completion")
                    }
                }
                
                android.util.Log.d("TrainingMapActivity", "Marked game task as completed: taskId=$gameType, sectionId=$gameSectionId, stars=$gameStars, earnedStars=$earnedStars")
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
                
                // Add stars to reward bank (converts to minutes using convertStarsToMinutes)
                if (earnedStars > 0) {
                    progressManager.addStarsToRewardBank(earnedStars)
                    android.util.Log.d("TrainingMapActivity", "Added $earnedStars stars to reward bank = ${progressManager.convertStarsToMinutes(earnedStars)} minutes")
                    
                    // Add berries to battle hub if task is from required or optional section
                    if (videoSectionId == "required" || videoSectionId == "optional") {
                        progressManager.addEarnedBerries(earnedStars)
                        android.util.Log.d("TrainingMapActivity", "Added $earnedStars berries to battle hub from video completion")
                    }
                }
                
                android.util.Log.d("TrainingMapActivity", "Marked video task as completed: taskId=$videoTaskId, sectionId=$videoSectionId, stars=$videoStars, earnedStars=$earnedStars")
            }
        }
        
        // Refresh the map when returning from any task to show updated completion status
        if (requestCode == 1001 || requestCode == 1002 || requestCode == 1003 || requestCode == 1004) {
            // Sync progress to cloud after task completion
            val currentProfile = SettingsManager.readProfile(this) ?: "AM"
            val cloudStorageManager = CloudStorageManager(this)
            lifecycleScope.launch {
                cloudStorageManager.saveIfEnabled(currentProfile)
            }

            // Reload tasks into map to show updated completion status
            mapContainer.removeAllViews()
            loadTasksIntoMap()
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
}

