package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanoramaScanMotionTest {
    @Test
    fun `scan distance is independent of preview callback frequency`() {
        fun distance(samples: Int): Int = (1..samples).sumOf { sample ->
            panoramaThumbnailAdvance(
                (sample - 1) * 0.6f / samples, sample * 0.6f / samples, 198, 1.5f,
            )
        }
        assertEquals(distance(6), distance(60))
        assertEquals(distance(6), distance(600))
        assertEquals(79, distance(600))
    }

    @Test
    fun `stationary and stale preview frames never grow the scan`() {
        assertEquals(0, panoramaThumbnailAdvance(0.4f, 0.4f, 198, 1.5f))
        assertEquals(0, panoramaThumbnailAdvance(0.4f, 0.3f, 198, 1.5f))
    }

    @Test
    fun `ultrawide preview moves less for the same physical sweep`() {
        fun distance(fov: Float): Int {
            val cropFov = panoramaThumbnailFov(480, 360, 198, 132, true, fov)
            return panoramaThumbnailAdvance(0f, 0.3f, 198, cropFov)
        }
        assertTrue(distance(2f) < distance(1.3f))
    }

    @Test
    fun `physical rotation exchanges the calibrated sweep axes`() {
        val landscapeX = panoramaThumbnailFov(480, 360, 198, 132, true, 1.5f)
        val portraitY = panoramaThumbnailFov(360, 480, 132, 198, false, 1.5f)
        val landscapeY = panoramaThumbnailFov(480, 360, 198, 132, false, 1.5f)
        val portraitX = panoramaThumbnailFov(360, 480, 132, 198, true, 1.5f)
        assertEquals(landscapeX, portraitY, 0.00001f)
        assertEquals(landscapeY, portraitX, 0.00001f)
    }
}
