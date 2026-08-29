package com.icarusalmighty.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class DeviceVideo(val uri: Uri, val name: String, val durationMs: Long, val sizeBytes: Long, val dateAdded: Long)

class MediaCatalog(private val context: Context) {
    fun listVideos(): List<DeviceVideo> {
        val videos = mutableListOf<DeviceVideo>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val date = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                videos += DeviceVideo(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id)),
                    cursor.getString(name).orEmpty(), cursor.getLong(duration), cursor.getLong(size), cursor.getLong(date)
                )
            }
        }
        return videos
    }
}
