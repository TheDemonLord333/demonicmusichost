package com.demonicmusichost.backend.model

import kotlinx.serialization.Serializable

@Serializable
enum class SongSource { SPOTIFY, YOUTUBE }

@Serializable
data class Song(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val thumbnailUrl: String = "",
    val durationMs: Long = 0L,
    val source: SongSource = SongSource.SPOTIFY,
    val spotifyUri: String = "",
    val youtubeVideoId: String = "",
    val addedByUserId: String = "",
    val addedByUserName: String = "",
    val queueTimestamp: Long = System.currentTimeMillis()
)
