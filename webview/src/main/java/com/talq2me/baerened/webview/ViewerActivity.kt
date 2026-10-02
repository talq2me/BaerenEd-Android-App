package com.talq2me.baerened.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

/**
 * Shows the BaerenEd website and speaks through the tablet text-to-speech engine.
 * The pages call Android.readText only when this bridge exists. A normal browser keeps its own voice.
 */
class ViewerActivity : Activity() {
    private var webView: WebView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this)
        webView = view
        setContentView(view)

        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val engine = tts ?: return@TextToSpeech
            engine.setSpeechRate(0.85f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null && utteranceId.startsWith("tts_callback_")) notifyPage()
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null && utteranceId.startsWith("tts_callback_")) notifyPage()
                }
            })
            ttsReady = true
            engine.setLanguage(Locale.US)
            val french = engine.setLanguage(Locale.FRENCH)
            if (french == TextToSpeech.LANG_AVAILABLE ||
                french == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                french == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) {
                engine.speak(" ", TextToSpeech.QUEUE_FLUSH, null, "tts_prewarm_fr")
            }
            engine.setLanguage(Locale.US)
        }

        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.settings.setSupportMultipleWindows(false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }
        }
        view.addJavascriptInterface(PageBridge(), "Android")
        view.loadUrl(getString(R.string.start_url))
    }

    @Deprecated("Kept for the system back button on older tablets.")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        webView?.removeJavascriptInterface("Android")
        webView = null
        super.onDestroy()
    }

    private fun notifyPage() {
        val script = """
            (function(){
              function ping(w){
                try {
                  if (w && typeof w.__baerenTtsDone === 'function') {
                    var fn = w.__baerenTtsDone;
                    w.__baerenTtsDone = null;
                    fn();
                    return true;
                  }
                } catch (e) {}
                return false;
              }
              if (ping(window)) return;
              var frames = document.querySelectorAll('iframe');
              for (var i = 0; i < frames.length; i++) {
                if (ping(frames[i].contentWindow)) return;
              }
            })();
        """.trimIndent()
        runOnUiThread {
            webView?.evaluateJavascript(script, null)
        }
    }

    private inner class PageBridge {
        @JavascriptInterface
        fun readText(text: String, lang: String) {
            readText(text, lang, "")
        }

        @JavascriptInterface
        fun readText(text: String, lang: String, rate: String) {
            val engine = tts
            if (engine == null || !ttsReady || text.isBlank()) {
                notifyPage()
                return
            }
            val locale = if (lang.lowercase().startsWith("fr")) Locale.FRENCH else Locale.US
            val parsed = rate.toFloatOrNull()?.takeIf { it in 0.1f..2.0f }
            engine.setSpeechRate(parsed ?: 0.85f)
            engine.setLanguage(locale)
            val utteranceId = "tts_callback_${System.currentTimeMillis()}"
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }
}
