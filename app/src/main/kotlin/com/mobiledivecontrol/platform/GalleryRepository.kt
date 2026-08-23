package com.mobiledivecontrol.platform

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mobiledivecontrol.core.GalleryItem
import com.mobiledivecontrol.core.GalleryTab
import com.mobiledivecontrol.core.RecordingSaveLocation
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MediaStore-backed gallery. No filesystem crawl or deprecated DATA column is used on Android Q+. */
class GalleryRepository(private val context: Context) {

    private val albumPreferences by lazy {
        context.getSharedPreferences(CREATED_ALBUMS_PREFERENCES, Context.MODE_PRIVATE)
    }

    /** Null [currentFolder] is the album grid; a value is a MediaStore BUCKET_ID. */
    fun loadItems(@Suppress("UNUSED_PARAMETER") tab: GalleryTab, currentFolder: String?): List<GalleryItem> {
        val media = loadAllMedia()
        return if (currentFolder == null) {
            buildAlbums(media)
        } else {
            media.filter { it.albumId == currentFolder }.sortedByDescending { it.dateAdded }
        }
    }

    /** Writable MediaStore destinations suitable for video output and housing selection. */
    fun loadRecordingSaveLocations(): List<RecordingSaveLocation> {
        val albums = buildAlbums(loadAllMedia()).mapNotNull { album ->
            val relativePath = album.relativePath?.ensureTrailingSlash() ?: return@mapNotNull null
            val normalized = relativePath.lowercase(Locale.ROOT)
            if (!normalized.startsWith("dcim/") && !normalized.startsWith("movies/")) {
                return@mapNotNull null
            }
            RecordingSaveLocation(
                name = album.name.ifBlank { relativePath.trimEnd('/').substringAfterLast('/') },
                relativePath = relativePath,
            )
        }
        return (listOf(RecordingSaveLocation.Default) + albums)
            .distinctBy { it.relativePath.trimEnd('/').lowercase(Locale.ROOT) }
    }

    private fun loadAllMedia(): List<GalleryItem> = buildList {
        addAll(loadMediaRows(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false))
        addAll(loadMediaRows(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true))
    }.sortedByDescending { it.dateAdded }

