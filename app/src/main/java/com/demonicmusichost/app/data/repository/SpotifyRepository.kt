package com.demonicmusichost.app.data.repository

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.demonicmusichost.app.BuildConfig
import com.demonicmusichost.app.data.model.SearchResult
import com.demonicmusichost.app.data.network.SpotifyApiService
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

    companion object {
        const val SPOTIFY_REQUEST_CODE = 1337
        val SPOTIFY_SCOPES = arrayOf(
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "streaming",
            "app-remote-control",
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
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
     * Opens the native Spotify app to play the given track URI (e.g. "spotify:track:ID"),
     * then starts background polling to detect when the track ends so we can auto-advance
     * the in-app queue.
     */
    fun startPlayback(uri: String, positionMs: Long = 0L): Result<Unit> {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            currentPlayingUri = uri
            wasPlayingLastPoll = false
            startPolling()
            Result.success(Unit)
        } catch (e: ActivityNotFoundException) {
            Result.failure(Exception("Spotify ist nicht installiert"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Polls the Spotify Web API every 4 seconds to detect when the current track ends.
     * Emits [PlaybackEventBus.notifySongEnded] when end is detected, then stops polling.
     * After launching the Spotify app via Intent the phone is an active Spotify device,
     * so the Web API calls succeed.
     */
    fun startPolling() {
        pollJob?.cancel()
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
                        // Spotify auto-advanced to a different track
                        state.isPlaying && state.item?.uri != currentPlayingUri
                            && currentPlayingUri != null -> true
                        // Was playing, now stopped, position reset → natural end
                        !state.isPlaying && wasPlayingLastPoll && state.progressMs < 3_000L -> true
                        else -> false
                    }
                    if (trackEnded) {
                        // Pause Spotify so it doesn't continue into its own queue
                        runCatching { spotifyApiService.pausePlayback("Bearer $token") }
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

    suspend fun pausePlayback(): Result<Unit> {
        return try {
            val token = accessToken ?: return Result.failure(Exception("Not authenticated"))
            spotifyApiService.pausePlayback("Bearer $token")
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
        prefs.edit().clear().apply()
    }

    fun clearActiveSession() {
        activeHostSessionId = null
    }
}
