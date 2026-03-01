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
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class HostEvent {
    data class ShowError(val message: String) : HostEvent()
    data class ShowMessage(val message: String) : HostEvent()
    object SessionEnded : HostEvent()
    data class PlayYouTube(val videoId: String) : HostEvent()
    data class PlayLocal(val filePath: String) : HostEvent()
    object PauseLocal : HostEvent()
    object ResumeLocal : HostEvent()
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
                // YouTube plays in the external app; can't pause programmatically
                _events.emit(HostEvent.ShowMessage("Wiedergabe in YouTube-App pausieren"))
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
            SongSource.SPOTIFY -> spotifyRepository.startPlayback(song.spotifyUri)
            SongSource.YOUTUBE -> {
                _events.emit(HostEvent.PlayYouTube(song.youtubeVideoId))
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

            // Push current song to history before advancing
            _currentSong.value?.let { previousSongs.addLast(it) }

            val nextSong = queue.first()
            sessionRepository.setCurrentSong(sessionId, nextSong)
            sessionRepository.removeFromQueue(sessionId, nextSong.id)
            _currentSong.value = nextSong
            _playbackPositionMs.value = 0L

            val result = when (nextSong.source) {
                SongSource.SPOTIFY -> spotifyRepository.startPlayback(nextSong.spotifyUri)
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
                _events.emit(HostEvent.ShowError("Wiedergabe fehlgeschlagen: ${e.message}"))
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
                    SongSource.SPOTIFY -> spotifyRepository.startPlayback(current.spotifyUri, 0L)
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
                SongSource.SPOTIFY -> spotifyRepository.startPlayback(prev.spotifyUri, 0L)
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
                _events.emit(HostEvent.ShowError("Wiedergabe fehlgeschlagen: ${e.message}"))
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
