package com.demonicmusichost.app.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.*

interface SpotifyApiService {

    @GET("search")
    suspend fun searchTracks(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("type") type: String = "track",
        @Query("limit") limit: Int = 20,
        @Query("market") market: String = "DE"
    ): SpotifySearchResponse

    @GET("tracks/{id}")
    suspend fun getTrack(
        @Header("Authorization") authorization: String,
        @Path("id") trackId: String
    ): SpotifyTrackItem

    @GET("me")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String
    ): SpotifyUserResponse

    @GET("me/player")
    suspend fun getCurrentPlaybackState(
        @Header("Authorization") authorization: String
    ): SpotifyPlaybackState?

    @GET("me/player/devices")
    suspend fun getDevices(
        @Header("Authorization") authorization: String
    ): SpotifyDevicesResponse

    @PUT("me/player/play")
    suspend fun startPlayback(
        @Header("Authorization") authorization: String,
        @Body body: SpotifyPlayRequest
    )

    @PUT("me/player")
    suspend fun transferPlayback(
        @Header("Authorization") authorization: String,
        @Body body: SpotifyTransferPlaybackRequest
    )

    @PUT("me/player/pause")
    suspend fun pausePlayback(
        @Header("Authorization") authorization: String
    )

    @POST("me/player/next")
    suspend fun skipToNext(
        @Header("Authorization") authorization: String
    )

    @POST("me/player/previous")
    suspend fun skipToPrevious(
        @Header("Authorization") authorization: String
    )

    @PUT("me/player/seek")
    suspend fun seekToPosition(
        @Header("Authorization") authorization: String,
        @Query("position_ms") positionMs: Long
    )

    @PUT("me/player/volume")
    suspend fun setVolume(
        @Header("Authorization") authorization: String,
        @Query("volume_percent") volumePercent: Int
    )
}

// Request/Response Models

data class SpotifySearchResponse(
    @SerializedName("tracks") val tracks: SpotifyTracksList
)

data class SpotifyTracksList(
    @SerializedName("items") val items: List<SpotifyTrackItem> = emptyList(),
    @SerializedName("total") val total: Int = 0,
    @SerializedName("next") val next: String? = null
)

data class SpotifyTrackItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("artists") val artists: List<SpotifyArtist> = emptyList(),
    @SerializedName("album") val album: SpotifyAlbum = SpotifyAlbum(),
    @SerializedName("duration_ms") val durationMs: Long = 0L,
    @SerializedName("uri") val uri: String = "",
    @SerializedName("preview_url") val previewUrl: String? = null,
    @SerializedName("explicit") val explicit: Boolean = false
)

data class SpotifyArtist(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = ""
)

data class SpotifyAlbum(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("images") val images: List<SpotifyImage> = emptyList()
) {
    fun getBestImageUrl(): String = images.maxByOrNull { it.width }?.url ?: ""
}

data class SpotifyImage(
    @SerializedName("url") val url: String = "",
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0
)

data class SpotifyUserResponse(
    @SerializedName("id") val id: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("email") val email: String = "",
    @SerializedName("images") val images: List<SpotifyImage> = emptyList(),
    @SerializedName("product") val product: String = "" // "premium" or "free"
) {
    fun isPremium(): Boolean = product == "premium"
}

data class SpotifyPlaybackState(
    @SerializedName("is_playing") val isPlaying: Boolean = false,
    @SerializedName("progress_ms") val progressMs: Long = 0L,
    @SerializedName("item") val item: SpotifyTrackItem? = null
)

data class SpotifyPlayRequest(
    @SerializedName("uris") val uris: List<String>? = null,
    @SerializedName("context_uri") val contextUri: String? = null,
    @SerializedName("position_ms") val positionMs: Long? = null
)

data class SpotifyTransferPlaybackRequest(
    @SerializedName("device_ids") val deviceIds: List<String>,
    @SerializedName("play") val play: Boolean = false
)

data class SpotifyDevicesResponse(
    @SerializedName("devices") val devices: List<SpotifyDevice> = emptyList()
)

data class SpotifyDevice(
    @SerializedName("id") val id: String = "",
    @SerializedName("is_active") val isActive: Boolean = false,
    @SerializedName("is_restricted") val isRestricted: Boolean = false,
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = ""
)
