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
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import android.util.Base64
import android.net.Uri
import android.graphics.BitmapFactory

class WebGameActivity : AppCompatActivity() {

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

    private var webView: WebView? = null
    private lateinit var timeTracker: TimeTracker
    private lateinit var progressManager: DailyProgressManager
    private var gameCompleted = false
    private var taskId: String? = null
    private var sectionId: String? = null
    private var stars: Int = 0
    private var taskTitle: String? = null
    private var cameraSuccessCallback: String? = null
    private var cameraErrorCallback: String? = null
    private var cameraImageFile: File? = null // Store file path for full resolution image
    
    // HTTP client for fetching JSON from GitHub
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lightweight layout (no WebView) so onCreate returns quickly and the previous
        // activity can process FocusEvent, avoiding ANR when launching bonus games.
        setContentView(R.layout.activity_web_game)

        val gameUrl = intent.getStringExtra(EXTRA_GAME_URL)
        taskId = intent.getStringExtra(EXTRA_TASK_ID)
        sectionId = intent.getStringExtra(EXTRA_SECTION_ID)
        stars = intent.getIntExtra(EXTRA_STARS, 0)
        taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)

        timeTracker = TimeTracker(this)
        progressManager = DailyProgressManager(this)
        val currentTaskId = taskId
        val currentSectionId = sectionId
        val uniqueTaskId = if (currentTaskId != null && currentSectionId != null) {
            progressManager.getUniqueTaskId(currentTaskId, currentSectionId)
        } else {
            currentTaskId ?: "webgame"
        }
        val gameName = taskTitle ?: taskId ?: "Web Game"
        timeTracker.startActivity(uniqueTaskId, "webgame", gameName)

        if (gameUrl.isNullOrEmpty()) {
            Toast.makeText(this, "No game URL provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Defer WebView creation and TTS init to next frame so onCreate returns immediately.
        // WebView first-time init and TTS engine startup are heavy and were blocking the
        // main thread long enough to cause "Input dispatching timed out" ANR in TrainingMapActivity.
        val container = findViewById<android.view.ViewGroup>(R.id.webview_container)
        container.post {
            if (isFinishing) return@post
            setupWebViewAndLoad(container, gameUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebViewAndLoad(container: android.view.ViewGroup, gameUrl: String) {
        val wv = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        webView = wv
        container.addView(wv)

        val ws: WebSettings = wv.settings
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
        wv.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress == 100) {
                    android.util.Log.d("WebGameActivity", "Game page fully loaded")
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                android.util.Log.d("WebGameActivity", "Game page finished loading: $url")
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

        // Use shared TtsManager; listener forwards onDone/onError to JS (onTTSFinished)
        val webGameTtsListener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    if (utteranceId != null && utteranceId.startsWith("tts_callback_")) {
                        webView?.evaluateJavascript("if (window.onTTSFinished) window.onTTSFinished('$utteranceId');", null)
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    if (utteranceId != null && utteranceId.startsWith("tts_callback_")) {
                        webView?.evaluateJavascript("if (window.onTTSFinished) window.onTTSFinished('$utteranceId');", null)
                    }
                }
            }
        }
        TtsManager.setOnUtteranceProgressListener(webGameTtsListener)

        android.util.Log.d("WebGameActivity", "Loading URL: $gameUrl")
        wv.addJavascriptInterface(WebGameInterface(taskId), "Android")
        wv.loadUrl(gameUrl)
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
        /** 2-arg overload for games that only pass correct/incorrect (e.g. Time Telling). WebView bridge matches on exact signature, so gameCompleted(1, 0) would otherwise "Method not found". */
        @JavascriptInterface
        fun gameCompleted(correctAnswers: Int, incorrectAnswers: Int) {
            gameCompleted(correctAnswers, incorrectAnswers, -1)
        }

        /** Called when the web game completes. Pass finalIndex (0-based) so game_indices are saved for cloud sync. */
        @JavascriptInterface
        fun gameCompleted(correctAnswers: Int, incorrectAnswers: Int, finalIndex: Int) {
            android.util.Log.d("WebGameActivity", "JavaScript called gameCompleted() with correct: $correctAnswers, incorrect: $incorrectAnswers, finalIndex: $finalIndex")
            // JavascriptInterface methods run on a WebView worker thread, so we must use runOnUiThread
            // for Activity methods like setResult() and finish()
            runOnUiThread {
                // Save game index before finishing so game_indices and last_updated sync to cloud (same as GameActivity)
                if (taskId != null && finalIndex >= 0) {
                    val webGameProgress = WebGameProgress(this@WebGameActivity, taskId)
                    webGameProgress.saveIndex(finalIndex)
                    android.util.Log.d("WebGameActivity", "Saved game index $finalIndex for $taskId (game_indices)")
                }
                timeTracker.updateAnswerCounts("webgame", correctAnswers, incorrectAnswers)
                finishGameWithResult(taskId)
            }
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
            val utteranceId = "tts_callback_${System.currentTimeMillis()}_${text.hashCode()}"
            TtsManager.speak(text, locale, TextToSpeech.QUEUE_FLUSH, utteranceId)
        }

        @JavascriptInterface
        fun loadJsonFile(fileName: String): String {
            val cacheFile = File(this@WebGameActivity.cacheDir, fileName)

            // 1. Try fetching from GitHub first (cache-bust + force network so tablets always get latest)
            try {
                val url = "https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/data/$fileName?nocache=${System.currentTimeMillis()}"
                val request = Request.Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
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
                        webView?.evaluateJavascript(
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
                            webView?.evaluateJavascript(
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
                            webView?.evaluateJavascript(
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
                            webView?.evaluateJavascript(
                                "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']('Supabase not configured');",
                                null
                            )
                        }
                        return@Thread
                    }
                    
                    // Create JSON body for UPSERT
                    val requestBody = JSONObject().apply {
                        put("profile", profile)
                        put("task", task)
                        put("image", base64Image)
                        put("capture_date_time", captureDateTime)
                    }
                    
                    val mediaType = "application/json".toMediaType()
                    val body = requestBody.toString().toRequestBody(mediaType)
                    
                    android.util.Log.d("WebGameActivity", "Uploading image (profile=$profile, task=$task)")
                    
                    // Try PATCH first (UPDATE if exists)
                    val patchUrl = "$supabaseUrl/rest/v1/image_uploads?profile=eq.$profile&task=eq.$task"
                    val patchRequest = Request.Builder()
                        .url(patchUrl)
                        .patch(body)
                        .addHeader("apikey", supabaseKey)
                        .addHeader("Authorization", "Bearer $supabaseKey")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "return=representation")
                        .build()
                    
                    android.util.Log.d("WebGameActivity", "Trying PATCH first: $patchUrl")
                    var response = httpClient.newCall(patchRequest).execute()
                    val responseBody = response.body?.string()
                    android.util.Log.d("WebGameActivity", "PATCH response code: ${response.code}")
                    
                    // If PATCH returns 404 or 0 rows updated, do INSERT
                    if (response.code == 404 || (response.isSuccessful && responseBody != null && responseBody == "[]")) {
                        android.util.Log.d("WebGameActivity", "Record doesn't exist, doing INSERT")
                        response.close()
                        
                        // INSERT new record
                        val postUrl = "$supabaseUrl/rest/v1/image_uploads"
                        val postRequest = Request.Builder()
                            .url(postUrl)
                            .post(body)
                            .addHeader("apikey", supabaseKey)
                            .addHeader("Authorization", "Bearer $supabaseKey")
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Prefer", "return=representation")
                            .build()
                        
                        android.util.Log.d("WebGameActivity", "Sending POST request: $postUrl")
                        response = httpClient.newCall(postRequest).execute()
                        val postResponseBody = response.body?.string()
                        android.util.Log.d("WebGameActivity", "POST response code: ${response.code}")
                        
                        if (response.isSuccessful) {
                            android.util.Log.d("WebGameActivity", "INSERT successful")
                            runOnUiThread {
                                val escapedCallback = successCallback.replace("\\", "\\\\").replace("'", "\\'")
                                webView?.evaluateJavascript(
                                    "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']();",
                                    null
                                )
                            }
                        } else {
                            android.util.Log.e("WebGameActivity", "INSERT failed: ${response.code}, body: $postResponseBody")
                            val errorMsg = (postResponseBody ?: "Upload failed with code ${response.code}").replace("'", "\\'").replace("\"", "\\\"")
                            runOnUiThread {
                                val escapedCallback = errorCallback.replace("\\", "\\\\").replace("'", "\\'")
                                webView?.evaluateJavascript(
                                    "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']('$errorMsg');",
                                    null
                                )
                            }
                        }
                    } else if (response.isSuccessful) {
                        // PATCH succeeded - record was updated
                        android.util.Log.d("WebGameActivity", "UPDATE successful")
                        runOnUiThread {
                            val escapedCallback = successCallback.replace("\\", "\\\\").replace("'", "\\'")
                            webView?.evaluateJavascript(
                                "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']();",
                                null
                            )
                        }
                    } else {
                        // PATCH failed for some other reason
                        android.util.Log.e("WebGameActivity", "PATCH failed: ${response.code}, body: $responseBody")
                        val errorMsg = (responseBody ?: "Upload failed with code ${response.code}").replace("'", "\\'").replace("\"", "\\\"")
                        runOnUiThread {
                            val escapedCallback = errorCallback.replace("\\", "\\\\").replace("'", "\\'")
                            webView?.evaluateJavascript(
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
                        webView?.evaluateJavascript(
                            "if (typeof window['$escapedCallback'] === 'function') window['$escapedCallback']('$errorMsg');",
                            null
                        )
                    }
                }
            }.start()
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
                        webView?.evaluateJavascript(
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
                android.util.Log.d("WebGameActivity", "Camera result OK, reading full resolution image from file")
                
                // Read full resolution image from file
                val imageFile = cameraImageFile
                android.util.Log.d("WebGameActivity", "Checking image file: path=${imageFile?.absolutePath}, exists=${imageFile?.exists()}, length=${imageFile?.length()}")
                
                if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {
                    try {
                        // Try reading directly from file first (simpler, more reliable)
                        var imageBitmap: Bitmap? = null
                        try {
                            android.util.Log.d("WebGameActivity", "Attempting to read bitmap directly from file")
                            imageBitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                        } catch (e: Exception) {
                            android.util.Log.w("WebGameActivity", "Direct file read failed, trying FileProvider", e)
                            // Fallback to FileProvider method
                            val imageUri = FileProvider.getUriForFile(
                                this,
                                "${packageName}.fileprovider",
                                imageFile
                            )
                            val inputStream = contentResolver.openInputStream(imageUri)
                            imageBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                        }
                        
                        if (imageBitmap != null) {
                            android.util.Log.d("WebGameActivity", "Full resolution bitmap loaded: ${imageBitmap.width}x${imageBitmap.height}")
                            // Convert bitmap to base64 (full resolution)
                            val base64 = bitmapToBase64(imageBitmap)
                            android.util.Log.d("WebGameActivity", "Bitmap converted to base64, length: ${base64.length}")
                            
                            // Clean up - delete temporary file after reading
                            try {
                                imageFile.delete()
                                android.util.Log.d("WebGameActivity", "Temporary camera file deleted")
                            } catch (e: Exception) {
                                android.util.Log.w("WebGameActivity", "Could not delete temp file", e)
                            }
                            
                            // Call JavaScript success callback
                            cameraSuccessCallback?.let { callback ->
                                runOnUiThread {
                                    android.util.Log.d("WebGameActivity", "Calling success callback: $callback")
                                    val jsonBase64 = base64.replace("\\", "\\\\")
                                        .replace("\"", "\\\"")
                                        .replace("\n", "\\n")
                                        .replace("\r", "\\r")
                                    webView?.evaluateJavascript(
                                        "if (typeof window.$callback === 'function') window.$callback('data:image/jpeg;base64,$jsonBase64'); else console.error('Callback function $callback not found');",
                                        null
                                    )
                                }
                            } ?: run {
                                android.util.Log.e("WebGameActivity", "No success callback stored!")
                            }
                        } else {
                            android.util.Log.e("WebGameActivity", "Could not decode bitmap from file")
                            // Clean up file
                            try { imageFile.delete() } catch (e: Exception) {}
                            cameraErrorCallback?.let { callback ->
                                runOnUiThread {
                                    webView?.evaluateJavascript(
                                        "if (typeof window.$callback === 'function') window.$callback('Could not read image file'); else console.error('Callback function $callback not found');",
                                        null
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WebGameActivity", "Error reading image file", e)
                        // Clean up file
                        try { imageFile.delete() } catch (ex: Exception) {}
                        cameraErrorCallback?.let { callback ->
                            runOnUiThread {
                                val errorMsg = (e.message ?: "Error reading image").replace("'", "\\'")
                                webView?.evaluateJavascript(
                                    "if (typeof window.$callback === 'function') window.$callback('$errorMsg'); else console.error('Callback function $callback not found');",
                                    null
                                )
                            }
                        }
                    }
                } else {
                    android.util.Log.e("WebGameActivity", "Image file not found or not created. File: ${imageFile?.absolutePath}, Exists: ${imageFile?.exists()}, Length: ${imageFile?.length()}")
                    // Try fallback: check if camera saved to a different location or use thumbnail from Intent
                    val thumbnail = data?.extras?.get("data") as? Bitmap
                    if (thumbnail != null) {
                        android.util.Log.d("WebGameActivity", "Using thumbnail as fallback: ${thumbnail.width}x${thumbnail.height}")
                        val base64 = bitmapToBase64(thumbnail)
                        cameraSuccessCallback?.let { callback ->
                            runOnUiThread {
                                val jsonBase64 = base64.replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n")
                                    .replace("\r", "\\r")
                                webView?.evaluateJavascript(
                                    "if (typeof window.$callback === 'function') window.$callback('data:image/jpeg;base64,$jsonBase64'); else console.error('Callback function $callback not found');",
                                    null
                                )
                            }
                        }
                    } else {
                        cameraErrorCallback?.let { callback ->
                            runOnUiThread {
                                webView?.evaluateJavascript(
                                    "if (typeof window.$callback === 'function') window.$callback('Image file not found'); else console.error('Callback function $callback not found');",
                                    null
                                )
                            }
                        }
                    }
                }
            } else {
                android.util.Log.d("WebGameActivity", "Camera result cancelled or error: $resultCode")
                cameraImageFile?.let { file ->
                    try { file.delete() } catch (e: Exception) {}
                }
                cameraErrorCallback?.let { callback ->
                    runOnUiThread {
                        webView?.evaluateJavascript(
                            "if (typeof window.$callback === 'function') window.$callback('Camera cancelled'); else console.error('Callback function $callback not found');",
                            null
                        )
                    }
                }
            }
            // Clear callbacks and file reference
            cameraSuccessCallback = null
            cameraErrorCallback = null
            cameraImageFile = null
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
        
        // Create a file to store the full resolution image - use original working path
        try {
            val imageFile = File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), 
                "spelling_photo_${System.currentTimeMillis()}.jpg")
            
            // Create parent directories if they don't exist
            imageFile.parentFile?.mkdirs()
            
            android.util.Log.d("WebGameActivity", "Image file path: ${imageFile.absolutePath}")
            
            cameraImageFile = imageFile
            
            // Get URI for the file using FileProvider
            val imageUri = try {
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    imageFile
                )
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error getting FileProvider URI", e)
                cameraImageFile = null
                handleCameraError("Error setting up file provider: ${e.message}")
                return
            }
            
            android.util.Log.d("WebGameActivity", "FileProvider URI: $imageUri")
            
            // Create camera intent with full resolution output
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // Grant temporary permission to camera apps
            val resInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                grantUriPermission(packageName, imageUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
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
                    cameraImageFile = null
                    handleCameraError("Failed to launch camera: ${e.message}")
                }
            } else {
                android.util.Log.e("WebGameActivity", "No camera app available - resolved activity is null")
                cameraImageFile = null
                handleCameraError("No camera app available. Please install a camera app.")
            }
        } catch (e: Exception) {
            android.util.Log.e("WebGameActivity", "Error setting up camera file", e)
            cameraImageFile = null
            handleCameraError("Error setting up camera: ${e.message}")
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
                webView?.evaluateJavascript(jsCode, null)
            } else {
                android.util.Log.e("WebGameActivity", "No error callback stored!")
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (!gameCompleted) {
            timeTracker.endActivity("webgame")
        }
        TtsManager.stop()
        TtsManager.setOnUtteranceProgressListener(null)
        webView?.removeJavascriptInterface("Android")
        webView?.destroy()
        super.onDestroy()
    }
}
