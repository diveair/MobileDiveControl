package com.mobiledivecontrol.ui.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PreviewPixelSamplingTest {
    @Test
    fun `portrait samples the original RGBA bytes for a cropped padded plane`() {
        val stride = 333 * 4 + 12
        val bytes = ByteArray(stride * 189) { (it * 31 + it / 7).toByte() }
        val source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        source.position(8)
        val expected = ByteArray(256 * 144 * 4)
        for (y in 0 until 144) for (x in 0 until 256) {
            val offset = (3 + y * 183 / 144) * stride + (5 + x * 321 / 256) * 4
            bytes.copyInto(expected, (y * 256 + x) * 4, offset, offset + 4)
        }
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val target = ByteBuffer.allocateDirect(expected.size).order(order)
            samplePortraitRgba(source, stride, 5, 3, 321, 183, target)
            val actual = ByteArray(target.remaining())
            target.get(actual)
            assertArrayEquals(expected, actual)
            assertEquals(8, source.position())
        }
    }

    @Test
    fun `YUV caches independent plane strides and respects truncated last rows`() {
        val y = ByteArray(15) { (20 + it).toByte() }
        val cb = byteArrayOf(120, 0, 110, 0, 0, 126)
        val cr = byteArrayOf(134.toByte(), 138.toByte(), 0, 136.toByte())
        val reader = AnalysisPixelReader(image(ImageFormat.YUV_420_888,
            plane(y, 5, 1), plane(cb, 5, 2), plane(cr, 3, 1)))
        assertEquals(yuvToArgb(28, 110, 138), reader.argb(3, 1))
        assertEquals(yuvToArgb(30, 126, 136), reader.argb(0, 2))
        assertNull(reader.argb(2, 2))
        assertNull(AnalysisPixelReader(image(ImageFormat.YUV_420_888, plane(y, 5, 1))).argb(0, 0))
    }

    @Test
    fun `RGBA retains channel order alpha and final pixel bounds`() {
        val rgba = byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8)
        val reader = AnalysisPixelReader(image(android.graphics.PixelFormat.RGBA_8888, plane(rgba, 6, 4)))
        assertEquals(0x08050607, reader.argb(0, 1))
        assertNull(reader.argb(1, 1))
    }

    private fun plane(data: ByteArray, row: Int, pixel: Int): ImageProxy.PlaneProxy {
        val buffer = ByteBuffer.wrap(data)
        return proxy { name ->
            when (name) {
                "getRowStride" -> row
                "getPixelStride" -> pixel
                "getBuffer" -> buffer
                else -> error(name)
            }
        }
    }

    private fun image(format: Int, vararg planes: ImageProxy.PlaneProxy): ImageProxy = proxy { name ->
        when (name) {
            "getFormat" -> format
            "getPlanes" -> planes
            else -> error(name)
        }
    }

    private inline fun <reified T> proxy(crossinline result: (String) -> Any): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            result(method.name)
        } as T
}
