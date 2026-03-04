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
                // Masquerade as a desktop browser so the Spotify Web Playback SDK does not
                // detect Android and silently activate App Remote (which opens the Spotify app).
                // With a desktop UA the SDK stays on the pure Web Playback path.
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
