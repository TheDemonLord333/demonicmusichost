package com.demonicmusichost.app.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.demonicmusichost.app.data.model.SearchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMusicRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getAllLocalMusic(): Flow<List<SearchResult.LocalFile>> = flow {
        emit(queryLocalMusic(""))
    }.flowOn(Dispatchers.IO)

    fun searchLocalMusic(query: String): Flow<List<SearchResult.LocalFile>> = flow {
        emit(queryLocalMusic(query))
    }.flowOn(Dispatchers.IO)

    private fun queryLocalMusic(query: String): List<SearchResult.LocalFile> {
        val results = mutableListOf<SearchResult.LocalFile>()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC
        )

        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            if (query.isNotBlank()) {
                append(
                    " AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR " +
                            "${MediaStore.Audio.Media.ARTIST} LIKE ? OR " +
                            "${MediaStore.Audio.Media.ALBUM} LIKE ?)"
                )
            }
        }

        val selectionArgs = if (query.isNotBlank()) {
            val q = "%$query%"
            arrayOf(q, q, q)
        } else null

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown Title"
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val album = cursor.getString(albumCol) ?: "Unknown Album"
                val albumId = cursor.getLong(albumIdCol)
                val filePath = cursor.getString(dataCol) ?: ""
                val duration = cursor.getLong(durationCol)

                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                ).toString()

                results.add(
                    SearchResult.LocalFile(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        filePath = filePath,
                        durationMs = duration,
                        albumArtUri = albumArtUri
                    )
                )
            }
        }

        return results
    }

    fun getMediaUri(filePath: String): Uri = Uri.parse(filePath)
}
