package com.demonicmusichost.app.data.repository

import com.demonicmusichost.app.data.model.PlaybackInfo
import com.demonicmusichost.app.data.model.PlaybackState
import com.demonicmusichost.app.data.model.Session
import com.demonicmusichost.app.data.model.SessionUser
import com.demonicmusichost.app.data.model.Song
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class SessionRepository @Inject constructor(
    private val database: FirebaseDatabase
) {

    companion object {
        private const val SESSIONS_REF = "sessions"
        private const val SESSION_CODE_LENGTH = 6
    }

    private val sessionsRef = database.getReference(SESSIONS_REF)

    // ─── Host Operations ──────────────────────────────────────────────────────

    suspend fun createSession(host: SessionUser): Result<Session> {
        return try {
            val sessionId = UUID.randomUUID().toString()
            val sessionCode = generateSessionCode()

            val session = Session(
                sessionId = sessionId,
                sessionCode = sessionCode,
                hostUserId = host.userId,
                hostDisplayName = host.displayName,
                guests = mapOf(host.userId to host.copy(isHost = true))
            )

            val sessionMap = mapOf(
                "sessionId" to sessionId,
                "sessionCode" to sessionCode,
                "hostUserId" to host.userId,
                "hostDisplayName" to host.displayName,
                "currentSong" to null,
                "queue" to emptyList<Any>(),
                "playbackInfo" to session.playbackInfo.toMap(),
                "guestsCanAddSongs" to true,
                "maxGuests" to 50,
                "createdAt" to System.currentTimeMillis(),
                "isActive" to true,
                "guests" to mapOf(host.userId to host.copy(isHost = true).toMap())
            )

            sessionsRef.child(sessionId).setValue(sessionMap).await()
            // Create a reverse lookup index: code -> sessionId
            database.getReference("session_codes").child(sessionCode).setValue(sessionId).await()

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun endSession(sessionId: String): Result<Unit> {
        return try {
            val session = getSessionSnapshot(sessionId)
            val code = session?.child("sessionCode")?.getValue(String::class.java)

            sessionsRef.child(sessionId).child("isActive").setValue(false).await()
            code?.let {
                database.getReference("session_codes").child(it).removeValue().await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePlaybackInfo(sessionId: String, info: PlaybackInfo): Result<Unit> {
        return try {
            sessionsRef.child(sessionId).child("playbackInfo").setValue(info.toMap()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setCurrentSong(sessionId: String, song: Song?): Result<Unit> {
        return try {
            sessionsRef.child(sessionId).child("currentSong").setValue(song?.toMap()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFromQueue(sessionId: String, songId: String): Result<Unit> {
        return try {
            val queueRef = sessionsRef.child(sessionId).child("queue")
            val snapshot = queueRef.get().await()
            val queueList = snapshot.children.mapNotNull { it.value as? Map<String, Any?> }
            val updated = queueList.filter { it["id"] != songId }
            queueRef.setValue(updated).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reorderQueue(sessionId: String, songs: List<Song>): Result<Unit> {
        return try {
            val songMaps = songs.map { it.toMap() }
            sessionsRef.child(sessionId).child("queue").setValue(songMaps).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setGuestsCanAddSongs(sessionId: String, canAdd: Boolean): Result<Unit> {
        return try {
            sessionsRef.child(sessionId).child("guestsCanAddSongs").setValue(canAdd).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeGuest(sessionId: String, guestUserId: String): Result<Unit> {
        return try {
            sessionsRef.child(sessionId).child("guests").child(guestUserId).removeValue().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Guest Operations ─────────────────────────────────────────────────────

    suspend fun joinSessionByCode(sessionCode: String, guest: SessionUser): Result<Session> {
        return try {
            val sessionId = resolveSessionCode(sessionCode)
                ?: return Result.failure(Exception("Session nicht gefunden"))

            val snapshot = getSessionSnapshot(sessionId)
                ?: return Result.failure(Exception("Session nicht gefunden"))

            val isActive = snapshot.child("isActive").getValue(Boolean::class.java) ?: false
            if (!isActive) return Result.failure(Exception("Diese Session ist nicht mehr aktiv"))

            val guestCount = snapshot.child("guests").childrenCount
            val maxGuests = snapshot.child("maxGuests").getValue(Long::class.java) ?: 50L
            if (guestCount >= maxGuests) return Result.failure(Exception("Session ist voll"))

            sessionsRef.child(sessionId).child("guests").child(guest.userId)
                .setValue(guest.toMap()).await()

            val session = parseSession(sessionId, snapshot)
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun leaveSession(sessionId: String, userId: String): Result<Unit> {
        return try {
            sessionsRef.child(sessionId).child("guests").child(userId).removeValue().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addSongToQueue(sessionId: String, song: Song): Result<Unit> {
        return try {
            val queueRef = sessionsRef.child(sessionId).child("queue")
            val snapshot = queueRef.get().await()

            @Suppress("UNCHECKED_CAST")
            val currentQueue = snapshot.children.mapNotNull {
                it.value as? Map<String, Any?>
            }.toMutableList()

            currentQueue.add(song.toMap())
            queueRef.setValue(currentQueue).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Real-time Observers ──────────────────────────────────────────────────

    fun observeSession(sessionId: String): Flow<Session?> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                trySend(parseSession(sessionId, snapshot))
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        val ref = sessionsRef.child(sessionId)
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun observeQueue(sessionId: String): Flow<List<Song>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                val queue = snapshot.children.mapNotNull { child ->
                    (child.value as? Map<String, Any?>)?.let { Song.fromMap(it) }
                }
                trySend(queue)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        val ref = sessionsRef.child(sessionId).child("queue")
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun resolveSessionCode(code: String): String? {
        val snapshot = database.getReference("session_codes").child(code).get().await()
        return snapshot.getValue(String::class.java)
    }

    private suspend fun getSessionSnapshot(sessionId: String): DataSnapshot? {
        val snapshot = sessionsRef.child(sessionId).get().await()
        return if (snapshot.exists()) snapshot else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSession(sessionId: String, snapshot: DataSnapshot): Session {
        val currentSongMap = snapshot.child("currentSong").value as? Map<String, Any?>
        val currentSong = currentSongMap?.let { Song.fromMap(it) }

        val queue = snapshot.child("queue").children.mapNotNull { child ->
            (child.value as? Map<String, Any?>)?.let { Song.fromMap(it) }
        }

        val playbackMap = snapshot.child("playbackInfo").value as? Map<String, Any?>
        val playbackInfo = playbackMap?.let { PlaybackInfo.fromMap(it) } ?: PlaybackInfo()

        val guests = snapshot.child("guests").children.mapNotNull { guestSnapshot ->
            val guestMap = guestSnapshot.value as? Map<String, Any?> ?: return@mapNotNull null
            val userId = guestMap["userId"] as? String ?: return@mapNotNull null
            userId to SessionUser.fromMap(guestMap)
        }.toMap()

        return Session(
            sessionId = snapshot.child("sessionId").getValue(String::class.java) ?: sessionId,
            sessionCode = snapshot.child("sessionCode").getValue(String::class.java) ?: "",
            hostUserId = snapshot.child("hostUserId").getValue(String::class.java) ?: "",
            hostDisplayName = snapshot.child("hostDisplayName").getValue(String::class.java) ?: "",
            currentSong = currentSong,
            queue = queue,
            playbackInfo = playbackInfo,
            guests = guests,
            guestsCanAddSongs = snapshot.child("guestsCanAddSongs").getValue(Boolean::class.java)
                ?: true,
            maxGuests = snapshot.child("maxGuests").getValue(Long::class.java)?.toInt() ?: 50,
            createdAt = snapshot.child("createdAt").getValue(Long::class.java)
                ?: System.currentTimeMillis(),
            isActive = snapshot.child("isActive").getValue(Boolean::class.java) ?: true
        )
    }

    private fun generateSessionCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Excludes confusing chars
        return (1..SESSION_CODE_LENGTH).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
