package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecialModeColourTest {
    @Test
    fun `food tone spans a symmetric cool warm kelvin range`() {
        assertEquals(3_200, foodColourTemperatureKelvin("-4"))
        assertEquals(5_000, foodColourTemperatureKelvin("0"))
        assertEquals(6_800, foodColourTemperatureKelvin("+4"))
        assertEquals(null, foodColourTemperatureKelvin("Auto"))
    }

    @Test
    fun `aqua tone is a bounded signed Duv trim`() {
        assertEquals(-0.006, aquaToneDuv("-4"), 1e-9)
        assertEquals(0.0, aquaToneDuv("0"), 1e-9)
        assertEquals(0.006, aquaToneDuv("+4"), 1e-9)
        assertEquals(0.0, aquaToneDuv(null), 1e-9)
    }

    @Test
    fun `hyperlapse recording limit accepts Samsung minute choices through 300`() {
        assertEquals(null, hyperlapseRecordingLimitMillis("∞"))
        assertEquals(600_000L, hyperlapseRecordingLimitMillis("10m"))
        assertEquals(600_000L, hyperlapseRecordingLimitMillis("10s"))
        assertEquals(18_000_000L, hyperlapseRecordingLimitMillis("300m"))
        assertEquals(null, hyperlapseRecordingLimitMillis("301m"))
    }

    @Test
    fun `hyperlapse cadence produces real frame intervals and shorter output clocks`() {
        assertEquals(5, hyperlapseSpeedFactor("5x", "Day"))
        assertEquals(45, hyperlapseSpeedFactor("Night 45x", "Night"))
        assertEquals(0.5, hyperlapseCaptureRateFps(60), 1e-9)
        assertEquals(2.0, hyperlapseFrameIntervalSeconds(60), 1e-9)
        assertEquals(2_000L, hyperlapsePlaybackDurationMs(10_000L, 5))
        assertEquals(0L, hyperlapsePlaybackDurationMs(-1L, 10))
        assertEquals(10, hyperlapseSpeedFactor("Auto", "Day", 10))
        assertEquals(15, hyperlapseSpeedFactor("Auto", "Day", 0x62))
        assertEquals("00:00:00", hyperlapseClockText(-1L))
        assertEquals("01:01:01", hyperlapseClockText(3_661_999L))
    }

    @Test
    fun `panorama motion follows the on screen sweep axis in landscape`() {
        // Android display rotations 1 and 3 are the two landscape orientations.
        assertEquals(0.8f, panoramaSweepAxisRate("Right", 1, 0.8f, 0.2f), 1e-6f)
        assertEquals(0.8f, panoramaSweepAxisRate("Left", 1, -0.8f, 0.2f), 1e-6f)
        assertEquals(0.2f, panoramaSweepAxisRate("Up", 1, 0.8f, 0.2f), 1e-6f)
        assertEquals(-0.2f, panoramaSweepAxisRate("Down", 1, 0.8f, 0.2f), 1e-6f)
        assertEquals("Right", panoramaDirectionFromGyro(1, 0.8f, 0.1f))
        assertEquals("Up", panoramaDirectionFromGyro(1, 0.1f, 0.8f))
        assertEquals(1f, panoramaProgressFraction(PANORAMA_TARGET_RADIANS), 1e-6f)
        assertEquals(0f, panoramaProgressFraction(-1f), 1e-6f)
    }

    @Test
    fun `pro video combined rate keeps capture and fractional playback distinct`() {
        assertEquals(23.976, proVideoPlaybackFrameRate("240fps/23.976fps playback"), 1e-9)
        assertEquals(48.0, proVideoPlaybackFrameRate("60fps/48fps playback"), 1e-9)
        assertEquals(2.5, captureToPlaybackTimestampScale(60, 24.0), 1e-9)
        assertEquals(30.0 / 29.97, captureToPlaybackTimestampScale(30, 29.97), 1e-9)
        assertEquals(24.0 / 23.976, captureToPlaybackTimestampScale(24, 23.976), 1e-9)
        assertEquals(1.0, captureToPlaybackTimestampScale(30, 30.0), 1e-9)
        assertEquals(1.0, captureToPlaybackTimestampScale(24, 24.0), 1e-9)
        assertEquals(24.0 / 23.976, playbackTimestampScale(23.976), 1e-9)
        assertEquals(1.0, playbackTimestampScale(24.0), 1e-9)
        assertEquals(30.0 / 29.97, playbackTimestampScale(29.97), 1e-9)
    }

    @Test
    fun `direct high speed capture uses a safe idle preview graph`() {
        assertEquals("FHD", previewBindingResolution("FHD 1920×824", true))
        assertEquals("FHD", previewBindingResolution("HD 720p", true))
        assertEquals("UHD 4K", previewBindingResolution("UHD 4K", false))
        assertEquals(30, previewFrameRateRequestFps(120, true))
        assertEquals(30, previewFrameRateRequestFps(240, true))
        assertEquals(60, previewFrameRateRequestFps(60, false))
    }

    @Test
    fun `playback-only rate changes do not rebind the preview`() {
        assertEquals(
            previewFrameRateSessionKey("30fps/23.976fps playback", false),
            previewFrameRateSessionKey("30fps/30fps playback", false),
        )
        assertEquals("30fps", previewFrameRateSessionKey("30fps/29.97fps playback", false))
        assertEquals(
            previewFrameRateSessionKey("24fps/23.976fps playback", false),
            previewFrameRateSessionKey("24fps/24fps playback", false),
        )
        assertEquals(
            previewFrameRateSessionKey("120fps/23.976fps playback", true),
            previewFrameRateSessionKey("240fps/240fps playback", true),
        )
        assertEquals(
            "stable-direct-camera2-preview",
            previewFrameRateSessionKey("120fps/120fps playback", true),
        )
    }

    @Test
    fun `samsung vendor video table exposes only genuine eight k rates`() {
        val configurations = intArrayOf(
            3840, 2160, 24, 60, 0, 0,
            7680, 4320, 24, 24, 0, 0,
            7680, 4320, 30, 30, 0, 0,
        )

        assertEquals(listOf(24, 30), samsungVendorEightKFrameRates(configurations))
        assertEquals(emptyList<Int>(), samsungVendorEightKFrameRates(intArrayOf(7680, 4000, 30)))
    }
}
