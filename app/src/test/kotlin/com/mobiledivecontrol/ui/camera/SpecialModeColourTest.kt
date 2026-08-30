package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `lens route validation compares focal lengths not Samsung internal sensor ids`() {
        assertTrue(cameraFocalLengthMatches(2.2f, 2.2f))
        assertTrue(cameraFocalLengthMatches(5.4f, 5.42f))
        assertFalse(cameraFocalLengthMatches(5.4f, 7.0f))
        assertTrue(cameraFocalLengthMatches(null, 7.0f))
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
        assertEquals(5, hyperlapseSpeedFactor("Auto", "Day", 10))
        assertEquals(45, hyperlapseSpeedFactor("Auto", "Day", 100))
        assertEquals(5, hyperlapseSpeedFactor("Auto", "Day", 98))
        assertEquals(45, hyperlapseSpeedFactor("Auto", "Night", 10))
        assertEquals("00:00:00", hyperlapseClockText(-1L))
        assertEquals("01:01:01", hyperlapseClockText(3_661_999L))
    }

    @Test
    fun `hyperlapse video format resolves H264 and all HEVC H265 aliases`() {
        assertEquals(TimeLapseVideoCodec.H264, hyperlapseVideoCodec("H.264"))
        assertEquals(TimeLapseVideoCodec.H264, hyperlapseVideoCodec(null))
        assertEquals(TimeLapseVideoCodec.HEVC, hyperlapseVideoCodec("HEVC"))
        assertEquals(TimeLapseVideoCodec.HEVC, hyperlapseVideoCodec("H.265"))
        assertEquals(TimeLapseVideoCodec.HEVC, hyperlapseVideoCodec("HEVC / H.265"))
    }

    @Test
    fun `panorama motion follows the on screen sweep axis in landscape`() {
        // Measured on the S24: clockwise/right in rotation 1 reports negative gyro X.
        assertEquals(0.8f, panoramaSweepAxisRate("Right", 1, -0.8f, 0.2f), 1e-6f)
        assertEquals(0.8f, panoramaSweepAxisRate("Left", 1, 0.8f, 0.2f), 1e-6f)
        assertEquals(0.2f, panoramaSweepAxisRate("Up", 1, -0.8f, 0.2f), 1e-6f)
        assertEquals(-0.2f, panoramaSweepAxisRate("Down", 1, -0.8f, 0.2f), 1e-6f)
        assertEquals("Right", panoramaDirectionFromGyro(1, -0.8f, 0.1f))
        assertEquals("Left", panoramaDirectionFromGyro(1, 0.8f, 0.1f))
        assertEquals("Right", panoramaDirectionFromGyro(1, -0.1f, 0.8f))
        assertEquals(1f, panoramaProgressFraction(PANORAMA_HORIZONTAL_TARGET_RADIANS), 1e-6f)
        assertEquals(1f, panoramaProgressFraction(PANORAMA_VERTICAL_TARGET_RADIANS, "Up"), 1e-6f)
        assertEquals(
            1f,
            panoramaProgressFraction(PANORAMA_WIDE_HORIZONTAL_TARGET_RADIANS, wideAngle = true),
            1e-6f,
        )
        assertEquals(
            1f,
            panoramaProgressFraction(PANORAMA_WIDE_VERTICAL_TARGET_RADIANS, "Down", wideAngle = true),
            1e-6f,
        )
        assertEquals(0f, panoramaProgressFraction(-1f), 1e-6f)
    }

    @Test
    fun `panorama direction ignores shutter jitter until sustained screen motion`() {
        assertEquals(null, panoramaDirectionFromAccumulatedMotion(0.008f))
        assertEquals(null, panoramaDirectionFromAccumulatedMotion(-0.02f))
        assertEquals("Right", panoramaDirectionFromAccumulatedMotion(0.06f))
        assertEquals("Left", panoramaDirectionFromAccumulatedMotion(-0.06f))
    }

    @Test
    fun `panorama guide exposes cross axis pitch and yaw independently`() {
        // In landscape, X is the horizontal sweep/yaw rate and Y is the vertical/pitch rate.
        assertEquals(0.25f, panoramaCrossAxisRate("Right", 1, -0.8f, 0.25f), 1e-6f)
        assertEquals(0.25f, panoramaCrossAxisRate("Left", 1, 0.8f, 0.25f), 1e-6f)
        assertEquals(0.8f, panoramaCrossAxisRate("Up", 1, -0.8f, 0.25f), 1e-6f)
        assertEquals(0.8f, panoramaCrossAxisRate("Down", 1, -0.8f, -0.25f), 1e-6f)
        assertEquals(0.5f, panoramaGuideCrossFraction(0.12217305f), 1e-5f)
        assertEquals(-1f, panoramaGuideCrossFraction(-0.3f), 1e-6f)
        assertEquals(PanoramaWarningLevel.None, panoramaWarningLevel(0.02f))
        assertEquals(PanoramaWarningLevel.Low, panoramaWarningLevel(0.09f))
        assertEquals(PanoramaWarningLevel.High, panoramaWarningLevel(0.13f))
        assertEquals(PanoramaCorrection.Down, panoramaCorrection("Right", 0.13f))
        assertEquals(PanoramaCorrection.Up, panoramaCorrection("Left", -0.13f))
        assertEquals(PanoramaCorrection.Left, panoramaCorrection("Down", 0.13f))
        assertEquals(0f, panoramaGravityElevationRadians(0f, 9.81f, 0f), 1e-6f)
        assertEquals(
            0.5235988f,
            panoramaGravityElevationRadians(0f, 8.495709f, 4.905f),
            1e-4f,
        )
        assertEquals(0.2f, panoramaGravityCrossAxisRadians(0.1f, -0.1f), 1e-6f)
        assertEquals(-0.2f, panoramaGravityCrossAxisRadians(-0.1f, 0.1f), 1e-6f)
    }

    @Test
    fun `panorama reversal finishes only after a useful clockwise sweep`() {
        assertFalse(panoramaShouldFinishOnReverse(0.2f, 0.2f, 4))
        assertFalse(panoramaShouldFinishOnReverse(0.05f, 1.2f, 8))
        assertFalse(panoramaShouldFinishOnReverse(0.2f, 1.2f, 1))
        assertTrue(panoramaShouldFinishOnReverse(0.09f, 1.2f, 8))
    }

    @Test
    fun `panorama cylinder keeps sweep dimension and crops projection wedges`() {
        val horizontal = panoramaCylindricalProjectionSize(
            sourceWidth = 1600,
            sourceHeight = 1200,
            horizontal = true,
            horizontalFovRadians = 1.2217305f,
        )
        assertEquals(1600, horizontal.width)
        assertTrue(horizontal.height in 970..990)

        val vertical = panoramaCylindricalProjectionSize(
            sourceWidth = 1600,
            sourceHeight = 1200,
            horizontal = false,
            horizontalFovRadians = 1.2217305f,
        )
        assertTrue(vertical.width in 1410..1430)
        assertEquals(1200, vertical.height)
    }

    @Test
    fun `panorama registration corrects gyro spacing and cross axis drift`() {
        val width = 96
        val height = 64
        fun texture(x: Int, y: Int): Int {
            var value = x * 0x45d9f3b xor y * 0x119de1f3
            value = value xor (value ushr 16)
            value *= 0x45d9f3b
            value = value xor (value ushr 16)
            return value and 0xff
        }
        val previous = IntArray(width * height) { index ->
            texture(index % width, index / width)
        }
        fun shifted(offsetX: Int, offsetY: Int) = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            texture(x + offsetX, y + offsetY)
        }

        val right = panoramaFrameOffset(previous, shifted(18, 4), width, height, "Right", 16)
        assertEquals(18, right.x)
        assertEquals(4, right.y)
        assertTrue(right.correlation > 0.99)

        val left = panoramaFrameOffset(previous, shifted(-14, -2), width, height, "Left", 16)
        assertEquals(-14, left.x)
        assertEquals(-2, left.y)
        assertTrue(left.correlation > 0.99)

        val down = panoramaFrameOffset(previous, shifted(2, 16), width, height, "Down", 15)
        assertEquals(2, down.x)
        assertEquals(16, down.y)
        assertTrue(down.correlation > 0.99)
    }

    @Test
    fun `panorama registration falls back to gyro for textureless frames`() {
        val blank = IntArray(80 * 60) { 127 }
        val offset = panoramaFrameOffset(blank, blank, 80, 60, "Right", 14)

        assertEquals(14, offset.x)
        assertEquals(0, offset.y)
    }

    @Test
    fun `panorama exposure matching preserves endpoints and joins adaptive midtones`() {
        assertEquals(1f, panoramaExposureMatchGamma(120, 122), 1e-6f)
        assertTrue(panoramaExposureMatchGamma(90, 130) < 1f)
        assertTrue(panoramaExposureMatchGamma(170, 110) > 1f)
        assertEquals(1f, panoramaExposureMatchGamma(255, 120), 1e-6f)
        assertEquals(0.72f, panoramaExposureMatchGamma(20, 240), 1e-6f)
        assertEquals(1.38f, panoramaExposureMatchGamma(240, 20), 1e-6f)
    }

    @Test
    fun `panorama off hdr and log have distinct deterministic transfer curves`() {
        assertEquals(128, panoramaProfileTone(128, PanoramaDynamicRangeProfile.Off))
        assertTrue(panoramaProfileTone(128, PanoramaDynamicRangeProfile.Hdr) > 128)
        assertTrue(panoramaProfileTone(0, PanoramaDynamicRangeProfile.Log) > 0)
        assertTrue(panoramaProfileTone(255, PanoramaDynamicRangeProfile.Log) < 255)
        val offRange = panoramaProfileTone(255, PanoramaDynamicRangeProfile.Off) -
            panoramaProfileTone(0, PanoramaDynamicRangeProfile.Off)
        val logRange = panoramaProfileTone(255, PanoramaDynamicRangeProfile.Log) -
            panoramaProfileTone(0, PanoramaDynamicRangeProfile.Log)
        assertTrue(logRange < offRange)
    }

    @Test
    fun `panorama profile transform preserves alpha and off preserves the source`() {
        val source = 0x7f3c78c8
        assertEquals(source, panoramaProfileArgb(source, PanoramaDynamicRangeProfile.Off))
        val hdr = panoramaProfileArgb(source, PanoramaDynamicRangeProfile.Hdr)
        val log = panoramaProfileArgb(source, PanoramaDynamicRangeProfile.Log)
        assertEquals(source ushr 24, hdr ushr 24)
        assertEquals(source ushr 24, log ushr 24)
        assertTrue(hdr != source)
        assertTrue(log != source)
        assertTrue(hdr != log)
    }

    @Test
    fun `panorama motion fusion retains more of the sharper overlap`() {
        assertTrue(panoramaSharpnessRetentionBias(24.0, 12.0) > 0f)
        assertTrue(panoramaSharpnessRetentionBias(12.0, 24.0) < 0f)
        assertEquals(0f, panoramaSharpnessRetentionBias(16.0, 16.0), 1e-6f)
        assertEquals(0.18f, panoramaSharpnessRetentionBias(100.0, 1.0), 1e-6f)
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
