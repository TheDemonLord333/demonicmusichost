package com.demonicmusichost.backend.service

import com.demonicmusichost.backend.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class SpotifyService(
    private val client: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val redirectUri: String
) {
    private val log = LoggerFactory.getLogger(SpotifyService::class.java)

    // userId → SpotifyTokens
    private val tokenStore = ConcurrentHashMap<String, SpotifyTokens>()

    // ── OAuth ──────────────────────────────────────────────────────────────

    fun buildAuthUrl(state: String): String {
        val scopes = listOf(
            "streaming",
            "user-read-email",
            "user-read-private",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing"
        ).joinToString(" ")

        return "https://accounts.spotify.com/authorize?" + Parameters.build {
            append("client_id", clientId)
            append("response_type", "code")
            append("redirect_uri", redirectUri)
            append("scope", scopes)
            append("state", state)
        }.formUrlEncode()
    }

    suspend fun exchangeCode(code: String): SpotifyTokenResponse {
        val credentials = Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray())

        return client.submitForm(
            url = "https://accounts.spotify.com/api/token",
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
            }
        ) {
            header(HttpHeaders.Authorization, "Basic $credentials")
        }.body()
    }

    suspend fun refreshToken(userId: String): String? {
        val tokens = tokenStore[userId] ?: return null
        if (!tokens.isExpired()) return tokens.accessToken

        return try {
            val credentials = Base64.getEncoder()
                .encodeToString("$clientId:$clientSecret".toByteArray())

            val resp: SpotifyTokenResponse = client.submitForm(
                url = "https://accounts.spotify.com/api/token",
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", tokens.refreshToken)
                }
            ) {
                header(HttpHeaders.Authorization, "Basic $credentials")
            }.body()

            storeTokens(userId, resp.accessToken, tokens.refreshToken, resp.expiresIn)
            resp.accessToken
        } catch (e: Exception) {
            log.error("Token refresh failed for $userId: ${e.message}")
            null
        }
    }

    fun storeTokens(userId: String, accessToken: String, refreshToken: String, expiresInSeconds: Int) {
        tokenStore[userId] = SpotifyTokens(
            accessToken  = accessToken,
            refreshToken = refreshToken,
            expiresAt    = System.currentTimeMillis() + expiresInSeconds * 1000L
        )
    }

    fun getTokens(userId: String): SpotifyTokens? = tokenStore[userId]

    fun removeTokens(userId: String) = tokenStore.remove(userId)

    // ── API-Calls ──────────────────────────────────────────────────────────

    suspend fun getValidToken(userId: String): String? {
        val tokens = tokenStore[userId] ?: return null
        return if (tokens.isExpired()) refreshToken(userId) else tokens.accessToken
    }

    suspend fun getCurrentUser(accessToken: String): SpotifyUserInfo? {
        return try {
            val json: JsonObject = client.get("https://api.spotify.com/v1/me") {
                bearerAuth(accessToken)
            }.body()
            SpotifyUserInfo(
                id          = json["id"]?.jsonPrimitive?.content ?: "",
                displayName = json["display_name"]?.jsonPrimitive?.content ?: "Unknown",
                email       = json["email"]?.jsonPrimitive?.content ?: "",
                isPremium   = json["product"]?.jsonPrimitive?.content == "premium"
            )
        } catch (e: Exception) {
            log.error("getCurrentUser failed: ${e.message}")
            null
        }
    }

    suspend fun searchTracks(accessToken: String, query: String, limit: Int = 20): List<Song> {
        return try {
            val json: JsonObject = client.get("https://api.spotify.com/v1/search") {
                bearerAuth(accessToken)
                parameter("q", query)
                parameter("type", "track")
                parameter("limit", limit)
                parameter("market", "DE")
            }.body()

            json["tracks"]?.jsonObject?.get("items")?.jsonArray?.mapNotNull { item ->
                val track = item.jsonObject
                val id       = track["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name     = track["name"]?.jsonPrimitive?.content ?: ""
                val uri      = track["uri"]?.jsonPrimitive?.content ?: ""
                val duration = track["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val artists  = track["artists"]?.jsonArray
                    ?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" } ?: ""
                val albumObj  = track["album"]?.jsonObject
                val albumName = albumObj?.get("name")?.jsonPrimitive?.content ?: ""
                val thumbnail = albumObj?.get("images")?.jsonArray
                    ?.maxByOrNull { it.jsonObject["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                    ?.jsonObject?.get("url")?.jsonPrimitive?.content ?: ""

                Song(
                    id           = id,
                    title        = name,
                    artist       = artists,
                    album        = albumName,
                    thumbnailUrl = thumbnail,
                    durationMs   = duration,
                    source       = SongSource.SPOTIFY,
                    spotifyUri   = uri
                )
            } ?: emptyList()
        } catch (e: Exception) {
            log.error("searchTracks failed: ${e.message}")
            emptyList()
        }
    }
}

// ── Serialisierbare Hilfsdaten ─────────────────────────────────────────────

@Serializable
data class SpotifyTokenResponse(
    @SerialName("access_token")  val accessToken: String,
    @SerialName("token_type")    val tokenType: String,
    @SerialName("expires_in")    val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("scope")         val scope: String = ""
)

data class SpotifyUserInfo(
    val id: String,
    val displayName: String,
    val email: String,
    val isPremium: Boolean
)
