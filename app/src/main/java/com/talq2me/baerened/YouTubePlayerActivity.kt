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

class YouTubePlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_PLAYLIST_ID = "playlist_id"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_player)

        webView = findViewById(R.id.yt_webview)

        // read extras for single video
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)

        // webView setup
        val ws: WebSettings = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress == 100) {
                    android.util.Log.d("YouTubePlayerActivity", "Video page fully loaded")
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                android.util.Log.d("YouTubePlayerActivity", "Video page finished loading: $url")

                // Inject script to detect video end and handle completion
                // injectVideoEndDetectionScript() // Removed
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                android.util.Log.e("YouTubePlayerActivity", "Error loading video: $description (code: $errorCode)")
                Toast.makeText(this@YouTubePlayerActivity, "Error loading video: $description", Toast.LENGTH_LONG).show()
            }
        }

        // Use direct YouTube embed URL for simpler and more reliable playback
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)

        val url: String = if (!videoId.isNullOrEmpty()) {
            "https://talq2me.github.io/Baeren/BaerenEd/androidPages/singleVideoPlayerForAndroid.html?videoId=$videoId"
        } else if (!playlistId.isNullOrEmpty()) {
            "https://talq2me.github.io/Baeren/BaerenEd/androidPages/playlistVideoPlayerForAndroid.html?playlistId=$playlistId"
        } else {
            Toast.makeText(this, "No video or playlist ID provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        android.util.Log.d("YouTubePlayerActivity", "Loading URL: $url")
        webView.addJavascriptInterface(VideoInterface(), "Android")

        webView.loadUrl(url)

        // Add a manual completion button after video loads
    }

    // Removed injectVideoEndDetectionScript()

    // Removed addManualCompletionButton()

    private fun finishVideoWithResult() {
        android.util.Log.d("YouTubePlayerActivity", "Finishing video activity with success result")

        // Get the task information from the intent
        val taskId = intent.getStringExtra("TASK_ID")
        val taskTitle = intent.getStringExtra("TASK_TITLE")
        val taskStars = intent.getIntExtra("TASK_STARS", 0)

        android.util.Log.d("YouTubePlayerActivity", "Task info - ID: $taskId, Title: $taskTitle, Stars: $taskStars")

        // Set result with task completion data
        val resultIntent = Intent().apply {
            putExtra("TASK_ID", taskId ?: "")
            putExtra("TASK_TITLE", taskTitle ?: "")
            putExtra("TASK_STARS", taskStars)
        }

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onBackPressed() {
        android.util.Log.d("YouTubePlayerActivity", "Back button pressed, finishing without completion")
        // When user manually exits, don't treat it as completion
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    inner class VideoInterface {
        @JavascriptInterface
        fun finishVideo() {
            android.util.Log.d("YouTubePlayerActivity", "JavaScript called finishVideo()")
            finishVideoWithResult()
        }

        @JavascriptInterface
        fun closeActivity() {
            android.util.Log.d("YouTubePlayerActivity", "JavaScript called closeActivity()")
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
