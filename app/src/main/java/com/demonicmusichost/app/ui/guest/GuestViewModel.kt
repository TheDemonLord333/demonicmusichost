package com.demonicmusichost.app.ui.guest

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demonicmusichost.app.data.model.Session
import com.demonicmusichost.app.data.model.Song
import com.demonicmusichost.app.data.model.SongSource
import com.demonicmusichost.app.data.repository.SessionRepository
import com.demonicmusichost.app.data.repository.SpotifyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed class GuestEvent {
    data class ShowError(val message: String) : GuestEvent()
    data class ShowMessage(val message: String) : GuestEvent()
    object SessionLeft : GuestEvent()
    object SessionEnded : GuestEvent()
    data class PlayYouTube(val videoId: String) : GuestEvent()
}

@HiltViewModel
class GuestViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val spotifyRepository: SpotifyRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val userId: String = spotifyRepository.userId ?: UUID.randomUUID().toString()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _events = MutableSharedFlow<GuestEvent>()
    val events: SharedFlow<GuestEvent> = _events.asSharedFlow()

    init {
        observeSession()
        observeQueue()
    }

    private fun observeSession() {
        viewModelScope.launch {
            sessionRepository.observeSession(sessionId)
                .catch { e -> _events.emit(GuestEvent.ShowError("Verbindungsfehler: ${e.message}")) }
                .collect { session ->
                    if (session == null || !session.isActive) {
                        _events.emit(GuestEvent.SessionEnded)
                        return@collect
                    }
                    _session.value = session

                    // Mirror current playback for YouTube tracks (guests see what host plays)
                    session.currentSong?.let { song ->
                        if (song.source == SongSource.YOUTUBE) {
                            _events.emit(GuestEvent.PlayYouTube(song.youtubeVideoId))
                        }
                    }
                }
        }
    }

    private fun observeQueue() {
        viewModelScope.launch {
            sessionRepository.observeQueue(sessionId)
                .catch { e -> _events.emit(GuestEvent.ShowError("Queue Fehler: ${e.message}")) }
                .collect { queue -> _queue.value = queue }
        }
    }

    fun addSongToQueue(song: Song) {
        viewModelScope.launch {
            val canAdd = _session.value?.guestsCanAddSongs ?: true
            if (!canAdd) {
                _events.emit(GuestEvent.ShowError("Der Host hat das Hinzufügen von Songs deaktiviert"))
                return@launch
            }

            sessionRepository.addSongToQueue(sessionId, song)
                .onSuccess {
                    _events.emit(GuestEvent.ShowMessage("\"${song.title}\" zur Warteschlange hinzugefügt"))
                }
                .onFailure { e ->
                    _events.emit(GuestEvent.ShowError("Fehler: ${e.message}"))
                }
        }
    }

    fun leaveSession() {
        viewModelScope.launch {
            sessionRepository.leaveSession(sessionId, userId)
                .onSuccess { _events.emit(GuestEvent.SessionLeft) }
                .onFailure { e -> _events.emit(GuestEvent.ShowError("Fehler: ${e.message}")) }
        }
    }

    fun getDisplayName(): String =
        spotifyRepository.displayName ?: "Gast"

    fun canAddSongs(): Boolean = _session.value?.guestsCanAddSongs ?: true
}
