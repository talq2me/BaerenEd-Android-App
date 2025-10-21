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
 * Activity for playing YouTube playlists with custom controls and overlay blocker
 * Prevents kids from accessing YouTube's interface while providing safe playlist navigation
 */
class YouTubePlaylistPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_PLAYLIST_TITLE = "playlist_title"
    }

    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var errorLayout: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var customControlsLayout: LinearLayout
    private lateinit var prevButton: Button
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var nextButton: Button

    private var playlistId: String? = null
    private var playlistTitle: String? = null
    private lateinit var timeTracker: TimeTracker

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_playlist_player)

        // Get playlist information from intent
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        playlistTitle = intent.getStringExtra(EXTRA_PLAYLIST_TITLE)

        if (playlistId.isNullOrEmpty()) {
            Log.e("YouTubePlaylistPlayerActivity", "No playlist ID provided")
            finish()
            return
        }

        // Initialize time tracker
        timeTracker = TimeTracker(this)

        // Start time tracking for this playlist
        timeTracker.startActivity(playlistId ?: "unknown_playlist", "youtube", playlistTitle ?: "YouTube Playlist")

        // Initialize views
        webView = findViewById(R.id.playlist_webview)
        loadingLayout = findViewById(R.id.loading_layout)
        errorLayout = findViewById(R.id.error_layout)
        retryButton = findViewById(R.id.retry_button)
        backButton = findViewById(R.id.back_button)
        customControlsLayout = findViewById(R.id.custom_controls)
        prevButton = findViewById(R.id.prev_button)
        playButton = findViewById(R.id.play_button)
        pauseButton = findViewById(R.id.pause_button)
        nextButton = findViewById(R.id.next_button)

        // Set up click listeners
        retryButton.setOnClickListener {
            showLoading()
            loadPlaylist()
        }

        backButton.setOnClickListener {
            finish()
        }

        // Set up custom control listeners
        setupCustomControls()

        // Configure WebView for YouTube playlist playback
        setupWebView()

        // Load the playlist
        loadPlaylist()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    showPlayer()
                    // Inject playlist player script after page loads
                    injectPlaylistPlayerScript()
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    showError("Failed to load playlist: $description")
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

            // Enable JavaScript for YouTube playlist embed and custom controls
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
                fun onPlaylistReady() {
                    runOnUiThread {
                        showCustomControls()
                    }
                }

                @android.webkit.JavascriptInterface
                fun onVideoEnd() {
                    // Could implement auto-advance or other logic here
                    Log.d("YouTubePlaylistPlayerActivity", "Video ended")
                }
            }, "AndroidBridge")

            // Hide WebView initially
            visibility = View.GONE
        }
    }

    private fun loadPlaylist() {
        // Create a simple HTML page that will load the YouTube playlist
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
                    #playlistPlayer {
                        width: 100vw;
                        height: 100vh;
                        border: none;
                    }
                </style>
            </head>
            <body>
                <div id="playlistPlayer"></div>
                <script src="https://www.youtube.com/iframe_api"></script>
                <script>
                    let playlistYTPlayer;

                    function onYouTubeIframeAPIReady() {
                        createYTPlaylistPlayer('$playlistId');
                    }

                    function createYTPlaylistPlayer(playlistId) {
                        playlistYTPlayer = new YT.Player('playlistPlayer', {
                            height: '100%',
                            width: '100%',
                            playerVars: {
                                autoplay: 1,
                                rel: 0,
                                controls: 0,
                                modestbranding: 1,
                                enablejsapi: 1,
                                listType: 'playlist',
                                list: playlistId,
                                loop: 0
                            },
                            events: {
                                'onReady': function (event) {
                                    console.log('Playlist player ready');
                                    // Notify Android that player is ready
                                    window.AndroidBridge.onPlaylistReady();
                                    // Make player globally accessible
                                    window.playlistYTPlayer = event.target;
                                },
                                'onStateChange': function (event) {
                                    console.log('Playlist state changed:', event.data);
                                    if (event.data === YT.PlayerState.ENDED) {
                                        window.AndroidBridge.onVideoEnd();
                                    }
                                }
                            }
                        });
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://www.youtube.com", htmlContent, "text/html", "UTF-8", null)
    }

    private fun injectPlaylistPlayerScript() {
        // Wait for the page to load, then inject our custom control script
        webView.postDelayed({
            val script = """
                (function() {
                    console.log('Injecting playlist player controls');

                    // Wait for YouTube API and player to be ready
                    function checkPlayerReady() {
                        if (window.playlistYTPlayer && window.playlistYTPlayer.playVideo) {
                            console.log('YouTube player is ready');
                            // Ensure player is accessible globally
                            window.youtubePlayerReady = true;
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

    private fun setupCustomControls() {
        // Set up click listeners for custom control buttons
        prevButton.setOnClickListener {
            webView.evaluateJavascript("javascript:if(window.playlistYTPlayer && window.playlistYTPlayer.previousVideo) window.playlistYTPlayer.previousVideo();", null)
        }

        playButton.setOnClickListener {
            webView.evaluateJavascript("javascript:if(window.playlistYTPlayer && window.playlistYTPlayer.playVideo) window.playlistYTPlayer.playVideo();", null)
        }

        pauseButton.setOnClickListener {
            webView.evaluateJavascript("javascript:if(window.playlistYTPlayer && window.playlistYTPlayer.pauseVideo) window.playlistYTPlayer.pauseVideo();", null)
        }

        nextButton.setOnClickListener {
            webView.evaluateJavascript("javascript:if(window.playlistYTPlayer && window.playlistYTPlayer.nextVideo) window.playlistYTPlayer.nextVideo();", null)
        }

        // Update button states based on player state
        updateControlStates()
    }

    private fun updateControlStates() {
        // This would be called from JavaScript to update button states
        // For now, just ensure controls are visible
        customControlsLayout.visibility = View.VISIBLE
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
    }

    private fun showCustomControls() {
        customControlsLayout.visibility = View.VISIBLE
    }

    private fun showError(errorMessage: String) {
        Log.e("YouTubePlaylistPlayerActivity", errorMessage)
        loadingLayout.visibility = View.GONE
        webView.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        customControlsLayout.visibility = View.GONE
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
