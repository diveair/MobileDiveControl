package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanoramaPreviewFitTest {
    @Test
    fun `equal ratio miniature uses the complete source`() {
        val crop = centerCropPanoramaPreview(132, 88, 264f, 176f)

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(132, crop.width)
        assertEquals(88, crop.height)
    }

    @Test
    fun `wide panorama thumbnail is centre cropped into horizontal chevron rectangle`() {
        val crop = centerCropPanoramaPreview(600, 200, 132f, 88f)

        assertEquals(150, crop.left)
        assertEquals(0, crop.top)
        assertEquals(300, crop.width)
        assertEquals(200, crop.height)
    }

    @Test
    fun `landscape frame is centre cropped into vertical chevron rectangle`() {
        val crop = centerCropPanoramaPreview(132, 88, 88f, 132f)

        assertEquals(36, crop.left)
        assertEquals(0, crop.top)
        assertEquals(59, crop.width)
        assertEquals(88, crop.height)
    }
}
