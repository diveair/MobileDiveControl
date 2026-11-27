package com.mobiledivecontrol.ui.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy

/** Sparse preview/white-balance sampling; full-resolution YUV conversion uses ImageProxy.toBitmap. */
internal class AnalysisPixelReader(image: ImageProxy) {
    private class Plane(layout: ImageProxy.PlaneProxy) {
        val buffer = layout.buffer.duplicate()
        val rowStride = layout.rowStride
        val pixelStride = layout.pixelStride
        val limit = buffer.limit()

        fun component(x: Int, y: Int): Int {
            val offset = y * rowStride + x * pixelStride
            return if (offset in 0 until limit) buffer.get(offset).toInt() and 255 else -1
        }
    }

    private val layouts = image.planes
    private val first = layouts.getOrNull(0)?.let(::Plane)
    private val u = layouts.getOrNull(1)?.let(::Plane)
    private val v = layouts.getOrNull(2)?.let(::Plane)
    private val yuv = image.format == ImageFormat.YUV_420_888

    fun argb(x: Int, y: Int): Int? {
        val layout = first ?: return null
        if (yuv) {
            val luma = layout.component(x, y)
            val cb = u?.component(x / 2, y / 2) ?: return null
            val cr = v?.component(x / 2, y / 2) ?: return null
            return if (luma < 0 || cb < 0 || cr < 0) null else yuvToArgb(luma, cb, cr)
        }
        val buffer = layout.buffer
        val offset = y * layout.rowStride + x * layout.pixelStride
        if (layout.pixelStride < 4 || offset < 0 || offset + 3 >= buffer.limit()) return null
        return ((buffer.get(offset + 3).toInt() and 255) shl 24) or
            ((buffer.get(offset).toInt() and 255) shl 16) or
            ((buffer.get(offset + 1).toInt() and 255) shl 8) or (buffer.get(offset + 2).toInt() and 255)
    }
}

internal fun yuvToArgb(y: Int, u: Int, v: Int): Int {
    val luma = 298 * (y - 16).coerceAtLeast(0)
    val cb = u - 128
    val cr = v - 128
    val red = ((luma + 409 * cr + 128) shr 8).coerceIn(0, 255)
    val green = ((luma - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, 255)
    val blue = ((luma + 516 * cb + 128) shr 8).coerceIn(0, 255)
    return -0x1000000 or (red shl 16) or (green shl 8) or blue
}
