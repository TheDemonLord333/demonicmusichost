package com.demonicmusichost.app.data.network

import com.demonicmusichost.app.data.model.PlaybackInfo
import com.demonicmusichost.app.data.model.PlaybackState
import com.demonicmusichost.app.data.model.Song
import com.demonicmusichost.app.data.model.SongSource
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronisiert den Zustand der Android-Host-Session mit dem Ktor-Backend.
 *
 * Alle Operationen sind fire-and-forget — Fehler werden geloggt aber nicht nach
 * oben weitergegeben, damit Backend-Ausfälle das native Playback nicht beeinflussen.
 *
 * Ablauf:
 *  1. Host-App ruft [registerAndCreateSession] auf → Token wird registriert, Session erstellt.
 *  2. [connectWebSocket] verbindet als "host" → Web-Gäste sehen den Host.
 *  3. Bei Playback-/Queue-Änderungen: [syncNowPlaying] / [syncQueueAdd] / [syncQueueRemove].
 *  4. Bei Token-Refresh: [refreshToken].
 *  5. Bei Session-Ende: [disconnect].
 */
@Singleton
class BackendSyncManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val config: BackendConfig
) {
    private val log = LoggerFactory.getLogger(BackendSyncManager::class.java)
    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Aktueller Session-Code (nach [registerAndCreateSession] gesetzt). */
    var sessionCode: String = ""
        private set

    /** Aktuelle Host-UserId (für WebSocket JOIN). */
    private var hostUserId: String = ""
    private var hostDisplayName: String = ""

    private var webSocket: WebSocket? = null

    // ── Session-Registrierung ──────────────────────────────────────────────

    /**
     * Registriert den Spotify-Access-Token beim Backend und erstellt eine Session.
     * Gibt den 6-stelligen Session-Code zurück oder null bei Fehler.
     */
    fun registerAndCreateSession(
        userId: String,
        displayName: String,
        accessToken: String,
        expiresIn: Int = 3600
    ): String? {
        if (!config.isConfigured()) return null
        return try {
            val body = gson.toJson(mapOf(
                "userId"      to userId,
                "displayName" to displayName,
                "accessToken" to accessToken,
                "refreshToken" to "",
                "expiresIn"   to expiresIn
            )).toRequestBody(json)

            val resp = okHttpClient.newCall(
                Request.Builder().url(config.registerTokenUrl()).post(body).build()
            ).execute()

            if (!resp.isSuccessful) {
                log.warn("register-token failed: ${resp.code} ${resp.message}")
                return null
            }

            val result = gson.fromJson(resp.body?.string(), JsonObject::class.java)
            val code   = result?.get("sessionCode")?.asString ?: return null

            sessionCode     = code
            hostUserId      = userId
            hostDisplayName = displayName
            log.info("Backend session created: code=$code")
            code
        } catch (e: Exception) {
            log.error("registerAndCreateSession failed: ${e.message}")
            null
        }
    }

    /**
     * Meldet einen neuen Access-Token an das Backend (nach SDK-seitigem Token-Refresh).
     */
    fun refreshToken(userId: String, newAccessToken: String, expiresIn: Int = 3600) {
        if (!config.isConfigured() || sessionCode.isBlank()) return
        try {
            val body = gson.toJson(mapOf(
                "userId"      to userId,
                "accessToken" to newAccessToken,
                "expiresIn"   to expiresIn
            )).toRequestBody(json)
            okHttpClient.newCall(
                Request.Builder().url(config.refreshTokenUrl()).post(body).build()
            ).execute().close()
        } catch (e: Exception) {
            log.warn("refreshToken sync failed: ${e.message}")
        }
    }

    // ── WebSocket ──────────────────────────────────────────────────────────

    /**
     * Verbindet die Android-App als "host" mit dem Backend-WebSocket.
     * Muss nach [registerAndCreateSession] aufgerufen werden.
     */
    fun connectWebSocket() {
        if (!config.isConfigured() || sessionCode.isBlank()) return
        val url = config.wsUrl(sessionCode)
        val req = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // JOIN-Nachricht senden
                ws.send(gson.toJson(mapOf(
                    "type" to "JOIN",
                    "payload" to mapOf(
                        "role"        to "host",
                        "userId"      to hostUserId,
                        "displayName" to hostDisplayName
                    )
                )))
                log.info("Backend WS connected [$sessionCode]")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                // Server-Nachrichten — für Android-Host nur WELCOME und GUEST_JOINED relevant
                log.debug("WS msg: $text")
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                log.warn("Backend WS failure: ${t.message}")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                log.info("Backend WS closed: $reason")
            }
        })
    }

    /** Schließt die WebSocket-Verbindung und setzt den State zurück. */
    fun disconnect() {
        webSocket?.close(1000, "session ended")
        webSocket    = null
        sessionCode  = ""
        hostUserId   = ""
    }

    // ── State-Sync (fire-and-forget REST) ─────────────────────────────────

    /**
     * Sync Now-Playing + Playback-State mit dem Backend.
     * Benachrichtigt alle Web-Gäste via Backend-WebSocket.
     */
    fun syncNowPlaying(song: Song?, isPlaying: Boolean, positionMs: Long) {
        if (!config.isConfigured() || sessionCode.isBlank()) return
        try {
            val body = gson.toJson(mapOf(
                "song" to song?.toBackendMap(),
                "playbackInfo" to mapOf(
                    "state"      to if (isPlaying) "PLAYING" else "PAUSED",
                    "positionMs" to positionMs,
                    "updatedAt"  to System.currentTimeMillis()
                )
            )).toRequestBody(json)
            okHttpClient.newCall(
                Request.Builder().url(config.nowPlayingUrl(sessionCode)).put(body).build()
            ).execute().close()
        } catch (e: Exception) {
            log.warn("syncNowPlaying failed: ${e.message}")
        }
    }

    /** Fügt einen Song zur Backend-Queue hinzu (damit Web-Gäste ihn sehen). */
    fun syncQueueAdd(song: Song) {
        if (!config.isConfigured() || sessionCode.isBlank()) return
        try {
            val body = gson.toJson(song.toBackendMap()).toRequestBody(json)
            okHttpClient.newCall(
                Request.Builder().url(config.queueUrl(sessionCode)).post(body).build()
            ).execute().close()
        } catch (e: Exception) {
            log.warn("syncQueueAdd failed: ${e.message}")
        }
    }

    /** Entfernt einen Song aus der Backend-Queue. */
    fun syncQueueRemove(songId: String) {
        if (!config.isConfigured() || sessionCode.isBlank()) return
        try {
            okHttpClient.newCall(
                Request.Builder().url(config.removeSongUrl(sessionCode, songId)).delete().build()
            ).execute().close()
        } catch (e: Exception) {
            log.warn("syncQueueRemove failed: ${e.message}")
        }
    }

    // ── Konvertierung ──────────────────────────────────────────────────────

    private fun Song.toBackendMap(): Map<String, Any?> = mapOf(
        "id"              to id,
        "title"           to title,
        "artist"          to artist,
        "album"           to album,
        "thumbnailUrl"    to thumbnailUrl,
        "durationMs"      to durationMs,
        "source"          to when (source) {
            SongSource.SPOTIFY  -> "SPOTIFY"
            SongSource.YOUTUBE  -> "YOUTUBE"
            SongSource.LOCAL    -> "YOUTUBE"   // LOCAL wird nicht ans Backend gesynct
        },
        "spotifyUri"      to spotifyUri,
        "youtubeVideoId"  to youtubeVideoId,
        "addedByUserId"   to addedByUserId,
        "addedByUserName" to addedByUserName,
        "queueTimestamp"  to queueTimestamp
    )
}
