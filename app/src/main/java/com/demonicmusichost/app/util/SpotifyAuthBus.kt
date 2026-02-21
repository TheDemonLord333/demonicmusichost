package com.demonicmusichost.app.util

import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A simple singleton event bus for Spotify auth responses.
 */
object SpotifyAuthBus {
    private val _events = MutableSharedFlow<AuthorizationResponse>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthorizationResponse> = _events.asSharedFlow()

    fun emit(response: AuthorizationResponse) {
        _events.tryEmit(response)
    }
}
