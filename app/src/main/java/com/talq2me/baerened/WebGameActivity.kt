package com.talq2me.baerened

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import android.util.Base64

class WebGameActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_GAME_URL = "game_url"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SECTION_ID = "section_id"
        const val EXTRA_STARS = "stars"
        const val EXTRA_TASK_TITLE = "task_title"
        const val RESULT_LAUNCH_GAME = 100
        const val RESULT_EXTRA_GAME_ID = "game_id_to_launch"
        const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        const val CAMERA_REQUEST_CODE = 1002
    }

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private lateinit var timeTracker: TimeTracker
    private lateinit var progressManager: DailyProgressManager
    private var gameCompleted = false
    private var taskId: String? = null
    private var sectionId: String? = null
    private var stars: Int = 0
    private var taskTitle: String? = null
    private var cameraSuccessCallback: String? = null
    private var cameraErrorCallback: String? = null
    
    // HTTP client for fetching JSON from GitHub
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_game)

        webView = findViewById(R.id.game_webview)

        val gameUrl = intent.getStringExtra(EXTRA_GAME_URL)
        taskId = intent.getStringExtra(EXTRA_TASK_ID)
        sectionId = intent.getStringExtra(EXTRA_SECTION_ID)
        stars = intent.getIntExtra(EXTRA_STARS, 0)
        taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)

        // Initialize time tracker (lightweight, safe to do on main thread)
        timeTracker = TimeTracker(this)
        
        // Initialize progress manager in background to avoid ANR from migration
        // The migration in DailyProgressManager.init can be heavy, so we'll lazy-init it
        progressManager = DailyProgressManager(this)
        
        // Use unique task ID that includes section info to track separately for required vs optional
        val currentTaskId = taskId
        val currentSectionId = sectionId
        val uniqueTaskId = if (currentTaskId != null && currentSectionId != null) {
            progressManager.getUniqueTaskId(currentTaskId, currentSectionId)
        } else {
            currentTaskId ?: "webgame"
        }
        
        // Start tracking time for this web game
        val gameName = taskTitle ?: taskId ?: "Web Game"
        timeTracker.startActivity(uniqueTaskId, "webgame", gameName)

        val ws: WebSettings = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        ws.allowFileAccessFromFileURLs = true
        ws.allowUniversalAccessFromFileURLs = true
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.setSupportZoom(false)
        ws.builtInZoomControls = false
        ws.displayZoomControls = false
        // Enable hardware acceleration for better performance
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress == 100) {
                    android.util.Log.d("WebGameActivity", "Game page fully loaded")
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                android.util.Log.d("WebGameActivity", "Game page finished loading: $url")
                // Inject a test script to verify Android interface is accessible
                view?.evaluateJavascript("""
                    setTimeout(function() {
                        console.log('=== Page Finished - Android Interface Check ===');
                        console.log('window.Android exists:', typeof window.Android !== 'undefined');
                        if (window.Android) {
                            console.log('Available methods:', Object.keys(window.Android));
                            console.log('openCamera available:', typeof window.Android.openCamera === 'function');
                        }
                    }, 500);
                """.trimIndent(), null)
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                android.util.Log.e("WebGameActivity", "Error loading game: $description (code: $errorCode)")
                Toast.makeText(this@WebGameActivity, "Error loading game: $description", Toast.LENGTH_LONG).show()
            }
        }

        tts = TextToSpeech(this, this)
        
        // Set up TTS utterance progress listener to notify JavaScript when TTS finishes
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // TTS started
            }
            
            override fun onDone(utteranceId: String?) {
                // TTS finished - notify JavaScript if there's a callback registered
                runOnUiThread {
                    if (utteranceId != null && utteranceId.startsWith("tts_callback_")) {
                        webView.evaluateJavascript("if (window.onTTSFinished) window.onTTSFinished('$utteranceId');", null)
                    }
                }
            }
            
            override fun onError(utteranceId: String?) {
                // TTS error - still notify JavaScript so it doesn't wait forever
                runOnUiThread {
                    if (utteranceId != null && utteranceId.startsWith("tts_callback_")) {
                        webView.evaluateJavascript("if (window.onTTSFinished) window.onTTSFinished('$utteranceId');", null)
                    }
                }
            }
        })

        if (!gameUrl.isNullOrEmpty()) {
            android.util.Log.d("WebGameActivity", "Loading URL: $gameUrl")
            webView.addJavascriptInterface(WebGameInterface(taskId), "Android")
            webView.loadUrl(gameUrl)
        } else {
            Toast.makeText(this, "No game URL provided", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun finishGameWithResult(taskId: String?) {
        android.util.Log.d("WebGameActivity", "Finishing game activity with success result for taskId: $taskId")
        
        // Mark game as completed and end time tracking
        gameCompleted = true
        timeTracker.endActivity("webgame")
        // Note: The session is automatically marked as completed when endActivity is called

        val resultIntent = Intent().apply {
            putExtra(EXTRA_TASK_ID, taskId ?: "")
            sectionId?.let { putExtra(EXTRA_SECTION_ID, it) }
            putExtra(EXTRA_STARS, stars)
            taskTitle?.let { putExtra(EXTRA_TASK_TITLE, it) }
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onBackPressed() {
        android.util.Log.d("WebGameActivity", "Back button pressed, finishing without completion")
        // End time tracking (game not completed)
        timeTracker.endActivity("webgame")
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    inner class WebGameInterface(private val taskId: String?) {
        @JavascriptInterface
        fun gameCompleted(correctAnswers: Int = 0, incorrectAnswers: Int = 0) {
            android.util.Log.d("WebGameActivity", "JavaScript called gameCompleted() with correct: $correctAnswers, incorrect: $incorrectAnswers")
            // Update answer counts before finishing
            timeTracker.updateAnswerCounts("webgame", correctAnswers, incorrectAnswers)
            finishGameWithResult(taskId)
        }

        @JavascriptInterface
        fun saveProgress(index: Int) {
            android.util.Log.d("WebGameActivity", "JavaScript called saveProgress() with index: $index for taskId: $taskId")
            if (taskId != null) {
                val webGameProgress = WebGameProgress(this@WebGameActivity, taskId)
                webGameProgress.saveIndex(index)
            }
        }

        @JavascriptInterface
        fun loadProgress(): Int {
            val index = if (taskId != null) {
                val webGameProgress = WebGameProgress(this@WebGameActivity, taskId)
                webGameProgress.getCurrentIndex()
            } else {
                0
            }
            android.util.Log.d("WebGameActivity", "JavaScript called loadProgress() returning index: $index for taskId: $taskId")
            return index
        }

        @JavascriptInterface
        fun closeGame() {
            android.util.Log.d("WebGameActivity", "JavaScript called closeGame()")
            runOnUiThread { // Ensure UI operations are on the main thread
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        @JavascriptInterface
        fun readText(text: String, lang: String) {
            val locale = if (lang.lowercase().startsWith("fr")) Locale.FRENCH else Locale.US
            tts.language = locale
            // Use a unique utterance ID so we can track when this specific TTS call finishes
            val utteranceId = "tts_callback_${System.currentTimeMillis()}_${text.hashCode()}"
            // Use a bundle to pass the utterance ID (required for UtteranceProgressListener)
            val params = android.os.Bundle()
            params.putString(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }

        @JavascriptInterface
        fun loadJsonFile(fileName: String): String {
            val cacheFile = File(this@WebGameActivity.cacheDir, fileName)

            // 1. Try fetching from GitHub first
            try {
                val url = "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/data/$fileName"
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            android.util.Log.d("WebGameActivity", "Successfully fetched JSON from GitHub: $fileName")
                            // Save to cache for offline use
                            try {
                                FileOutputStream(cacheFile).use { it.write(body.toByteArray()) }
                                android.util.Log.d("WebGameActivity", "Saved JSON to cache: $fileName")
                            } catch (e: IOException) {
                                android.util.Log.e("WebGameActivity", "Error writing JSON to cache", e)
                            }
                            return body
                        }
                    }
                    android.util.Log.w("WebGameActivity", "GitHub fetch failed with code: ${response.code}. Trying cache.")
                }
            } catch (e: IOException) {
                android.util.Log.w("WebGameActivity", "Network error fetching JSON: ${e.message}. Trying cache.")
            }

            // 2. Network failed, try loading from Cache
            if (cacheFile.exists()) {
                try {
                    val cachedContent = cacheFile.readText()
                    android.util.Log.d("WebGameActivity", "Successfully loaded JSON from cache: $fileName")
                    return cachedContent
                } catch (e: Exception) {
                    android.util.Log.e("WebGameActivity", "Error reading JSON from cache. Trying assets.", e)
                }
            }

            // 3. Cache failed or doesn't exist, fall back to bundled Assets
            try {
                android.util.Log.d("WebGameActivity", "Loading JSON from bundled asset: $fileName")
                val inputStream: InputStream = assets.open("data/$fileName")
                val size = inputStream.available()
                val buffer = ByteArray(size)
                inputStream.read(buffer)
                inputStream.close()
                val content = String(buffer, Charset.forName("UTF-8"))
                android.util.Log.d("WebGameActivity", "Successfully loaded JSON from assets: $fileName")
                return content
            } catch (e: IOException) {
                android.util.Log.e("WebGameActivity", "Error loading JSON file from assets: $fileName", e)
                return "[]"
            }
        }

        @JavascriptInterface
        fun loadPokemonManifest(): String {
            return try {
                val inputStream: InputStream = assets.open("images/pokeSprites/sprites/pokemon/pokedex_manifest.json")
                val size = inputStream.available()
                val buffer = ByteArray(size)
                inputStream.read(buffer)
                inputStream.close()
                String(buffer, Charset.forName("UTF-8"))
            } catch (e: IOException) {
                android.util.Log.e("WebGameActivity", "Error loading Pokemon manifest", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun loadPokemonImage(filename: String): String {
            return try {
                val inputStream: InputStream = assets.open("images/pokeSprites/sprites/pokemon/$filename")
                val size = inputStream.available()
                val buffer = ByteArray(size)
                inputStream.read(buffer)
                inputStream.close()
                
                // Convert to base64
                val base64 = android.util.Base64.encodeToString(buffer, android.util.Base64.NO_WRAP)
                "data:image/png;base64,$base64"
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error loading Pokemon image: $filename", e)
                "" // Return empty string on error
            }
        }

        @JavascriptInterface
        fun getUnlockedPokemonCount(): Int {
            return try {
                DailyProgressManager(this@WebGameActivity).getUnlockedPokemonCount()
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error getting unlocked Pokemon count", e)
                0
            }
        }

        @JavascriptInterface
        fun getPokemonFileList(): String {
            return try {
                val files = assets.list("images/pokeSprites/sprites/pokemon") ?: emptyArray()
                val pngFiles = files.filter { it.endsWith(".png") }
                JSONArray(pngFiles).toString()
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error getting Pokemon file list", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun unlockPokemon(count: Int) {
            try {
                DailyProgressManager(this@WebGameActivity).unlockPokemon(count)
                android.util.Log.d("WebGameActivity", "Unlocked $count Pokemon via JavaScript interface")
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error unlocking Pokemon", e)
            }
        }

        @JavascriptInterface
        fun launchGame(gameId: String) {
            android.util.Log.d("WebGameActivity", "JavaScript requested to launch game: $gameId")
            // Return result to MainActivity so it can launch the game
            runOnUiThread {
                val resultIntent = Intent().apply {
                    putExtra(RESULT_EXTRA_GAME_ID, gameId)
                }
                setResult(RESULT_LAUNCH_GAME, resultIntent)
                finish() // Close battle hub and return to MainActivity
            }
        }

        @JavascriptInterface
        fun getEarnedBerries(): Int {
            return try {
                progressManager.getEarnedBerries()
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error getting earned berries", e)
                0
            }
        }

        @JavascriptInterface
        fun resetEarnedBerries() {
            try {
                progressManager.setEarnedBerries(0)
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error resetting earned berries", e)
            }
        }

        @JavascriptInterface
        fun getConfigJson(): String {
            return try {
                // Try to get from cache first (most up-to-date)
                val cacheFile = File(this@WebGameActivity.filesDir, "main_content.json")
                if (cacheFile.exists()) {
                    android.util.Log.d("WebGameActivity", "Returning cached config")
                    cacheFile.readText()
                } else {
                    // Fallback to assets based on current profile
                    val profile = SettingsManager.readProfile(this@WebGameActivity) ?: "AM"
                    val configFileName = "${profile}_config.json"
                    android.util.Log.d("WebGameActivity", "Loading config from assets: $configFileName")
                    val inputStream: InputStream = assets.open("config/$configFileName")
                    val size = inputStream.available()
                    val buffer = ByteArray(size)
                    inputStream.read(buffer)
                    inputStream.close()
                    String(buffer, Charset.forName("UTF-8"))
                }
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error loading config JSON", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun openCamera(successCallback: String, errorCallback: String) {
            android.util.Log.d("WebGameActivity", "JavaScript called openCamera() with callbacks: $successCallback, $errorCallback")
            runOnUiThread {
                try {
                    // Store callbacks for later use
                    cameraSuccessCallback = successCallback
                    cameraErrorCallback = errorCallback
                    android.util.Log.d("WebGameActivity", "Stored callbacks: success=$successCallback, error=$errorCallback")
                    
                    // Check camera permission
                    val hasPermission = ContextCompat.checkSelfPermission(
                        this@WebGameActivity,
                        android.Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    
                    android.util.Log.d("WebGameActivity", "Camera permission granted: $hasPermission")
                    
                    if (hasPermission) {
                        android.util.Log.d("WebGameActivity", "Launching camera...")
                        launchCamera()
                    } else {
                        android.util.Log.d("WebGameActivity", "Requesting camera permission...")
                        // Request camera permission
                        ActivityCompat.requestPermissions(
                            this@WebGameActivity,
                            arrayOf(android.Manifest.permission.CAMERA),
                            CAMERA_PERMISSION_REQUEST_CODE
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebGameActivity", "Error in openCamera", e)
                    errorCallback.let { callback ->
                        webView.evaluateJavascript(
                            "if (typeof $callback === 'function') $callback('Error: ${e.message}');",
                            null
                        )
                    }
                    cameraSuccessCallback = null
                    cameraErrorCallback = null
                }
            }
        }

        @JavascriptInterface
        fun getCurrentProfile(): String {
            return SettingsManager.readProfile(this@WebGameActivity) ?: "AM"
        }

        @JavascriptInterface
        fun uploadImageToSupabase(jsonData: String, successCallback: String, errorCallback: String) {
            android.util.Log.d("WebGameActivity", "JavaScript called uploadImageToSupabase()")
            android.util.Log.d("WebGameActivity", "Success callback: $successCallback, Error callback: $errorCallback")
            
            // Run upload in background thread
            Thread {
                try {
                    // Parse JSON data
                    val uploadData = JSONObject(jsonData)
                    val profile = uploadData.getString("profile")
                    val task = uploadData.getString("task")
                    val imageBase64 = uploadData.getString("image")
                    val captureDateTime = uploadData.getString("capture_date_time")
                    
                    android.util.Log.d("WebGameActivity", "Uploading image for profile: $profile, task: $task")
                    
                    // Remove data:image/jpeg;base64, prefix if present
                    val base64Image = if (imageBase64.startsWith("data:image")) {
                        imageBase64.substring(imageBase64.indexOf(",") + 1)
                    } else {
                        imageBase64
                    }
                    
                    // Get Supabase configuration from BuildConfig (same package, direct access)
                    val supabaseUrl = try {
                        BuildConfig.SUPABASE_URL
                    } catch (e: Exception) {
                        android.util.Log.e("WebGameActivity", "Supabase URL not configured", e)
                        runOnUiThread {
                            val escapedCallback = errorCallback.replace("\\", "\\\\").replace("'", "\\'")
                            webView.evaluateJavascript(
                                "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']('Supabase not configured');",
                                null
                            )
                        }
                        return@Thread
                    }
                    
                    val supabaseKey = try {
                        BuildConfig.SUPABASE_KEY
                    } catch (e: Exception) {
                        android.util.Log.e("WebGameActivity", "Supabase key not configured", e)
                        runOnUiThread {
                            val escapedCallback = errorCallback.replace("\\", "\\\\").replace("'", "\\'")
                            webView.evaluateJavascript(
                                "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']('Supabase not configured');",
                                null
                            )
                        }
                        return@Thread
                    }
                    
                    if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
                        android.util.Log.e("WebGameActivity", "Supabase URL or key is blank")
                        runOnUiThread {
                            val escapedCallback = errorCallback.replace("\\", "\\\\").replace("'", "\\'")
                            webView.evaluateJavascript(
                                "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']('Supabase not configured');",
                                null
                            )
                        }
                        return@Thread
                    }
                    
                    // Create JSON body for UPSERT (Supabase will merge on conflict with unique constraint)
                    val requestBody = JSONObject().apply {
                        put("profile", profile)
                        put("task", task)
                        put("image", base64Image)
                        put("capture_date_time", captureDateTime)
                    }
                    
                    // Use UPSERT: POST with Prefer: resolution=merge-duplicates
                    // This will INSERT if new, UPDATE if (profile, task) already exists
                    val url = "$supabaseUrl/rest/v1/image_uploads"
                    val mediaType = "application/json".toMediaType()
                    val body = requestBody.toString().toRequestBody(mediaType)
                    
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("apikey", supabaseKey)
                        .addHeader("Authorization", "Bearer $supabaseKey")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "resolution=merge-duplicates")
                        .build()
                    
                    android.util.Log.d("WebGameActivity", "Sending POST request to: $url")
                    
                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string()
                    
                    android.util.Log.d("WebGameActivity", "Upload response code: ${response.code}")
                    
                    if (response.isSuccessful) {
                        android.util.Log.d("WebGameActivity", "Upload successful")
                        runOnUiThread {
                            val escapedCallback = successCallback.replace("\\", "\\\\").replace("'", "\\'")
                            webView.evaluateJavascript(
                                "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']();",
                                null
                            )
                        }
                    } else {
                        android.util.Log.e("WebGameActivity", "Upload failed: ${response.code}, body: $responseBody")
                        val errorMsg = (responseBody ?: "Upload failed with code ${response.code}").replace("'", "\\'").replace("\"", "\\\"")
                        runOnUiThread {
                            val escapedCallback = errorCallback.replace("\\", "\\\\").replace("'", "\\'")
                            webView.evaluateJavascript(
                                "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']('$errorMsg');",
                                null
                            )
                        }
                    }
                    response.close()
                    
                } catch (e: Exception) {
                    android.util.Log.e("WebGameActivity", "Error uploading image", e)
                    runOnUiThread {
                        val errorMsg = (e.message ?: "Unknown error").replace("'", "\\'").replace("\"", "\\\"")
                        val escapedCallback = errorCallback.replace("\\", "\\\\").replace("'", "\\'")
                        webView.evaluateJavascript(
                            "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']('$errorMsg');",
                            null
                        )
                    }
                }
            }.start()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            android.util.Log.e("WebGameActivity", "TTS initialization failed")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        android.util.Log.d("WebGameActivity", "onRequestPermissionsResult: requestCode=$requestCode")
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            android.util.Log.d("WebGameActivity", "Camera permission granted: $granted")
            if (granted) {
                launchCamera()
            } else {
                // Permission denied - notify JavaScript
                android.util.Log.e("WebGameActivity", "Camera permission denied")
                cameraErrorCallback?.let { callback ->
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if (typeof window.$callback === 'function') window.$callback('Camera permission denied'); else console.error('Callback function $callback not found');",
                            null
                        )
                    }
                }
                cameraSuccessCallback = null
                cameraErrorCallback = null
                Toast.makeText(this, "Camera permission is required to take pictures", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        android.util.Log.d("WebGameActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                android.util.Log.d("WebGameActivity", "Camera result OK, extracting bitmap")
                val imageBitmap = data?.extras?.get("data") as? Bitmap
                if (imageBitmap != null) {
                    android.util.Log.d("WebGameActivity", "Bitmap extracted: ${imageBitmap.width}x${imageBitmap.height}")
                    // Convert bitmap to base64
                    val base64 = bitmapToBase64(imageBitmap)
                    android.util.Log.d("WebGameActivity", "Bitmap converted to base64, length: ${base64.length}")
                    // Call JavaScript success callback
                    cameraSuccessCallback?.let { callback ->
                        runOnUiThread {
                            android.util.Log.d("WebGameActivity", "Calling success callback: $callback")
                            // Escape the base64 string for JavaScript - need to escape JSON properly
                            val jsonBase64 = base64.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            webView.evaluateJavascript(
                                "if (typeof window.$callback === 'function') window.$callback('data:image/jpeg;base64,$jsonBase64'); else console.error('Callback function $callback not found');",
                                null
                            )
                        }
                    } ?: run {
                        android.util.Log.e("WebGameActivity", "No success callback stored!")
                    }
                } else {
                    android.util.Log.e("WebGameActivity", "No bitmap in camera result")
                    // No image captured
                    cameraErrorCallback?.let { callback ->
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "if (typeof window.$callback === 'function') window.$callback('No image captured'); else console.error('Callback function $callback not found');",
                                null
                            )
                        }
                    }
                }
            } else {
                android.util.Log.d("WebGameActivity", "Camera result cancelled or error: $resultCode")
                // User cancelled or error
                cameraErrorCallback?.let { callback ->
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if (typeof window.$callback === 'function') window.$callback('Camera cancelled'); else console.error('Callback function $callback not found');",
                            null
                        )
                    }
                }
            }
            // Clear callbacks
            cameraSuccessCallback = null
            cameraErrorCallback = null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun launchCamera() {
        android.util.Log.d("WebGameActivity", "launchCamera() called")
        
        // Try ACTION_IMAGE_CAPTURE first
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        
        // Add explicit flags to ensure it works on all devices
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        
        // List all apps that can handle camera intents for debugging
        val cameraActivities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        android.util.Log.d("WebGameActivity", "Found ${cameraActivities.size} camera apps")
        cameraActivities.forEachIndexed { index, resolveInfo ->
            android.util.Log.d("WebGameActivity", "Camera app $index: ${resolveInfo.activityInfo.packageName}/${resolveInfo.activityInfo.name}")
        }
        
        val resolved = intent.resolveActivity(packageManager)
        android.util.Log.d("WebGameActivity", "Camera intent resolved: ${resolved != null}, component: ${resolved?.className}")
        
        if (resolved != null) {
            android.util.Log.d("WebGameActivity", "Starting camera activity for result with component: ${resolved.className}")
            try {
                startActivityForResult(intent, CAMERA_REQUEST_CODE)
                android.util.Log.d("WebGameActivity", "Camera activity started successfully")
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error starting camera activity", e)
                handleCameraError("Failed to launch camera: ${e.message}")
            }
        } else {
            android.util.Log.e("WebGameActivity", "No camera app available - resolved activity is null")
            handleCameraError("No camera app available. Please install a camera app.")
        }
    }
    
    private fun handleCameraError(message: String) {
        val errorCallback = cameraErrorCallback
        cameraSuccessCallback = null
        cameraErrorCallback = null
        
        runOnUiThread {
            android.util.Log.d("WebGameActivity", "Handling camera error, calling callback: $errorCallback")
            if (errorCallback != null) {
                // Properly escape the callback name and error message for JavaScript
                val escapedCallback = errorCallback.replace("\\", "\\\\").replace("'", "\\'")
                val escapedMsg = message.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                
                val jsCode = """
                    if (typeof window['$escapedCallback'] === 'function') {
                        window['$escapedCallback']('$escapedMsg');
                    } else {
                        console.error('Callback function $escapedCallback not found on window object');
                    }
                """.trimIndent()
                
                android.util.Log.d("WebGameActivity", "Executing JavaScript error callback")
                webView.evaluateJavascript(jsCode, null)
            } else {
                android.util.Log.e("WebGameActivity", "No error callback stored!")
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        // End time tracking if not already ended
        if (!gameCompleted) {
            timeTracker.endActivity("webgame")
        }
        
        tts.stop()
        tts.shutdown()
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }
}
