package com.demonicmusichost.app.ui.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Manages a hidden WebView that runs the Spotify Web Playback SDK.
 *
 * The SDK registers this WebView as a Spotify Connect device. Once the device
 * is ready, its ID is passed to [onDeviceReady] so the Web API can target it
 * directly — meaning Spotify playback happens inside the app, without ever
 * opening the Spotify app.
 *
 * Lifecycle: create in onViewCreated, call [release] in onDestroyView.
 */
class SpotifyWebPlayer(private val context: Context) {

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Returns the current Spotify access token; called from JavaScript. */
    var accessTokenProvider: () -> String? = { null }

    /** Fired when the SDK connects and provides a device ID. */
    var onDeviceReady: (deviceId: String) -> Unit = {}

    /** Fired when the SDK device goes offline (e.g. network loss). */
    var onDeviceNotReady: () -> Unit = {}

    /** Fired when the current track ends naturally (not on user pause). */
    var onTrackEnded: () -> Unit = {}

    /** Fired on SDK errors (init, auth, account). */
    var onError: (message: String) -> Unit = {}

    /**
     * Creates and returns the WebView. Add the returned view to your layout
     * as INVISIBLE (not GONE) so audio playback works while the view is hidden.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        val wv = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Allow audio to play without a user gesture (required for background playback)
                mediaPlaybackRequiresUserGesture = false
                // The Spotify Web Playback SDK reads navigator.userAgent and throws
                // init_error:Failed to initialize player on any mobile/Android UA.
                // Object.defineProperty on navigator is non-configurable in Android WebView,
                // so the only reliable fix is overriding the UA at the WebView settings level.
                // Spotify's token-based Web API and WebSocket auth are not UA-sensitive,
                // so using a desktop UA here does not break server-side authentication.
                // Windows UA is required: the SDK also checks `'ontouchstart' in window`
                // and only skips the mobile block when "Windows" is present in the UA
                // (to allow touch-screen Windows laptops). Linux passes the Android
                // check but still fails the ontouchstart+non-Windows combination.
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    msg ?: return false
                    Log.d("SpotifyWebView", "${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]")
                    return true
                }
            }
            addJavascriptInterface(SpotifyJSInterface(), "Android")
        }

        // Load the HTML from assets. The base URL must be http://localhost so the
        // Spotify SDK accepts it (it requires HTTPS or localhost as origin).
        val html = context.assets.open("spotify_player.html").bufferedReader().use { it.readText() }
        wv.loadDataWithBaseURL("http://localhost", html, "text/html", "UTF-8", null)

        webView = wv
        return wv
    }

    /** Pause the SDK player via JavaScript. */
    fun pause() {
        webView?.evaluateJavascript("pausePlayback()", null)
    }

    /** Resume the SDK player via JavaScript. */
    fun resume() {
        webView?.evaluateJavascript("resumePlayback()", null)
    }

    /** Destroy the WebView and release resources. */
    fun release() {
        webView?.destroy()
        webView = null
    }

    private inner class SpotifyJSInterface {

        @JavascriptInterface
        fun getAccessToken(): String = accessTokenProvider() ?: ""

        @JavascriptInterface
        fun onDeviceReady(deviceId: String) {
            mainHandler.post { this@SpotifyWebPlayer.onDeviceReady(deviceId) }
        }

        @JavascriptInterface
        fun onDeviceNotReady(@Suppress("UNUSED_PARAMETER") deviceId: String) {
            mainHandler.post { this@SpotifyWebPlayer.onDeviceNotReady() }
        }

        @JavascriptInterface
        fun onTrackEnded() {
            mainHandler.post { this@SpotifyWebPlayer.onTrackEnded() }
        }

        @JavascriptInterface
        fun onError(message: String) {
            mainHandler.post { this@SpotifyWebPlayer.onError(message) }
        }
    }
}
