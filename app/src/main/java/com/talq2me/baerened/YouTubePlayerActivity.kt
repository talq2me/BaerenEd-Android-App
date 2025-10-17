package com.talq2me.baerened

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity for playing YouTube videos in an embedded WebView without YouTube UI
 * Uses YouTube's privacy-enhanced embed mode to avoid showing related videos and controls
 */
class YouTubePlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_VIDEO_TITLE = "video_title"
    }

    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var errorLayout: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var backButton: Button

    private var videoId: String? = null
    private var videoTitle: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_player)

        // Get video information from intent
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE)

        if (videoId.isNullOrEmpty()) {
            Log.e("YouTubePlayerActivity", "No video ID provided")
            finish()
            return
        }

        // Initialize views
        webView = findViewById(R.id.youtube_webview)
        loadingLayout = findViewById(R.id.loading_layout)
        errorLayout = findViewById(R.id.error_layout)
        retryButton = findViewById(R.id.retry_button)
        backButton = findViewById(R.id.back_button)

        // Set up click listeners
        retryButton.setOnClickListener {
            showLoading()
            loadVideo()
        }

        backButton.setOnClickListener {
            finish()
        }

        // Configure WebView for YouTube playback
        setupWebView()

        // Load the video
        loadVideo()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    showPlayer()
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    showError("Failed to load video: $description")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        showLoading()
                    } else {
                        showPlayer()
                    }
                }
            }

            // Enable JavaScript for YouTube embed
            settings.javaScriptEnabled = true

            // Configure settings for better video playback
            settings.apply {
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
                domStorageEnabled = true
            }

            // Prevent scrolling and zooming
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setOnTouchListener { _, _ -> true } // Disable touch interaction

            // Hide WebView initially
            visibility = View.GONE
        }
    }

    private fun loadVideo() {
        // Use YouTube's privacy-enhanced embed with minimal UI
        // This prevents showing related videos, comments, and suggested videos
        val embedUrl = "https://www.youtube.com/embed/$videoId?" +
                "autoplay=1&" +           // Auto-play the video
                "controls=0&" +           // Hide player controls
                "disablekb=1&" +          // Disable keyboard shortcuts
                "fs=0&" +                 // Hide fullscreen button
                "iv_load_policy=3&" +     // Hide video annotations
                "modestbranding=1&" +     // Hide YouTube logo
                "rel=0&" +                // Don't show related videos at the end
                "showinfo=0&" +           // Hide video info
                "cc_load_policy=0&" +     // Hide closed captions by default
                "playsinline=1"           // Play inline on mobile

        webView.loadUrl(embedUrl)
    }

    private fun showLoading() {
        loadingLayout.visibility = View.VISIBLE
        webView.visibility = View.GONE
        errorLayout.visibility = View.GONE
    }

    private fun showPlayer() {
        loadingLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
    }

    private fun showError(errorMessage: String) {
        Log.e("YouTubePlayerActivity", errorMessage)
        loadingLayout.visibility = View.GONE
        webView.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up WebView to prevent memory leaks
        webView.apply {
            stopLoading()
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            destroy()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Override back button to return to app instead of navigating in WebView
        finish()
    }
}
