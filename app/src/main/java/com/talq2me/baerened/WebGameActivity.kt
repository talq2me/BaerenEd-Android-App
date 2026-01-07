package com.talq2me.baerened

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
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
import java.io.IOException
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale
import org.json.JSONArray
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WebGameActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_GAME_URL = "game_url"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SECTION_ID = "section_id"
        const val EXTRA_STARS = "stars"
        const val EXTRA_TASK_TITLE = "task_title"
        const val RESULT_LAUNCH_GAME = 100
        const val RESULT_EXTRA_GAME_ID = "game_id_to_launch"
    }

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private lateinit var timeTracker: TimeTracker
    private var gameCompleted = false
    private var taskId: String? = null
    private var sectionId: String? = null
    private var stars: Int = 0
    private var taskTitle: String? = null
    
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

        // Initialize time tracker
        timeTracker = TimeTracker(this)
        
        // Use unique task ID that includes section info to track separately for required vs optional
        val progressManager = DailyProgressManager(this)
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
                getSharedPreferences("pokemonBattleHub", MODE_PRIVATE)
                    .getInt("earnedBerries", 0)
            } catch (e: Exception) {
                android.util.Log.e("WebGameActivity", "Error getting earned berries", e)
                0
            }
        }

        @JavascriptInterface
        fun resetEarnedBerries() {
            try {
                getSharedPreferences("pokemonBattleHub", MODE_PRIVATE)
                    .edit()
                    .putInt("earnedBerries", 0)
                    .apply()
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
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            android.util.Log.e("WebGameActivity", "TTS initialization failed")
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
