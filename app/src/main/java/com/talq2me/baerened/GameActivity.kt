package com.talq2me.baerened

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.gridlayout.widget.GridLayout
import com.google.gson.Gson
import com.talq2me.baerened.GameData
import com.talq2me.baerened.Media
import kotlinx.coroutines.*
import java.util.Locale

// GameActivity.kt
class GameActivity : AppCompatActivity() {
    private lateinit var gameEngine: GameEngine
    private val userAnswers = mutableListOf<String>()
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var currentQuestion: GameData? = null
    private var messageClearHandler: android.os.Handler? = null
    private var audioClipsPlayedForCurrentQuestion = false
    private lateinit var progressManager: DailyProgressManager
    private lateinit var timeTracker: TimeTracker

    // Game configuration
    private lateinit var gameType: String
    private lateinit var gameTitle: String
    private var gameStars: Int = 1
    private var isRequiredGame: Boolean = false
    private var blockOutlines: Boolean = false
    private val selectedChoices = mutableSetOf<String>()

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        // Get game content and config from intent extras
        val gameContent = intent.getStringExtra("GAME_CONTENT")
        gameType = intent.getStringExtra("GAME_TYPE") ?: "unknown"
        gameTitle = intent.getStringExtra("GAME_TITLE") ?: "Game"
        val totalQuestions = intent.getIntExtra("TOTAL_QUESTIONS", 5) // Default to 5 if not provided
        gameStars = intent.getIntExtra("GAME_STARS", 1) // Default to 1 star if not provided
        val isRequiredGame = intent.getBooleanExtra("IS_REQUIRED_GAME", false)
        blockOutlines = intent.getBooleanExtra("BLOCK_OUTLINES", false)

