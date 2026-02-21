package com.demonicmusichost.app.data.repository

import com.demonicmusichost.app.BuildConfig
import com.demonicmusichost.app.data.model.SearchResult
import com.demonicmusichost.app.data.network.YouTubeApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeRepository @Inject constructor(
    private val youTubeApiService: YouTubeApiService
) {

    fun searchVideos(query: String): Flow<List<SearchResult.YouTubeVideo>> = flow {
        val searchResponse = youTubeApiService.searchVideos(
            query = query,
            apiKey = BuildConfig.YOUTUBE_API_KEY
        )

        val videoIds = searchResponse.items.map { it.id.videoId }.joinToString(",")

        val detailsResponse = if (videoIds.isNotEmpty()) {
            youTubeApiService.getVideoDetails(
                videoIds = videoIds,
                apiKey = BuildConfig.YOUTUBE_API_KEY
            )
        } else null

        val durationMap = detailsResponse?.items?.associate { item ->
            item.id to parseIsoDuration(item.contentDetails.duration)
        } ?: emptyMap()

        val results = searchResponse.items.map { item ->
            val durationMs = durationMap[item.id.videoId] ?: 0L
            SearchResult.YouTubeVideo(
                videoId = item.id.videoId,
                title = item.snippet.title,
                channelTitle = item.snippet.channelTitle,
                thumbnailUrl = item.snippet.thumbnails.getBestUrl(),
                duration = formatDuration(durationMs),
                durationMs = durationMs
            )
        }
        emit(results)
    }

    private fun parseIsoDuration(isoDuration: String): Long {
        // Parse ISO 8601 duration format (e.g., PT3M45S)
        val pattern = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
        val matcher = pattern.matcher(isoDuration)
        return if (matcher.matches()) {
            val hours = matcher.group(1)?.toLong() ?: 0L
            val minutes = matcher.group(2)?.toLong() ?: 0L
            val seconds = matcher.group(3)?.toLong() ?: 0L
            (hours * 3600 + minutes * 60 + seconds) * 1000
        } else 0L
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * Extracts video ID from various YouTube URL formats
     */
    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Pattern.compile("(?:v=|youtu\\.be/|embed/|shorts/)([\\w-]{11})"),
            Pattern.compile("([\\w-]{11})")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    /**
     * Returns the stream URL for a YouTube video via ExoPlayer-compatible format.
     * Note: Direct YouTube streaming requires youtube-dl or similar. In a production
     * app, use a backend service or the YouTube IFrame API in a WebView for playback.
     */
    fun getYouTubeEmbedUrl(videoId: String): String {
        return "https://www.youtube.com/embed/$videoId?autoplay=1&controls=0"
    }
}
