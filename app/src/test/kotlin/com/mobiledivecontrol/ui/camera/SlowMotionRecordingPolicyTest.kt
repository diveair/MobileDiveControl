package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlowMotionRecordingPolicyTest {
    @Test
    fun `lens selection and wheel zoom both reach the slow motion recorder`() {
        val zoom = SlowMotionZoomControl()
        assertEquals(0.6f, zoom.resolve("0.6x", 0.6f))
        zoom.set("0.6x", 2f)
        assertEquals(2f, zoom.resolve("0.6x", 0.6f))
        assertEquals(3f, zoom.resolve("3x", 3f))
        assertEquals(0.6f, zoom.resolve("0.6x", 0.6f))
    }

    @Test
    fun `slower playback does not reduce the encoded budget per captured frame`() {
        val playbackBudgets = listOf(60, 120, 240).map { fps ->
            SlowMotionRecordingPolicy.bitrate(1920, 1080, fps.toDouble(), true) * 30L / fps
        }
        assertEquals(1, playbackBudgets.distinct().size)
        assertTrue(playbackBudgets.first() >= 10_000_000)
    }

    @Test
    fun `48 uses real 60 fps input and export retains a full quality budget`() {
        assertEquals(60, SlowMotionRecordingPolicy.sourceFrameRate(48))
        val source = SlowMotionRecordingPolicy.bitrate(1920, 1080, 60.0, true)
        val export = SlowMotionRecordingPolicy.bitrate(1920, 1080, 30.0, true)
        assertEquals(source / 2, export)
        assertTrue(SlowMotionRecordingPolicy.bitrate(1920, 1080, 30.0, false) > export)
        assertThrows(IllegalArgumentException::class.java) { SlowMotionRecordingPolicy.sourceFrameRate(30) }
    }
}
