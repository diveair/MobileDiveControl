package com.mobiledivecontrol.platform

import android.content.ContentUris
import android.content.Context
import android.media.ExifInterface
import android.os.Environment
import android.provider.MediaStore
import com.mobiledivecontrol.core.GalleryItem
import com.mobiledivecontrol.core.GalleryTab
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryRepository(private val context: Context) {

    fun loadItems(tab: GalleryTab, currentFolder: String?): List<GalleryItem> {
        return when (tab) {
            GalleryTab.Photos -> loadMedia(isVideo = false, folder = currentFolder)
            GalleryTab.Videos -> loadMedia(isVideo = true, folder = currentFolder)
            GalleryTab.Folders -> loadFolders()
        }
    }

    private fun loadMedia(isVideo: Boolean, folder: String?): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        val uri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )

        val selection = if (folder != null) {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        } else {
            null
        }
        val selectionArgs = if (folder != null) {
            arrayOf("$folder/%")
        } else {
            null
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)

            while (cursor.moveToNext()) {
                items.add(
                    GalleryItem(
                        id = cursor.getLong(idCol),
                        name = cursor.getString(nameCol) ?: "",
                        path = cursor.getString(pathCol) ?: "",
                        isVideo = isVideo,
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(dateCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                    )
                )
            }
        }
        return items
    }

    private fun loadFolders(): List<GalleryItem> {
        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val folders = mutableListOf<GalleryItem>()
        var idCounter = -1L

        listOf(dcimDir, picturesDir).forEach { parentDir ->
            parentDir?.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                folders.add(
                    GalleryItem(
                        id = idCounter--,
                        name = dir.name,
                        path = dir.absolutePath,
                        isFolder = true,
                    )
                )
            }
        }
        return folders.sortedBy { it.name }
    }

    fun loadExifData(item: GalleryItem): List<String> {
        return try {
            if (item.isVideo) {
                listOf(
                    "Name: ${item.name}",
                    "Size: ${formatFileSize(item.sizeBytes)}",
                    "Date: ${formatDate(item.dateAdded)}",
                    "Resolution: ${item.width} × ${item.height}",
                    "Type: Video",
                )
            } else {
                val lines = mutableListOf<String>()
                lines.add("Name: ${item.name}")
                lines.add("Size: ${formatFileSize(item.sizeBytes)}")
                lines.add("Date: ${formatDate(item.dateAdded)}")
                lines.add("Resolution: ${item.width} × ${item.height}")

                try {
                    val exif = ExifInterface(item.path)
                    exif.getAttribute(ExifInterface.TAG_MAKE)?.let { lines.add("Camera: $it") }
                    exif.getAttribute(ExifInterface.TAG_MODEL)?.let { lines.add("Model: $it") }
                    exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { lines.add("Focal Length: $it") }
                    exif.getAttribute(ExifInterface.TAG_APERTURE_VALUE)?.let { lines.add("Aperture: f/$it") }
                    exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                        val seconds = it.toDoubleOrNull()
                        if (seconds != null && seconds > 0) {
                            val displayTime = if (seconds < 1) "1/${(1.0 / seconds).toInt()}" else "${seconds}s"
                            lines.add("Shutter: $displayTime")
                        }
                    }
                    exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { lines.add("ISO: $it") }
                    exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)?.let {
                        val wb = if (it == "0") "Auto" else "Manual"
                        lines.add("White Balance: $wb")
                    }
                    val latLong = FloatArray(2)
                    if (exif.getLatLong(latLong)) {
                        lines.add("GPS: %.4f, %.4f".format(latLong[0], latLong[1]))
                    }
                } catch (_: Exception) {
                    // EXIF not available for this file
                }
                lines
            }
        } catch (_: Exception) {
            listOf("Unable to read file metadata")
        }
    }

    fun deleteItem(item: GalleryItem) {
        try {
            val uri = if (item.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val contentUri = ContentUris.withAppendedId(uri, item.id)
            context.contentResolver.delete(contentUri, null, null)
        } catch (_: Exception) {
            // Deletion failed, may need MANAGE_EXTERNAL_STORAGE on Android 11+
        }
    }

    fun createFolder(name: String) {
        try {
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val newFolder = File(dcimDir, name)
            newFolder.mkdirs()
        } catch (_: Exception) {
            // Folder creation failed
        }
    }

    fun deleteFolder(path: String) {
        try {
            val folder = File(path)
            if (folder.isDirectory) {
                folder.deleteRecursively()
            }
        } catch (_: Exception) {
            // Folder deletion failed
        }
    }

    fun contentUriForItem(item: GalleryItem): android.net.Uri {
        val baseUri = if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(baseUri, item.id)
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatDate(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(epochSeconds * 1000))
    }
}
