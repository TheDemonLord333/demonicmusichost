package com.demonicmusichost.app.data.model

sealed class SearchResult {
    data class SpotifyTrack(
        val id: String,
        val name: String,
        val artists: List<String>,
        val albumName: String,
        val albumImageUrl: String,
        val durationMs: Long,
        val uri: String,
        val previewUrl: String?
    ) : SearchResult() {
        fun toSong(addedByUserId: String, addedByUserName: String): Song = Song(
            id = "spotify_$id",
            title = name,
            artist = artists.joinToString(", "),
            album = albumName,
            thumbnailUrl = albumImageUrl,
            durationMs = durationMs,
            source = SongSource.SPOTIFY,
            spotifyUri = uri,
            addedByUserId = addedByUserId,
            addedByUserName = addedByUserName
        )
    }

    data class YouTubeVideo(
        val videoId: String,
        val title: String,
        val channelTitle: String,
        val thumbnailUrl: String,
        val duration: String,
        val durationMs: Long
    ) : SearchResult() {
        fun toSong(addedByUserId: String, addedByUserName: String): Song = Song(
            id = "youtube_$videoId",
            title = title,
            artist = channelTitle,
            album = "",
            thumbnailUrl = thumbnailUrl,
            durationMs = durationMs,
            source = SongSource.YOUTUBE,
            youtubeVideoId = videoId,
            addedByUserId = addedByUserId,
            addedByUserName = addedByUserName
        )
    }

    data class LocalFile(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val filePath: String,
        val durationMs: Long,
        val albumArtUri: String?
    ) : SearchResult() {
        fun toSong(addedByUserId: String, addedByUserName: String): Song = Song(
            id = "local_$id",
            title = title,
            artist = artist,
            album = album,
            thumbnailUrl = albumArtUri ?: "",
            durationMs = durationMs,
            source = SongSource.LOCAL,
            localFilePath = filePath,
            addedByUserId = addedByUserId,
            addedByUserName = addedByUserName
        )
    }
}
