package com.demonicmusichost.backend.service

import com.demonicmusichost.backend.model.Song
import com.demonicmusichost.backend.model.SongSource
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class YouTubeService(
    private val client: HttpClient,
    private val apiKey: String
) {
    private val log = LoggerFactory.getLogger(YouTubeService::class.java)

    suspend fun searchVideos(query: String, limit: Int = 20): List<Song> {
        if (apiKey.isBlank()) {
            log.warn("YouTube API key not configured")
            return emptyList()
        }
        return try {
            val json: JsonObject = client.get("https://www.googleapis.com/youtube/v3/search") {
                parameter("part", "snippet")
                parameter("q", query)
                parameter("type", "video")
                parameter("videoCategoryId", "10") // Music
                parameter("maxResults", limit)
                parameter("key", apiKey)
            }.body()

            json["items"]?.jsonArray?.mapNotNull { item ->
                val obj       = item.jsonObject
                val videoId   = obj["id"]?.jsonObject?.get("videoId")?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val snippet   = obj["snippet"]?.jsonObject ?: return@mapNotNull null
                val title     = snippet["title"]?.jsonPrimitive?.content ?: ""
                val channel   = snippet["channelTitle"]?.jsonPrimitive?.content ?: ""
                val thumbnail = snippet["thumbnails"]?.jsonObject
                    ?.get("high")?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: snippet["thumbnails"]?.jsonObject
                        ?.get("default")?.jsonObject?.get("url")?.jsonPrimitive?.content ?: ""

                Song(
                    id             = videoId,
                    title          = title,
                    artist         = channel,
                    album          = "",
                    thumbnailUrl   = thumbnail,
                    durationMs     = 0L,  // benötigt separaten videos.list-Call
                    source         = SongSource.YOUTUBE,
                    youtubeVideoId = videoId
                )
            } ?: emptyList()
        } catch (e: Exception) {
            log.error("YouTube search failed: ${e.message}")
            emptyList()
        }
    }
}
