package com.demonicmusichost.app.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface YouTubeApiService {

    @GET("search")
    suspend fun searchVideos(
        @Query("part") part: String = "snippet",
        @Query("q") query: String,
        @Query("type") type: String = "video",
        @Query("videoCategoryId") videoCategoryId: String = "10", // Music category
        @Query("maxResults") maxResults: Int = 20,
        @Query("key") apiKey: String
    ): YouTubeSearchResponse

    @GET("videos")
    suspend fun getVideoDetails(
        @Query("part") part: String = "contentDetails,snippet",
        @Query("id") videoIds: String,
        @Query("key") apiKey: String
    ): YouTubeVideoDetailsResponse
}

data class YouTubeSearchResponse(
    @SerializedName("items") val items: List<YouTubeSearchItem> = emptyList(),
    @SerializedName("nextPageToken") val nextPageToken: String? = null,
    @SerializedName("pageInfo") val pageInfo: PageInfo? = null
)

data class YouTubeSearchItem(
    @SerializedName("id") val id: VideoId,
    @SerializedName("snippet") val snippet: SearchSnippet
)

data class VideoId(
    @SerializedName("videoId") val videoId: String = ""
)

data class SearchSnippet(
    @SerializedName("title") val title: String = "",
    @SerializedName("channelTitle") val channelTitle: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("thumbnails") val thumbnails: Thumbnails
)

data class Thumbnails(
    @SerializedName("default") val default: Thumbnail? = null,
    @SerializedName("medium") val medium: Thumbnail? = null,
    @SerializedName("high") val high: Thumbnail? = null,
    @SerializedName("standard") val standard: Thumbnail? = null,
    @SerializedName("maxres") val maxres: Thumbnail? = null
) {
    fun getBestUrl(): String = (maxres ?: standard ?: high ?: medium ?: default)?.url ?: ""
}

data class Thumbnail(
    @SerializedName("url") val url: String = ""
)

data class YouTubeVideoDetailsResponse(
    @SerializedName("items") val items: List<YouTubeVideoDetailItem> = emptyList()
)

data class YouTubeVideoDetailItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("contentDetails") val contentDetails: ContentDetails,
    @SerializedName("snippet") val snippet: SearchSnippet
)

data class ContentDetails(
    @SerializedName("duration") val duration: String = "" // ISO 8601 format e.g. PT3M45S
)

data class PageInfo(
    @SerializedName("totalResults") val totalResults: Int = 0,
    @SerializedName("resultsPerPage") val resultsPerPage: Int = 0
)
