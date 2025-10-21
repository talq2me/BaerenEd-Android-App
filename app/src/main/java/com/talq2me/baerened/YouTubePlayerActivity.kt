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
    private lateinit var customControlsLayout: LinearLayout
    private lateinit var replayButton: Button
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button

    private var videoId: String? = null
    private var videoTitle: String? = null
    private var autoReturnHandler: android.os.Handler? = null
    private lateinit var timeTracker: TimeTracker

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
        customControlsLayout = findViewById(R.id.custom_controls)
        replayButton = findViewById(R.id.replay_button)
        playButton = findViewById(R.id.play_button)
        pauseButton = findViewById(R.id.pause_button)

        // Initialize time tracker
        timeTracker = TimeTracker(this)

        // Start time tracking for this video
        timeTracker.startActivity(videoId ?: "unknown_video", "youtube", videoTitle ?: "YouTube Video")

        // Set up click listeners
        retryButton.setOnClickListener {
            showLoading()
            loadVideo()
        }

        backButton.setOnClickListener {
            finish()
        }

        // Set up custom control listeners
        setupCustomControls()

        // Configure WebView for YouTube playback
        setupWebView()

        // Load the video
        loadVideo()

        // Set up auto-return timer as fallback (15 minutes max video length)
        setupAutoReturnTimer()
    }

    private fun injectPlayerReadyScript() {
        // Wait for the page to load, then ensure player is accessible and show controls
        webView.postDelayed({
            val script = """
                (function() {
                    console.log('Ensuring YouTube player is accessible');

                    // Wait for YouTube API and player to be ready
                    function checkPlayerReady() {
                        if (window.ytPlayer && window.ytPlayer.playVideo) {
                            console.log('YouTube player is ready');
                            // Ensure player is accessible globally
                            window.youtubePlayerReady = true;
                            // Show custom controls
                            if (window.Android && window.Android.onVideoPlayerReady) {
                                window.Android.onVideoPlayerReady();
                            }
                        } else {
                            setTimeout(checkPlayerReady, 500);
                        }
                    }

                    // Start checking for player readiness
                    checkPlayerReady();
                })();
            """.trimIndent()

            webView.evaluateJavascript(script, null)
        }, 2000) // Wait 2 seconds for the page to load
    }

    private fun setupAutoReturnTimer() {
        autoReturnHandler = android.os.Handler()
        autoReturnHandler?.postDelayed({
            // Auto-return after 15 minutes as fallback
            finish()
        }, 15 * 60 * 1000L) // 15 minutes
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    showPlayer()
                    // Inject script to ensure player is ready after page loads
                    injectPlayerReadyScript()
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

            // Enable JavaScript for YouTube embed and API
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

            // Add JavaScript interface for communication
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onVideoPlayerReady() {
                    runOnUiThread {
                        showCustomControls()
                    }
                }

                @android.webkit.JavascriptInterface
                fun returnToMainScreen() {
                    runOnUiThread {
                        finish() // Return to main screen
                    }
                }
            }, "Android")

            // Prevent scrolling and zooming
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setOnTouchListener { _, _ -> true } // Disable touch interaction

            // Hide WebView initially
            visibility = View.GONE
        }
    }

    private fun loadVideo() {
        // Create a simple HTML page that will load the YouTube video using the API
        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        margin: 0;
                        padding: 0;
                        background: #000;
                        overflow: hidden;
                        font-family: Arial, sans-serif;
                    }
                    #videoPlayer {
                        width: 100vw;
                        height: 100vh;
                        border: none;
                    }
                </style>
            </head>
            <body>
                <div id="videoPlayer"></div>
                <script src="https://www.youtube.com/iframe_api"></script>
                <script>
                    let ytPlayer;

                    function onYouTubeIframeAPIReady() {
                        createYTPlayer('$videoId');
                    }

                    function createYTPlayer(videoId) {
                        ytPlayer = new YT.Player('videoPlayer', {
                            height: '100%',
                            width: '100%',
                            videoId: videoId,
                            playerVars: {
                                autoplay: 1,
                                rel: 0,
                                controls: 0,
                                modestbranding: 1,
                                enablejsapi: 1,
                                fs: 0,
                                iv_load_policy: 3,
                                cc_load_policy: 0,
                                playsinline: 1
                            },
                            events: {
                                'onReady': function (event) {
                                    console.log('Video player ready');
                                    // Notify Android that player is ready
                                    window.Android.onVideoPlayerReady();
                                },
                                'onStateChange': function (event) {
                                    console.log('Video state changed:', event.data);
                                    if (event.data === YT.PlayerState.ENDED) {
                                        console.log('Video ended, returning to main screen');
                                        window.Android.returnToMainScreen();
                                    }
                                }
                            }
                        });
                    }

                    // Make player available globally for custom controls
                    window.ytPlayer = ytPlayer;
                </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://www.youtube.com", htmlContent, "text/html", "UTF-8", null)
    }

    private fun setupCustomControls() {
        // Set up click listeners for custom control buttons
        replayButton.setOnClickListener {
            webView.evaluateJavascript("javascript:if(window.ytPlayer && window.ytPlayer.seekTo) window.ytPlayer.seekTo(0);", null)
        }

        playButton.setOnClickListener {
            webView.evaluateJavascript("javascript:if(window.ytPlayer && window.ytPlayer.playVideo) window.ytPlayer.playVideo();", null)
        }

        pauseButton.setOnClickListener {
            webView.evaluateJavascript("javascript:if(window.ytPlayer && window.ytPlayer.pauseVideo) window.ytPlayer.pauseVideo();", null)
        }
    }

    private fun showCustomControls() {
        // Use postDelayed to ensure the WebView is fully loaded
        customControlsLayout.postDelayed({
            customControlsLayout.visibility = View.VISIBLE
        }, 500)
    }


    private fun showLoading() {
        loadingLayout.visibility = View.VISIBLE
        webView.visibility = View.GONE
        errorLayout.visibility = View.GONE
        customControlsLayout.visibility = View.GONE
    }

    private fun showPlayer() {
        loadingLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
        // Show custom controls when player is displayed
        showCustomControls()
    }

    private fun showError(errorMessage: String) {
        Log.e("YouTubePlayerActivity", errorMessage)
        loadingLayout.visibility = View.GONE
        webView.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        customControlsLayout.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up auto-return timer
        autoReturnHandler?.removeCallbacksAndMessages(null)

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
        super.onBackPressed()
        // Override back button to return to app instead of navigating in WebView
        finish()
    }

}
