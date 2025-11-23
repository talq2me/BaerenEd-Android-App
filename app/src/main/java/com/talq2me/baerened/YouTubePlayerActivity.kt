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
    private lateinit var timeTracker: TimeTracker
    private var taskId: String? = null
    private var sectionId: String? = null
    private var taskTitle: String? = null
    private var videoCompleted = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_player)

        webView = findViewById(R.id.yt_webview)
        
        // Get task information
        taskId = intent.getStringExtra("TASK_ID")
        taskTitle = intent.getStringExtra("TASK_TITLE")
        sectionId = intent.getStringExtra("SECTION_ID")
        
        // Initialize time tracker
        timeTracker = TimeTracker(this)
        
        // Use unique task ID that includes section info to track separately for required vs optional
        val progressManager = DailyProgressManager(this)
        val currentTaskId = taskId
        val currentSectionId = sectionId
        val uniqueTaskId = if (currentTaskId != null && currentSectionId != null) {
            progressManager.getUniqueTaskId(currentTaskId, currentSectionId)
        } else {
            currentTaskId ?: "video"
        }
        
        // Start tracking time for this video
        val videoName = taskTitle ?: "Video"
        timeTracker.startActivity(uniqueTaskId, "video", videoName)

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
            "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/html/singleVideoPlayerForAndroid.html?videoId=$videoId"
        } else if (!playlistId.isNullOrEmpty()) {
            "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/html/playlistVideoPlayerForAndroid.html?playlistId=$playlistId"
        } else {
            // End time tracking if we're finishing early
            if (::timeTracker.isInitialized) {
                timeTracker.endActivity("video")
            }
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

        // Mark video as completed and end time tracking
        videoCompleted = true
        if (::timeTracker.isInitialized) {
            timeTracker.endActivity("video")
        }

        // Get the task information from the intent
        val taskStars = intent.getIntExtra("TASK_STARS", 0)
        val videoFile = intent.getStringExtra("VIDEO_FILE")
        val videoIndex = intent.getIntExtra("VIDEO_INDEX", -1)
        val isSequential = intent.getBooleanExtra("IS_SEQUENTIAL", false)

        android.util.Log.d("YouTubePlayerActivity", "Task info - ID: $taskId, Title: $taskTitle, Stars: $taskStars, Sequential: $isSequential, VideoFile: $videoFile, Index: $videoIndex")

        // Set result with task completion data
        val resultIntent = Intent().apply {
            putExtra("TASK_ID", taskId ?: "")
            putExtra("TASK_TITLE", taskTitle ?: "")
            putExtra("TASK_STARS", taskStars)
            sectionId?.let { putExtra("SECTION_ID", it) }
            if (isSequential && videoFile != null && videoIndex != -1) {
                putExtra("VIDEO_FILE", videoFile)
                putExtra("VIDEO_INDEX", videoIndex)
            }
        }

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onBackPressed() {
        android.util.Log.d("YouTubePlayerActivity", "Back button pressed, finishing without completion")
        // End time tracking (video not completed)
        if (::timeTracker.isInitialized && !videoCompleted) {
            timeTracker.endActivity("video")
        }
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
        // End time tracking if not already ended
        if (::timeTracker.isInitialized && !videoCompleted) {
            timeTracker.endActivity("video")
        }
        
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
        super.onDestroy()
    }
}
