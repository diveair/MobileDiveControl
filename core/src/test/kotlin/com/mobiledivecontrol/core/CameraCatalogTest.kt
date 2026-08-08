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

    /**
     * The focus ladder is now built once and shared rather than rebuilt per lookup. These pin its
     * exact shape, because the dial's feel is a function of the rung count: the reducer converts a
     * nudge into an index into this list, so adding, removing or reformatting a rung silently
     * changes how far one detent moves the lens.
     */
    @Test
    fun `focus ladder keeps AF plus exactly 201 rungs from 0_000 to 1_000`() {
        val focus = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro.manual_focus" }
            .options

        assertEquals(202, focus.size)
        assertEquals("AF", focus[0])
        assertEquals("0.000", focus[1])
        assertEquals("1.000", focus.last())
        assertEquals("0.500", focus[101])
        assertEquals("0.005", focus[2])
    }

    @Test
    fun `exposure ladder keeps Auto plus exactly 161 rungs`() {
        val ev = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro.exposure_value" }
            .options

        assertEquals(162, ev.size)
        assertEquals("Auto", ev[0])
        assertEquals("-2.00", ev[1])
        assertEquals("0", ev[81])
        assertEquals("+2.00", ev.last())
    }

    /**
     * Guards the memoisation itself: a cached profile must be indistinguishable from a fresh one.
     * Sharing an instance is only safe while nothing downstream mutates it, so if a future change
     * makes these lists mutable this test is the tripwire.
     */
    @Test
    fun `repeated catalog lookups return equal profiles`() {
        val first = CameraCatalog.profile(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
        val second = CameraCatalog.profile(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)

        assertEquals(first, second)
        assertEquals(first.settings.map { it.id }, second.settings.map { it.id })
        assertEquals(
            first.settings.map { it.options },
            second.settings.map { it.options },
        )
    }

    @Test
    fun `capability clipping still applies after repeated lookups`() {
        val caps = CameraCapabilities(isoMin = 100, isoMax = 800)
        val lenses = listOf("0.6x", "1x")

        val a = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, lenses, caps)
        val b = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, lenses, caps)
        val unclipped = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, lenses, null)

        assertEquals(a, b)
        val clippedIso = a.first { it.id == "pro.iso" }.options
        val fullIso = unclipped.first { it.id == "pro.iso" }.options
        assertTrue(clippedIso.size < fullIso.size, "capability clipping must survive caching")
        assertEquals(lenses, a.first { it.id.endsWith(".lens") }.options)
    }
}


