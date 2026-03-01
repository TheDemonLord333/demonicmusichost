package com.demonicmusichost.app.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A simple singleton event bus for Spotify auth responses.
 */
object SpotifyAuthBus {
    private val _events = MutableSharedFlow<SpotifyAuthResult>(extraBufferCapacity = 1)
    val events: SharedFlow<SpotifyAuthResult> = _events.asSharedFlow()

    fun emit(result: SpotifyAuthResult) {
        _events.tryEmit(result)
    }
}
