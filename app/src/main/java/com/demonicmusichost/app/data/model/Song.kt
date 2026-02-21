package com.demonicmusichost.app.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class SongSource {
    SPOTIFY, YOUTUBE, LOCAL
}

@Parcelize
data class Song(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val thumbnailUrl: String = "",
    val durationMs: Long = 0L,
    val source: SongSource = SongSource.SPOTIFY,
    // Spotify-specific
    val spotifyUri: String = "",
    // YouTube-specific
    val youtubeVideoId: String = "",
    val youtubeStreamUrl: String = "",
    // Local-specific
    val localFilePath: String = "",
    // Queue metadata
    val addedByUserId: String = "",
    val addedByUserName: String = "",
    val queueTimestamp: Long = System.currentTimeMillis()
) : Parcelable {

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "artist" to artist,
        "album" to album,
        "thumbnailUrl" to thumbnailUrl,
        "durationMs" to durationMs,
        "source" to source.name,
        "spotifyUri" to spotifyUri,
        "youtubeVideoId" to youtubeVideoId,
        "youtubeStreamUrl" to youtubeStreamUrl,
        "localFilePath" to localFilePath,
        "addedByUserId" to addedByUserId,
        "addedByUserName" to addedByUserName,
        "queueTimestamp" to queueTimestamp
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Song = Song(
            id = map["id"] as? String ?: "",
            title = map["title"] as? String ?: "",
            artist = map["artist"] as? String ?: "",
            album = map["album"] as? String ?: "",
            thumbnailUrl = map["thumbnailUrl"] as? String ?: "",
            durationMs = (map["durationMs"] as? Long) ?: (map["durationMs"] as? Number)?.toLong() ?: 0L,
            source = SongSource.valueOf(map["source"] as? String ?: SongSource.SPOTIFY.name),
            spotifyUri = map["spotifyUri"] as? String ?: "",
            youtubeVideoId = map["youtubeVideoId"] as? String ?: "",
            youtubeStreamUrl = map["youtubeStreamUrl"] as? String ?: "",
            localFilePath = map["localFilePath"] as? String ?: "",
            addedByUserId = map["addedByUserId"] as? String ?: "",
            addedByUserName = map["addedByUserName"] as? String ?: "",
            queueTimestamp = (map["queueTimestamp"] as? Long) ?: (map["queueTimestamp"] as? Number)?.toLong()
                ?: System.currentTimeMillis()
        )
    }

    fun getFormattedDuration(): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
