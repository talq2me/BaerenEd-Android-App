package com.talq2me.baerened

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.*

class SpellingOCRActivity : AppCompatActivity() {
    private lateinit var drawingCanvas: DrawingCanvasView
    private lateinit var wordTextView: TextView
    private lateinit var sentenceTextView: TextView
    private lateinit var checkButton: Button
    private lateinit var deleteButton: Button
    private lateinit var modeToggleContainer: android.view.ViewGroup
    private lateinit var printingLabel: TextView
    private lateinit var cursiveLabel: TextView
    private lateinit var toggleSlider: View
    private lateinit var progressTextView: TextView
    
    private lateinit var progressManager: DailyProgressManager
    private lateinit var timeTracker: TimeTracker
    
    private var currentWordIndex = 0
    private var words: List<WordData> = emptyList()
    private var correctWords = 0
    
    // Game configuration
    private lateinit var gameType: String
    private lateinit var gameTitle: String
    private var gameStars: Int = 1
    private var isRequiredGame: Boolean = false
    private var sectionId: String? = null
    private var battleHubTaskId: String? = null
    private var wordFile: String = "englishWordsGr1.json"
    
    // TTS
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingWordData: WordData? = null // Word waiting to be spoken when TTS is ready
    
    // OCR
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Upload tracking
    private var hasUploadedImage = false // Track if we've uploaded image for this word
    
    // Writing mode (printing vs cursive)
    private var isPrintingMode = true
    
