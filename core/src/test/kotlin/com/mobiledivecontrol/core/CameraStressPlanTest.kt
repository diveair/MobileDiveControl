package com.mobiledivecontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraStressPlanTest {
    @Test
    fun `covers every user-facing camera mode in circular order`() {
        assertEquals(CameraCatalog.centerModeCycle, CameraStressPlan.modes)
        assertEquals(12, CameraStressPlan.modes.distinct().size)
    }

    @Test
    fun `every catalog setting has at least one test target`() {
        CameraStressPlan.modes.forEach { mode ->
            CameraCatalog.settingsFor(mode, GalaxyDeviceVariant.S26Ultra).forEach { spec ->
                assertTrue(
                    CameraStressPlan.targetValues(spec).isNotEmpty(),
                    "No stress target for ${spec.id}",
                )
            }
        }
    }

    @Test
    fun `every option is exhaustive by default and long sliders can use a bounded smoke plan`() {
        val discrete = CameraSettingSpec(
            id = "video.resolution",
            label = "Resolution",
            group = "Video",
            kind = CameraSettingKind.Choice,
            options = List(40) { "value-$it" },
            defaultValue = "value-0",
        )
        assertEquals(discrete.options, CameraStressPlan.targetValues(discrete))

        val slider = discrete.copy(
            id = "pro.manual_focus",
            kind = CameraSettingKind.Slider,
            options = listOf("AF") + List(100) { "%.2f".format(it / 99.0) },
            defaultValue = "AF",
        )
        assertEquals(slider.options, CameraStressPlan.targetValues(slider))

        val targets = CameraStressPlan.targetValues(slider, exhaustiveSliders = false)
        assertTrue("AF" in targets)
        assertTrue(slider.options.first() in targets)
        assertTrue(slider.options.last() in targets)
        assertTrue(targets.size <= 6)
    }

    @Test
    fun `all stream contract selectors are classified as rebinds`() {
        listOf(
            "pro_video.resolution",
            "pro_video.frame_rate",
            "pro_video.lens",
            "pro_video.hdr",
            "pro_video.log",
            "panorama.hdr_log",
            "video.video_stabilization",
            "photo.save_format",
            "video.aspect_ratio",
        ).forEach { assertTrue(CameraStressPlan.requiresCameraRebind(it), it) }
        assertFalse(CameraStressPlan.requiresCameraRebind("photo.aspect_ratio"))
        assertFalse(CameraStressPlan.requiresCameraRebind("portrait.aspect_ratio"))
    }
}
