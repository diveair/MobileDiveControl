package com.mobiledivecontrol.core

import kotlin.math.abs
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
                "Track Heading",
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
        assertContains(settings, "pro_video.resolution")
        assertContains(settings, "pro_video.frame_rate")
        assertContains(settings, "pro_video.audio_recording")
        assertContains(settings, "pro_video.focus_peaking")
        assertContains(settings, "pro_video.exposure_display")
        assertContains(settings, "pro_video.guides")
        assertContains(settings, "pro_video.video_stabilization")
        assertContains(settings, "pro_video.log")
    }

    @Test
    fun `slider defaults include manual controls`() {
        assertTrue("pro.iso" in CameraCatalog.defaultSliderSensitivities)
        assertTrue("expert.white_balance" in CameraCatalog.defaultSliderSensitivities)
        assertTrue("pro_video.iso" in CameraCatalog.defaultSliderSensitivities)
        assertTrue("pro_video.frame_rate" !in CameraCatalog.defaultSliderSensitivities)
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

        // Opening Options never mutates the horizontal rail. Extras belong only to the
        // independent vertical collection.
        val videoClosed = CameraCatalog.settingsBarItems(CameraModeId.Video, GalaxyDeviceVariant.S26Ultra, showMore = false)
        val videoItems = CameraCatalog.settingsBarItems(CameraModeId.Video, GalaxyDeviceVariant.S26Ultra, showMore = true)
        val videoIds = ids(videoItems)
        assertEquals(ids(videoClosed), videoIds)
        assertTrue(videoItems.none { it is BottomBarItem.LensShortcut })
        assertTrue(videoIds.contains("video.lens"))
        assertTrue("video.flash" !in videoIds)
        assertTrue("video.resolution" !in videoIds)
        assertEquals("gallery", videoIds.last())
        assertEquals("video.slider_assignment", videoIds[videoIds.size - 2])
        assertEquals("more", videoIds.first())

        val videoOptions = CameraCatalog.optionsMenuSettings(
            CameraCatalog.launchCameraState(CameraModeId.Video),
        ).map { it.id }
        assertContains(videoOptions, "video.flash")
        assertContains(videoOptions, "video.super_steady")
        assertContains(videoOptions, "video.resolution")
        assertContains(videoOptions, "video.frame_rate")

        // The wheel's default assignment is Focus wherever focus exists.
        val slider = proItems.filterIsInstance<BottomBarItem.Setting>()
            .first { it.spec.id.endsWith(CameraCatalog.SLIDER_ASSIGNMENT_SUFFIX) }
        assertEquals("Focus", slider.spec.defaultValue)
    }

    @Test
    fun `pro options contain only controls absent from the horizontal spine`() {
        val camera = CameraCatalog.launchCameraState(CameraModeId.ProVideo)
        val horizontalIds = CameraCatalog.settingsBarItems(camera)
            .filterIsInstance<BottomBarItem.Setting>()
            .map { it.spec.id }
            .toSet()
        val options = CameraCatalog.optionsMenuSettings(camera)
        val optionIds = options.map { it.id }.toSet()

        assertTrue(horizontalIds.intersect(optionIds).isEmpty())
        listOf(
            "pro_video.resolution",
            "pro_video.frame_rate",
            "pro_video.save_location",
            "pro_video.metering",
            "pro_video.guides",
            "pro_video.exposure_display",
            "pro_video.hdr",
            "pro_video.log",
            "pro_video.video_stabilization",
            "pro_video.audio_recording",
            "pro_video.metadata_depth",
            "pro_video.metadata_temperature",
            "pro_video.metadata_heading",
        ).forEach { assertContains(optionIds, it) }
        assertTrue("pro_video.focus_peaking" !in optionIds)
        assertTrue("pro_video.focus_curve" !in optionIds)
    }

    @Test
    fun `device capability probe removes options the capture pipeline cannot execute`() {
        val caps = CameraCapabilities(
            availableVideoFrameRates = listOf(30, 60),
            availableVideoResolutions = listOf("FHD", "UHD 4K"),
            videoStabilizationSupported = false,
            ultraHdrJpegSupported = false,
        )
        val proVideo = CameraCatalog.launchCameraState(CameraModeId.ProVideo).copy(capabilities = caps)
        val videoSettings = CameraCatalog.settingsFor(proVideo)
        assertEquals(
            listOf("30fps", "60fps"),
            videoSettings.first { it.id == "pro_video.frame_rate" }.options,
        )
        assertEquals(
            listOf("FHD", "UHD 4K"),
            videoSettings.first { it.id == "pro_video.resolution" }.options,
        )
        assertTrue(videoSettings.none { it.id == "pro_video.video_stabilization" })

        val pro = CameraCatalog.launchCameraState(CameraModeId.Pro).copy(capabilities = caps)
        assertEquals(
            listOf("JPEG"),
            CameraCatalog.settingsFor(pro).first { it.id == "pro.save_format" }.options,
        )
    }

    @Test
    fun `resolution menu shows every real size while fps follows the selected resolution`() {
        val caps = CameraCapabilities(
            availableVideoFrameRates = listOf(24, 30, 60, 120, 240),
            availableVideoResolutions = listOf("SD 480p", "HD 720p", "FHD", "UHD 4K"),
            videoFrameRatesByResolution = mapOf(
                "SD 480p" to listOf(30),
                "HD 720p" to listOf(30, 60, 120, 240),
                "FHD" to listOf(30, 60, 120),
                "UHD 4K" to listOf(24, 30, 60),
            ),
        )
        val highSpeed = CameraCatalog.launchCameraState(CameraModeId.ProVideo).copy(
            capabilities = caps,
            settingValues = CameraCatalog.defaultSettingValues +
                ("pro_video.resolution" to "HD 720p") +
                ("pro_video.frame_rate" to "240fps"),
        )
        val highSpeedSettings = CameraCatalog.settingsFor(highSpeed)
        assertEquals(
            listOf("30fps", "60fps", "120fps", "240fps"),
            highSpeedSettings.first { it.id == "pro_video.frame_rate" }.options,
        )
        assertEquals(
            listOf("SD 480p", "HD 720p", "FHD", "UHD 4K"),
            highSpeedSettings.first { it.id == "pro_video.resolution" }.options,
        )

        val sixtyFps = highSpeed.copy(
            settingValues = highSpeed.settingValues + ("pro_video.frame_rate" to "60fps"),
        )
        assertEquals(
            listOf("SD 480p", "HD 720p", "FHD", "UHD 4K"),
            CameraCatalog.settingsFor(sixtyFps)
                .first { it.id == "pro_video.resolution" }
                .options,
        )
        assertTrue(
            CameraCatalog.settingsFor(CameraModeId.ProVideo, GalaxyDeviceVariant.S26Ultra)
                .first { it.id == "pro_video.resolution" }
                .options
                .none { it == "8K" },
        )
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

    /**
     * The Pro EV dialer, exactly as the native camera ships it: the 81-entry exposure_value
     * array, -4.0..+4.0 at the hardware's own 0.1 step, spelled the native way ("0.0" at the
     * midpoint, signed elsewhere), and with NO Auto rung — the native dial has a reset-to-0.0
     * affordance instead, and EV authority is governed by the ISO/shutter Auto states.
     */
    @Test
    fun `pro exposure ladder is the native 81-entry dial`() {
        val ev = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro.exposure_value" }
            .options

        assertEquals(81, ev.size)
        assertEquals("-4.0", ev.first())
        assertEquals("0.0", ev[40])
        assertEquals("+4.0", ev.last())
        assertTrue("Auto" !in ev, "the native EV dial has no Auto rung")
        // Exactly one tenth between neighbours, all the way across.
        ev.map { it.replace("+", "").toDouble() }.zipWithNext { a, b ->
            assertTrue(abs(b - a - 0.1) < 1e-9, "$a -> $b is not a 0.1 EV step")
        }
    }

    /** The quick EV bar every non-Pro mode shows natively: the same array's middle 41 entries. */
    @Test
    fun `photo exposure ladder is the native 41-entry quick bar`() {
        val ev = CameraCatalog.settingsFor(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "photo.exposure_compensation" }
            .options

        assertEquals(41, ev.size)
        assertEquals("-2.0", ev.first())
        assertEquals("0.0", ev[20])
        assertEquals("+2.0", ev.last())
        assertTrue("Auto" !in ev)
    }

    /**
     * The ISO dial, exactly as the native camera ships it: Auto plus
     * MakerParameter.SENSOR_SENSITIVITY_ARRAY's fifteen values — third-stops from 50 to 800,
     * then whole stops. Pinned as a full-table equality: the count is the detent distance, the
     * values are the photographer's vocabulary, and both must match the stock Pro dial verbatim.
     */
    @Test
    fun `iso ladder is the native fifteen-value table`() {
        val iso = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro.iso" }
            .options

        assertEquals(
            listOf(
                "Auto", "50", "64", "80", "100", "125", "160", "200", "250", "320",
                "400", "500", "640", "800", "1600", "3200",
            ),
            iso,
        )
    }

    /** ISO is the one exposure control with no mode-dependent clamp — Pro Video matches Pro exactly. */
    @Test
    fun `pro video iso ladder matches pro`() {
        val pro = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro.iso" }.options
        val proVideo = CameraCatalog.settingsFor(CameraModeId.ProVideo, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro_video.iso" }.options
        assertEquals(pro, proVideo)
    }

    /**
     * The shutter dial, exactly as the native camera ships it: Auto plus
     * MakerParameter.EXPOSURE_TIME_ARRAY's 37 speeds, each label parsing back to the native
     * table's exact nanosecond entry. A hand-made list, copied — not generated.
     */
    @Test
    fun `shutter ladder is the native 37-entry table`() {
        val shutter = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro.shutter_speed" }
            .options

        assertEquals(
            listOf(
                "Auto",
                "1/24000", "1/16000", "1/12000", "1/8000", "1/6000", "1/4000", "1/3000", "1/2000",
                "1/1500", "1/1000", "1/750", "1/500", "1/350", "1/250", "1/180", "1/125", "1/90",
                "1/60", "1/50", "1/45", "1/30", "1/20", "1/15", "1/10", "1/8", "1/6", "1/4",
                "0.3\"", "0.5\"", "1\"", "2\"", "4\"", "8\"", "10\"", "15\"", "20\"", "30\"",
            ),
            shutter,
        )
        assertEquals(null, CameraCatalog.shutterOptionNanos("Auto"))
        // Each label IS its native nanosecond value — the label is what reaches the request.
        val nativeNanos = listOf(
            41_667L, 62_500L, 83_333L, 125_000L, 166_667L, 250_000L, 333_333L, 500_000L,
            666_667L, 1_000_000L, 1_333_333L, 2_000_000L, 2_857_143L, 4_000_000L, 5_555_556L,
            8_000_000L, 11_111_111L, 16_666_667L, 20_000_000L, 22_222_222L, 33_333_333L,
            50_000_000L, 66_666_667L, 100_000_000L, 125_000_000L, 166_666_667L, 250_000_000L,
            300_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L,
            8_000_000_000L, 10_000_000_000L, 15_000_000_000L, 20_000_000_000L, 30_000_000_000L,
        )
        assertEquals(nativeNanos, shutter.drop(1).map { CameraCatalog.shutterOptionNanos(it) })
    }

    /**
     * The native video rule (ProUtil.getMaxVideoShutterSpeed): the slowest video rung is the
     * longest table entry not exceeding one frame period, and the fast end is the vendor floor.
     * Pro Video at 30 fps must therefore read exactly 1/12000 .. 1/30 — the range the stock
     * camera shows — once the capability floor is applied.
     */
    @Test
    fun `pro video shutter ladder is clipped to the frame period`() {
        // Rounded periods, matching videoShutterCapNs: the native rule admits the entry that IS
        // one frame period, and 1/60 spells 16666667 ns where truncating division says 16666666.
        val caps = CameraCapabilities(exposureMinNs = 83_333L, exposureMaxNs = 150_001_124L)
        val at30 = CameraCatalog.settingsFor(
            CameraModeId.ProVideo, GalaxyDeviceVariant.S26Ultra, emptyList(), caps,
            videoShutterCapNs = 33_333_333L,
        ).first { it.id == "pro_video.shutter_speed" }.options
        assertEquals("Auto", at30.first())
        assertEquals("1/12000", at30[1])
        assertEquals("1/30", at30.last())
        assertEquals(20, at30.size, "Auto + 1/12000..1/30 is 19 rungs on the native table")

        val at60 = CameraCatalog.settingsFor(
            CameraModeId.ProVideo, GalaxyDeviceVariant.S26Ultra, emptyList(), caps,
            videoShutterCapNs = 16_666_667L,
        ).first { it.id == "pro_video.shutter_speed" }.options
        assertEquals("1/60", at60.last())

        val at120 = CameraCatalog.settingsFor(
            CameraModeId.ProVideo, GalaxyDeviceVariant.S26Ultra, emptyList(), caps,
            videoShutterCapNs = 8_333_333L,
        ).first { it.id == "pro_video.shutter_speed" }.options
        assertEquals("1/125", at120.last(), "120 fps admits 1/125 as the longest entry within 8.33 ms")
    }

    /** videoShutterCapNs derives the frame period from the mode's own frame-rate setting. */
    @Test
    fun `videoShutterCapNs follows the pro video frame rate setting`() {
        val base = CameraCatalog.launchCameraState(CameraModeId.ProVideo)
        assertEquals(33_333_333L, CameraCatalog.videoShutterCapNs(base), "default 30fps")
        val at60 = base.copy(settingValues = base.settingValues + ("pro_video.frame_rate" to "60fps"))
        assertEquals(16_666_667L, CameraCatalog.videoShutterCapNs(at60))
        val photo = CameraCatalog.launchCameraState(CameraModeId.Pro)
        assertEquals(null, CameraCatalog.videoShutterCapNs(photo), "photo modes carry no frame clamp")
    }

    /**
     * EVERY SLIDER'S DEFAULT MUST NAME A REAL RUNG. This is the invariant whose absence let the
     * shutter default sit off its own ladder: the value ladders are GENERATED, so a hand-written
     * default silently stops being a member the moment the generator's spacing changes, and the
     * reducer reads the miss as index 0 — which is "Auto" on three of these four scales and the
     * fastest shutter on the fourth.
     */
    @Test
    fun `every slider default names an option on its own ladder`() {
        GalaxyDeviceVariant.entries.forEach { variant ->
            CameraModeId.entries.forEach { mode ->
                CameraCatalog.settingsFor(mode, variant)
                    .filter { it.kind == CameraSettingKind.Slider }
                    .forEach { spec ->
                        assertTrue(
                            spec.defaultValue in spec.options,
                            "$variant/$mode ${spec.id} default '${spec.defaultValue}' is not on its ladder",
                        )
                    }
            }
        }
    }

    /** Samsung's native manual table plus the three housing-specific automatic modes. */
    @Test
    fun `white balance ladder is the native 100K table followed by both auto modes`() {
        val wb = CameraCatalog.settingsFor(CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro.white_balance" }
            .options

        assertEquals(CameraCatalog.WB_LADDER_RUNGS + 3, wb.size)
        assertEquals("2300K", wb.first())
        assertEquals("10000K", wb[CameraCatalog.WB_LADDER_RUNGS - 1])
        assertEquals(CameraCatalog.WB_AUTO_CONTINUOUS, wb[CameraCatalog.WB_LADDER_RUNGS])
        assertEquals(CameraCatalog.WB_AUTO_UNDERWATER, wb[CameraCatalog.WB_LADDER_RUNGS + 1])
        assertEquals(CameraCatalog.WB_AUTO_SHUTTER, wb.last())
        val kelvin = wb.take(CameraCatalog.WB_LADDER_RUNGS).map { it.removeSuffix("K").toInt() }
        assertTrue(kelvin.zipWithNext().all { (a, b) -> b - a == 100 })

        val proVideo = CameraCatalog.settingsFor(CameraModeId.ProVideo, GalaxyDeviceVariant.S26Ultra)
            .first { it.id == "pro_video.white_balance" }.options
        assertEquals(wb, proVideo, "the WB scale is identical in every Pro mode")
    }

    /** The Auto readouts snap metered values onto the dial by linear distance. */
    @Test
    fun `metered values snap to the nearest rung`() {
        val wbOptions = listOf("5000K", "5600K", "5700K", CameraCatalog.WB_AUTO_CONTINUOUS)
        assertEquals("5600K", CameraCatalog.nearestWhiteBalanceOption(5649, wbOptions))
        assertEquals("5700K", CameraCatalog.nearestWhiteBalanceOption(5651, wbOptions))
        assertEquals("5000K", CameraCatalog.nearestWhiteBalanceOption(40, wbOptions))
        assertEquals(null, CameraCatalog.nearestWhiteBalanceOption(5000, listOf(CameraCatalog.WB_AUTO_CONTINUOUS)))
        // ...and findNearestShutterSpeed (linear nanosecond distance over the visible options).
        val options = listOf("Auto", "1/12000", "1/8000", "1/6000", "1/30")
        assertEquals("1/8000", CameraCatalog.nearestShutterOption(126_000L, options))
        assertEquals("1/12000", CameraCatalog.nearestShutterOption(50_000L, options))
        assertEquals("1/30", CameraCatalog.nearestShutterOption(1_000_000_000L, options))
        assertEquals(null, CameraCatalog.nearestShutterOption(1_000L, listOf("Auto")))
    }

    /** The reusable metered snap remains correct, although circular controls do not jump to it. */
    @Test
    fun `metered seed lands on the rung nearest the live value`() {
        val camera = CameraCatalog.launchCameraState(CameraModeId.Pro).copy(
            meteredExposure = MeteredExposure(iso = 137, shutterNs = 5_000_000L, wbKelvin = 5_649),
        )
        val settings = CameraCatalog.settingsFor(camera)
        val iso = settings.first { it.id == "pro.iso" }
        val shutter = settings.first { it.id == "pro.shutter_speed" }
        val wb = settings.first { it.id == "pro.white_balance" }

        assertEquals("125", CameraCatalog.meteredSeedValue(camera, iso), "137 sits nearest 125 linearly")
        assertEquals("1/180", CameraCatalog.meteredSeedValue(camera, shutter), "5.0ms sits nearest 1/180")
        val wbSeed = CameraCatalog.meteredSeedValue(camera, wb)
        assertTrue(wbSeed in wb.options && wbSeed != "Auto", "WB seed must be a real rung")
        val seedKelvin = wbSeed!!.removeSuffix("K").toInt()
        assertTrue(
            kotlin.math.abs(seedKelvin - 5_649) <= 50,
            "WB seed $wbSeed must sit within half a rung of the metered 5649",
        )

        val blind = camera.copy(meteredExposure = MeteredExposure())
        assertEquals(null, CameraCatalog.meteredSeedValue(blind, iso), "no telemetry, no seed")
    }

    /**
     * The EV authority rule, matched to what THIS app's sensor honours: our lone-manual-axis
     * implementation turns AE fully off (the vendor priority channel the native app uses is
     * closed to third parties), so the compensation index is dead the moment EITHER axis is
     * manual — and the dial must lock right there, in agreement with the controller's `!aeOff`
     * write gate, rather than at the native UI's both-manual boundary.
     */
    @Test
    fun `ev meter locks whenever either iso or shutter is manual`() {
        val base = CameraCatalog.launchCameraState(CameraModeId.Pro)
        val ev = CameraCatalog.settingsFor(base).first { it.id == "pro.exposure_value" }

        assertTrue(!CameraCatalog.evMeterLocked(base, ev), "defaults are Auto/Auto — EV live")
        val isoOnly = base.copy(settingValues = base.settingValues + ("pro.iso" to "400"))
        assertTrue(
            CameraCatalog.evMeterLocked(isoOnly, ev),
            "lone manual ISO turns AE off here, so EV must lock with it",
        )
        val both = isoOnly.copy(settingValues = isoOnly.settingValues + ("pro.shutter_speed" to "1/60"))
        assertTrue(CameraCatalog.evMeterLocked(both, ev), "full manual locks the EV meter")
        // Photo mode has no iso/shutter settings at all — EV is always live there.
        val photo = CameraCatalog.launchCameraState(CameraModeId.Photo)
        val photoEv = CameraCatalog.settingsFor(photo).first { it.id == "photo.exposure_compensation" }
        assertTrue(!CameraCatalog.evMeterLocked(photo, photoEv))
    }

    /**
     * Capability arrival must re-spell stored values onto the CLIPPED ladders. Persistence snaps
     * against the full native tables, so a real native rung the probed window removes ("0.5\"" on
     * a 0.15 s live ceiling) would otherwise sit unfound at runtime and resolve from index 0.
     */
    @Test
    fun `capability arrival re-snaps stored values onto the clipped ladders`() {
        val caps = CameraCapabilities(exposureMinNs = 83_333L, exposureMaxNs = 150_001_124L)
        val base = CameraCatalog.launchCameraState(CameraModeId.Pro)
        val camera = base.copy(
            capabilities = caps,
            settingValues = base.settingValues +
                mapOf("pro.shutter_speed" to "0.5\"", "pro.iso" to "400", "pro.white_balance" to "Auto"),
        )

        val resnapped = CameraCatalog.resnapToClippedLadders(camera)
        assertEquals(
            "1/8",
            resnapped.settingValues["pro.shutter_speed"],
            "an off-clip rung must land on the nearest surviving one",
        )
        assertEquals("400", resnapped.settingValues["pro.iso"], "on-ladder values pass through")
        assertEquals(
            CameraCatalog.WB_AUTO_CONTINUOUS,
            resnapped.settingValues["pro.white_balance"],
            "legacy Auto migrates to its exact behavioural successor",
        )
        // Idempotent: a second pass changes nothing.
        assertEquals(resnapped.settingValues, CameraCatalog.resnapToClippedLadders(resnapped).settingValues)
    }

    /**
     * The single shutter parser. Its whole reason for existing is that three near-copies used to
     * disagree, and a label one accepted and another rejected became a manual-looking HUD over an
     * auto-exposing sensor.
     */
    @Test
    fun `shutter labels parse identically whatever spelling they arrive in`() {
        assertEquals(125_000L, CameraCatalog.shutterOptionNanos("1/8000"))
        assertEquals(2_000_000_000L, CameraCatalog.shutterOptionNanos("2\""))
        assertEquals(2_000_000_000L, CameraCatalog.shutterOptionNanos("2s"))
        assertEquals(2_000_000_000L, CameraCatalog.shutterOptionNanos("2"))
        assertEquals(300_000_000L, CameraCatalog.shutterOptionNanos("0.3\""))
        assertEquals(300_000_000L, CameraCatalog.shutterOptionNanos("0.3"))
        assertEquals(500_000_000L, CameraCatalog.shutterOptionNanos("1/2"))
        assertEquals(null, CameraCatalog.shutterOptionNanos("1/0"))
        assertEquals(null, CameraCatalog.shutterOptionNanos("banana"))
        assertEquals(null, CameraCatalog.shutterOptionNanos(""))
    }

    /** Clipping may shorten a ladder but must never delete "Auto" where the scale has one. */
    @Test
    fun `capability clipping keeps Auto on every clipped ladder`() {
        val caps = CameraCapabilities(
            isoMin = 100,
            isoMax = 800,
            exposureMinNs = 83_333L,
            exposureMaxNs = 150_001_124L,
            evMin = -2.0,
            evMax = 2.0,
        )
        val clipped = CameraCatalog.settingsFor(
            CameraModeId.Pro, GalaxyDeviceVariant.S26Ultra, listOf("1x"), caps,
        )

        listOf("pro.iso", "pro.shutter_speed", "pro.white_balance").forEach { id ->
            val spec = clipped.firstOrNull { it.id == id }
            assertTrue(spec != null, "$id was clipped out of existence")
            assertTrue(spec!!.options.isNotEmpty(), "$id lost every option")
        }
        assertEquals("Auto", clipped.first { it.id == "pro.iso" }.options.first())
        assertEquals("Auto", clipped.first { it.id == "pro.shutter_speed" }.options.first())
        val clippedWb = clipped.first { it.id == "pro.white_balance" }.options
        assertEquals("2300K", clippedWb.first())
        assertEquals(CameraCatalog.WB_AUTO_CONTINUOUS, clippedWb[clippedWb.lastIndex - 2])
        assertEquals(CameraCatalog.WB_AUTO_UNDERWATER, clippedWb[clippedWb.lastIndex - 1])
        assertEquals(CameraCatalog.WB_AUTO_SHUTTER, clippedWb.last())
        // The native fast floor (the vendor characteristic's 1/12000) trims the two rungs the
        // stock dial never shows either.
        val shutter = clipped.first { it.id == "pro.shutter_speed" }.options
        assertEquals("1/12000", shutter[1], "the native floor starts the dial at 1/12000")
        assertEquals("1/8", shutter.last(), "the public 0.15s live ceiling ends the photo dial at 1/8")
        // The ISO clip narrows by value, exactly as the native findNearestIso clamp would.
        val iso = clipped.first { it.id == "pro.iso" }.options
        assertEquals(listOf("Auto", "100", "125", "160", "200", "250", "320", "400", "500", "640", "800"), iso)
        // EV has no Auto natively; a device whose window is the public +/-2.0 clips the Pro dial
        // to the middle 41 with its endpoints exact (20 * 0.1 is exact in IEEE-754), and the
        // clipped default stays "0.0".
        val ev = clipped.first { it.id == "pro.exposure_value" }
        assertEquals(41, ev.options.size)
        assertEquals("-2.0", ev.options.first())
        assertEquals("+2.0", ev.options.last())
        assertEquals("0.0", ev.defaultValue)
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


