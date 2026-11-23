package com.mobiledivecontrol.ui.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.heifwriter.HeifWriter
import java.io.File

/** Encode the processed still, preserving its crop and EXIF orientation, before publishing it. */
internal object PhotoFileEncoder {
    fun publish(context: Context, source: File, format: String, baseName: String, relativePath: String): Uri {
        require(format == "PNG" || format == "HEIF")
        val extension = if (format == "PNG") "png" else "heic"
        val mime = if (format == "PNG") "image/png" else "image/heic"
        val encoded = File(source.parentFile, "$baseName.$extension")
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        var published: Uri? = null
        try {
            if (format == "PNG") encoded.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            else {
                val writer = HeifWriter.Builder(encoded.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(95).setMaxImages(1).build()
                try { writer.start(); writer.addBitmap(bitmap); writer.stop(30_000) } finally { writer.close() }
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, encoded.name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.WIDTH, bitmap.width)
                put(MediaStore.Images.Media.HEIGHT, bitmap.height)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            published = uri
            checkNotNull(resolver.openOutputStream(uri)).use { out -> encoded.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Throwable) {
            published?.let { context.contentResolver.delete(it, null, null) }
            throw error
        } finally {
            bitmap.recycle()
            encoded.delete()
            source.delete()
        }
    }
}
