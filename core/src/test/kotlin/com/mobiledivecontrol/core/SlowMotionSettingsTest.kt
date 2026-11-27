package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlowMotionSettingsTest {
    @Test
    fun `housing FPS control uses the same resolution and rate as the recorder`() {
        for (uhdRates in listOf(listOf(60), listOf(60, 120))) {
            val rates = mapOf("FHD" to listOf(120, 240), "UHD 4K" to uhdRates)
            val caps = CameraCapabilities(galaxyS24VideoModes = true, videoCaptureLens = "1x",
                videoFrameRatesByResolution = rates)
            for ((resolution, allowed) in rates) {
                for (saved in listOf(60, 120, 240)) {
                    val base = camera(saved)
                    val state = base.copy(capabilities = caps, settingValues = base.settingValues +
                        mapOf("slow_motion.resolution" to resolution, "slow_motion.playback_fps" to "30fps"))
                    val bar = CameraCatalog.settingsBarItems(state)
                    val index = bar.indexOfFirst { it is BottomBarItem.Setting && it.spec.id == "slow_motion.frame_rate" }
                    val control = requireNotNull(state.copy(settingsCursor = index).selectedSetting)
                    val recorder = CameraCatalog.settingsFor(state).single { it.id == "slow_motion.frame_rate" }
                    assertEquals(allowed.map { "${it}fps" }, control.options)
                    assertEquals(recorder.options, control.options)
                    assertEquals(CameraCatalog.currentValue(state, recorder), CameraCatalog.currentValue(state, control))
                    if (saved in allowed) assertEquals("${saved}fps", CameraCatalog.currentValue(state, control))
                }
            }
        }
    }

    @Test
    fun `a late high speed profile preserves the playback rate selected for review`() {
        val initial = CameraCatalog.launchCameraState(CameraModeId.SlowMotion)
        val requested = initial.copy(
            settingValues = initial.settingValues + mapOf(
                "slow_motion.resolution" to "FHD",
                "slow_motion.frame_rate" to "60fps",
                "slow_motion.playback_fps" to "30fps",
            ),
        )
        val caps = CameraCapabilities(
            galaxyS24VideoModes = true,
            videoCaptureLens = "1x",
            videoFrameRatesByResolution = mapOf("FHD" to listOf(120, 240)),
        )
        val state = CameraCatalog.resnapToClippedLadders(requested.copy(capabilities = caps))
        assertEquals("120fps", state.settingValues["slow_motion.frame_rate"])
        assertEquals("30fps", state.settingValues["slow_motion.playback_fps"])
    }

    @Test
    fun `slow motion camera limits never rewrite another modes saved settings`() {
        val original = camera(240).let { it.copy(settingValues = it.settingValues + mapOf(
            "pro.exposure_value" to "+4.0",
            "pro_video.frame_rate" to CameraCatalog.proVideoFrameRateOption(240),
            "hyperlapse.resolution" to "UHD 4K",
            "slow_motion.exposure" to "+3.0",
        ), capabilities = CameraCapabilities(evMin = -1.0, evMax = 1.0,
            availableVideoFrameRates = listOf(60, 120), availableVideoResolutions = listOf("FHD"),
            videoFrameRatesByResolution = mapOf("FHD" to listOf(60, 120)))) }
        val snapped = CameraCatalog.resnapToClippedLadders(original)
        assertEquals(original.settingValues.filterKeys { !it.startsWith("slow_motion.") },
            snapped.settingValues.filterKeys { !it.startsWith("slow_motion.") })
        assertEquals("120fps", snapped.settingValues["slow_motion.frame_rate"])
        assertEquals("+1.0", snapped.settingValues["slow_motion.exposure"])
    }

    private fun camera(fps: Int) = CameraCatalog.launchCameraState(CameraModeId.SlowMotion).let {
        it.copy(settingValues = it.settingValues + mapOf(
            "slow_motion.frame_rate" to "${fps}fps", "slow_motion.focus_mode" to "Single AF"))
    }

    @Test
    fun `camera without a flash cannot retain a stale torch selection`() {
        val camera = camera(120).let { it.copy(capabilities = CameraCapabilities(torchSupported = false),
            settingValues = it.settingValues + ("slow_motion.flash" to "Torch")) }
        val flash = CameraCatalog.settingsFor(camera).first { it.id == "slow_motion.flash" }
        assertEquals(listOf("Off"), flash.options)
        assertEquals("Off", CameraCatalog.currentValue(camera, flash))
    }

    @Test
    fun `high speed sessions cannot advertise single AF even with an older saved selection`() {
        for (fps in listOf(120, 240)) {
            val camera = camera(fps)
            val focus = CameraCatalog.settingsFor(camera).first { it.id == "slow_motion.focus_mode" }
            assertEquals(listOf("Continuous AF"), focus.options)
            assertEquals("Continuous AF", CameraCatalog.currentValue(camera, focus))
        }
        for (fps in listOf(48, 60)) {
            val camera = camera(fps)
            val focus = CameraCatalog.settingsFor(camera).first { it.id == "slow_motion.focus_mode" }
            assertTrue("Single AF" in focus.options)
            assertEquals("Single AF", CameraCatalog.currentValue(camera, focus))
        }
    }

    @Test
    fun `slow motion EV options respect the capture camera range`() {
        val camera = camera(120).copy(capabilities = CameraCapabilities(evMin = -1.0, evMax = 1.0))
        val ev = CameraCatalog.settingsFor(camera).first { it.id == "slow_motion.exposure" }
        assertTrue(ev.options.size > 1)
        assertTrue(ev.options.all { it == "Auto" || it.replace("+", "").toDouble() in -1.0..1.0 })
    }

    @Test
    fun `fixed focus lens has no ineffective AF choices`() {
        val camera = camera(60).copy(capabilities = CameraCapabilities(manualFocusSupported = false))
        val focus = CameraCatalog.settingsFor(camera).first { it.id == "slow_motion.focus_mode" }
        assertEquals(listOf("Fixed focus"), focus.options)
        assertEquals("Fixed focus", CameraCatalog.currentValue(camera, focus))
    }
}
