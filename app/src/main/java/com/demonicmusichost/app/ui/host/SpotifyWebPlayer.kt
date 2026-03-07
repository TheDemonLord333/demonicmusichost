package com.demonicmusichost.app.ui.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

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

    companion object {
        // WebViewAssetLoader serves our HTML from assets/ over a guaranteed HTTPS
        // origin (https://appassets.androidplatform.net). This is the standard
        // Android way to get isSecureContext = true for local assets, which the
        // Spotify Web Playback SDK requires (it calls crypto.subtle internally).
        // Using http://localhost did NOT reliably give isSecureContext = true on
        // all Android WebView versions — hence the switch.
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val PLAYER_URL =
            "https://$ASSET_HOST/assets/index.html"

        // The Spotify Web Playback SDK script URL.
        // We intercept this in shouldInterceptRequest, download it on the device,
        // patch out the mobile-detection block that emits initialization_error, and
        // serve the modified version so the SDK initialises in our Android WebView.
        private const val SDK_URL = "https://sdk.scdn.co/spotify-player.js"

        // Cached patched SDK JS — persists for the app process lifetime so we only
        // download and patch once per process (not per WebView instance).
        @Volatile private var patchedSdkJs: String? = null
    }

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
     * as VISIBLE with alpha=0 (not INVISIBLE/GONE) so audio playback works
     * while the view is visually hidden.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        // Serves files under assets/ over https://appassets.androidplatform.net/assets/
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

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
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    val path = request.url.path ?: ""

                    // 1. WebViewAssetLoader: serves index.html, app.js etc. from assets/
                    //    over https://appassets.androidplatform.net — gives isSecureContext=true.
                    val assetResponse = assetLoader.shouldInterceptRequest(request.url)
                    if (assetResponse != null) return assetResponse

                    // 2. /api/token — returns the current Spotify access token as JSON.
                    //    Intercepted here instead of using Android.getAccessToken() so
                    //    the SDK can call it asynchronously (fetch-based getOAuthToken).
                    if (path == "/api/token") {
                        val token = accessTokenProvider() ?: ""
                        val json = """{"access_token":"$token"}"""
                        return WebResourceResponse(
                            "application/json",
                            "utf-8",
                            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
                        )
                    }

                    // 3. /api/spotify/* — proxies GET requests to Spotify Web API v1.
                    //    Allows app.js to make authenticated Spotify calls without a
                    //    separate server; kein externer Server nötig.
                    if (path.startsWith("/api/spotify/")) {
                        return proxySpotifyApi(request)
                    }

                    // 4. Spotify SDK JS — download, patch mobile-detection, serve cached.
                    if (url == SDK_URL) {
                        return fetchAndPatchSdkJs()
                    }

                    return null
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    msg ?: return false
                    Log.d("SpotifyWebView", "${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]")
                    return true
                }
            }
            addJavascriptInterface(SpotifyJSInterface(), "Android")
        }

        wv.loadUrl(PLAYER_URL)

        webView = wv
        return wv
    }

    /**
     * Downloads the Spotify SDK JS (or uses the cached copy), patches out the
     * mobile-detection code that emits [initialization_error], and returns a
     * [WebResourceResponse] serving the patched script.
     *
     * Returns null if the download fails so the WebView falls back to its own
     * network stack and loads the unpatched SDK (which may still fail, but at
     * least we don't lose playback on a transient network error).
     */
    private fun fetchAndPatchSdkJs(): WebResourceResponse? {
        try {
            val js = patchedSdkJs ?: run {
                val raw = downloadSdkJs() ?: return null
                val patched = patchSdkJs(raw)
                patchedSdkJs = patched
                patched
            }
            return WebResourceResponse(
                "application/javascript",
                "utf-8",
                ByteArrayInputStream(js.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e("SpotifyWebView", "fetchAndPatchSdkJs error: ${e.message}")
            return null
        }
    }

    /**
     * Proxies a GET request from /api/spotify/<path> to https://api.spotify.com/v1/<path>
     * with the current Bearer token injected. Called on a background thread.
     * POST/PUT bodies are not forwarded (WebResourceRequest doesn't expose them);
     * those calls continue to go through Kotlin/Retrofit as before.
     */
    private fun proxySpotifyApi(request: WebResourceRequest): WebResourceResponse? {
        val spotifyPath = request.url.path?.removePrefix("/api/spotify") ?: return null
        val query = request.url.query?.let { "?$it" } ?: ""
        val spotifyUrl = "https://api.spotify.com/v1$spotifyPath$query"
        val token = accessTokenProvider() ?: return null

        return try {
            val conn = URL(spotifyUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val mime = conn.contentType?.substringBefore(";")?.trim() ?: "application/json"
            Log.d("SpotifyWebView", "proxySpotifyApi $spotifyPath → $code")
            WebResourceResponse(mime, "utf-8", code, conn.responseMessage ?: "OK", emptyMap(), stream)
        } catch (e: Exception) {
            Log.e("SpotifyWebView", "proxySpotifyApi error: ${e.message}")
            null
        }
    }

    private fun downloadSdkJs(): String? {
        return try {
            val conn = URL(SDK_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            Log.d("SpotifyWebView", "SDK JS downloaded (${text.length} chars)")
            text
        } catch (e: Exception) {
            Log.e("SpotifyWebView", "SDK JS download failed: ${e.message}")
            null
        }
    }

    /**
     * Applies targeted patches to the minified Spotify SDK JS to allow it to
     * run inside Android WebView without triggering the mobile-device guard.
     *
     * Patch strategy:
     *  1. Find the `emit("initialization_error", new Error("Failed to initialize player"))`
     *     call that the SDK makes when it decides the runtime is a mobile browser,
     *     and replace it with a no-op.  This is the innermost call, so the outer
     *     condition that triggered it is irrelevant — we just prevent the signal.
     *  2. (Fallback) Replace any reference to the literal error message with an
     *     empty string so the Error object carries no recognisable message, which
     *     prevents our own `onError` handler from reacting to it.
     */
    private fun patchSdkJs(js: String): String {
        // Pattern covers common minifier outputs:
        //   x.emit("initialization_error",new Error("Failed to initialize player"))
        //   this._emitter.emit('initialization_error',new Error('Failed to initialize player'))
        //   e.emit("initialization_error",new Error("Failed to initialize player"))
        val emitPattern = Regex(
            """([\w${'$'}]+(?:\.[\w${'$'}]+)*)\s*\.\s*emit\s*\(\s*["']initialization_error["']\s*,\s*new\s+Error\s*\(\s*["']Failed to initialize player["']\s*\)\s*\)"""
        )

        var patched = emitPattern.replace(js) { _ ->
            // Keep the object reference expression intact so surrounding comma-separated
            // expressions still parse; replace the whole call with void 0.
            "(void 0 /* DMH: mobile-detection patch */)"
        }

        if (patched != js) {
            Log.d("SpotifyWebView", "SDK JS patched: initialization_error emit removed")
            return patched
        }

        // Fallback: try a simpler match without the object prefix in case the
        // minifier inlined the emit differently.
        val simplePattern = Regex(
            """emit\s*\(\s*["']initialization_error["']\s*,\s*new\s+Error\s*\(\s*["']Failed to initialize player["']\s*\)\s*\)"""
        )
        patched = simplePattern.replace(js, "(void 0 /* DMH: mobile-detection patch */)")
        if (patched != js) {
            Log.d("SpotifyWebView", "SDK JS patched (simple): initialization_error emit removed")
            return patched
        }

        // Second fallback: just erase the literal error message so our handler
        // doesn't recognise it as a mobile-detection failure.
        val messagePattern = Regex("""["']Failed to initialize player["']""")
        patched = messagePattern.replace(js, "\"\"")
        if (patched != js) {
            Log.w("SpotifyWebView", "SDK JS patched (message erasure fallback)")
            return patched
        }

        Log.w("SpotifyWebView", "SDK JS: no patch pattern matched — serving unpatched")
        return js
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