    private fun loadMediaRows(uri: Uri, isVideo: Boolean): List<GalleryItem> {
        val rows = mutableListOf<GalleryItem>()
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.Images.ImageColumns.BUCKET_ID)
            add(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.BUCKET_ID)
                val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
                val locationColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn).orEmpty()
                    val rawLocation = cursor.getString(locationColumn).orEmpty()
                    val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        rawLocation
                    } else {
                        File(rawLocation).parent.orEmpty()
                    }
                    val bucketId = cursor.getString(bucketIdColumn)
                        ?: relativePath.trimEnd('/').ifBlank { "unfiled" }
                    val bucketName = cursor.getString(bucketNameColumn)
                        ?: relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "Other" }

                    rows += GalleryItem(
                        id = id,
                        name = name,
                        path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "$relativePath$name" else rawLocation,
                        contentUri = ContentUris.withAppendedId(uri, id).toString(),
                        albumId = bucketId,
                        folderDisplayName = bucketName,
                        relativePath = relativePath,
                        isVideo = isVideo,
                        sizeBytes = cursor.getLong(sizeColumn),
                        dateAdded = cursor.getLong(dateColumn),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                    )
                }
            }
        } catch (_: SecurityException) {
            // The permission surface owns the explanation; return the media type still permitted.
        } catch (_: IllegalArgumentException) {
            // Vendor MediaStore omitted a projected column. The other media type may still load.
        }
        return rows
    }

    private fun buildAlbums(media: List<GalleryItem>): List<GalleryItem> {
        data class Album(
            val id: String,
            val name: String,
            var count: Int,
            var latest: Long,
            var cover: GalleryItem,
        )

        val albums = linkedMapOf<String, Album>()
        media.forEach { item ->
            val id = item.albumId ?: return@forEach
            val existing = albums[id]
            if (existing == null) {
                albums[id] = Album(
                    id = id,
                    name = item.folderDisplayName.ifBlank { "Other" },
                    count = 1,
                    latest = item.dateAdded,
                    cover = item,
                )
            } else {
                existing.count += 1
                if (item.dateAdded > existing.latest) {
                    existing.latest = item.dateAdded
                    existing.cover = item
                }
            }
        }

        val mediaAlbums = albums.values
            .sortedByDescending { it.latest }
            .map { album ->
                GalleryItem(
                    id = album.id.toLongOrNull() ?: album.id.hashCode().toLong(),
                    name = album.name,
                    path = album.id,
                    contentUri = album.cover.contentUri,
                    albumId = album.id,
                    folderDisplayName = album.name,
                    relativePath = album.cover.relativePath,
                    isVideo = album.cover.isVideo,
                    isFolder = true,
                    mediaCount = album.count,
                    dateAdded = album.latest,
                )
            }
        val occupiedPaths = mediaAlbums.mapNotNull { it.relativePath?.normalizedAlbumPath() }.toSet()
        val emptyCreatedAlbums = createdAlbumPaths()
            .filterNot { it.normalizedAlbumPath() in occupiedPaths }
            .sorted()
            .map { relativePath ->
                GalleryItem(
                    id = relativePath.hashCode().toLong(),
                    name = relativePath.trimEnd('/').substringAfterLast('/'),
                    path = "$EMPTY_ALBUM_PREFIX$relativePath",
                    albumId = "$EMPTY_ALBUM_PREFIX$relativePath",
                    folderDisplayName = relativePath.trimEnd('/').substringAfterLast('/'),
                    relativePath = relativePath,
                    isFolder = true,
                    mediaCount = 0,
                )
            }
        return pinCreatedAlbumsFirst(
            albums = mediaAlbums + emptyCreatedAlbums,
            createdRelativePathsNewestFirst = createdAlbumOrder(),
        )
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
                buildList {
                    add("Name: ${item.name}")
                    add("Size: ${formatFileSize(item.sizeBytes)}")
                    add("Date: ${formatDate(item.dateAdded)}")
                    add("Resolution: ${item.width} × ${item.height}")
                    try {
                        context.contentResolver.openInputStream(contentUriForItem(item))?.use { input ->
                            val exif = ExifInterface(input)
                            exif.getAttribute(ExifInterface.TAG_MAKE)?.let { add("Camera: $it") }
                            exif.getAttribute(ExifInterface.TAG_MODEL)?.let { add("Model: $it") }
                            exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { add("Focal Length: $it") }
                            exif.getAttribute(ExifInterface.TAG_APERTURE_VALUE)?.let { add("Aperture: f/$it") }
                            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { seconds ->
                                if (seconds > 0) {
                                    add("Shutter: ${if (seconds < 1) "1/${(1.0 / seconds).toInt()}" else "${seconds}s"}")
                                }
                            }
                            exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { add("ISO: $it") }
                            exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)?.let {
                                add("White Balance: ${if (it == "0") "Auto" else "Manual"}")
                            }
                            val coordinates = FloatArray(2)
                            if (exif.getLatLong(coordinates)) {
                                add("GPS: %.4f, %.4f".format(coordinates[0], coordinates[1]))
                            }
                        }
                    } catch (_: Exception) {
                        // Base MediaStore details above remain useful when EXIF is unavailable.
                    }
                }
            }
        } catch (_: Exception) {
            listOf("Unable to read file metadata")
        }
    }

    fun deleteItem(item: GalleryItem): Result<Unit> = try {
        val changed = context.contentResolver.delete(contentUriForItem(item), null, null)
        if (changed > 0) Result.success(Unit) else Result.failure(IllegalStateException("File was not deleted."))
    } catch (error: SecurityException) {
        Result.failure(error)
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun deleteAlbum(album: GalleryItem): Result<Unit> = try {
        albumItems(album).forEach { item -> deleteItem(item).getOrThrow() }
        finishAlbumDeletion(album)
        Result.success(Unit)
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun createAlbumDeleteRequest(album: GalleryItem): PendingIntent? {
        val uris = albumItems(album).map(::contentUriForItem)
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return MediaStore.createDeleteRequest(context.contentResolver, uris)
    }

    fun finishAlbumDeletion(album: GalleryItem) {
        album.relativePath?.let(::forgetCreatedAlbum)
    }

    fun moveItem(item: GalleryItem, targetAlbum: GalleryItem): Result<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(UnsupportedOperationException("Moving media requires Android 10 or newer."))
        }
        val destination = targetAlbum.relativePath?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("The destination album has no writable path."))
        return updateItem(
            item = item,
            values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, destination.ensureTrailingSlash())
            },
            deniedMessage = "Android did not grant permission to move this file.",
        )
    }

    fun renameItem(item: GalleryItem, requestedName: String): Result<Unit> {
        val trimmed = requestedName.trim()
        if (trimmed.isBlank() || trimmed.any { it == '/' || it == '\\' || it == '\u0000' }) {
            return Result.failure(IllegalArgumentException("Enter a valid file name."))
        }
        val originalExtension = item.name.substringAfterLast('.', missingDelimiterValue = "")
        val displayName = if (originalExtension.isNotBlank() && '.' !in trimmed) {
            "$trimmed.$originalExtension"
        } else {
            trimmed
        }
        return updateItem(
            item = item,
            values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) },
            deniedMessage = "Android did not grant permission to rename this file.",
        )
    }

    /**
     * Builds Android's mandatory per-file consent prompt for media owned by another app.
     * Delete requests perform the deletion when approved; write requests grant access and the
     * caller must retry its move/rename after Activity.RESULT_OK.
     */
    fun createConsentRequest(
        item: GalleryItem,
        delete: Boolean,
        cause: Throwable?,
    ): PendingIntent? {
        val recoverable = generateSequence(cause) { it.cause }
            .filterIsInstance<RecoverableSecurityException>()
            .firstOrNull()
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && recoverable != null) {
            return recoverable.userAction.actionIntent
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uri = contentUriForItem(item)
        return if (delete) {
            MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
        } else {
            MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
        }
    }

    private fun updateItem(item: GalleryItem, values: ContentValues, deniedMessage: String): Result<Unit> = try {
        val changed = context.contentResolver.update(contentUriForItem(item), values, null, null)
        if (changed > 0) Result.success(Unit) else Result.failure(IllegalStateException("The file was not changed."))
    } catch (error: SecurityException) {
        Result.failure(SecurityException(deniedMessage, error))
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun createFolder(name: String): Result<Unit> = runCatching {
        val safeName = name.trim()
        require(safeName.isNotBlank() && safeName.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "Enter a valid album name."
        }
        val basePath = "${Environment.DIRECTORY_DCIM}/$safeName/"
        val usedPaths = buildAlbums(loadAllMedia())
            .mapNotNull { it.relativePath?.normalizedAlbumPath() }
            .toMutableSet()
            .apply { addAll(createdAlbumPaths().map { it.normalizedAlbumPath() }) }
        var candidate = basePath
        var suffix = 2
        while (candidate.normalizedAlbumPath() in usedPaths) {
            candidate = "${Environment.DIRECTORY_DCIM}/$safeName $suffix/"
            suffix += 1
        }
        val existingPaths = createdAlbumPaths()
        val newestFirst = (listOf(candidate) + createdAlbumOrder())
            .distinctBy { it.normalizedAlbumPath() }
        albumPreferences.edit()
            .putStringSet(CREATED_ALBUMS_KEY, existingPaths + candidate)
            .putString(CREATED_ALBUM_ORDER_KEY, JSONArray(newestFirst).toString())
            .putString(LAST_CREATED_ALBUM_KEY, candidate)
            .apply()
    }

    fun deleteFolder(path: String) {
        runCatching { File(path).takeIf { it.isDirectory }?.delete() }
    }

    private fun albumItems(album: GalleryItem): List<GalleryItem> {
        val targetPath = album.relativePath?.normalizedAlbumPath()
        return loadAllMedia().filter { item ->
            item.albumId == album.path ||
                (targetPath != null && item.relativePath?.normalizedAlbumPath() == targetPath)
        }
    }

    private fun createdAlbumPaths(): Set<String> =
        albumPreferences.getStringSet(CREATED_ALBUMS_KEY, emptySet()).orEmpty().toSet()

    private fun createdAlbumOrder(): List<String> {
        val existingPaths = createdAlbumPaths()
        val existingByNormalizedPath = existingPaths.associateBy { it.normalizedAlbumPath() }
        val storedOrder = albumPreferences.getString(CREATED_ALBUM_ORDER_KEY, null)
            ?.let { encoded ->
                runCatching {
                    val array = JSONArray(encoded)
                    List(array.length()) { index -> array.getString(index) }
                }.getOrDefault(emptyList())
            }
            .orEmpty()
        val legacyLastCreated = albumPreferences.getString(LAST_CREATED_ALBUM_KEY, null)
        return (storedOrder + listOfNotNull(legacyLastCreated) + existingPaths.sorted())
            .mapNotNull { path -> existingByNormalizedPath[path.normalizedAlbumPath()] }
            .distinctBy { it.normalizedAlbumPath() }
    }

    private fun forgetCreatedAlbum(relativePath: String) {
        val target = relativePath.normalizedAlbumPath()
        val remaining = createdAlbumPaths().filterNot { it.normalizedAlbumPath() == target }.toSet()
        val remainingOrder = createdAlbumOrder().filterNot { it.normalizedAlbumPath() == target }
        val editor = albumPreferences.edit()
            .putStringSet(CREATED_ALBUMS_KEY, remaining)
            .putString(CREATED_ALBUM_ORDER_KEY, JSONArray(remainingOrder).toString())
        remainingOrder.firstOrNull()?.let { editor.putString(LAST_CREATED_ALBUM_KEY, it) }
            ?: editor.remove(LAST_CREATED_ALBUM_KEY)
        editor.apply()
    }

    private fun String.normalizedAlbumPath(): String = ensureTrailingSlash().lowercase(Locale.ROOT)

    fun contentUriForItem(item: GalleryItem): Uri {
        if (item.contentUri.isNotBlank()) return Uri.parse(item.contentUri)
        val baseUri = if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(baseUri, item.id)
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatDate(epochSeconds: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return formatter.format(Date(epochSeconds * 1000))
    }

    private companion object {
        const val CREATED_ALBUMS_PREFERENCES = "gallery-created-albums"
        const val CREATED_ALBUMS_KEY = "relative-paths"
        const val CREATED_ALBUM_ORDER_KEY = "relative-path-order-newest-first"
        const val LAST_CREATED_ALBUM_KEY = "last-created-relative-path"
        const val EMPTY_ALBUM_PREFIX = "empty:"
    }
}

/** Pins every app-created album newest-first, preserving repository order for all other albums. */
internal fun pinCreatedAlbumsFirst(
    albums: List<GalleryItem>,
    createdRelativePathsNewestFirst: List<String>,
): List<GalleryItem> {
    val rankByPath = createdRelativePathsNewestFirst
        .mapNotNull { path ->
            path.trim().trimEnd('/').lowercase(Locale.ROOT)
                .takeIf { it.isNotEmpty() }
        }
        .distinct()
        .withIndex()
        .associate { (rank, path) -> path to rank }
    if (rankByPath.isEmpty()) return albums
    val pinned = albums.mapIndexedNotNull { originalIndex, album ->
        val normalizedPath = album.relativePath?.trim()?.trimEnd('/')?.lowercase(Locale.ROOT)
        val rank = rankByPath[normalizedPath] ?: return@mapIndexedNotNull null
        Triple(rank, originalIndex, album)
    }
        .sortedWith(compareBy<Triple<Int, Int, GalleryItem>> { it.first }.thenBy { it.second })
        .map { it.third }
    if (pinned.isEmpty()) return albums
    val pinnedPaths = pinned.mapNotNull { it.relativePath?.trim()?.trimEnd('/')?.lowercase(Locale.ROOT) }.toSet()
    return buildList(albums.size) {
        addAll(pinned)
        addAll(albums.filterNot { it.relativePath?.trim()?.trimEnd('/')?.lowercase(Locale.ROOT) in pinnedPaths })
    }
}