    data class WordData(val word: String, val sentence: String)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spelling_ocr)
        
        // Get game config from intent
        gameType = intent.getStringExtra("GAME_TYPE") ?: "spellingOCR"
        gameTitle = intent.getStringExtra("GAME_TITLE") ?: "Spelling OCR"
        gameStars = intent.getIntExtra("GAME_STARS", 1)
        isRequiredGame = intent.getBooleanExtra("IS_REQUIRED_GAME", false)
        sectionId = intent.getStringExtra("SECTION_ID")
        battleHubTaskId = intent.getStringExtra("BATTLE_HUB_TASK_ID")
        val wordFileFromIntent = intent.getStringExtra("WORD_FILE")
        wordFile = if (wordFileFromIntent != null && wordFileFromIntent.isNotEmpty() && wordFileFromIntent.endsWith(".json")) {
            wordFileFromIntent
        } else {
            Log.w(TAG, "Invalid or missing WORD_FILE in intent: '$wordFileFromIntent', using default")
            "englishWordsGr1.json"
        }
        Log.d(TAG, "Word file from intent: $wordFile")
        
        // Initialize views
        drawingCanvas = findViewById(R.id.drawingCanvas)
        wordTextView = findViewById(R.id.wordTextView)
        sentenceTextView = findViewById(R.id.sentenceTextView)
        checkButton = findViewById(R.id.checkButton)
        deleteButton = findViewById(R.id.deleteButton)
        modeToggleContainer = findViewById(R.id.modeToggleContainer)
        printingLabel = findViewById(R.id.printingLabel)
        cursiveLabel = findViewById(R.id.cursiveLabel)
        toggleSlider = findViewById(R.id.toggleSlider)
        progressTextView = findViewById(R.id.progressTextView)
        
        // Initialize progress manager
        progressManager = DailyProgressManager(this)
        timeTracker = TimeTracker(this)
        
        val uniqueTaskId = if (sectionId != null) {
            progressManager.getUniqueTaskId(gameType, sectionId)
        } else {
            gameType
        }
        
        timeTracker.startActivity(uniqueTaskId, "game", gameTitle)
        
        // Initialize TTS
        initTTS()
        
        // Load words from JSON
        loadWords()
        
        // Setup button listeners
        setupButtons()
        
        // Start first word (will wait for TTS if not ready)
        if (words.isNotEmpty()) {
            showNextWord()
        } else {
            Toast.makeText(this, "Failed to load words", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.ENGLISH)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS language not supported")
                } else {
                    isTtsReady = true
                    Log.d(TAG, "TTS initialized successfully")
                    
                    // If we have a pending word, speak it now
                    pendingWordData?.let { wordData ->
                        speakWord(wordData)
                        pendingWordData = null
                    }
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }
    
    private fun loadWords() {
        try {
            val filePath = "data/$wordFile"
            Log.d(TAG, "Attempting to load words from: $filePath")
            val inputStream = assets.open(filePath)
            val json = inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(json)
            
            words = (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                WordData(
                    word = obj.getString("word"),
                    sentence = obj.getString("sentence")
                )
            }
            
            Log.d(TAG, "Successfully loaded ${words.size} words from $wordFile")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading words from '$wordFile': ${e.message}", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            words = emptyList()
        }
    }
    
    private fun setupButtons() {
        checkButton.setOnClickListener {
            checkSpelling()
        }
        
        deleteButton.setOnClickListener {
            drawingCanvas.clear()
        }
        
        // Toggle between printing and cursive
        printingLabel.setOnClickListener {
            if (!isPrintingMode) {
                isPrintingMode = true
                updateModeToggle()
            }
        }
        
        cursiveLabel.setOnClickListener {
            if (isPrintingMode) {
                isPrintingMode = false
                updateModeToggle()
            }
        }
        
        // Start with printing mode active
        // Post to ensure views are measured
        modeToggleContainer.post {
            updateModeToggle()
        }
    }
    
    private fun updateModeToggle() {
        modeToggleContainer.post {
            val containerWidth = modeToggleContainer.width.toFloat()
            val margin = 4f * resources.displayMetrics.density // Convert dp to pixels
            
            // Set slider width to half the container (minus margins)
            val sliderWidth = (containerWidth / 2f) - (margin * 2f)
            val params = toggleSlider.layoutParams as android.widget.FrameLayout.LayoutParams
            params.width = sliderWidth.toInt()
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            toggleSlider.layoutParams = params
            
            // Position slider on the active side
            val targetX = if (isPrintingMode) {
                margin // Left side (printing)
            } else {
                containerWidth / 2f + margin // Right side (cursive)
            }
            
            toggleSlider.animate()
                .x(targetX)
                .setDuration(200)
                .start()
            
            // Update text colors/alpha to show active state
            printingLabel.alpha = if (isPrintingMode) 1.0f else 0.6f
            cursiveLabel.alpha = if (!isPrintingMode) 1.0f else 0.6f
        }
    }
    
    private fun showNextWord() {
        if (currentWordIndex >= words.size) {
            // All words completed
            completeGame()
            return
        }
        
        val wordData = words[currentWordIndex]
        
        // Reset upload flag
        hasUploadedImage = false
        
        // Hide word
        wordTextView.text = "?"
        wordTextView.alpha = 0.3f
        
        // Replace the word in the sentence with underlines (case-insensitive)
        val sentenceWithBlanks = wordData.sentence.replace(
            Regex(wordData.word, RegexOption.IGNORE_CASE),
            "_".repeat(wordData.word.length)
        )
        sentenceTextView.text = sentenceWithBlanks
        drawingCanvas.clear()
        
        // Update progress
        updateProgress()
        
        // Speak the word and sentence
        speakWord(wordData)
    }
    
    private fun speakWord(wordData: WordData) {
        if (!isTtsReady) {
            // Store the word to speak when TTS becomes ready
            pendingWordData = wordData
            Log.d(TAG, "TTS not ready yet, storing word to speak later")
            return
        }
        
        // Speak sentence first, then word
        tts?.speak(wordData.sentence, TextToSpeech.QUEUE_FLUSH, null, null)
        tts?.speak("Spell: ${wordData.word}", TextToSpeech.QUEUE_ADD, null, null)
    }
    
    private fun checkSpelling() {
        val wordData = words[currentWordIndex]
        val drawing = drawingCanvas.getDrawingBitmap()
        
        if (drawing == null) {
            Toast.makeText(this, "Please write the word first!", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Disable check button during processing
        checkButton.isEnabled = false
        
        // Perform OCR
        // Capture the expected word explicitly to ensure we use the correct word, not OCR result
        val expectedWord = wordData.word
        val isCursiveMode = !isPrintingMode // Capture mode for validation
        recognizeText(drawing) { recognizedText ->
            checkButton.isEnabled = true
            
            val isCorrect = isSpellingCorrect(recognizedText, expectedWord, isCursiveMode)
            
            // Upload image with correct status (only once per word)
            // Use the expected word (from JSON), NOT the OCR recognized text
            if (!hasUploadedImage) {
                uploadSpellingImage(drawing, wordData, isCorrect, expectedWord)
                hasUploadedImage = true
            }
            
            // Move to next word regardless of correctness
            // Parent can review incorrect answers in the report
            correctWords++
            currentWordIndex++
            
            if (isCorrect) {
                showFireworks()
                android.os.Handler().postDelayed({
                    showNextWord()
                }, 2000)
            } else {
                // No message - just move to next word silently
                android.os.Handler().postDelayed({
                    showNextWord()
                }, 500)
            }
        }
    }
    
    private fun recognizeText(bitmap: Bitmap, onResult: (String) -> Unit) {
        // Preprocess image to improve OCR accuracy
        // Use less aggressive preprocessing for cursive mode
        val processedBitmap = preprocessImageForOCR(bitmap, !isPrintingMode)
        val image = InputImage.fromBitmap(processedBitmap, 0)
        
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val recognizedText = visionText.text.trim().lowercase()
                Log.d(TAG, "OCR recognized: '$recognizedText'")
                onResult(recognizedText)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed: ${e.message}", e)
                Toast.makeText(this, "Could not read your writing. Please try again.", Toast.LENGTH_SHORT).show()
                onResult("")
            }
    }
    
    /**
     * Preprocesses the bitmap to improve OCR accuracy for handwriting
     * - Increases contrast
     * - Converts to grayscale
     * - Applies thresholding to make strokes darker
     * - Scales up if image is too small
     * @param isCursiveMode If true, uses less aggressive preprocessing to preserve cursive strokes
     */
    private fun preprocessImageForOCR(bitmap: Bitmap, isCursiveMode: Boolean): Bitmap {
        // Scale up if image is too small (OCR works better with larger images)
        val scaleFactor = if (bitmap.width < 400 || bitmap.height < 400) {
            2.0f // Double the size if too small
        } else {
            1.0f
        }
        
        val scaledWidth = (bitmap.width * scaleFactor).toInt()
        val scaledHeight = (bitmap.height * scaleFactor).toInt()
        
        // First scale up if needed
        val scaledBitmap = if (scaleFactor > 1.0f) {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        } else {
            bitmap
        }
        
        // For cursive mode, use less aggressive preprocessing to preserve connected strokes
        if (isCursiveMode) {
            // Less aggressive preprocessing for cursive - preserve more detail
            val processedBitmap = Bitmap.createBitmap(
                scaledBitmap.width,
                scaledBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            
            val canvas = Canvas(processedBitmap)
            
            // Apply lighter contrast for cursive to preserve stroke connections
            val colorMatrix = ColorMatrix().apply {
                // Convert to grayscale
                setSaturation(0f)
                
                // Moderate contrast increase for cursive (less aggressive)
                val contrast = 1.3f // Lower contrast to preserve cursive connections
                val scale = contrast
                val translate = (-128f * scale + 128f)
                
                val contrastMatrix = floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
                
                postConcat(ColorMatrix(contrastMatrix))
            }
            
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
                isAntiAlias = true // Enable anti-aliasing for cursive to preserve smooth strokes
            }
            
            // Draw the scaled bitmap with preprocessing applied
            canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)
            
            // Clean up if we created a new scaled bitmap
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            
            return processedBitmap
        } else {
            // Aggressive preprocessing for printing mode
            val processedBitmap = Bitmap.createBitmap(
                scaledBitmap.width,
                scaledBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            
            val canvas = Canvas(processedBitmap)
            
            // Apply color matrix to increase contrast and convert to grayscale
            val colorMatrix = ColorMatrix().apply {
                // Convert to grayscale
                setSaturation(0f)
                
                // Increase contrast (make dark pixels darker, light pixels lighter)
                val contrast = 1.8f // Higher contrast for printing
                val scale = contrast
                val translate = (-128f * scale + 128f)
                
                val contrastMatrix = floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
                
                postConcat(ColorMatrix(contrastMatrix))
            }
            
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
                isAntiAlias = false // Disable anti-aliasing for sharper edges in printing
            }
            
            // Draw the scaled bitmap with preprocessing applied
            canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)
            
            // Clean up if we created a new scaled bitmap
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            
            return processedBitmap
        }
    }
    
    private fun isSpellingCorrect(recognizedText: String, expectedWord: String, isCursiveMode: Boolean): Boolean {
        // Normalize both strings - remove all non-alphabetic characters
        val normalizedRecognized = recognizedText.lowercase().trim().replace(Regex("[^a-z]"), "")
        val normalizedExpected = expectedWord.lowercase().trim()
        
        if (normalizedRecognized.isEmpty()) {
            return false
        }
        
        // Exact match - always accept
        if (normalizedRecognized == normalizedExpected) {
            return true
        }
        
        // If recognized text contains the expected word (OCR added extra characters)
        if (normalizedRecognized.contains(normalizedExpected)) {
            return true
        }
        
        // For cursive mode, be more lenient due to OCR accuracy issues
        if (isCursiveMode) {
            // Allow if recognized text is at least 70% of expected length and is contained in expected word
            if (normalizedExpected.contains(normalizedRecognized) && 
                normalizedRecognized.length >= normalizedExpected.length * 0.7) {
                return true
            }
            
            // Allow up to 2 character differences for short words (4 or fewer), or 30% for longer words
            val maxAllowedDiff = if (normalizedExpected.length <= 4) {
                2
            } else {
                (normalizedExpected.length * 0.3).toInt().coerceAtLeast(2)
            }
            
            val editDistance = calculateEditDistance(normalizedRecognized, normalizedExpected)
            if (editDistance <= maxAllowedDiff) {
                Log.d(TAG, "Accepted cursive with edit distance $editDistance (max: $maxAllowedDiff)")
                return true
            }
        }
        
        // For printing mode, strict matching only
        return false
    }
    
    /**
     * Calculates Levenshtein distance (edit distance) between two strings
     * Used for lenient matching in cursive mode
     */
    private fun calculateEditDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        // Initialize base cases
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        
        // Fill the dp table
        for (i in 1..m) {
            for (j in 1..n) {
                if (s1[i - 1] == s2[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1]
                } else {
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,      // deletion
                        dp[i][j - 1] + 1,      // insertion
                        dp[i - 1][j - 1] + 1   // substitution
                    )
                }
            }
        }
        
        return dp[m][n]
    }
    
    
    private fun showFireworks() {
        // Simple fireworks effect using emoji and animation
        // Could be enhanced with actual animation library
        val fireworksText = "🎆🎇✨🎉"
        Toast.makeText(this, "$fireworksText Great job! $fireworksText", Toast.LENGTH_LONG).show()
        
        // Animate word text view
        wordTextView.animate()
            .scaleX(1.5f)
            .scaleY(1.5f)
            .setDuration(300)
            .withEndAction {
                wordTextView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }
    
    private fun updateProgress() {
        progressTextView.text = "Words: $correctWords/${words.size}"
    }
    
    /**
     * Uploads spelling image to Supabase image_uploads table
     * Task format: "EngSpellingOCR-01-uncooked-X" or "EngSpellingOCR-01-uncooked-✓"
     * Uploads once per word with the final correct/incorrect status
     * @param expectedWord The word that was asked to be spelled (from JSON), NOT the OCR recognized text
     */
    private fun uploadSpellingImage(bitmap: Bitmap, wordData: WordData, isCorrect: Boolean, expectedWord: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cloudSyncService = CloudSyncService()
                if (!cloudSyncService.isConfigured()) {
                    Log.d(TAG, "Supabase not configured, skipping image upload")
                    return@launch
                }
                
                val profile = SettingsManager.readProfile(this@SpellingOCRActivity) ?: "AM"
                val questionNumber = String.format("%02d", currentWordIndex + 1)
                
                // Determine game name (EngSpellingOCR or FrSpellingOCR)
                val gameName = when {
                    wordFile.contains("english", ignoreCase = true) -> "EngSpellingOCR"
                    wordFile.contains("french", ignoreCase = true) -> "FrSpellingOCR"
                    else -> "EngSpellingOCR" // Default to English
                }
                
                // Determine status based on correctness
                val status = if (isCorrect) "✓" else "X"
                
                // Format: EngSpellingOCR-01-uncooked-X or EngSpellingOCR-01-uncooked-✓
                // Use expectedWord (the word asked to spell), NOT the OCR recognized text
                val task = "$gameName-$questionNumber-$expectedWord-$status"
                
                // Convert bitmap to base64
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                
                // Get timestamp in EST
                val captureDateTime = cloudSyncService.generateESTTimestamp()
                
                val supabaseUrl = cloudSyncService.getSupabaseUrl()
                val supabaseKey = cloudSyncService.getSupabaseKey()
                val httpClient = cloudSyncService.getClient()
                
                // Create JSON body
                val requestBody = JsonObject().apply {
                    addProperty("profile", profile)
                    addProperty("task", task)
                    addProperty("image", base64Image)
                    addProperty("capture_date_time", captureDateTime)
                }
                
                val mediaType = "application/json".toMediaType()
                val body = requestBody.toString().toRequestBody(mediaType)
                
                Log.d(TAG, "Uploading spelling image (profile=$profile, task=$task)")
                
                // Check if entry exists for this profile/game/question/word (any status)
                // URL encode the pattern for LIKE query
                // Use expectedWord (the word asked to spell), NOT the OCR recognized text
                val taskPattern = "$gameName-$questionNumber-$expectedWord-"
                val encodedPattern = java.net.URLEncoder.encode(taskPattern, "UTF-8") + "%25"
                val queryUrl = "$supabaseUrl/rest/v1/image_uploads?profile=eq.$profile&task=like.$encodedPattern&select=id"
                
                val queryRequest = Request.Builder()
                    .url(queryUrl)
                    .addHeader("apikey", supabaseKey)
                    .addHeader("Authorization", "Bearer $supabaseKey")
                    .build()
                
                val queryResponse = httpClient.newCall(queryRequest).execute()
                val queryBody = queryResponse.body?.string()
                
                if (queryResponse.isSuccessful && queryBody != null && queryBody != "[]") {
                    // Found existing entry - delete it first (to handle UNIQUE constraint)
                    val existingId = com.google.gson.JsonParser.parseString(queryBody)
                        .asJsonArray[0].asJsonObject["id"].asLong
                    
                    val deleteUrl = "$supabaseUrl/rest/v1/image_uploads?id=eq.$existingId"
                    val deleteRequest = Request.Builder()
                        .url(deleteUrl)
                        .delete()
                        .addHeader("apikey", supabaseKey)
                        .addHeader("Authorization", "Bearer $supabaseKey")
                        .build()
                    
                    val deleteResponse = httpClient.newCall(deleteRequest).execute()
                    Log.d(TAG, "Deleted existing image entry (response: ${deleteResponse.code})")
                    deleteResponse.close()
                }
                
                queryResponse.close()
                
                // INSERT new record (or re-insert after delete)
                val postUrl = "$supabaseUrl/rest/v1/image_uploads"
                val postRequest = Request.Builder()
                    .url(postUrl)
                    .post(body)
                    .addHeader("apikey", supabaseKey)
                    .addHeader("Authorization", "Bearer $supabaseKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation")
                    .build()
                
                val response = httpClient.newCall(postRequest).execute()
                Log.d(TAG, "Image upload POST response code: ${response.code}")
                response.close()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading spelling image: ${e.message}", e)
                // Don't show error to user - image upload is non-critical
            }
        }
    }
    
    
    private fun completeGame() {
        // Load config to pass to markTaskCompletedWithName (needed to find task and update properly)
        val contentUpdateService = ContentUpdateService()
        val configJson = contentUpdateService.getCachedMainContent(this)
        val config = if (!configJson.isNullOrEmpty()) {
            try {
                Gson().fromJson(configJson, MainContent::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing config JSON", e)
                null
            }
        } else {
            null
        }
        
        Log.d(TAG, "CRITICAL: About to mark task as complete - gameType: '$gameType', gameTitle: '$gameTitle', isRequiredGame: $isRequiredGame, sectionId: $sectionId")
        val earnedStars = progressManager.markTaskCompletedWithName(
            gameType, gameTitle, gameStars, isRequiredGame, config, sectionId
        )
        Log.d(TAG, "CRITICAL: Task marked as complete, earnedStars: $earnedStars")
        
        if (earnedStars > 0) {
            val totalRewardMinutes = progressManager.addStarsToRewardBank(earnedStars)
            timeTracker.updateStarsEarned("game", earnedStars)
            
            // Add berries if task is from required or optional section (or battle hub)
            if (battleHubTaskId != null || sectionId == "required" || sectionId == "optional") {
                progressManager.addEarnedBerries(earnedStars)
                Log.d(TAG, "Added $earnedStars berries to battle hub")
            }
        }
        
        // Sync to cloud (update_cloud_with_local according to Daily Reset Logic)
        val currentProfile = SettingsManager.readProfile(this) ?: "AM"
        val cloudStorageManager = CloudStorageManager(this)
        lifecycleScope.launch {
            // saveIfEnabled calls update_cloud_with_local if cloud is enabled
            cloudStorageManager.saveIfEnabled(currentProfile)
        }
        
        // Return result for TrainingMapActivity (always set result, not just for battle hub)
        val resultIntent = android.content.Intent().apply {
            putExtra("GAME_TYPE", gameType)
            putExtra("GAME_STARS", earnedStars)
            battleHubTaskId?.let { putExtra("BATTLE_HUB_TASK_ID", it) }
        }
        setResult(RESULT_OK, resultIntent)
        
        Toast.makeText(this, "🎉🎆 Great job! You completed all the spelling words! 🎆🎉", Toast.LENGTH_LONG).show()
        
        android.os.Handler().postDelayed({
            finish()
        }, 3000)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        textRecognizer.close()
        timeTracker.endActivity("game")
    }
    
    companion object {
        private const val TAG = "SpellingOCRActivity"
    }
}
