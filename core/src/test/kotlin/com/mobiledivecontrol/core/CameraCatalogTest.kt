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
    fun `settingsBarItems returns prioritized items for Pro, Photo, and Video modes`() {
        // Pro Mode
        val proItems = CameraCatalog.settingsBarItems(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, showMore = false)
        assertEquals(
            CameraCatalog.defaultSettingsCursor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra),
            proItems.indexOf(BottomBarItem.ModesButton),
        )
        val proSettingIds = proItems.filterIsInstance<BottomBarItem.Setting>().map { it.spec.id }
        assertTrue(proSettingIds.contains("pro.iso"))
        assertTrue(proSettingIds.contains("pro.shutter_speed"))
        assertTrue(proSettingIds.contains("pro.exposure_value"))
        assertTrue(proSettingIds.contains("pro.lens"))
        assertTrue(proSettingIds.contains("pro.manual_focus"))
        assertTrue(proSettingIds.contains("pro.white_balance"))

        // Photo Mode
        val photoItems = CameraCatalog.settingsBarItems(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra, showMore = false)
        val photoOrder = photoItems.map { item ->
            when (item) {
                is BottomBarItem.ModesButton -> "modes"
                is BottomBarItem.Setting -> item.spec.id
                is BottomBarItem.GalleryShortcut -> "gallery"
                is BottomBarItem.LensShortcut -> "lens:${item.value}"
                is BottomBarItem.MoreSettings -> "more"
            }
        }
        assertEquals(
            listOf(
                "photo.flash",
                "photo.megapixels",
                "photo.save_format",
                "photo.lens",
                "photo.manual_focus",
                "modes",
                "photo.exposure_compensation",
                "photo.hdr_log",
                "photo.filters",
                "gallery",
            ),
            photoOrder,
        )
        assertEquals(5, CameraCatalog.defaultSettingsCursor(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra))

        // Video Mode
        val videoItems = CameraCatalog.settingsBarItems(CameraModeId.Video, GalaxyDeviceVariant.S26Ultra, showMore = false)
        assertEquals(
            CameraCatalog.defaultSettingsCursor(CameraModeId.Video, GalaxyDeviceVariant.S26Ultra),
            videoItems.indexOf(BottomBarItem.ModesButton),
        )
        val videoSettings = videoItems.filterIsInstance<BottomBarItem.Setting>().map { it.spec.id }
        val videoLensShortcuts = videoItems.filterIsInstance<BottomBarItem.LensShortcut>().map { it.value }
        assertTrue(videoSettings.contains("video.flash"))
        assertTrue(videoSettings.contains("video.super_steady"))
        assertTrue(videoSettings.contains("video.resolution"))
        assertTrue(videoSettings.contains("video.frame_rate"))
        assertTrue(videoLensShortcuts.contains("0.6x"))
        assertTrue(videoLensShortcuts.contains("1x"))
        assertTrue(videoLensShortcuts.contains("front"))
    }
}


