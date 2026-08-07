package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraCatalogTest {
    @Test
    fun `primary rail contains the required housing modes in order`() {
        val labels = CameraCatalog.primaryRailEntries.map { it.label }
        assertEquals(
            listOf(
                "Photo",
                "Expert RAW",
                "Pro",
                "Panorama",
                "Night",
                "Burst",
                "Single Take",
                "Hyperlapse",
                "Video",
                "Pro Video",
                "Portrait Video",
                "Slow Motion",
                "Dual Record",
                "Night Video",
            ),
            labels,
        )
    }

    @Test
    fun `secondary rail is intentionally unused`() {
        assertTrue(CameraCatalog.secondaryModes.isEmpty())
    }

    @Test
    fun `photo mode exposes required housing settings`() {
        val settings = CameraCatalog.settingsFor(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra).map { it.id }.toSet()
        assertContains(settings, "photo.flash")
        assertContains(settings, "photo.megapixels")
        assertContains(settings, "photo.save_format")
        assertContains(settings, "photo.lens")
        assertContains(settings, "photo.manual_focus")
        assertContains(settings, "photo.focus_peaking")
        assertContains(settings, "photo.exposure_compensation")
        assertContains(settings, "photo.hdr_log")
        assertContains(settings, "photo.filters")
    }

    @Test
    fun `pro video exposes manual video and audio controls`() {
        val settings = CameraCatalog.settingsFor(CameraModeId.ProVideo, GalaxyDeviceVariant.S26Ultra).map { it.id }.toSet()
        assertContains(settings, "pro_video.iso")
        assertContains(settings, "pro_video.shutter_speed")
        assertContains(settings, "pro_video.microphone_source")
        assertContains(settings, "pro_video.microphone_gain")
        assertContains(settings, "pro_video.focus_peaking")
        assertContains(settings, "pro_video.exposure_monitor")
        assertContains(settings, "pro_video.grid")
        assertContains(settings, "pro_video.log")
    }

    @Test
    fun `slider defaults include manual controls`() {
        assertTrue("pro.iso" in CameraCatalog.defaultSliderSensitivities)
        assertTrue("expert.white_balance" in CameraCatalog.defaultSliderSensitivities)
        assertTrue("pro_video.microphone_gain" in CameraCatalog.defaultSliderSensitivities)
        assertTrue("pro_video.frame_rate" in CameraCatalog.defaultSliderSensitivities)
    }

    @Test
    fun `settingsBarItems follows the universal template in every mode`() {
        fun ids(items: List<BottomBarItem>): List<String> = items.map { item ->
            when (item) {
                is BottomBarItem.ModesButton -> "modes"
                is BottomBarItem.Setting -> item.spec.id
                is BottomBarItem.GalleryShortcut -> "gallery"
                is BottomBarItem.LensShortcut -> "lens:${item.value}"
                is BottomBarItem.MoreSettings -> "more"
            }
        }

        // Pro: the full spine. More at far left, mode token anchored after ISO,
        // Slider always immediately left of Gallery at the far right.
        val proItems = CameraCatalog.settingsBarItems(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, showMore = false)
        assertEquals(
            listOf(
                "more",
                "pro.lens",
                "pro.exposure_value",
                "pro.shutter_speed",
                "pro.iso",
                "modes",
                "pro.manual_focus",
                "pro.white_balance",
                "pro.slider_assignment",
                "gallery",
            ),
            ids(proItems),
        )
        assertEquals(
            CameraCatalog.defaultSettingsCursor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra),
            proItems.indexOf(BottomBarItem.ModesButton),
        )

        // Pro Video: identical shape.
        val proVideoItems = CameraCatalog.settingsBarItems(CameraModeId.ProVideo, GalaxyDeviceVariant.S26Ultra, showMore = false)
        assertEquals(
            listOf(
                "more",
                "pro_video.lens",
                "pro_video.exposure_value",
                "pro_video.shutter_speed",
                "pro_video.iso",
                "modes",
                "pro_video.manual_focus",
                "pro_video.white_balance",
                "pro_video.slider_assignment",
                "gallery",
            ),
            ids(proVideoItems),
        )

        // Photo: missing spine tiles simply drop out; the template's shape holds.
        val photoItems = CameraCatalog.settingsBarItems(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra, showMore = false)
        assertEquals(
            listOf(
                "more",
                "photo.lens",
                "photo.exposure_compensation",
                "modes",
                "photo.manual_focus",
                "photo.slider_assignment",
                "gallery",
            ),
            ids(photoItems),
        )
        assertEquals(3, CameraCatalog.defaultSettingsCursor(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra))

        // Video: no per-lens shortcut chips anywhere any more — one synthesized Lens tile
        // instead — and the mode extras live behind More at the far left.
        val videoItems = CameraCatalog.settingsBarItems(CameraModeId.Video, GalaxyDeviceVariant.S26Ultra, showMore = true)
        val videoIds = ids(videoItems)
        assertTrue(videoItems.none { it is BottomBarItem.LensShortcut })
        assertTrue(videoIds.contains("video.lens"))
        assertTrue(videoIds.contains("video.flash"))
        assertTrue(videoIds.contains("video.super_steady"))
        assertTrue(videoIds.contains("video.resolution"))
        assertTrue(videoIds.contains("video.frame_rate"))
        assertEquals("gallery", videoIds.last())
        assertEquals("video.slider_assignment", videoIds[videoIds.size - 2])
        assertEquals("more", videoIds.first())

        // The wheel's default assignment is Focus wherever focus exists.
        val slider = proItems.filterIsInstance<BottomBarItem.Setting>()
            .first { it.spec.id.endsWith(CameraCatalog.SLIDER_ASSIGNMENT_SUFFIX) }
        assertEquals("Focus", slider.spec.defaultValue)
    }
}


