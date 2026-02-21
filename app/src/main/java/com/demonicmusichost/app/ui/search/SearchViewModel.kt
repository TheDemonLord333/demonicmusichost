package com.demonicmusichost.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demonicmusichost.app.data.model.SearchResult
import com.demonicmusichost.app.data.model.Song
import com.demonicmusichost.app.data.repository.LocalMusicRepository
import com.demonicmusichost.app.data.repository.SessionRepository
import com.demonicmusichost.app.data.repository.SpotifyRepository
import com.demonicmusichost.app.data.repository.YouTubeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchTab { SPOTIFY, YOUTUBE, LOCAL }

sealed class SearchEvent {
    data class SongAdded(val song: Song) : SearchEvent()
    data class ShowError(val message: String) : SearchEvent()
    data class ShowMessage(val message: String) : SearchEvent()
    object NavigateBack : SearchEvent()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val spotifyRepository: SpotifyRepository,
    private val youTubeRepository: YouTubeRepository,
    private val localMusicRepository: LocalMusicRepository,
    private val sessionRepository: SessionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val sessionId: String = savedStateHandle["sessionId"] ?: ""
    val isHost: Boolean = savedStateHandle["isHost"] ?: false

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeTab = MutableStateFlow(SearchTab.SPOTIFY)
    val activeTab: StateFlow<SearchTab> = _activeTab.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _spotifyResults = MutableStateFlow<List<SearchResult.SpotifyTrack>>(emptyList())
    val spotifyResults: StateFlow<List<SearchResult.SpotifyTrack>> = _spotifyResults.asStateFlow()

    private val _youtubeResults = MutableStateFlow<List<SearchResult.YouTubeVideo>>(emptyList())
    val youtubeResults: StateFlow<List<SearchResult.YouTubeVideo>> = _youtubeResults.asStateFlow()

    private val _localResults = MutableStateFlow<List<SearchResult.LocalFile>>(emptyList())
    val localResults: StateFlow<List<SearchResult.LocalFile>> = _localResults.asStateFlow()

    private val _events = MutableSharedFlow<SearchEvent>()
    val events: SharedFlow<SearchEvent> = _events.asSharedFlow()

    private var searchJob: Job? = null

    init {
        // Load local music on start
        viewModelScope.launch {
            localMusicRepository.getAllLocalMusic()
                .catch { }
                .collect { files -> _localResults.value = files }
        }
    }

    fun onQueryChanged(query: String) {
        _searchQuery.value = query
        if (query.length >= 2) {
            search(query)
        } else if (query.isEmpty()) {
            _spotifyResults.value = emptyList()
            _youtubeResults.value = emptyList()
            // Reload local music
            viewModelScope.launch {
                localMusicRepository.getAllLocalMusic().catch { }.collect {
                    _localResults.value = it
                }
            }
        }
    }

    private fun search(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isLoading.value = true
            when (_activeTab.value) {
                SearchTab.SPOTIFY -> searchSpotify(query)
                SearchTab.YOUTUBE -> searchYouTube(query)
                SearchTab.LOCAL -> searchLocal(query)
            }
            _isLoading.value = false
        }
    }

    fun onTabChanged(tab: SearchTab) {
        _activeTab.value = tab
        val query = _searchQuery.value
        if (query.length >= 2) {
            search(query)
        } else if (tab == SearchTab.LOCAL) {
            viewModelScope.launch {
                localMusicRepository.getAllLocalMusic().catch { }.collect {
                    _localResults.value = it
                }
            }
        }
    }

    private suspend fun searchSpotify(query: String) {
        if (!spotifyRepository.isAuthenticated()) {
            _events.emit(SearchEvent.ShowError("Nicht mit Spotify angemeldet"))
            return
        }
        spotifyRepository.searchTracks(query)
            .catch { e -> _events.emit(SearchEvent.ShowError("Spotify-Fehler: ${e.message}")) }
            .collect { results -> _spotifyResults.value = results }
    }

    private suspend fun searchYouTube(query: String) {
        youTubeRepository.searchVideos(query)
            .catch { e -> _events.emit(SearchEvent.ShowError("YouTube-Fehler: ${e.message}")) }
            .collect { results -> _youtubeResults.value = results }
    }

    private suspend fun searchLocal(query: String) {
        localMusicRepository.searchLocalMusic(query)
            .catch { e -> _events.emit(SearchEvent.ShowError("Lokaler Fehler: ${e.message}")) }
            .collect { results -> _localResults.value = results }
    }

    fun addToQueue(searchResult: SearchResult) {
        viewModelScope.launch {
            val userId = spotifyRepository.userId ?: "unknown"
            val userName = spotifyRepository.displayName ?: "Gast"

            val song = when (searchResult) {
                is SearchResult.SpotifyTrack -> searchResult.toSong(userId, userName)
                is SearchResult.YouTubeVideo -> searchResult.toSong(userId, userName)
                is SearchResult.LocalFile -> searchResult.toSong(userId, userName)
            }

            sessionRepository.addSongToQueue(sessionId, song)
                .onSuccess {
                    _events.emit(SearchEvent.SongAdded(song))
                    _events.emit(SearchEvent.ShowMessage("\"${song.title}\" hinzugefügt"))
                }
                .onFailure { e ->
                    _events.emit(SearchEvent.ShowError("Fehler: ${e.message}"))
                }
        }
    }
}
