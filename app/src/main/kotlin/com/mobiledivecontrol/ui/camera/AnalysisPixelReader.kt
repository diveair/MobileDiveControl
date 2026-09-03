package com.mobiledivecontrol.ui.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy

/** Sparse preview/white-balance sampling; full-resolution YUV conversion uses ImageProxy.toBitmap. */
internal class AnalysisPixelReader(image: ImageProxy) {
    private val planes = image.planes
    private val buffers = planes.map { it.buffer.duplicate() }
    private val yuv = image.format == ImageFormat.YUV_420_888

    private fun component(plane: Int, x: Int, y: Int): Int? {
        val layout = planes.getOrNull(plane) ?: return null
        val buffer = buffers[plane]
        val offset = y * layout.rowStride + x * layout.pixelStride
        return if (offset in 0 until buffer.limit()) buffer.get(offset).toInt() and 255 else null
    }

    fun argb(x: Int, y: Int): Int? {
        if (yuv) return yuvToArgb(component(0, x, y) ?: return null,
            component(1, x / 2, y / 2) ?: return null, component(2, x / 2, y / 2) ?: return null)
        val layout = planes.firstOrNull() ?: return null
        val buffer = buffers.first()
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
