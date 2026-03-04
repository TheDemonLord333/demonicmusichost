package com.demonicmusichost.app.data.repository

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.demonicmusichost.app.BuildConfig
import com.demonicmusichost.app.data.model.SearchResult
import com.demonicmusichost.app.data.network.SpotifyApiService
import com.demonicmusichost.app.data.network.SpotifyPlayRequest
import com.demonicmusichost.app.data.network.SpotifyTransferPlaybackRequest
import com.demonicmusichost.app.data.network.SpotifyUserResponse
import com.demonicmusichost.app.service.PlaybackEventBus
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyRepository @Inject constructor(
    private val spotifyApiService: SpotifyApiService,
    private val playbackEventBus: PlaybackEventBus,
    @ApplicationContext private val context: Context
) {

    /** Background scope for polling; outlives any single ViewModel. */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** URI of the track we last commanded Spotify to play. */
    private var currentPlayingUri: String? = null
    private var wasPlayingLastPoll = false

    /**
     * Device ID of the Spotify Web Playback SDK running in our embedded WebView.
     * Set by HostFragment when the SDK fires its `ready` event.
     * When non-null, all play commands are targeted at this device so Spotify
     * audio plays inside the app without ever opening the Spotify app.
     */
    var sdkDeviceId: String? = null

    /** Device ID of the Spotify app on this phone, cached after first lookup. */
    private var cachedDeviceId: String? = null

    companion object {
        const val SPOTIFY_REQUEST_CODE = 1337
        val SPOTIFY_SCOPES = arrayOf(
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            // "streaming" enables the Web Playback SDK (in-app player via WebView)
            "streaming",
            // "app-remote-control" is intentionally omitted: it is only required by the
            // Spotify App Remote SDK (which directly controls the Spotify Android app).
            // We use the Web Playback SDK instead. Having this scope causes Spotify's
            // backend to notify the Spotify Android app that an app-remote connection is
            // active, which can cause the Spotify app to open uninvited.
            "user-read-email",
            "user-read-private",
            "playlist-read-private",
            "playlist-read-collaborative"
        )
        private const val PREFS_NAME = "spotify_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_IS_PREMIUM = "is_premium"
        private const val KEY_ACTIVE_HOST_SESSION_ID = "active_host_session_id"

        // Increment this value whenever SPOTIFY_SCOPES changes. On first launch after an
        // update, the stored token is invalidated so the user re-auths with the new scopes.
        private const val SCOPES_VERSION = 2
        private const val KEY_SCOPES_VERSION = "scopes_version"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { p ->
                // If the stored scopes version is outdated, clear the token so the user
                // re-authenticates and gets a token with the updated scopes.
                if (p.getInt(KEY_SCOPES_VERSION, 0) < SCOPES_VERSION) {
                    p.edit()
                        .remove(KEY_ACCESS_TOKEN)
                        .remove(KEY_TOKEN_EXPIRY)
                        .putInt(KEY_SCOPES_VERSION, SCOPES_VERSION)
                        .apply()
                }
            }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var tokenExpiry: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var isPremium: Boolean
        get() = prefs.getBoolean(KEY_IS_PREMIUM, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PREMIUM, value).apply()

    var activeHostSessionId: String?
        get() = prefs.getString(KEY_ACTIVE_HOST_SESSION_ID, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_ACTIVE_HOST_SESSION_ID).apply()
            else prefs.edit().putString(KEY_ACTIVE_HOST_SESSION_ID, value).apply()
        }

    fun isAuthenticated(): Boolean {
        return accessToken != null && System.currentTimeMillis() < tokenExpiry
    }

    fun buildAuthRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(
            BuildConfig.SPOTIFY_CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            BuildConfig.SPOTIFY_REDIRECT_URI
        )
            .setScopes(SPOTIFY_SCOPES)
            .setShowDialog(true)
            .build()
    }

    fun handleAuthResponse(response: AuthorizationResponse): Boolean {
        return when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                accessToken = response.accessToken
                tokenExpiry = System.currentTimeMillis() + (response.expiresIn * 1000L)
                true
            }
            else -> false
        }
    }

    suspend fun fetchUserProfile(): Result<SpotifyUserResponse> {
        return try {
            val token = accessToken ?: return Result.failure(Exception("Not authenticated"))
            val user = spotifyApiService.getCurrentUser("Bearer $token")
            userId = user.id
            displayName = user.displayName
            isPremium = user.isPremium()
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun searchTracks(query: String): Flow<List<SearchResult.SpotifyTrack>> = flow {
        val token = accessToken ?: throw Exception("Not authenticated with Spotify")
        val response = spotifyApiService.searchTracks("Bearer $token", query)
        val results = response.tracks.items.map { track ->
            SearchResult.SpotifyTrack(
                id = track.id,
                name = track.name,
                artists = track.artists.map { it.name },
                albumName = track.album.name,
                albumImageUrl = track.album.getBestImageUrl(),
                durationMs = track.durationMs,
                uri = track.uri,
                previewUrl = track.previewUrl
            )
        }
        emit(results)
    }

    /**
     * Starts playing the given Spotify URI without leaving DemonicMusicHost.
     *
     * Four-step strategy (each step only runs if the previous one fails/is unavailable):
     *
     * 0. SDK device play: if our embedded WebView has registered a Spotify Connect
     *    device, target it directly. No polling needed — the SDK JS fires [onTrackEnded]
     *    via callback. This is the primary path after the WebView is ready.
     *
     * 1. Direct Web API play: works when Spotify already has an active device
     *    (e.g. the Spotify app was recently used). Uses polling to detect track end.
     *
     * 2. Transfer + play: fetches available Spotify devices, transfers playback
     *    to this phone's Spotify client (keeps it in background), then plays.
     *
     * 3. Intent fallback: only runs when Spotify has no device at all (i.e. the
     *    Spotify app is not running). After this one-time launch Spotify is in the
     *    background and steps 1/2 succeed for subsequent songs.
     */
    suspend fun startPlayback(uri: String, positionMs: Long = 0L): Result<Unit> {
        currentPlayingUri = uri
        wasPlayingLastPoll = false

        val token = accessToken ?: return fallbackToIntent(uri)
        val playRequest = SpotifyPlayRequest(
            uris = listOf(uri),
            positionMs = if (positionMs > 0L) positionMs else null
        )

        // Step 0: Web Playback SDK device — best path, no polling, no app switch
        sdkDeviceId?.let { deviceId ->
            stopPolling() // cancel any residual polling job from a previous fallback play
            return try {
                spotifyApiService.startPlayback("Bearer $token", playRequest, deviceId)
                // SDK fires onTrackEnded via JavaScript — no polling needed
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        // Step 1: Direct play (works when Spotify app device is already active)
        try {
            spotifyApiService.startPlayback("Bearer $token", playRequest)
            startPolling()
            return Result.success(Unit)
        } catch (_: Exception) { }

        // Step 2: Find the Spotify app device, transfer playback, then play
        try {
            val deviceId = cachedDeviceId ?: run {
                val devices = spotifyApiService.getDevices("Bearer $token").devices
                devices.firstOrNull { !it.isRestricted }?.id.also { cachedDeviceId = it }
            }
            if (deviceId != null) {
                spotifyApiService.transferPlayback(
                    "Bearer $token",
                    SpotifyTransferPlaybackRequest(deviceIds = listOf(deviceId), play = false)
                )
                delay(800L) // give Spotify time to activate the device
                spotifyApiService.startPlayback("Bearer $token", playRequest)
                startPolling()
                return Result.success(Unit)
            }
        } catch (_: Exception) {
            cachedDeviceId = null // stale cache — clear so next call re-fetches
        }

        // Step 3: No Spotify device found — launch Intent once to start the Spotify app
        return fallbackToIntent(uri)
    }

    private fun fallbackToIntent(uri: String): Result<Unit> {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            startPolling()
            Result.success(Unit)
        } catch (e: ActivityNotFoundException) {
            Result.failure(Exception("Spotify ist nicht installiert"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resumes the currently paused Spotify track via the Web API (no app switch).
     * Sending an empty play request body tells Spotify to resume the active context.
     * If the SDK device is active it is targeted explicitly so playback stays
     * inside the app rather than switching to the Spotify app device.
     */
    suspend fun resumeCurrentPlayback(): Result<Unit> {
        return try {
            val token = accessToken ?: return Result.failure(Exception("Not authenticated"))
            // SpotifyPlayRequest() → Gson serialises to {} → Spotify resumes current track.
            // Providing sdkDeviceId ensures we resume on the in-app WebView device, not
            // whatever device Spotify considers "active" (which might be the Spotify app).
            spotifyApiService.startPlayback("Bearer $token", SpotifyPlayRequest(), sdkDeviceId)
            if (sdkDeviceId == null) startPolling() // SDK callbacks handle state when SDK is active
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Polls the Spotify Web API every 4 seconds to detect when the current track ends.
     *
     * Key design decisions:
     * - wasPlayingLastPoll is reset to false here, so a fresh poll cycle always starts clean.
     * - We do NOT pause Spotify inside this loop when a track ends. Keeping Spotify active
     *   means the device stays reachable via Web API, so startPlayback() for the next queue
     *   song succeeds via the Web API without opening the Spotify app.
     * - The progressMs < 3000 guard is intentionally removed. Since pausePolling() is called
     *   whenever the user intentionally pauses, wasPlayingLastPoll is always false during a
     *   user pause, so "!isPlaying && wasPlayingLastPoll" only fires on natural track ends.
     */
    fun startPolling() {
        pollJob?.cancel()
        wasPlayingLastPoll = false  // reset state for fresh polling cycle
        pollJob = repositoryScope.launch {
            while (isActive) {
                delay(4_000L)
                try {
                    val token = accessToken ?: break
                    val state = spotifyApiService.getCurrentPlaybackState("Bearer $token")
                    if (state == null) {
                        wasPlayingLastPoll = false
                        continue
                    }
                    val trackEnded = when {
                        // Case 1: Spotify auto-advanced to its own next track.
                        // The device is still PLAYING so the next Web API call will succeed.
                        state.isPlaying && state.item?.uri != null
                            && state.item.uri != currentPlayingUri
                            && currentPlayingUri != null -> true
                        // Case 2: Track stopped naturally (was playing, now stopped).
                        // pausePolling() is called on user-initiated pauses, so wasPlayingLastPoll
                        // is false during those — this branch only fires on real track ends.
                        !state.isPlaying && wasPlayingLastPoll -> true
                        else -> false
                    }
                    if (trackEnded) {
                        // Do NOT pause here: leaving Spotify in its current state keeps the
                        // device active so startPlayback() can use the Web API for the next song.
                        currentPlayingUri = null
                        wasPlayingLastPoll = false
                        playbackEventBus.notifySongEnded()
                        break  // stop polling until next song starts
                    }
                    wasPlayingLastPoll = state.isPlaying
                } catch (_: Exception) {
                    // Network error or token expired – keep polling silently
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        currentPlayingUri = null
        wasPlayingLastPoll = false
    }

    /**
     * Cancels the polling job while keeping [currentPlayingUri].
     * Called when the user intentionally pauses so the polling loop cannot
     * mistake a user-initiated pause for a natural track end.
     */
    private fun pausePolling() {
        pollJob?.cancel()
        pollJob = null
        wasPlayingLastPoll = false
    }

    suspend fun pausePlayback(): Result<Unit> {
        return try {
            val token = accessToken ?: return Result.failure(Exception("Not authenticated"))
            spotifyApiService.pausePlayback("Bearer $token")
            pausePolling()  // stop monitoring while intentionally paused
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun skipToNext(): Result<Unit> {
        return try {
            val token = accessToken ?: return Result.failure(Exception("Not authenticated"))
            spotifyApiService.skipToNext("Bearer $token")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun seekToPosition(positionMs: Long): Result<Unit> {
        return try {
            val token = accessToken ?: return Result.failure(Exception("Not authenticated"))
            spotifyApiService.seekToPosition("Bearer $token", positionMs)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        prefs.edit().clear().putInt(KEY_SCOPES_VERSION, SCOPES_VERSION).apply()
        sdkDeviceId = null
        cachedDeviceId = null
    }

    fun clearActiveSession() {
        activeHostSessionId = null
    }
}
