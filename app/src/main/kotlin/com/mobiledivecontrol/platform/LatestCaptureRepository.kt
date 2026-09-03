package com.mobiledivecontrol.platform

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Observe published media, not camera state: photos, RAW, panorama and all video pipelines. */
internal class LatestCaptureRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun changes() = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    fun latest(): LatestCapture? = latestCaptureCover(
        rows(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) +
            rows(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true),
    )

    @Suppress("DEPRECATION")
    fun thumbnail(item: LatestCapture): Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
        resolver.loadThumbnail(Uri.parse(item.uri), Size(128, 128), null)
    } else if (item.isVideo) {
        MediaStore.Video.Thumbnails.getThumbnail(resolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    } else {
        MediaStore.Images.Thumbnails.getThumbnail(resolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
    }

    private fun rows(collection: Uri, isVideo: Boolean): List<LatestCapture> {
        val projection = buildList {
            add("_id")
            add("_display_name")
            add("_size")
            add("date_added")
            add("datetaken")
            if (Build.VERSION.SDK_INT >= 29) add("relative_path")
        }.toTypedArray()
        val selection = buildList {
            add("_size > 0")
            if (Build.VERSION.SDK_INT >= 29) add("is_pending = 0")
            if (Build.VERSION.SDK_INT >= 30) add("is_trashed = 0")
        }.joinToString(" AND ")
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_added DESC, _id DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 16)
        }
        return resolver.query(collection, projection, args, null)?.use { cursor ->
            buildList {
                // Bound cursor reads too, in case a vendor ignores QUERY_ARG_LIMIT.
                while (size < 16 && cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    // Our capture name has millisecond precision, including RAW/JPEG pairs.
                    val captureTime = name.takeIf { it.startsWith("DiveControl_") }
                        ?.substringBeforeLast('.')?.substringAfterLast('_')?.toLongOrNull()
                        ?: cursor.getLong(4).takeIf { it > 0 }
                        ?: (cursor.getLong(3) * 1000)
                    add(LatestCapture(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        name = name,
                        relativePath = if (Build.VERSION.SDK_INT >= 29) cursor.getString(5).orEmpty() else "",
                        isVideo = isVideo,
                        capturedAtMillis = captureTime,
                        sizeBytes = cursor.getLong(2),
                    ))
                }
            }
        }.orEmpty()
    }
}
