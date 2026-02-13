package com.talq2me.baerened

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.gridlayout.widget.GridLayout
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.gson.Gson
import com.talq2me.baerened.GameData
import kotlinx.coroutines.*
import java.util.Locale

// GameActivity.kt
class GameActivity : AppCompatActivity() {
    private lateinit var gameEngine: GameEngine
    private val userAnswers = mutableListOf<String>()
    private var ttsReady = false
    private var currentQuestion: GameData? = null
    private var messageClearHandler: android.os.Handler? = null
    private var audioClipsPlayedForCurrentQuestion = false
    private lateinit var progressManager: DailyProgressManager
    private lateinit var timeTracker: TimeTracker
    private lateinit var gameProgress: GameProgress

    // Game configuration
    private lateinit var gameType: String
    private lateinit var gameTitle: String
    private var gameStars: Int = 1
    private var isRequiredGame: Boolean = false
    private var sectionId: String? = null
    private var blockOutlines: Boolean = false
    private var battleHubTaskId: String? = null
    private val selectedChoices = mutableSetOf<String>()
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        swipeRefreshLayout = findViewById(R.id.gameSwipeRefresh)

        // Get game content and config from intent extras
        val gameContent = intent.getStringExtra("GAME_CONTENT")
        gameType = intent.getStringExtra("GAME_TYPE") ?: "unknown"
        gameTitle = intent.getStringExtra("GAME_TITLE") ?: "Game"
        val totalQuestions = intent.getIntExtra("TOTAL_QUESTIONS", 5) // Default to 5 if not provided
        gameStars = intent.getIntExtra("GAME_STARS", 1) // Default to 1 star if not provided
        isRequiredGame = intent.getBooleanExtra("IS_REQUIRED_GAME", false)
        sectionId = intent.getStringExtra("SECTION_ID")
        blockOutlines = intent.getBooleanExtra("BLOCK_OUTLINES", false)
        battleHubTaskId = intent.getStringExtra("BATTLE_HUB_TASK_ID")
        val taskId = battleHubTaskId // Local val for smart cast in when

        // Use canonical game id for game_progress so indices sync to cloud under same key (sightWords, skSpelling, etc.)
        val storageGameType = when {
            taskId != null && taskId.startsWith("battleHub_") -> taskId.substringAfter("battleHub_")
            taskId != null && taskId.startsWith("gymMap_") -> taskId.substringAfter("gymMap_")
            else -> gameType
        }
        android.util.Log.d("GameActivity", "Game initialized: title=$gameTitle, type=$gameType, storageType=$storageGameType, totalQuestions=$totalQuestions, stars=$gameStars")

