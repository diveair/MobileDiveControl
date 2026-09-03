package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExpertRawCapturePolicyTest {
    @Test
    fun `still labels select distinct camera capture outputs`() {
        assertEquals(StillCaptureOutput.Jpeg, stillCaptureOutput("JPEG"))
        assertEquals(StillCaptureOutput.Raw, stillCaptureOutput("RAW"))
        assertEquals(StillCaptureOutput.RawJpeg, stillCaptureOutput("RAW + JPEG"))
        assertEquals(StillCaptureOutput.UltraHdrJpeg, stillCaptureOutput("Ultra HDR JPEG"))
    }

    @Test
    fun `effected live graph remains JPEG and incompatible outputs use a shutter transaction`() {
        StillCaptureOutput.entries.forEach { requested ->
            assertEquals(StillCaptureOutput.Jpeg, livePreviewCaptureOutput(requested, previewAssistanceRequired = true))
        }
        assertFalse(requiresDetachedStillCapture(StillCaptureOutput.Jpeg))
        assertTrue(requiresDetachedStillCapture(StillCaptureOutput.Raw))
        assertTrue(requiresDetachedStillCapture(StillCaptureOutput.RawJpeg))
        assertTrue(requiresDetachedStillCapture(StillCaptureOutput.UltraHdrJpeg))
    }

    @Test
    fun `virtual aperture has a continuous F16 to F1_4 effect range`() {
        assertEquals(0.0, virtualApertureStrength("F16.0"), 1e-9)
        assertTrue(virtualApertureStrength("F8.0") in 0.5..0.6)
        assertEquals(1.0, virtualApertureStrength("F1.4"), 1e-9)
    }

    @Test
    fun `ordinary still preview keeps requested output ready before shutter`() {
        StillCaptureOutput.entries.forEach { requested ->
            assertEquals(requested, livePreviewCaptureOutput(requested))
        }
    }

    @Test
    fun `ND stops scale a bounded real frame sequence`() {
        assertEquals(1, ndCaptureFrameCount("Off"))
        assertEquals(4, ndCaptureFrameCount("2 stops"))
        assertEquals(12, ndCaptureFrameCount("6 stops"))
        assertEquals(24, ndCaptureFrameCount("10 stops"))
    }

    @Test
    fun `astro plans span the selected real duration`() {
        val four = requireNotNull(astroCapturePlan("4 min"))
        val ten = requireNotNull(astroCapturePlan("10 min"))
        assertEquals(16, four.frameCount)
        assertEquals(240_000L, (four.frameCount - 1) * four.intervalMillis)
        assertEquals(30, ten.frameCount)
        assertTrue((ten.frameCount - 1) * ten.intervalMillis in 599_950L..600_000L)
        assertEquals(null, astroCapturePlan("Off"))
    }

    @Test
    fun `J2000 sidereal reference matches the standard Greenwich angle`() {
        val epoch = 946_728_000_000L // 2000-01-01 12:00:00 UTC (JD 2451545.0)
        assertEquals(
            280.46061837,
            SkyGuideAstronomy.localSiderealDegrees(epoch, 0.0),
            1e-6,
        )
    }
}
