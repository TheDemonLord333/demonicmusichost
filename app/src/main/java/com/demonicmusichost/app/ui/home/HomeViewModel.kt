package com.demonicmusichost.app.ui.home

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demonicmusichost.app.data.model.Session
import com.demonicmusichost.app.data.model.SessionUser
import com.demonicmusichost.app.data.repository.SessionRepository
import com.demonicmusichost.app.data.repository.SpotifyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed class HomeEvent {
    data class NavigateToHost(val session: Session) : HomeEvent()
    data class NavigateToGuest(val session: Session) : HomeEvent()
    data class ShowError(val message: String) : HomeEvent()
    object SpotifyAuthRequired : HomeEvent()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val spotifyRepository: SpotifyRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>()
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    private val _isSpotifyAuthenticated = MutableStateFlow(false)
    val isSpotifyAuthenticated: StateFlow<Boolean> = _isSpotifyAuthenticated.asStateFlow()

    private val _isSpotifyPremium = MutableStateFlow(false)
    val isSpotifyPremium: StateFlow<Boolean> = _isSpotifyPremium.asStateFlow()

    private val _spotifyDisplayName = MutableStateFlow<String?>(null)
    val spotifyDisplayName: StateFlow<String?> = _spotifyDisplayName.asStateFlow()

    init {
        checkSpotifyAuth()
    }

    fun checkSpotifyAuth() {
        _isSpotifyAuthenticated.value = spotifyRepository.isAuthenticated()
        _isSpotifyPremium.value = spotifyRepository.isPremium
        _spotifyDisplayName.value = spotifyRepository.displayName
    }

    fun onSpotifyAuthSuccess(accessToken: String, expiresIn: Int) {
        spotifyRepository.accessToken = accessToken
        spotifyRepository.tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000L)
        _isSpotifyAuthenticated.value = true

        viewModelScope.launch {
            spotifyRepository.fetchUserProfile().onSuccess { user ->
                _isSpotifyPremium.value = user.isPremium()
                _spotifyDisplayName.value = user.displayName
            }
        }
    }

    fun createHostSession() {
        if (!spotifyRepository.isAuthenticated()) {
            viewModelScope.launch { _events.emit(HomeEvent.SpotifyAuthRequired) }
            return
        }
        if (!spotifyRepository.isPremium) {
            viewModelScope.launch {
                _events.emit(HomeEvent.ShowError("Spotify Premium ist erforderlich, um eine Session als Host zu starten"))
            }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            val host = SessionUser(
                userId = spotifyRepository.userId ?: UUID.randomUUID().toString(),
                displayName = spotifyRepository.displayName ?: "Host",
                isHost = true
            )
            sessionRepository.createSession(host)
                .onSuccess { session ->
                    _events.emit(HomeEvent.NavigateToHost(session))
                }
                .onFailure { e ->
                    _events.emit(HomeEvent.ShowError("Session konnte nicht erstellt werden: ${e.message}"))
                }
            _isLoading.value = false
        }
    }

    fun joinSession(code: String) {
        val trimmedCode = code.trim().uppercase()
        if (trimmedCode.length != 6) {
            viewModelScope.launch {
                _events.emit(HomeEvent.ShowError("Bitte gib einen 6-stelligen Session-Code ein"))
            }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            val guest = SessionUser(
                userId = spotifyRepository.userId ?: UUID.randomUUID().toString(),
                displayName = spotifyRepository.displayName ?: "Gast",
                isHost = false
            )
            sessionRepository.joinSessionByCode(trimmedCode, guest)
                .onSuccess { session ->
                    _events.emit(HomeEvent.NavigateToGuest(session))
                }
                .onFailure { e ->
                    _events.emit(HomeEvent.ShowError(e.message ?: "Session konnte nicht gefunden werden"))
                }
            _isLoading.value = false
        }
    }

    fun getSpotifyAuthRequest() = spotifyRepository.buildAuthRequest()
}
