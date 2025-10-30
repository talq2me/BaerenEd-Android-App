package com.talq2me.baerened

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WebGameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GAME_URL = "game_url"
        const val EXTRA_REWARD_ID = "reward_id"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_game)

        webView = findViewById(R.id.game_webview)

        val gameUrl = intent.getStringExtra(EXTRA_GAME_URL)
        val rewardId = intent.getStringExtra(EXTRA_REWARD_ID)

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

        if (!gameUrl.isNullOrEmpty()) {
            android.util.Log.d("WebGameActivity", "Loading URL: $gameUrl")
            webView.addJavascriptInterface(WebGameInterface(rewardId), "Android")
            webView.loadUrl(gameUrl)
        } else {
            Toast.makeText(this, "No game URL provided", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun finishGameWithResult(rewardId: String?) {
        android.util.Log.d("WebGameActivity", "Finishing game activity with success result for reward: $rewardId")

        // In a real application, you would integrate your reward system here
        // For now, we'll just return the rewardId
        val resultIntent = Intent().apply {
            putExtra(EXTRA_REWARD_ID, rewardId ?: "")
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onBackPressed() {
        android.util.Log.d("WebGameActivity", "Back button pressed, finishing without completion")
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    inner class WebGameInterface(private val rewardId: String?) {
        @JavascriptInterface
        fun gameCompleted() {
            android.util.Log.d("WebGameActivity", "JavaScript called gameCompleted()")
            finishGameWithResult(rewardId)
        }

        @JavascriptInterface
        fun closeGame() {
            android.util.Log.d("WebGameActivity", "JavaScript called closeGame()")
            runOnUiThread { // Ensure UI operations are on the main thread
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }
}
