package com.mobiledivecontrol.ui.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AnalysisPixelReaderTest {
    private fun plane(bytes: ByteArray, rowStride: Int, pixelStride: Int) =
        object : ImageProxy.PlaneProxy {
            override fun getBuffer() = ByteBuffer.wrap(bytes)
            override fun getRowStride() = rowStride
            override fun getPixelStride() = pixelStride
        }

    private fun image(format: Int, vararg planes: ImageProxy.PlaneProxy): ImageProxy =
        Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getFormat" -> format
                "getPlanes" -> planes
                else -> error("Unexpected image access: ${method.name}")
            }
        } as ImageProxy

    @Test fun readsPaddedYuvRowsAndInterleavedChromaWithoutReadingPadding() {
        val luma = ByteArray(24) { 16 }.apply { this[15] = 81 }
        val u = byteArrayOf(128.toByte(), 0, 90, 0)
        val v = byteArrayOf(128.toByte(), 0, 240.toByte(), 0)
        val reader = AnalysisPixelReader(image(ImageFormat.YUV_420_888,
            plane(luma, 12, 1), plane(u, 4, 2), plane(v, 4, 2)))
        assertEquals(0xff000000.toInt(), reader.argb(0, 0))
        assertEquals(0xffff0000.toInt(), reader.argb(3, 1))
        assertNull(reader.argb(0, 2))
    }

    @Test fun preservesRgbaChannelsAndAlphaWithPaddedRows() {
        val reader = AnalysisPixelReader(image(ImageFormat.FLEX_RGBA_8888,
            plane(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0, 20, 30, 40, 255.toByte()), 8, 4)))
        assertEquals(0x04010203, reader.argb(0, 0))
        assertEquals(0xff141e28.toInt(), reader.argb(0, 1))
        assertNull(reader.argb(1, 1))
    }
}
