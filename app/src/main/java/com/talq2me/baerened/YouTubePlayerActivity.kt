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
    private var autoReturnHandler: android.os.Handler? = null

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

        // Set up auto-return timer as fallback (15 minutes max video length)
        setupAutoReturnTimer()
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
        // Use YouTube's privacy-enhanced embed with minimal UI and JavaScript API
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
                "playsinline=1&" +        // Play inline on mobile
                "enablejsapi=1"           // Enable JavaScript API for event detection

        webView.loadUrl(embedUrl)
    }

    private fun injectVideoEndDetectionScript() {
        // Wait for the page to load, then inject our script
        webView.postDelayed({
            val script = """
                (function() {
                    console.log('YouTube video end detection script injected');

                    // First, let's also try a simple approach - listen for when the video ends naturally
                    // by checking if the iframe src changes or if certain elements appear
                    function simpleVideoEndDetection() {
                        var iframe = document.querySelector('iframe[src*="youtube.com"]');
                        if (iframe) {
                            // Check every second if the video has ended (when autoplay stops)
                            var checkInterval = setInterval(function() {
                                try {
                                    // If the iframe is still there but no longer autoplaying, video likely ended
                                    var iframeSrc = iframe.src || '';
                                    if (iframeSrc.indexOf('autoplay=1') === -1) {
                                        console.log('Video autoplay stopped, likely ended');
                                        clearInterval(checkInterval);
                                        window.Android.returnToMainScreen();
                                    }
                                } catch (e) {
                                    clearInterval(checkInterval);
                                }
                            }, 1000);
                        }
                    }

                    // Also try the YouTube API approach
                    if (!window.YT) {
                        console.log('Loading YouTube API...');
                        var tag = document.createElement('script');
                        tag.src = "https://www.youtube.com/iframe_api";
                        var firstScriptTag = document.getElementsByTagName('script')[0];
                        firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                        window.onYouTubeIframeAPIReady = function() {
                            console.log('YouTube API ready, setting up player');
                            setupYouTubeAPIPlayer();
                        };
                    } else {
                        console.log('YouTube API already loaded');
                        setupYouTubeAPIPlayer();
                    }

                    function setupYouTubeAPIPlayer() {
                        console.log('Setting up YouTube API player');

                        // Function to find and setup the player
                        function findAndSetupPlayer() {
                            try {
                                // Look for the YouTube iframe
                                var iframe = document.querySelector('iframe[src*="youtube.com"]');
                                console.log('Found iframe:', iframe);

                                if (iframe) {
                                    console.log('Creating YT.Player instance');
                                    var player = new YT.Player(iframe, {
                                        events: {
                                            'onReady': function(event) {
                                                console.log('YouTube player ready');
                                            },
                                            'onStateChange': function(event) {
                                                console.log('Player state changed to:', event.data);
                                                console.log('YT.PlayerState constants:', {
                                                    UNSTARTED: YT.PlayerState.UNSTARTED,
                                                    ENDED: YT.PlayerState.ENDED,
                                                    PLAYING: YT.PlayerState.PLAYING,
                                                    PAUSED: YT.PlayerState.PAUSED,
                                                    BUFFERING: YT.PlayerState.BUFFERING,
                                                    CUED: YT.PlayerState.CUED
                                                });
                                                if (event.data === YT.PlayerState.ENDED) {
                                                    console.log('Video ended via API, returning to main screen');
                                                    window.Android.returnToMainScreen();
                                                }
                                            }
                                        }
                                    });
                                } else {
                                    console.log('Iframe not found, retrying...');
                                    setTimeout(findAndSetupPlayer, 500);
                                }
                            } catch (e) {
                                console.log('Error in setupYouTubeAPIPlayer: ' + e);
                                setTimeout(findAndSetupPlayer, 500);
                            }
                        }

                        // Start trying to find and setup the player
                        findAndSetupPlayer();
                    }

                    // Start the simple detection as backup
                    simpleVideoEndDetection();
                })();
            """.trimIndent()

            webView.evaluateJavascript(script, null)
        }, 3000) // Wait 3 seconds for the page to fully load
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
        // Inject video end detection script when player is shown
        injectVideoEndDetectionScript()
    }

    private fun showError(errorMessage: String) {
        Log.e("YouTubePlayerActivity", errorMessage)
        loadingLayout.visibility = View.GONE
        webView.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
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
        // Override back button to return to app instead of navigating in WebView
        super.onBackPressed()
        finish()
    }

}