        if (gameContent == null) {
            Toast.makeText(this, "Game content not available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Load JSON from the provided content
        val questions = try {
            Gson().fromJson(gameContent, Array<GameData>::class.java).toList()
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

        gameEngine = GameEngine(this, gameType, questions, config)

        // Initialize progress manager
        progressManager = DailyProgressManager(this)

        // Initialize time tracker
        timeTracker = TimeTracker(this)

        // Start time tracking for this game
        timeTracker.startActivity(gameType, "game", gameTitle)

        // TTS Init
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                ttsReady = true
                // Set up utterance progress listener to handle TTS completion
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // TTS started
                    }

                    override fun onDone(utteranceId: String?) {
                        // This is called when the entire queue is finished.
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
                        // TTS error occurred, still try to play audio clips
                        runOnUiThread {
                            if (!audioClipsPlayedForCurrentQuestion) {
                                currentQuestion?.let { question ->
                                    playQuestionAudioClips(question)
                                    audioClipsPlayedForCurrentQuestion = true
                                }
                            }
                        }
                    }
                })
                // Show the first question after TTS is ready
                showNextQuestion()
            } else {
                // TTS failed to initialize, show question without TTS
                ttsReady = false
                showNextQuestion()
            }
        }

        // Set up click listeners after views are initialized
        setupClickListeners()

        setupHeaderButtons()
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
                    // Game completed successfully - award stars to reward bank
                    val earnedStars = progressManager.markTaskCompletedWithName(gameType, gameTitle, gameStars, isRequiredGame)
                    if (earnedStars > 0) {
                        // Add stars to reward bank and convert to minutes
                        val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)
                        android.util.Log.d("GameActivity", "Game $gameType completed, earned $earnedStars stars = ${progressManager.convertStarsToMinutes(earnedStars)} minutes, total bank: $totalRewardMinutes minutes")

                        // Update time tracker with stars earned
                        timeTracker.updateStarsEarned("game", earnedStars)
                    }

                    // Final update of answer counts before finishing
                    timeTracker.updateAnswerCounts("game", gameEngine.getCorrectCount(), gameEngine.getIncorrectCount())

                    // Navigate back to profile screen after delay
                    android.os.Handler().postDelayed({
                        finish()
                    }, 2500) // 2.5 seconds total (2s message + 0.5s buffer)
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

    private fun setupHeaderButtons() {
        findViewById<Button>(R.id.gameBackButton).setOnClickListener {
            // Go back to main screen
            finish()
        }

        findViewById<Button>(R.id.gameHomeButton).setOnClickListener {
            // Go back to main screen (same as back)
            finish()
        }

        findViewById<Button>(R.id.gameRefreshButton).setOnClickListener {
            // Reset the game state but keep progress
            userAnswers.clear()
            selectedChoices.clear()
            reEnableAllChoiceButtons()
            findViewById<TextView>(R.id.messageArea).text = ""
            val blocksContainer = findViewById<LinearLayout>(R.id.blocksContainer)
            blocksContainer.removeAllViews()
            showNextQuestion()
        }

        findViewById<Button>(R.id.replayButton).setOnClickListener {
            // Replay the TTS content for current question and audio clips
            currentQuestion?.let { question ->
                audioClipsPlayedForCurrentQuestion = false // Reset flag for replay
                speakSequentially(question)
                // Audio clips will be played after TTS completes (handled by UtteranceProgressListener)
            }
        }
    }

    private fun speakSequentially(question: GameData) {
        if (!ttsReady) return

        val questionId = System.currentTimeMillis().toString()

        // Set language for the whole sequence based on prompt, assuming question is the same.
        val locale = when (question.prompt?.lang?.lowercase()) {
            "eng", "en" -> Locale.US
            "fr", "fra" -> Locale.FRENCH
            "es", "spa" -> Locale("es", "ES")
            "de", "ger" -> Locale.GERMAN
            else -> Locale.US // Default to English
        }
        tts.language = locale

        // Speak prompt first, flushing any previous audio.
        question.prompt?.text?.let {
            if (it.isNotEmpty()) {
                tts.speak(it, TextToSpeech.QUEUE_FLUSH, null, "${questionId}-prompt")
            }
        }

        // Speak question, adding it to the queue to play after the prompt.
        question.question?.text?.let {
            if (it.isNotEmpty()) {
                tts.speak(it, TextToSpeech.QUEUE_ADD, null, "${questionId}-question")
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

        // Choices
        val grid = findViewById<androidx.gridlayout.widget.GridLayout>(R.id.choicesGrid)
        grid.removeAllViews()

        // For translation game, use fewer columns to allow wider buttons with text wrapping
        val isTranslationGame = gameType == "translation"
        if (isTranslationGame) {
            grid.columnCount = 2 // Use 2 columns for translation game
        } else {
            // For other games, use a reasonable default column count
            // GridLayout with columnCount = 0 can cause issues, so set a default
            grid.columnCount = 3 // Default to 3 columns for non-translation games
        }

        val correctChoices = q.correctChoices ?: emptyList()
        val extraChoices = q.extraChoices ?: emptyList()
        val allChoices = (correctChoices + extraChoices).shuffled()

        for (choice in allChoices) {
            val btn = Button(this)
            btn.text = choice.text
            btn.textSize = 24f // Larger text for kids
            // Ensure text displays exactly as in JSON (no auto-capitalization)
            btn.transformationMethod = null

            // Set button layout parameters - wrap content width, auto-fit to screen
            val params = androidx.gridlayout.widget.GridLayout.LayoutParams()
            
            if (isTranslationGame) {
                // For translation game, allow text wrapping with max width
                // Account for: root padding (24dp * 2), grid padding (12dp * 2), button margins (12dp * 2)
                val displayMetrics = resources.displayMetrics
                val density = displayMetrics.density
                val rootPadding = (24 * density).toInt() * 2 // 24dp on each side
                val gridPadding = (12 * density).toInt() * 2 // 12dp on each side
                val buttonMargin = (12 * density).toInt() * 2 // 12dp margin on each side
                val screenWidth = displayMetrics.widthPixels
                // Available width per column: (screen - root padding - grid padding) / 2 - button margins
                val maxButtonWidth = ((screenWidth - rootPadding - gridPadding) / 2) - buttonMargin
                params.width = maxButtonWidth
                params.height = androidx.gridlayout.widget.GridLayout.LayoutParams.WRAP_CONTENT
                // Enable text wrapping
                btn.maxLines = 0 // Unlimited lines
                btn.isSingleLine = false
            } else {
                params.width = androidx.gridlayout.widget.GridLayout.LayoutParams.WRAP_CONTENT
                params.height = androidx.gridlayout.widget.GridLayout.LayoutParams.WRAP_CONTENT
            }
            
            params.setMargins(12, 12, 12, 12) // Equal margins around each button
            btn.layoutParams = params

            // Make button more prominent with rounded corners and equal padding
            btn.background = resources.getDrawable(R.drawable.button_rounded_choice)
            btn.setPadding(20, 20, 20, 20) // Equal horizontal and vertical padding
            btn.minHeight = 140 // Minimum height for finger tapping

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
        super.onDestroy()

        // End time tracking when the activity is destroyed
        timeTracker.endActivity("game")
    }
}
