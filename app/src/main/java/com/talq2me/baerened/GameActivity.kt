package com.talq2me.baerened

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    // Game configuration
    private lateinit var gameType: String
    private var gameStars: Int = 1
    private var isRequiredGame: Boolean = false

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        // Get game content and config from intent extras
        val gameContent = intent.getStringExtra("GAME_CONTENT")
        gameType = intent.getStringExtra("GAME_TYPE") ?: "unknown"
        val gameTitle = intent.getStringExtra("GAME_TITLE") ?: "Game"
        val totalQuestions = intent.getIntExtra("TOTAL_QUESTIONS", 5) // Default to 5 if not provided
        gameStars = intent.getIntExtra("GAME_STARS", 1) // Default to 1 star if not provided
        val isRequiredGame = intent.getBooleanExtra("IS_REQUIRED_GAME", false)

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
                        // TTS completed, play audio clips after a short delay to ensure all TTS is done
                        runOnUiThread {
                            if (!audioClipsPlayedForCurrentQuestion) {
                                currentQuestion?.let { question ->
                                    android.os.Handler().postDelayed({
                                        playQuestionAudioClips(question)
                                        audioClipsPlayedForCurrentQuestion = true
                                    }, 500) // Small delay to ensure all TTS utterances complete
                                }
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        // TTS error occurred, still try to play audio clips
                        runOnUiThread {
                            if (!audioClipsPlayedForCurrentQuestion) {
                                currentQuestion?.let { question ->
                                    android.os.Handler().postDelayed({
                                        playQuestionAudioClips(question)
                                        audioClipsPlayedForCurrentQuestion = true
                                    }, 500)
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
                userAnswers.removeAt(userAnswers.lastIndex)
                updateMessage() // Update message area with remaining text
            }
        }

        findViewById<Button>(R.id.submitButton).setOnClickListener {
            val correct = gameEngine.submitAnswer(userAnswers)
            if (correct) {
                // Clear user input and show feedback
                findViewById<TextView>(R.id.messageArea).text = ""
                showMessageAndClear("✅ Correct!", 2000)
                if (gameEngine.shouldEndGame()) {
                    // Game completed successfully - award stars
                    val earnedStars = progressManager.markTaskCompleted(gameType, gameStars, isRequiredGame)
                    if (earnedStars > 0) {
                        // Update progress display on main screen if this activity has access to it
                        // For now, just log the progress
                        android.util.Log.d("GameActivity", "Game $gameType completed, earned $earnedStars stars")
                    }

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
                // Show "Try again" message without including the wrong answer text
                // (prevents visual glitches with the user's chosen text display)
                showMessageAndClear("❌ Try again!", 2000)

                // Schedule clearing user answers after message clears (with small buffer)
                android.os.Handler().postDelayed({
                    userAnswers.clear()
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
            findViewById<TextView>(R.id.messageArea).text = ""
            showNextQuestion()
        }

        findViewById<Button>(R.id.replayButton).setOnClickListener {
            // Replay the TTS content for current question and audio clips
            currentQuestion?.let { question ->
                audioClipsPlayedForCurrentQuestion = false // Reset flag for replay
                speakSequentially(question)
                // Audio clips will be played after TTS completes (handled by UtteranceProgressListener)
            }
            // Clear message area when replaying (but don't clear user answers)
            // findViewById<TextView>(R.id.messageArea).text = ""
        }
    }

    private fun speakText(text: String, lang: String, utteranceId: String = "default") {
        if (text.isNotEmpty() && ttsReady) {
            val locale = when (lang.lowercase()) {
                "eng", "en" -> Locale.US
                "fr", "fra" -> Locale.FRENCH
                "es", "spa" -> Locale("es", "ES")
                "de", "ger" -> Locale.GERMAN
                else -> Locale.US // Default to English
            }

            tts.language = locale
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private fun speakSequentially(question: GameData) {
        if (!ttsReady) return

        var totalDelay = 0L
        val questionId = System.currentTimeMillis().toString() // Unique ID for this question

        // Determine what text to speak and its language
        val textToSpeak: String
        val langToSpeak: String

        if (question.question?.text != null && question.question.text.isNotEmpty()) {
            // Question has its own text - speak the question
            textToSpeak = question.question.text
            langToSpeak = question.question.lang
        } else if (question.prompt?.text != null && question.prompt.text.isNotEmpty()) {
            // Question has no text but prompt exists - speak the prompt
            textToSpeak = question.prompt.text
            langToSpeak = question.prompt.lang
        } else {
            // No text to speak
            return
        }

        // Speak the determined text
        speakTextWithDelay(textToSpeak, langToSpeak, totalDelay, "$questionId-content")
    }

    private fun speakTextWithDelay(text: String, lang: String, delayMs: Long, utteranceId: String = "default") {
        android.os.Handler().postDelayed({
            speakText(text, lang, utteranceId)
        }, delayMs)
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
        messageArea.text = message

        // Cancel any existing clear handler
        messageClearHandler?.removeCallbacksAndMessages(null)

        // Schedule message clearing
        messageClearHandler = android.os.Handler()
        messageClearHandler?.postDelayed({
            messageArea.text = ""
        }, delayMs)
    }

    private fun showNextQuestion() {
        currentQuestion = gameEngine.getCurrentQuestion()
        val q = currentQuestion!!
        userAnswers.clear()
        audioClipsPlayedForCurrentQuestion = false // Reset flag for new question

        // Cancel any existing message clear handlers and clear current message
        messageClearHandler?.removeCallbacksAndMessages(null)
        findViewById<TextView>(R.id.messageArea).text = ""

        // Handle prompt display/TTS based on lang property
        val promptTextView = findViewById<TextView>(R.id.promptText)
        val replayButton = findViewById<Button>(R.id.replayButton)

        if (q.prompt?.lang != null || q.question.lang != null) {
            // Has TTS content - show replay button and speak sequentially
            replayButton.visibility = View.VISIBLE
            promptTextView.text = ""
            speakSequentially(q)
        } else {
            // No TTS content - hide replay button and display visually
            replayButton.visibility = View.GONE
            promptTextView.text = q.prompt?.text ?: ""
        }

        // Question images/text
        val container = findViewById<LinearLayout>(R.id.questionContainer)
        container.removeAllViews()

        // Hide question text if it has TTS or audio clips (since those provide the question content)
        if (q.question.lang == null && q.question.media?.audioclips == null) {
            // No TTS or audio clips - display question text visually
            q.question.text?.let {
                val tv = TextView(this)
                tv.text = it
                tv.textSize = 24f
                container.addView(tv)
            }
        }

        // Create horizontal layout for images to display them side by side
        if (q.question.media?.images?.isNotEmpty() == true) {
            val imageContainer = LinearLayout(this)
            imageContainer.orientation = LinearLayout.HORIZONTAL
            imageContainer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            q.question.media.images.forEach {
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

        // Audio clips will be played after TTS completes (handled by UtteranceProgressListener)

        // Choices
        val grid = findViewById<androidx.gridlayout.widget.GridLayout>(R.id.choicesGrid)
        grid.removeAllViews()

        val allChoices = (q.correctChoices + q.extraChoices).shuffled()
        for (choice in allChoices) {
            val btn = Button(this)
            btn.text = choice.text
            btn.textSize = 24f // Larger text for kids
            // Ensure text displays exactly as in JSON (no auto-capitalization)
            btn.transformationMethod = null

            // Set button layout parameters - wrap content width, auto-fit to screen
            val params = androidx.gridlayout.widget.GridLayout.LayoutParams()
            params.width = androidx.gridlayout.widget.GridLayout.LayoutParams.WRAP_CONTENT
            params.height = androidx.gridlayout.widget.GridLayout.LayoutParams.WRAP_CONTENT
            params.setMargins(12, 12, 12, 12) // Equal margins around each button
            btn.layoutParams = params

            // Make button more prominent with rounded corners and equal padding
            btn.background = resources.getDrawable(R.drawable.button_rounded_choice)
            btn.setPadding(20, 20, 20, 20) // Equal horizontal and vertical padding
            btn.minHeight = 140 // Minimum height for finger tapping

            btn.setOnClickListener {
                userAnswers.add(choice.text)
                updateMessage()
                choice.media?.audioclip?.let { clip ->
                    val afd = assets.openFd(clip)
                    val mp = MediaPlayer()
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    mp.prepare()
                    mp.start()
                }
            }
            grid.addView(btn)
        }
    }

    private fun updateMessage() {
        val message = userAnswers.joinToString("") // No spaces between answers
        // Show user input immediately and keep it visible (no auto-clear)
        findViewById<TextView>(R.id.messageArea).text = message
    }
}