        if (gameContent == null) {
            Toast.makeText(this, "Game content not available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Load JSON from the provided content
        val questions = try {
            val parsedQuestions = Gson().fromJson(gameContent, Array<GameData>::class.java).toList()
            android.util.Log.d("GameActivity", "Loaded ${parsedQuestions.size} questions from JSON for game: $gameTitle")
            parsedQuestions
        } catch (e: Exception) {
            android.util.Log.e("GameActivity", "Error parsing game content", e)
            Toast.makeText(this, "Error loading game content", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Create a simple config for the game
        val config = GameConfig(
            launch = gameType,
            requiredCorrectAnswers = totalQuestions
        )
        
        android.util.Log.d("GameActivity", "GameConfig created: requiredCorrectAnswers=${config.requiredCorrectAnswers}, questions available=${questions.size}")

        // Use storageGameType so game_indices are saved under cloud key (sightWords, skSpelling, etc.)
        gameEngine = GameEngine(this, storageGameType, questions, config)

        // Initialize progress manager
        progressManager = DailyProgressManager(this)

        // Initialize time tracker
        timeTracker = TimeTracker(this)

        // Initialize game progress tracker (use storageGameType for cloud sync)
        gameProgress = GameProgress(this, storageGameType)
        
        // Use unique task ID that includes section info to track separately for required vs optional
        val uniqueTaskId = if (sectionId != null) {
            progressManager.getUniqueTaskId(gameType, sectionId)
        } else {
            gameType
        }

        // Start time tracking for this game
        timeTracker.startActivity(uniqueTaskId, "game", gameTitle)

        // Use shared TTS (TtsManager); set listener so we can play audio clips after TTS
        val utteranceListener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    if (!audioClipsPlayedForCurrentQuestion) {
                        currentQuestion?.let { question ->
                            playQuestionAudioClips(question)
                            audioClipsPlayedForCurrentQuestion = true
                        }
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    if (!audioClipsPlayedForCurrentQuestion) {
                        currentQuestion?.let { question ->
                            playQuestionAudioClips(question)
                            audioClipsPlayedForCurrentQuestion = true
                        }
                    }
                }
            }
        }
        TtsManager.setOnUtteranceProgressListener(utteranceListener)
        TtsManager.whenReady(Runnable {
            runOnUiThread {
                ttsReady = true
                showNextQuestion()
            }
        })
        // Fallback: if TTS never becomes ready (e.g. init failed), show first question after 8s
        Handler(Looper.getMainLooper()).postDelayed({
            if (!ttsReady) {
                ttsReady = true
                showNextQuestion()
            }
        }, 8000)

        // Set up click listeners after views are initialized
        setupClickListeners()

        setupReplayButton()
        setupPullToRefresh()
    }

    private fun setupClickListeners() {
        findViewById<Button>(R.id.deleteButton).setOnClickListener {
            if (userAnswers.isNotEmpty()) {
                val removedChoice = userAnswers.removeAt(userAnswers.lastIndex)
                selectedChoices.remove(removedChoice)

                // Re-enable the choice button that was just removed
                reEnableChoiceButton(removedChoice)

                updateMessage() // Update message area with remaining text
            }
        }

        findViewById<Button>(R.id.submitButton).setOnClickListener {
            val correct = gameEngine.submitAnswer(userAnswers)

            // Update answer counts in time tracker
            timeTracker.updateAnswerCounts("game", gameEngine.getCorrectCount(), gameEngine.getIncorrectCount())

            // Clear blocks and reset choice buttons immediately when answer is submitted
            findViewById<TextView>(R.id.messageArea).text = ""
            val blocksContainer = findViewById<LinearLayout>(R.id.blocksContainer)
            blocksContainer.removeAllViews()
            reEnableAllChoiceButtons() // Re-enable choice buttons immediately

            if (correct) {
                // Show feedback for correct answer
                showMessageAndClear("✅ Correct!", 2000)
                if (gameEngine.shouldEndGame()) {
                    android.util.Log.d("GameActivity", "CRITICAL: Game should end - shouldEndGame() returned true")
                    try {
                        // Extract original gameId if this came from battle hub or gym map
                        val currentBattleHubTaskId = battleHubTaskId // Store in local val to avoid smart cast issue
                        val actualGameType = when {
                            currentBattleHubTaskId != null && currentBattleHubTaskId.startsWith("battleHub_") -> {
                                currentBattleHubTaskId.substringAfter("battleHub_")
                            }
                            currentBattleHubTaskId != null && currentBattleHubTaskId.startsWith("gymMap_") -> {
                                currentBattleHubTaskId.substringAfter("gymMap_")
                            }
                            else -> {
                                gameType
                            }
                        }
                        
                        android.util.Log.d("GameActivity", "CRITICAL: actualGameType='$actualGameType', gameType='$gameType', gameTitle='$gameTitle', isRequiredGame=$isRequiredGame, sectionId=$sectionId")
                    
                        // Game completed successfully - award stars to reward bank
                        // Use actualGameType (without battleHub_ prefix) for completion tracking
                        // Load config to pass to markTaskCompletedWithName (needed to find task and update properly)
                        val contentUpdateService = ContentUpdateService()
                        val configJson = contentUpdateService.getCachedMainContent(this)
                        val config = if (!configJson.isNullOrEmpty()) {
                            try {
                                Gson().fromJson(configJson, MainContent::class.java)
                            } catch (e: Exception) {
                                android.util.Log.e("GameActivity", "Error parsing config JSON", e)
                                null
                            }
                        } else {
                            android.util.Log.w("GameActivity", "Config JSON is null or empty")
                            null
                        }
                        
                        // CRITICAL: Game completion flow per requirements:
                        // 1. Update all local data FIRST (berries, stars, banked_time, game_indices, task completion with correct/incorrect/questions)
                        // 2. Update last_updated timestamp
                        // 3. THEN sync to cloud (synchronously)
                        // 4. THEN redraw screen
                        
                        val correctCount = gameEngine.getCorrectCount()
                        val incorrectCount = gameEngine.getIncorrectCount()
                        val questionsAnswered = correctCount + incorrectCount
                        val finalGameIndex = gameEngine.getCurrentIndex()
                        
                        android.util.Log.d("GameActivity", "CRITICAL: About to mark task as complete - gameType: '$actualGameType', gameTitle: '$gameTitle', isRequiredGame: $isRequiredGame, sectionId: $sectionId, config loaded: ${config != null}")
                        android.util.Log.d("GameActivity", "CRITICAL: Game stats - correct: $correctCount, incorrect: $incorrectCount, questions: $questionsAnswered, finalIndex: $finalGameIndex")
                        
                        // Step 1: Update all local data (save index first so game_indices persist even if later steps throw)
                        // 1c. Update game_indices - gameProgress uses storageGameType (canonical key: sightWords, skSpelling, etc.)
                        gameProgress.saveIndex(finalGameIndex)
                        android.util.Log.d("GameActivity", "Saved final game index: $finalGameIndex (game_indices, key=storageType)")
                        
                        // 1a. Mark task as complete with correct/incorrect/questions
                        val earnedStars = progressManager.markTaskCompletedWithName(
                            actualGameType, 
                            gameTitle, 
                            gameStars, 
                            isRequiredGame, 
                            config, 
                            sectionId,
                            correctAnswers = correctCount,
                            incorrectAnswers = incorrectCount,
                            questionsAnswered = questionsAnswered
                        )
                        android.util.Log.d("GameActivity", "CRITICAL: Task marked as complete, earnedStars: $earnedStars")
                        
                        // 1b. Grant all rewards in one place (time, berries)
                        if (earnedStars > 0) {
                            // Use "optional" when from Battle Hub so berries are granted; otherwise use actual sectionId
                            val effectiveSectionId = if (currentBattleHubTaskId != null && (sectionId == null || sectionId !in listOf("required", "optional"))) "optional" else sectionId
                            progressManager.grantRewardsForTaskCompletion(earnedStars, effectiveSectionId)
                            android.util.Log.d("GameActivity", "Game $actualGameType completed, granted $earnedStars stars (section=$sectionId, effective=$effectiveSectionId)")

                            // Update time tracker with stars earned
                            timeTracker.updateStarsEarned("game", earnedStars)
                        }
                        
                        // 1d. Update answer counts in TimeTracker and mark session as completed
                        timeTracker.updateAnswerCounts("game", correctCount, incorrectCount)
                        // CRITICAL: End the TimeTracker session to mark it as completed
                        // This is especially important for practice tasks which are tracked via TimeTracker
                        timeTracker.endActivity("game")
                        
                        // Step 2: Update last_updated timestamp (already done in markTaskCompletedWithName -> saveRequiredTasks)
                        
                        // Step 3: Sync to cloud SYNCHRONOUSLY (wait for completion)
                        val currentProfile = SettingsManager.readProfile(this) ?: "AM"
                        android.util.Log.d("GameActivity", "CRITICAL: Starting synchronous cloud sync for profile: $currentProfile")
                        runBlocking(Dispatchers.IO) {
                            try {
                                val resetAndSyncManager = DailyResetAndSyncManager(this@GameActivity)
                                resetAndSyncManager.updateLocalTimestampAndSyncToCloud(currentProfile)
                                android.util.Log.d("GameActivity", "CRITICAL: Cloud sync completed successfully")
                            } catch (e: Exception) {
                                android.util.Log.e("GameActivity", "CRITICAL ERROR: Cloud sync failed", e)
                                e.printStackTrace()
                            }
                        }

                        // Always set result when game completed so caller (e.g. TrainingMap) can refresh
                        val resultIntent = android.content.Intent().apply {
                            putExtra("GAME_TYPE", actualGameType)
                            putExtra("GAME_STARS", earnedStars)
                            putExtra("REWARDS_APPLIED", true)  // We already granted time+berries; caller should not grant again
                            currentBattleHubTaskId?.let { putExtra("BATTLE_HUB_TASK_ID", it) }
                            sectionId?.let { putExtra("SECTION_ID", it) }
                        }
                        setResult(RESULT_OK, resultIntent)

                        // Navigate back after delay
                        android.os.Handler().postDelayed({
                            finish()
                        }, 2500) // 2.5 seconds total (2s message + 0.5s buffer)
                    } catch (e: Exception) {
                        android.util.Log.e("GameActivity", "CRITICAL ERROR: Exception during game completion", e)
                        e.printStackTrace()
                        // Still finish the activity even if completion tracking failed
                        android.os.Handler().postDelayed({
                            finish()
                        }, 2500)
                    }
                } else {
                    android.os.Handler().postDelayed({
                        userAnswers.clear()
                        showNextQuestion()
                    }, 2500) // 2.5 seconds total
                }
            } else {
                // Show "Try again" message - choice buttons already re-enabled above
                showMessageAndClear("❌ Try again!", 2000)

                // Schedule clearing user answers and resetting UI after message clears (with small buffer)
                android.os.Handler().postDelayed({
                    userAnswers.clear()
                    selectedChoices.clear()
                    // Choice buttons are already re-enabled above

                    // Reset audio clips played flag for retry
                    audioClipsPlayedForCurrentQuestion = false

                    // Replay the question content (TTS and audio) for retry
                    currentQuestion?.let { question ->
                        speakSequentially(question)
                        // Audio clips will be played after TTS completes (handled by UtteranceProgressListener)
                    }
                }, 2100) // Clear after 2.1 seconds (after message clears + buffer)
            }
        }
    }

    private fun setupReplayButton() {
        findViewById<Button>(R.id.replayButton).setOnClickListener {
            // Replay the TTS content for current question and audio clips
            currentQuestion?.let { question ->
                audioClipsPlayedForCurrentQuestion = false // Reset flag for replay
                speakSequentially(question)
                // Audio clips will be played after TTS completes (handled by UtteranceProgressListener)
            }
        }
    }

    private fun setupPullToRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            resetForRefresh()
        }
    }

    private fun resetForRefresh() {
        userAnswers.clear()
        selectedChoices.clear()
        reEnableAllChoiceButtons()
        findViewById<TextView>(R.id.messageArea).text = ""
        val blocksContainer = findViewById<LinearLayout>(R.id.blocksContainer)
        blocksContainer.removeAllViews()
        showNextQuestion()
        swipeRefreshLayout.isRefreshing = false
    }

    private fun speakSequentially(question: GameData) {
        if (!ttsReady) return

        // Only use TTS if lang is explicitly present in prompt or question
        // If lang is not present, skip TTS (text is display-only)
        val promptLang = question.prompt?.lang
        val questionLang = question.question?.lang
        val hasLang = !promptLang.isNullOrEmpty() || !questionLang.isNullOrEmpty()
        
        if (!hasLang) {
            // No lang field present, skip TTS but still play audio clips if available
            playQuestionAudioClips(question)
            return
        }

        val questionId = System.currentTimeMillis().toString()

        // Set language for the whole sequence based on prompt or question lang
        val locale = when (promptLang?.lowercase() ?: questionLang?.lowercase()) {
            "eng", "en" -> Locale.US
            "fr", "fra" -> Locale.FRENCH
            "es", "spa" -> Locale("es", "ES")
            "de", "ger" -> Locale.GERMAN
            else -> Locale.US // Default to English
        }

        // Speak prompt first, then question (using shared TtsManager)
        question.prompt?.text?.let { promptText ->
            if (promptText.isNotEmpty() && !promptLang.isNullOrEmpty()) {
                TtsManager.speak(promptText, locale, TextToSpeech.QUEUE_FLUSH, "${questionId}-prompt")
            }
        }
        question.question?.text?.let { questionText ->
            if (questionText.isNotEmpty() && !questionLang.isNullOrEmpty()) {
                TtsManager.speak(questionText, locale, TextToSpeech.QUEUE_ADD, "${questionId}-question")
            }
        }
    }

    private fun playQuestionAudioClips(question: GameData) {
        // Check for audio clips in question (prompt doesn't have media property)
        val audioClips = question.question?.media?.audioclips

        audioClips?.forEachIndexed { index, audioClip ->
            try {
                android.os.Handler().postDelayed({
                    val afd = assets.openFd(audioClip)
                    val mp = MediaPlayer()
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    mp.prepare()
                    mp.start()
                    mp.setOnCompletionListener {
                        mp.release()
                    }
                }, index * 300L) // 300ms delay between each audio clip
            } catch (e: Exception) {
                android.util.Log.w("GameActivity", "Failed to play question audio clip: $audioClip", e)
            }
        }
    }

    private fun showMessageAndClear(message: String, delayMs: Long) {
        val messageArea = findViewById<TextView>(R.id.messageArea)
        val blocksContainer = findViewById<LinearLayout>(R.id.blocksContainer)

        messageArea.text = message
        messageArea.visibility = View.VISIBLE
        blocksContainer.visibility = View.GONE

        // Cancel any existing clear handler
        messageClearHandler?.removeCallbacksAndMessages(null)

        // Schedule message clearing
        messageClearHandler = android.os.Handler()
        messageClearHandler?.postDelayed({
            messageArea.text = ""
            // Restore blocks container if we're in blockOutlines mode
            if (blockOutlines && currentQuestion != null) {
                messageArea.visibility = View.GONE
                blocksContainer.visibility = View.VISIBLE
                updateMessage()
            }
        }, delayMs)
    }

    private fun showNextQuestion() {
        if (::swipeRefreshLayout.isInitialized && swipeRefreshLayout.isRefreshing) {
            swipeRefreshLayout.isRefreshing = false
        }
        currentQuestion = gameEngine.getCurrentQuestion()
        val q = currentQuestion!!
        userAnswers.clear()
        selectedChoices.clear() // Clear selected choices for new question
        reEnableAllChoiceButtons() // Re-enable all choice buttons for new question
        audioClipsPlayedForCurrentQuestion = false // Reset flag for new question

        // Cancel any existing message clear handlers and clear current message
        messageClearHandler?.removeCallbacksAndMessages(null)
        findViewById<TextView>(R.id.messageArea).text = ""

        // Clear blocks container for new question
        val blocksContainer = findViewById<LinearLayout>(R.id.blocksContainer)
        blocksContainer.removeAllViews()

        // Initialize empty dashed blocks for blockOutlines games
        updateMessage()

        val promptTextView = findViewById<TextView>(R.id.promptText)
        val replayButton = findViewById<Button>(R.id.replayButton)

        // Always speak if lang is present, but only display if displayText is true
        if (q.prompt?.lang != null) {
            speakSequentially(q)
            replayButton.visibility = View.VISIBLE
        } else {
            replayButton.visibility = View.GONE
        }

        if (q.prompt?.displayText == true) {
            promptTextView.text = q.prompt.text
        } else if (q.prompt?.lang == null) {
            // Fallback to display text if no TTS is available and displayText is not explicitly set to true
            promptTextView.text = q.prompt?.text ?: ""
        } else {
            promptTextView.text = ""
        }

        // Question images/text
        val container = findViewById<LinearLayout>(R.id.questionContainer)
        container.removeAllViews()

        // Display question text if displayText is true
        if (q.question?.displayText == true) {
            q.question.text?.let {
                val tv = TextView(this)
                tv.text = it
                tv.textSize = 24f
                tv.setTextColor(resources.getColor(android.R.color.darker_gray))
                container.addView(tv)
            }
        } else if (q.question?.lang == null && q.question?.media?.audioclips == null) {
            // Fallback to display text if no audio/TTS is available and displayText is not explicitly set to true
            q.question?.text?.let {
                val tv = TextView(this)
                tv.text = it
                tv.textSize = 24f
                container.addView(tv)
            }
        }

        // Create horizontal layout for images to display them side by side
        if (q.question?.media?.images?.isNotEmpty() == true) {
            val imageContainer = LinearLayout(this)
            imageContainer.orientation = LinearLayout.HORIZONTAL
            imageContainer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            q.question?.media?.images?.forEach {
                val img = ImageView(this)
                img.layoutParams = LinearLayout.LayoutParams(200, 200).apply {
                    setMargins(8, 0, 8, 0) // Add some spacing between images
                }

                // Load bitmap asynchronously to avoid blocking UI thread
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val afd = assets.openFd(it)
                        val bmp = BitmapFactory.decodeStream(afd.createInputStream())
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            img.setImageBitmap(bmp)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("GameActivity", "Failed to load image: $it", e)
                    }
                }

                imageContainer.addView(img)
            }

            container.addView(imageContainer)
        }

        // Choices - use dynamic layout that fits as many buttons as possible per row
        val grid = findViewById<androidx.gridlayout.widget.GridLayout>(R.id.choicesGrid)
        grid.removeAllViews()

        val correctChoices = q.correctChoices ?: emptyList()
        val extraChoices = q.extraChoices ?: emptyList()
        val allChoices = (correctChoices + extraChoices).shuffled()

        // Calculate dynamic column count based on screen width and button size
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        
        // Calculate available width (account for root padding and grid padding)
        val rootPadding = (24 * density).toInt() * 2 // 24dp on each side
        val gridPadding = grid.paddingLeft + grid.paddingRight // Actual grid padding
        val availableWidth = screenWidth - rootPadding - gridPadding
        
        // Measure text widths accurately using Paint to determine optimal column count
        val paint = android.graphics.Paint()
        paint.textSize = 24f * density // 24sp text size in pixels
        paint.typeface = android.graphics.Typeface.DEFAULT
        
        // Calculate button dimensions constants
        val buttonPadding = (10 * density).toInt() * 2 // 10dp padding on each side (left + right)
        val buttonMargin = (6 * density).toInt() * 2 // 6dp margin on each side (left + right)
        val minButtonWidth = (80 * density).toInt() // Minimum button width in pixels
        
        // Measure all text widths to find optimal button sizing
        val textWidths = allChoices.map { choice ->
            paint.measureText(choice.text).toInt()
        }
        val maxTextWidth = if (textWidths.isNotEmpty()) textWidths.maxOrNull() ?: 0 else 0
        val avgTextWidth = if (textWidths.isNotEmpty()) {
            textWidths.average().toInt()
        } else {
            0
        }
        
        // For games with short words (like sentence builder), use average width to fit more buttons
        // For games with long text, use max width to ensure all buttons fit
        // Use average if it's significantly shorter than max (more than 30% difference)
        val useAverageWidth = maxTextWidth > 0 && avgTextWidth > 0 && 
                              avgTextWidth < maxTextWidth * 0.7
        
        // Calculate base button width based on text length characteristics
        val baseTextWidth = if (useAverageWidth && avgTextWidth > 0) {
            // Use average for short words to allow more columns
            avgTextWidth
        } else {
            // Use max for long text to ensure all buttons fit
            maxTextWidth
        }
        
        // Calculate required button width: max of min width or text width + padding
        // For short words, allow smaller minimum width to fit more buttons
        val effectiveMinWidth = if (useAverageWidth) {
            (60 * density).toInt() // Smaller min for short words
        } else {
            minButtonWidth // Normal min for longer text
        }
        val requiredButtonWidth = maxOf(effectiveMinWidth, baseTextWidth + buttonPadding)
        
        // Calculate how many buttons can fit per row
        // Formula: availableWidth = columnCount * buttonWidth + (columnCount - 1) * buttonMargin
        // Solving for columnCount: columnCount = (availableWidth + buttonMargin) / (buttonWidth + buttonMargin)
        val buttonWidthWithMargin = requiredButtonWidth + buttonMargin
        val calculatedColumns = if (buttonWidthWithMargin > 0) {
            maxOf(1, (availableWidth + buttonMargin) / buttonWidthWithMargin)
        } else {
            3 // Fallback
        }
        
        // Limit to reasonable range: between 2 and 12 columns (allowing more for short words)
        var columnCount = maxOf(2, minOf(calculatedColumns, 12))
        
        // Recalculate actual button width to fill available space evenly
        var totalMarginWidth = (columnCount - 1) * buttonMargin
        var evenButtonWidth = (availableWidth - totalMarginWidth) / columnCount
        
        // Final check: ensure button width accommodates the longest text
        // If even distribution makes buttons too narrow for longest text, reduce columns
        val maxRequiredWidth = maxTextWidth + buttonPadding
        if (evenButtonWidth < maxRequiredWidth && columnCount > 2) {
            // Recalculate with fewer columns to ensure longest text fits
            columnCount = maxOf(2, (availableWidth + buttonMargin) / (maxRequiredWidth + buttonMargin))
            totalMarginWidth = (columnCount - 1) * buttonMargin
            evenButtonWidth = (availableWidth - totalMarginWidth) / columnCount
        }
        
        // Final button width: ensure it's at least minimum, but use calculated width otherwise
        val finalButtonWidth = maxOf(effectiveMinWidth, evenButtonWidth)
        
        grid.columnCount = columnCount
        android.util.Log.d("GameActivity", "Dynamic grid: screenWidth=$screenWidth, availableWidth=$availableWidth, maxTextWidth=$maxTextWidth, requiredButtonWidth=$requiredButtonWidth, calculatedColumns=$calculatedColumns, columnCount=$columnCount, finalButtonWidth=$finalButtonWidth")

        for (choice in allChoices) {
            val btn = Button(this)
            btn.text = choice.text
            btn.textSize = 24f // Larger text for kids
            // Ensure text displays exactly as in JSON (no auto-capitalization)
            btn.transformationMethod = null

            // Set button layout parameters - use calculated even button width
            val params = androidx.gridlayout.widget.GridLayout.LayoutParams()
            
            // Use the pre-calculated final button width to fill available space
            params.width = finalButtonWidth
            params.height = androidx.gridlayout.widget.GridLayout.LayoutParams.WRAP_CONTENT
            
            // Enable text wrapping for long text (will wrap if text is longer than button width)
            btn.maxLines = 0 // Unlimited lines
            btn.isSingleLine = false
            btn.gravity = android.view.Gravity.CENTER
            
            // Set margins (smaller horizontal margin, normal vertical margin)
            params.setMargins((3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt())
            btn.layoutParams = params

            // Make button more prominent with rounded corners and equal padding
            btn.background = resources.getDrawable(R.drawable.button_rounded_choice)
            btn.setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            btn.minHeight = (60 * density).toInt() // Minimum height for finger tapping

            btn.setOnClickListener {
                if (!selectedChoices.contains(choice.text)) {
                    userAnswers.add(choice.text)
                    selectedChoices.add(choice.text)
                    // Mark button as used by greying it out
                    btn.alpha = 0.5f
                    updateMessage()
                    choice.media?.audioclip?.let { clip ->
                        val afd = assets.openFd(clip)
                        val mp = MediaPlayer()
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        mp.prepare()
                        mp.start()
                    }
                }
            }
            grid.addView(btn)
        }
    }

    private fun updateMessage() {
        val messageArea = findViewById<TextView>(R.id.messageArea)
        val blocksContainer = findViewById<LinearLayout>(R.id.blocksContainer)

        if (blockOutlines && currentQuestion != null) {
            // Show selected text in dashed outline blocks
            val correctChoicesCount = currentQuestion?.correctChoices?.size ?: 0

            // Hide the text message area
            messageArea.visibility = View.GONE

            // Clear existing blocks and create new ones
            blocksContainer.removeAllViews()

            // Create dashed outline blocks - one for each correct answer needed
            for (i in 0 until correctChoicesCount) {
                val blockTextView = TextView(this)

                if (i < userAnswers.size) {
                    // Show selected text in dashed block
                    val text = userAnswers[i]
                    blockTextView.text = text
                } else {
                    // Show empty dashed block with placeholder
                    blockTextView.text = ""
                }

                // Set up the dashed block appearance
                blockTextView.background = resources.getDrawable(R.drawable.button_rounded_choice_used)
                blockTextView.setTextColor(resources.getColor(android.R.color.black))
                blockTextView.textSize = 27f // Increased from 18f (1.5x bigger)
                blockTextView.gravity = Gravity.CENTER
                blockTextView.setPadding(24, 18, 24, 18) // Increased from 16,12,16,12 (1.5x bigger)

                // Set minimum width to ensure consistent block sizes
                blockTextView.minWidth = 180 // Increased from 120 (1.5x bigger)

                // Add to container
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(8, 0, 8, 0)
                blockTextView.layoutParams = params

                blocksContainer.addView(blockTextView)
            }

            // Show blocks container only after all blocks are created and added
            blocksContainer.visibility = View.VISIBLE
        } else {
            // Default behavior - just show the selected text
            val message = userAnswers.joinToString("") // No spaces between answers
            messageArea.text = message
            messageArea.visibility = View.VISIBLE
            blocksContainer.visibility = View.GONE
        }
    }

    private fun reEnableChoiceButton(choiceText: String) {
        val choicesGrid = findViewById<androidx.gridlayout.widget.GridLayout>(R.id.choicesGrid)
        for (i in 0 until choicesGrid.childCount) {
            val child = choicesGrid.getChildAt(i)
            if (child is Button && child.text == choiceText) {
                child.isEnabled = true
                child.alpha = 1.0f // Restore normal appearance
                break
            }
        }
    }

    private fun reEnableAllChoiceButtons() {
        val choicesGrid = findViewById<androidx.gridlayout.widget.GridLayout>(R.id.choicesGrid)
        for (i in 0 until choicesGrid.childCount) {
            val child = choicesGrid.getChildAt(i)
            if (child is Button) {
                child.isEnabled = true
                child.alpha = 1.0f // Restore normal appearance
            }
        }
    }

    override fun onDestroy() {
        TtsManager.stop()
        TtsManager.setOnUtteranceProgressListener(null)
        super.onDestroy()

        // End time tracking when the activity is destroyed
        timeTracker.endActivity("game")
    }
}
