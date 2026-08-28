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
    fun `hyperlapse recording limit accepts infinity through 300 seconds`() {
        assertEquals(null, hyperlapseRecordingLimitMillis("∞"))
        assertEquals(10_000L, hyperlapseRecordingLimitMillis("10s"))
        assertEquals(300_000L, hyperlapseRecordingLimitMillis("300s"))
        assertEquals(null, hyperlapseRecordingLimitMillis("301s"))
    }

    @Test
    fun `pro video combined rate keeps capture and fractional playback distinct`() {
        assertEquals(23.976, proVideoPlaybackFrameRate("240fps/23.976fps playback"), 1e-9)
        assertEquals(24.0 / 23.976, playbackTimestampScale(23.976), 1e-9)
        assertEquals(1.0, playbackTimestampScale(24.0), 1e-9)
        assertEquals(30.0 / 29.97, playbackTimestampScale(29.97), 1e-9)
    }
}
