package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UhdDirectProfileTest {
    private val wide = UhdCaptureProfile("0", null, "1x", 1f, 120, false,
        TimeLapseVideoCodec.HEVC, expectedSensorId = "5")

    @Test fun regularHighRateUsesRecordingSessionParameters() {
        for (fps in listOf(100, 120)) {
            assertTrue(wide.copy(fps = fps).regularHfrSession)
            assertFalse(wide.copy(fps = fps, constrained = true).regularHfrSession)
        }
        assertFalse(wide.copy(fps = 60).regularHfrSession)
    }

    @Test fun silentHighRatePassCannotQualifyAudio() {
        val certificates = mutableSetOf(wide.certificateKey("firmware", audio = false))
        assertTrue(wide.hasCertificate("firmware", false, certificates::contains))
        assertFalse(wide.hasCertificate("firmware", true, certificates::contains))
        certificates.clear()
        certificates += wide.certificateKey("firmware", audio = true)
        assertTrue(wide.hasCertificate("firmware", true, certificates::contains))
        assertTrue(wide.hasCertificate("firmware", false, certificates::contains))
        assertFalse(wide.hasCertificate("update", true, certificates::contains))
        assertFalse(wide.copy(constrained = true).hasCertificate("firmware", true, certificates::contains))
    }

    @Test fun samsungSlowMotionIsLimitedToTheTwoS24OpticalRoutes() {
        assertTrue(SamsungUhdSlowMotion.acceptsLens("SM-S921W", "1x", "0", "5"))
        assertTrue(SamsungUhdSlowMotion.acceptsLens("SM-S921W", "0.6x", "0", "2"))
        assertFalse(SamsungUhdSlowMotion.acceptsLens("SM-S921W", "0.6x", "2", "2"))
        assertFalse(SamsungUhdSlowMotion.acceptsLens("SM-S921W", "1x", "0", null))
        assertFalse(SamsungUhdSlowMotion.acceptsLens("SM-S921W", "3x", "0", "6"))
        assertFalse(SamsungUhdSlowMotion.acceptsLens("SM-S921W", "front", "1", "1"))
        assertFalse(SamsungUhdSlowMotion.acceptsLens("SM-S911W", "1x", "0", "5"))
    }

    @Test fun samsungAndStandardSessionsRequireSeparateMeasurements() {
        val samsung = wide.copy(samsungSlowMotion = true)
        val standardPass = wide.certificateKey("firmware", audio = true)
        val samsungPass = samsung.certificateKey("firmware", audio = true)
        assertFalse(samsung.hasCertificate("firmware", true) { it == standardPass })
        assertFalse(wide.hasCertificate("firmware", true) { it == samsungPass })
        assertFalse(samsung.copy(cameraId = "2", lens = "0.6x", expectedSensorId = "2")
            .hasCertificate("firmware", true) { it == samsungPass })
    }

    @Test fun measuredSamsungRoutesAreSelectableBeforeTheMotionTest() {
        val firmware = SamsungUhdSlowMotion.MEASURED_FIRMWARE
        val main = wide.copy(samsungSlowMotion = true)
        val ultra = main.copy(lens = "0.6x", expectedSensorId = "2", zoom = 0.6f)
        for (route in listOf(main, ultra)) {
            assertTrue(route.selectableOn(firmware, qualified = false))
            assertFalse(route.hasCertificate(firmware, audio = true) { false })
            assertFalse(route.selectableOn("another firmware", qualified = false))
            assertTrue(route.selectableOn("another firmware", qualified = true))
        }
        assertFalse(wide.selectableOn(firmware, qualified = false))
    }

    @Test fun earlierHighRateCertificatesExpireButVerified60KeepsItsExactKey() {
        val oldHigh = "v3|false|5|firmware|0|null|1x|1.0|120|false|HEVC"
        assertFalse(wide.hasCertificate("firmware", false) { it == oldHigh })
        val old60 = "v1|firmware|0|null|1x|1.0|60|false|HEVC"
        val at60 = wide.copy(fps = 60)
        for (audio in listOf(false, true)) {
            assertEquals(old60, at60.certificateKey("firmware", audio))
            assertTrue(at60.hasCertificate("firmware", audio) { it == old60 })
        }
    }
}
