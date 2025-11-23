package com.talq2me.baerened

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class WebGameActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_GAME_URL = "game_url"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SECTION_ID = "section_id"
        const val EXTRA_STARS = "stars"
        const val EXTRA_TASK_TITLE = "task_title"
    }

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private lateinit var timeTracker: TimeTracker
    private var gameCompleted = false
    private var taskId: String? = null
    private var sectionId: String? = null
    private var stars: Int = 0
    private var taskTitle: String? = null

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
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
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
