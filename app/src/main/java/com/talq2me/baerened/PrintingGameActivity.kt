package com.talq2me.baerened

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PrintingGameActivity : AppCompatActivity() {
    private lateinit var drawingCanvas: DrawingCanvasView
    private lateinit var wordTextView: TextView
    private lateinit var sentenceTextView: TextView
    private lateinit var checkButton: Button
    private lateinit var deleteButton: Button
    private lateinit var eraserButton: Button
    private lateinit var penButton: Button
    private lateinit var starsTextView: TextView
    
    private lateinit var progressManager: DailyProgressManager
    private lateinit var timeTracker: TimeTracker
    
    private var currentWordIndex = 0
    private var sentence: List<String> = emptyList()
    private var wordStars = mutableMapOf<Int, Int>() // word index -> stars earned
    
    // Game configuration
    private lateinit var gameType: String
    private lateinit var gameTitle: String
    private var gameStars: Int = 1
    private var isRequiredGame: Boolean = false
    private var sectionId: String? = null
    private var battleHubTaskId: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printing_game)
        
        // Get game config from intent
        gameType = intent.getStringExtra("GAME_TYPE") ?: "printing"
        gameTitle = intent.getStringExtra("GAME_TITLE") ?: "Printing Practice"
        gameStars = intent.getIntExtra("GAME_STARS", 1)
        isRequiredGame = intent.getBooleanExtra("IS_REQUIRED_GAME", false)
        sectionId = intent.getStringExtra("SECTION_ID")
        battleHubTaskId = intent.getStringExtra("BATTLE_HUB_TASK_ID")
        
        // Initialize views
        drawingCanvas = findViewById(R.id.drawingCanvas)
        wordTextView = findViewById(R.id.wordTextView)
        sentenceTextView = findViewById(R.id.sentenceTextView)
        checkButton = findViewById(R.id.checkButton)
        deleteButton = findViewById(R.id.deleteButton)
        eraserButton = findViewById(R.id.eraserButton)
        penButton = findViewById(R.id.penButton)
        starsTextView = findViewById(R.id.starsTextView)
        
        // Initialize progress manager
        progressManager = DailyProgressManager(this)
        timeTracker = TimeTracker(this)
        
        val uniqueTaskId = if (sectionId != null) {
            progressManager.getUniqueTaskId(gameType, sectionId)
        } else {
            gameType
        }
        
        timeTracker.startActivity(uniqueTaskId, "game", gameTitle)
        
        // Generate sentence with all alphabet letters
        sentence = generateAlphabetSentence()
        
        // Setup button listeners
        setupButtons()
        
        // Start first word
        showNextWord()
    }
    
    private fun setupButtons() {
        checkButton.setOnClickListener {
            gradeCurrentWord()
        }
        
        deleteButton.setOnClickListener {
            drawingCanvas.clear()
        }
        
        eraserButton.setOnClickListener {
            drawingCanvas.setEraserMode(true)
            eraserButton.alpha = 0.5f
            penButton.alpha = 1.0f
        }
        
        penButton.setOnClickListener {
            drawingCanvas.setEraserMode(false)
            penButton.alpha = 0.5f
            eraserButton.alpha = 1.0f
        }
        
        // Start with pen mode active
        penButton.alpha = 0.5f
    }
    
    private fun showNextWord() {
        if (currentWordIndex >= sentence.size) {
            // All words completed
            completeGame()
            return
        }
        
        val word = sentence[currentWordIndex]
        wordTextView.text = word
        sentenceTextView.text = sentence.joinToString(" ")
        drawingCanvas.clear()
        
        // Update stars display
        updateStarsDisplay()
    }
    
    private fun gradeCurrentWord() {
        val word = sentence[currentWordIndex]
        val drawing = drawingCanvas.getDrawingBitmap()
        
        if (drawing == null) {
            Toast.makeText(this, "Please draw the word first!", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Grade the drawing (1-3 stars)
        val stars = LetterGrader.gradeWord(word, drawing, drawingCanvas.width, drawingCanvas.height)
        wordStars[currentWordIndex] = stars
        
        // Show feedback
        val feedback = when (stars) {
            3 -> "⭐⭐⭐ Excellent!"
            2 -> "⭐⭐ Good job!"
            else -> "⭐ Keep practicing!"
        }
        
        Toast.makeText(this, feedback, Toast.LENGTH_LONG).show()
        
        // Check if they can proceed (need at least 2 stars)
        if (stars >= 2) {
            currentWordIndex++
            android.os.Handler().postDelayed({
                showNextWord()
            }, 2000)
        } else {
            // Need to try again
            android.os.Handler().postDelayed({
                drawingCanvas.clear()
            }, 2000)
        }
    }
    
    private fun updateStarsDisplay() {
        val totalStars = wordStars.values.sum()
        val completedWords = wordStars.size
        starsTextView.text = "Stars: $totalStars | Words: $completedWords/${sentence.size}"
    }
    
    private fun completeGame() {
        val totalStars = wordStars.values.sum()
        val earnedStars = progressManager.markTaskCompletedWithName(
            gameType, gameTitle, gameStars, isRequiredGame, null, sectionId
        )
        
        if (earnedStars > 0) {
            val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)
            timeTracker.updateStarsEarned("game", earnedStars)
            
            if (battleHubTaskId != null || sectionId == "required" || sectionId == "optional") {
                progressManager.addEarnedBerries(earnedStars)
            }
        }
        
        // Sync to cloud
        val currentProfile = SettingsManager.readProfile(this) ?: "AM"
        val cloudStorageManager = CloudStorageManager(this)
        lifecycleScope.launch {
            cloudStorageManager.saveIfEnabled(currentProfile)
        }
        
        // Return result if launched from battle hub
        if (battleHubTaskId != null) {
            val resultIntent = android.content.Intent().apply {
                putExtra("BATTLE_HUB_TASK_ID", battleHubTaskId)
                putExtra("GAME_TYPE", gameType)
                putExtra("GAME_STARS", earnedStars)
            }
            setResult(RESULT_OK, resultIntent)
        }
        
        Toast.makeText(this, "🎉 Great job! You completed all words!", Toast.LENGTH_LONG).show()
        
        android.os.Handler().postDelayed({
            finish()
        }, 3000)
    }
    
    private fun generateAlphabetSentence(): List<String> {
        // Get a random word set from the hardcoded collection
        // Each set contains 6 grade 1 level words that together use all 26 letters
        return PrintingWordSets.getRandomWordSet()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        timeTracker.endActivity("game")
    }
}
