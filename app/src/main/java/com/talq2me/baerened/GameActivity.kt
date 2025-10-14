package com.talq2me.baerened

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import java.util.Locale

// GameActivity.kt
class GameActivity : AppCompatActivity() {
    private lateinit var gameEngine: GameEngine
    private val userAnswers = mutableListOf<String>()
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var currentQuestion: GameData? = null
    private var messageClearHandler: android.os.Handler? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        // Get game content and config from intent extras
        val gameContent = intent.getStringExtra("GAME_CONTENT")
        val gameType = intent.getStringExtra("GAME_TYPE") ?: "unknown"
        val gameTitle = intent.getStringExtra("GAME_TITLE") ?: "Game"
        val totalQuestions = intent.getIntExtra("TOTAL_QUESTIONS", 5) // Default to 5 if not provided

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

        // TTS Init
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                ttsReady = true
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
                // Show their wrong answer with red X and "Try again"
                val wrongAnswer = userAnswers.lastOrNull() ?: ""
                val messageWithX = "$wrongAnswer ❌ Try again!"
                showMessageAndClear(messageWithX, 2000)

                // Schedule clearing user answers after message clears
                android.os.Handler().postDelayed({
                    userAnswers.clear()
                }, 2000) // Clear after 2 seconds (when message clears)
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
            // Replay the TTS content for current question
            currentQuestion?.let { speakSequentially(it) }
            // Clear message area when replaying (but don't clear user answers)
            // findViewById<TextView>(R.id.messageArea).text = ""
        }
    }

    private fun speakText(text: String, lang: String) {
        if (text.isNotEmpty() && ttsReady) {
            val locale = when (lang.lowercase()) {
                "eng", "en" -> Locale.US
                "fr", "fra" -> Locale.FRENCH
                "es", "spa" -> Locale("es", "ES")
                "de", "ger" -> Locale.GERMAN
                else -> Locale.US // Default to English
            }

            tts.language = locale
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun speakSequentially(question: GameData) {
        if (!ttsReady) return

        var totalDelay = 0L

        // Speak prompt first (if it has lang)
        question.prompt?.let { prompt ->
            if (prompt.lang != null) {
                speakText(prompt.text, prompt.lang)
                totalDelay += 2000 // 2 second delay for prompt
            }
        }

        // Speak question after prompt (if it has lang)
        if (question.question.lang != null) {
            android.os.Handler().postDelayed({
                speakText(question.question.text ?: "", question.question.lang)
            }, totalDelay)
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

        if (q.question.lang == null) {
            // No lang property - display visually
            q.question.text?.let {
                val tv = TextView(this)
                tv.text = it
                tv.textSize = 24f
                container.addView(tv)
            }
        }

        q.question.media?.images?.forEach {
            val img = ImageView(this)
            val afd = assets.openFd(it)
            val bmp = BitmapFactory.decodeStream(afd.createInputStream())
            img.setImageBitmap(bmp)
            img.layoutParams = ViewGroup.LayoutParams(200, 200)
            container.addView(img)
        }

        q.question.media?.audioclips?.forEach {
            try {
                val afd = assets.openFd(it)
                val mp = MediaPlayer()
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                android.util.Log.w("GameActivity", "Failed to play question audio clip: $it", e)
            }
        }

        // Choices
        val grid = findViewById<androidx.gridlayout.widget.GridLayout>(R.id.choicesGrid)
        grid.removeAllViews()

        val allChoices = (q.correctChoices + q.extraChoices).shuffled()
        for (choice in allChoices) {
            val btn = Button(this)
            btn.text = choice.text
            // Ensure text displays exactly as in JSON (no auto-capitalization)
            btn.transformationMethod = null
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
        val message = userAnswers.joinToString(" ")
        // Show user input immediately and keep it visible (no auto-clear)
        findViewById<TextView>(R.id.messageArea).text = message
    }
}
