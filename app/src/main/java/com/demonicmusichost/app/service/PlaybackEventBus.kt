package com.demonicmusichost.app.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped event bus for playback lifecycle signals.
 * Used to propagate "song ended" from MusicService and SpotifyRepository
 * to HostViewModel without tight coupling.
 */
@Singleton
class PlaybackEventBus @Inject constructor() {

    private val _songEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits whenever the currently playing song ends naturally (not skipped). */
    val songEnded: SharedFlow<Unit> = _songEnded.asSharedFlow()

    /** Call from any thread; safe because extraBufferCapacity = 1. */
    fun notifySongEnded() {
        _songEnded.tryEmit(Unit)
    }
}
