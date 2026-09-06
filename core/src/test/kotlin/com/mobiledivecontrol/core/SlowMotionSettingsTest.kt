package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlowMotionSettingsTest {
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
        assertTrue(ev.options.all { it.replace("+", "").toDouble() in -1.0..1.0 })
    }

    @Test
    fun `fixed focus lens has no ineffective AF choices`() {
        val camera = camera(60).copy(capabilities = CameraCapabilities(manualFocusSupported = false))
        val focus = CameraCatalog.settingsFor(camera).first { it.id == "slow_motion.focus_mode" }
        assertEquals(listOf("Fixed focus"), focus.options)
        assertEquals("Fixed focus", CameraCatalog.currentValue(camera, focus))
    }
}
