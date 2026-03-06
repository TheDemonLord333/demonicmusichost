package com.demonicmusichost.app.ui.host

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demonicmusichost.app.data.model.PlaybackInfo
import com.demonicmusichost.app.data.model.PlaybackState
import com.demonicmusichost.app.data.model.Session
import com.demonicmusichost.app.data.model.Song
import com.demonicmusichost.app.data.model.SongSource
import com.demonicmusichost.app.data.repository.SessionRepository
import com.demonicmusichost.app.data.repository.SpotifyRepository
import com.demonicmusichost.app.service.PlaybackEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

sealed class HostEvent {
    data class ShowError(val message: String) : HostEvent()
    data class ShowMessage(val message: String) : HostEvent()
    object SessionEnded : HostEvent()
    data class PlayYouTube(val videoId: String) : HostEvent()
    data class PlayLocal(val filePath: String) : HostEvent()
    object PauseLocal : HostEvent()
    object ResumeLocal : HostEvent()
    object PauseYouTube : HostEvent()
    object ResumeYouTube : HostEvent()
    object StopLocal : HostEvent()
    object StopYouTube : HostEvent()
}

@HiltViewModel
class HostViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val spotifyRepository: SpotifyRepository,
    private val playbackEventBus: PlaybackEventBus,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _events = MutableSharedFlow<HostEvent>()
    val events: SharedFlow<HostEvent> = _events.asSharedFlow()

    /** Songs that were played before the current one, most recent last. */
    private val previousSongs = ArrayDeque<Song>()

    init {
        observeSession()
        observeQueue()
        observeSongEnded()
    }

    /**
     * Listens to [PlaybackEventBus.songEnded] which is fired by MusicService (local files)
     * and SpotifyRepository (Spotify polling). Advances the queue automatically.
     */
    private fun observeSongEnded() {
        viewModelScope.launch {
            playbackEventBus.songEnded.collect {
                playNextInQueue()
            }
        }
    }

    /** Called by HostFragment when the YouTube player fires the video-ended callback. */
    fun onYouTubeSongEnded() {
        playNextInQueue()
    }

    /** Called by HostFragment when the Spotify Web Playback SDK detects a natural track end. */
    fun onSpotifyTrackEnded() {
        playNextInQueue()
    }

    // ── Spotify Web Playback SDK state ──────────────────────────────────────

    private enum class SdkState { INITIALIZING, READY, FAILED }

    /**
     * Tracks whether the embedded Spotify SDK WebView has registered a device.
     * Starts as INITIALIZING. Transitions:
     *  - READY:  onDeviceReady fired (sdkDeviceId set)
     *  - FAILED: onError fired or device went offline
     */
    private val _sdkState = MutableStateFlow(SdkState.INITIALIZING)

    /**
     * Set to true when a Spotify song is queued for playback but the SDK device
     * has not registered yet. [setSpotifyDeviceId] will auto-start the song when
     * the SDK fires its `ready` event.
     */
    private var pendingSpotifyPlay = false

    /** Last SDK error message (init/auth/account), kept for user-facing display. */
    private var lastSdkError: String? = null

    /** Called by HostFragment when the Spotify Web Playback SDK device becomes ready. */
    fun setSpotifyDeviceId(deviceId: String) {
        spotifyRepository.sdkDeviceId = deviceId
        spotifyRepository.stopPolling()
        _sdkState.value = SdkState.READY

        // If a play was requested before the SDK registered, start it now.
        if (pendingSpotifyPlay) {
            pendingSpotifyPlay = false
            val song = _currentSong.value?.takeIf { it.source == SongSource.SPOTIFY } ?: return
            viewModelScope.launch {
                spotifyRepository.startPlayback(song.spotifyUri)
                    .onSuccess {
                        _isPlaying.value = true
                        sessionRepository.updatePlaybackInfo(
                            sessionId,
                            PlaybackInfo(state = PlaybackState.PLAYING, positionMs = 0L)
                        )
                    }
                    .onFailure { e ->
                        _events.emit(HostEvent.ShowError("Wiedergabe fehlgeschlagen: ${e.message}"))
                    }
            }
        }
    }

    /** Called by HostFragment when the SDK device goes offline. */
    fun clearSpotifyDeviceId() {
        spotifyRepository.sdkDeviceId = null
        _sdkState.value = SdkState.FAILED
    }

    /** Called by HostFragment when the SDK fires an initialization/auth/account error. */
    fun onSdkError(message: String) {
        lastSdkError = message
        _sdkState.value = SdkState.FAILED
    }

    /**
     * Suspends until the SDK WebView has registered its Spotify device or has
     * definitively failed (auth error, account error, etc.).
     *
     * Waits up to 15 seconds. If the SDK is already ready or failed, returns
     * immediately.
     */
    private suspend fun awaitSdkReady() {
        if (_sdkState.value != SdkState.INITIALIZING) return
        withTimeoutOrNull(15_000L) {
            _sdkState.first { it != SdkState.INITIALIZING }
        }
    }

    /**
     * Starts playback of [spotifyUri] via the SDK, or queues it if the SDK is
     * still initializing. Returns true if playback started immediately.
     */
    private suspend fun startSpotifyPlayback(spotifyUri: String): Result<Unit> {
        awaitSdkReady()
        return when {
            spotifyRepository.sdkDeviceId != null -> {
                spotifyRepository.startPlayback(spotifyUri)
            }
            _sdkState.value == SdkState.INITIALIZING -> {
                // SDK is still loading (timeout elapsed) — queue and auto-start when ready
                pendingSpotifyPlay = true
                _events.emit(HostEvent.ShowMessage("Verbinde mit Spotify Web Player…"))
                // Return failure with a sentinel so the caller skips _isPlaying = true.
                // The pending play in setSpotifyDeviceId() sets _isPlaying when it fires.
                Result.failure(Exception("__pending__"))
            }
            else -> {
                // FAILED state — surface the actual SDK error
                val reason = lastSdkError ?: "Unbekannter Fehler"
                Result.failure(Exception("Spotify Web Player Fehler: $reason"))
            }
        }
    }

    /** Returns the current Spotify access token for the WebView JS bridge. */
    fun getSpotifyAccessToken(): String? = spotifyRepository.accessToken

    private fun observeSession() {
        viewModelScope.launch {
            sessionRepository.observeSession(sessionId)
                .catch { e -> _events.emit(HostEvent.ShowError("Session Fehler: ${e.message}")) }
                .collect { session ->
                    _session.value = session
                    session?.currentSong?.let { _currentSong.value = it }
                }
        }
    }

    private fun observeQueue() {
        viewModelScope.launch {
            sessionRepository.observeQueue(sessionId)
                .catch { e -> _events.emit(HostEvent.ShowError("Queue Fehler: ${e.message}")) }
                .collect { queue -> _queue.value = queue }
        }
    }

    fun playPause() {
        val song = _currentSong.value ?: run {
            playNextInQueue()
            return
        }

        viewModelScope.launch {
            if (_isPlaying.value) {
                pausePlayback(song)
            } else {
                resumePlayback(song)
            }
        }
    }

    private suspend fun pausePlayback(song: Song) {
        val result = when (song.source) {
            SongSource.SPOTIFY -> spotifyRepository.pausePlayback()
            SongSource.LOCAL -> {
                _events.emit(HostEvent.PauseLocal)
                Result.success(Unit)
            }
            SongSource.YOUTUBE -> {
                _events.emit(HostEvent.PauseYouTube)
                Result.success(Unit)
            }
        }
        result.onSuccess {
            _isPlaying.value = false
            sessionRepository.updatePlaybackInfo(
                sessionId,
                PlaybackInfo(state = PlaybackState.PAUSED, positionMs = _playbackPositionMs.value)
            )
        }.onFailure { e ->
            _events.emit(HostEvent.ShowError("Pause fehlgeschlagen: ${e.message}"))
        }
    }

    private suspend fun resumePlayback(song: Song) {
        val result = when (song.source) {
            SongSource.SPOTIFY -> spotifyRepository.resumeCurrentPlayback()
            SongSource.YOUTUBE -> {
                _events.emit(HostEvent.ResumeYouTube)
                Result.success(Unit)
            }
            SongSource.LOCAL -> {
                _events.emit(HostEvent.ResumeLocal)
                Result.success(Unit)
            }
        }
        result.onSuccess {
            _isPlaying.value = true
            sessionRepository.updatePlaybackInfo(
                sessionId,
                PlaybackInfo(state = PlaybackState.PLAYING, positionMs = _playbackPositionMs.value)
            )
        }.onFailure { e ->
            _events.emit(HostEvent.ShowError("Wiedergabe fehlgeschlagen: ${e.message}"))
        }
    }

    fun playNextInQueue() {
        viewModelScope.launch {
            val queue = _queue.value
            if (queue.isEmpty()) {
                _events.emit(HostEvent.ShowMessage("Die Warteschlange ist leer"))
                return@launch
            }

            // Stop the current source before switching to a different one
            val currentSong = _currentSong.value
            val nextSong = queue.first()
            if (currentSong != null && currentSong.source != nextSong.source) {
                when (currentSong.source) {
                    SongSource.SPOTIFY -> spotifyRepository.pausePlayback()
                    SongSource.YOUTUBE -> _events.emit(HostEvent.StopYouTube)
                    SongSource.LOCAL -> _events.emit(HostEvent.StopLocal)
                }
            }

            // Push current song to history before advancing
            currentSong?.let { previousSongs.addLast(it) }

            sessionRepository.setCurrentSong(sessionId, nextSong)
            sessionRepository.removeFromQueue(sessionId, nextSong.id)
            _currentSong.value = nextSong
            _playbackPositionMs.value = 0L

            val result = when (nextSong.source) {
                SongSource.SPOTIFY -> startSpotifyPlayback(nextSong.spotifyUri)
                SongSource.YOUTUBE -> {
                    _events.emit(HostEvent.PlayYouTube(nextSong.youtubeVideoId))
                    Result.success(Unit)
                }
                SongSource.LOCAL -> {
                    _events.emit(HostEvent.PlayLocal(nextSong.localFilePath))
                    Result.success(Unit)
                }
            }

            result.onSuccess {
                _isPlaying.value = true
                sessionRepository.updatePlaybackInfo(
                    sessionId,
                    PlaybackInfo(state = PlaybackState.PLAYING, positionMs = 0L)
                )
            }.onFailure { e ->
                // "__pending__" is a sentinel: the play was queued and will auto-start
                // when the SDK device registers. No error should be shown to the user.
                if (e.message != "__pending__") {
                    _events.emit(HostEvent.ShowError("Wiedergabe fehlgeschlagen: ${e.message}"))
                }
            }
        }
    }

    fun playPrevious() {
        viewModelScope.launch {
            val prev = previousSongs.removeLastOrNull()

            if (prev == null) {
                // No history — restart current song from the beginning
                val current = _currentSong.value ?: return@launch
                _playbackPositionMs.value = 0L
                val result = when (current.source) {
                    SongSource.SPOTIFY -> startSpotifyPlayback(current.spotifyUri)
                    SongSource.YOUTUBE -> {
                        _events.emit(HostEvent.PlayYouTube(current.youtubeVideoId))
                        Result.success(Unit)
                    }
                    SongSource.LOCAL -> {
                        _events.emit(HostEvent.PlayLocal(current.localFilePath))
                        Result.success(Unit)
                    }
                }
                result.onSuccess {
                    _isPlaying.value = true
                    sessionRepository.updatePlaybackInfo(
                        sessionId,
                        PlaybackInfo(state = PlaybackState.PLAYING, positionMs = 0L)
                    )
                }.onFailure { e ->
                    _events.emit(HostEvent.ShowError("Wiedergabe fehlgeschlagen: ${e.message}"))
                }
                return@launch
            }

            // Put current song back at the front of the queue
            _currentSong.value?.let { current ->
                val updatedQueue = listOf(current) + _queue.value
                sessionRepository.reorderQueue(sessionId, updatedQueue)
            }

            // Play the previous song
            sessionRepository.setCurrentSong(sessionId, prev)
            _currentSong.value = prev
            _playbackPositionMs.value = 0L

            val result = when (prev.source) {
                SongSource.SPOTIFY -> startSpotifyPlayback(prev.spotifyUri)
                SongSource.YOUTUBE -> {
                    _events.emit(HostEvent.PlayYouTube(prev.youtubeVideoId))
                    Result.success(Unit)
                }
                SongSource.LOCAL -> {
                    _events.emit(HostEvent.PlayLocal(prev.localFilePath))
                    Result.success(Unit)
                }
            }

            result.onSuccess {
                _isPlaying.value = true
                sessionRepository.updatePlaybackInfo(
                    sessionId,
                    PlaybackInfo(state = PlaybackState.PLAYING, positionMs = 0L)
                )
            }.onFailure { e ->
                // "__pending__" is a sentinel: the play was queued and will auto-start
                // when the SDK device registers. No error should be shown to the user.
                if (e.message != "__pending__") {
                    _events.emit(HostEvent.ShowError("Wiedergabe fehlgeschlagen: ${e.message}"))
                }
            }
        }
    }

    fun skipSong() {
        playNextInQueue()
    }

    fun updatePlaybackPosition(positionMs: Long) {
        _playbackPositionMs.value = positionMs
    }

    fun removeFromQueue(song: Song) {
        viewModelScope.launch {
            sessionRepository.removeFromQueue(sessionId, song.id)
                .onFailure { e -> _events.emit(HostEvent.ShowError("Entfernen fehlgeschlagen: ${e.message}")) }
        }
    }

    fun toggleGuestsCanAdd() {
        val current = _session.value?.guestsCanAddSongs ?: true
        viewModelScope.launch {
            sessionRepository.setGuestsCanAddSongs(sessionId, !current)
        }
    }

    fun endSession() {
        viewModelScope.launch {
            _currentSong.value?.let { song ->
                if (song.source == SongSource.SPOTIFY) {
                    spotifyRepository.pausePlayback()
                }
            }
            spotifyRepository.activeHostSessionId = null
            sessionRepository.endSession(sessionId)
                .onSuccess { _events.emit(HostEvent.SessionEnded) }
                .onFailure { e -> _events.emit(HostEvent.ShowError("Session beenden fehlgeschlagen: ${e.message}")) }
        }
    }

    fun getSessionCode(): String = _session.value?.sessionCode ?: ""

    fun getGuestCount(): Int = _session.value?.getGuestCount() ?: 0

    override fun onCleared() {
        super.onCleared()
        spotifyRepository.stopPolling()
    }
}
