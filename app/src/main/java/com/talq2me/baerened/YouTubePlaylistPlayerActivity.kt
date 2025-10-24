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
    private var playlistId: String? = null
    private var playlistTitle: String? = null

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

        // Initialize views
        webView = findViewById(R.id.playlist_webview)
        loadingLayout = findViewById(R.id.loading_layout)
        errorLayout = findViewById(R.id.error_layout)
        retryButton = findViewById(R.id.retry_button)
        backButton = findViewById(R.id.back_button)

        // Set up click listeners
        retryButton.setOnClickListener {
            showLoading()
            loadPlaylist()
        }

        backButton.setOnClickListener {
            finish()
        }

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
                    // Playlist is ready - no custom controls needed
                }

                @android.webkit.JavascriptInterface
                fun onVideoEnd() {
                    // Playlist video ended - just log for now
                    Log.d("YouTubePlaylistPlayerActivity", "Playlist video ended")
                }
            }, "AndroidBridge")

            // Hide WebView initially
            visibility = View.GONE
        }
    }

    private fun loadPlaylist() {
        // Use YouTube's playlist embed with minimal UI and JavaScript API
        val embedUrl = "https://www.youtube.com/embed/videoseries?" +
                "list=$playlistId&" +
                "autoplay=1&" +           // Auto-play the playlist
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

    private fun injectPlaylistEndDetectionScript() {
        // Wait for the page to load, then inject our script
        webView.postDelayed({
            val script = """
                (function() {
                    console.log('YouTube playlist end detection script injected');

                    // Simple approach - listen for when videos change or autoplay stops
                    function simplePlaylistDetection() {
                        var iframe = document.querySelector('iframe[src*="youtube.com"]');
                        if (iframe) {
                            // Check every few seconds if the playlist has ended
                            var checkInterval = setInterval(function() {
                                try {
                                    // If autoplay stops and no new video loads, playlist likely ended
                                    var iframeSrc = iframe.src || '';
                                    if (iframeSrc.indexOf('autoplay=1') === -1) {
                                        console.log('Playlist autoplay stopped, likely ended');
                                        clearInterval(checkInterval);
                                        window.AndroidBridge.onVideoEnd();
                                    }
                                } catch (e) {
                                    clearInterval(checkInterval);
                                }
                            }, 2000);
                        }
                    }

                    // Also try the YouTube API approach if available
                    if (!window.YT) {
                        console.log('Loading YouTube API for playlist...');
                        var tag = document.createElement('script');
                        tag.src = "https://www.youtube.com/iframe_api";
                        var firstScriptTag = document.getElementsByTagName('script')[0];
                        firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                        window.onYouTubeIframeAPIReady = function() {
                            console.log('YouTube API ready for playlist');
                            setupYouTubeAPIPlaylistPlayer();
                        };
                    } else {
                        console.log('YouTube API already loaded for playlist');
                        setupYouTubeAPIPlaylistPlayer();
                    }

                    function setupYouTubeAPIPlaylistPlayer() {
                        console.log('Setting up YouTube API playlist player');

                        function findAndSetupPlayer() {
                            try {
                                var iframe = document.querySelector('iframe[src*="youtube.com"]');
                                console.log('Found playlist iframe:', iframe);

                                if (iframe) {
                                    console.log('Creating YT.Player instance for playlist');
                                    var player = new YT.Player(iframe, {
                                        events: {
                                            'onReady': function(event) {
                                                console.log('YouTube playlist player ready');
                                                window.AndroidBridge.onPlaylistReady();
                                            },
                                            'onStateChange': function(event) {
                                                console.log('Playlist player state changed to:', event.data);
                                                if (event.data === YT.PlayerState.ENDED) {
                                                    console.log('Playlist ended via API');
                                                    window.AndroidBridge.onVideoEnd();
                                                }
                                            }
                                        }
                                    });
                                } else {
                                    console.log('Playlist iframe not found, retrying...');
                                    setTimeout(findAndSetupPlayer, 500);
                                }
                            } catch (e) {
                                console.log('Error in setupYouTubeAPIPlaylistPlayer: ' + e);
                                setTimeout(findAndSetupPlayer, 500);
                            }
                        }

                        findAndSetupPlayer();
                    }

                    // Start the simple detection as backup
                    simplePlaylistDetection();
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
        // Inject playlist end detection script when player is shown
        injectPlaylistEndDetectionScript()
    }

    private fun showError(errorMessage: String) {
        Log.e("YouTubePlaylistPlayerActivity", errorMessage)
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
        super.onBackPressed()
        finish()
    }

}
