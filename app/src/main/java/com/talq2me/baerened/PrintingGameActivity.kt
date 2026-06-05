package com.talq2me.baerened

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PrintingGameActivity : AppCompatActivity() {
    private companion object {
        private const val TAG = "PrintingGameActivity"
    }
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
    private var retryUsedForCurrentWord = false // only allow one redo per word when they get 1 star
    
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

        clearPrintingUploadsOnLaunch()
        
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
            progressManager.getUniqueTaskId(gameType, gameTitle, sectionId)
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
        retryUsedForCurrentWord = false
        
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
        
        // Proceed if 2+ stars, or if 1 star and they've already had their one retry
        if (stars >= 2 || retryUsedForCurrentWord) {
            uploadWordSnapshot(drawing, currentWordIndex, word)
            currentWordIndex++
            android.os.Handler().postDelayed({
                showNextWord()
            }, 2000)
        } else {
            // One retry only: let them try the word once more
            retryUsedForCurrentWord = true
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
    
    /** Wipe prior printing-practice snapshots for this profile so each session starts fresh. */
    private fun clearPrintingUploadsOnLaunch() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sync = SupabaseInterface()
                if (!sync.isConfigured()) {
                    Log.d(TAG, "Supabase not configured; skipping clear of printing uploads")
                    return@launch
                }
                val profile = SettingsManager.readProfile(this@PrintingGameActivity) ?: "AM"
                val n = sync.invokeAfDeleteImageUploadsIlike(profile, "HandwritingPractice%-printing-%").getOrElse {
                    Log.e(TAG, "clear printing uploads RPC failed: ${it.message}")
                    return@launch
                }
                Log.d(TAG, "Cleared printing uploads (profile=$profile): deleted $n rows")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing printing uploads", e)
            }
        }
    }

    private fun torontoYmd(): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("America/Toronto")
        return fmt.format(Date())
    }

    private fun sanitizeTaskWord(word: String): String {
        val slug = word.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (slug.isEmpty()) "word" else slug.take(40)
    }

    /** One row per word per day; retries overwrite the same word slot only. */
    private fun handwritingTaskKey(wordIndex: Int, word: String): String {
        val nn = String.format(Locale.US, "%02d", wordIndex + 1)
        return "HandwritingPractice-${torontoYmd()}-printing-$nn-${sanitizeTaskWord(word)}"
    }

    private fun uploadWordSnapshot(bitmap: Bitmap, wordIndex: Int, word: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sync = SupabaseInterface()
            if (!sync.isConfigured()) {
                Log.d(TAG, "Supabase not configured; skipping word snapshot upload")
                return@launch
            }
            val profile = SettingsManager.readProfile(this@PrintingGameActivity) ?: "AM"
            val task = handwritingTaskKey(wordIndex, word)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            val upload = sync.invokeAfUpsertImageUpload(profile, task, base64Image)
            if (upload.isFailure) {
                Log.e(TAG, "Word snapshot upload failed: ${upload.exceptionOrNull()?.message}")
            } else {
                Log.d(TAG, "Word snapshot uploaded (profile=$profile, task=$task)")
            }
        }
    }

    private fun completeGame() {
        val totalStars = wordStars.values.sum()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = progressManager.markTaskCompletedWithName(
                gameType, gameTitle, gameStars, sectionId
            )
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { earnedStars ->
                        if (earnedStars > 0) {
                            timeTracker.updateStarsEarned("game", earnedStars)
                        }
                        if (battleHubTaskId != null) {
                            val resultIntent = android.content.Intent().apply {
                                putExtra("BATTLE_HUB_TASK_ID", battleHubTaskId)
                                putExtra("GAME_TYPE", gameType)
                                putExtra("GAME_TITLE", gameTitle)
                                putExtra("GAME_STARS", earnedStars)
                                sectionId?.let { putExtra("SECTION_ID", it) }
                            }
                            setResult(RESULT_OK, resultIntent)
                        }
                        Toast.makeText(this@PrintingGameActivity, "🎉 Great job! You completed all words!", Toast.LENGTH_LONG).show()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            finish()
                        }, 3000)
                    },
                    onFailure = { e ->
                        AlertDialog.Builder(this@PrintingGameActivity)
                            .setTitle("Could not save progress")
                            .setMessage(e.message ?: "Server sync failed.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                )
            }
        }
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
