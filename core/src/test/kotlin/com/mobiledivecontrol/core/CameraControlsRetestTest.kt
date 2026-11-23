package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraControlsRetestTest {
    private val reducer = ControlReducer()
    @Test fun `all EV dials traverse minus four plus four Auto and wrap`() {
        for (mode in CameraModeId.entries) {
            val state = CameraCatalog.launchCameraState(activeMode = mode)
            val spec = CameraCatalog.settingsFor(state).firstOrNull {
                it.id.endsWith(".exposure") || it.id.endsWith(".exposure_value") || it.id.endsWith(".exposure_compensation")
            } ?: continue
            assertEquals("-4.0", spec.options.first(), "$mode")
            assertEquals(listOf("+4.0", "Auto"), spec.options.takeLast(2), "$mode")
            for ((value, step, expected) in listOf(Triple("+4.0", 1, "Auto"), Triple("Auto", 1, "-4.0"), Triple("-4.0", -1, "Auto"))) {
                val camera = state.copy(settingValues = state.settingValues + (spec.id to value))
                assertEquals(expected, reducer.reduce(AppState(camera = camera), CameraCommand.NudgeSetting(spec.id, step))
                    .state.camera.settingValues[spec.id], "$mode $value")
            }
        }
    }
    @Test fun `fine ISO follows selected increment without snapping to regular ladder`() {
        for (step in listOf(1, 2, 3, 5, 10)) {
            val camera = CameraCatalog.launchCameraState(activeMode = CameraModeId.Pro).copy(
                capabilities = CameraCapabilities(isoMin = 25, isoMax = 3200),
                settingValues = CameraCatalog.defaultSettingValues + ("pro.iso" to "101"),
                sliderSensitivities = mapOf("pro.iso" to SliderSensitivity.of(step)),
            )
            val next = reducer.reduce(AppState(camera = camera), CameraCommand.NudgeSetting("pro.iso", 1)).state.camera
            assertEquals((101 + step).toString(), next.settingValues["pro.iso"])
            assertEquals((101 + step).toString(), CameraCatalog.currentValue(next, CameraCatalog.settingsFor(next).first { it.id == "pro.iso" }))
        }
    }
    @Test fun `slow motion FPS menu matches selected size and never changes lens`() {
        val base = CameraCatalog.launchCameraState(activeMode = CameraModeId.SlowMotion).copy(
            capabilities = CameraCapabilities(availableVideoResolutions = listOf("FHD", "UHD 4K"),
                availableVideoFrameRates = listOf(24, 30, 60, 120, 240),
                videoFrameRatesByResolution = mapOf("FHD" to listOf(24, 30, 60, 120, 240), "UHD 4K" to listOf(24, 30))),
            settingValues = CameraCatalog.defaultSettingValues + ("slow_motion.lens" to "3x") + ("slow_motion.resolution" to "FHD"),
        )
        val fps = CameraCatalog.settingsFor(base).first { it.id == "slow_motion.frame_rate" }
        assertEquals(listOf("24fps", "30fps", "48fps", "60fps", "100fps", "120fps", "240fps"), fps.options)
        for (value in fps.options) {
            val current = CameraCatalog.currentValue(base, fps)
            val next = reducer.reduce(AppState(camera = base), CameraCommand.NudgeSetting(fps.id, fps.options.indexOf(value) - fps.options.indexOf(current))).state.camera
            assertEquals("3x", next.settingValues["slow_motion.lens"])
        }
        val uhd = base.copy(settingValues = base.settingValues + ("slow_motion.resolution" to "UHD 4K"))
        assertEquals(listOf("24fps", "30fps"), CameraCatalog.settingsFor(uhd).first { it.id == fps.id }.options)
        assertTrue(CameraCatalog.settingsFor(base).any { it.id == "slow_motion.manual_focus" })
        assertTrue(CameraCatalog.settingsFor(base).any { it.id == "slow_motion.white_balance" })
    }
    @Test fun `photo menu exposes actual lens size and supported formats only`() {
        val camera = CameraCatalog.launchCameraState(activeMode = CameraModeId.Pro).copy(capabilities = CameraCapabilities(
            availablePhotoResolutions = listOf("10MP"), rawCaptureSupported = false, rawJpegCaptureSupported = false,
            encodedPhotoFormats = listOf("PNG"),
        ))
        val settings = CameraCatalog.settingsFor(camera)
        assertEquals(listOf("10MP"), settings.first { it.id == "pro.megapixels" }.options)
        assertEquals(listOf("JPEG", "PNG"), settings.first { it.id == "pro.save_format" }.options)
        assertFalse(settings.first { it.id == "pro.megapixels" }.options.contains("200MP"))
    }
}
