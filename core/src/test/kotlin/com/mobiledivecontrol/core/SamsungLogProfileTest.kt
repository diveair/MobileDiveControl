package com.mobiledivecontrol.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamsungLogProfileTest {
    @Test
    fun `published Samsung Log anchors are exact`() {
        assertClose(0.125124, SamsungLogProfile.encode(0.0), 0.000001)
        assertClose(0.206562, SamsungLogProfile.encode(0.01), 0.000001)
        assertClose(0.527859, SamsungLogProfile.encode(0.18), 0.000001)
        assertClose(0.708700, SamsungLogProfile.encode(0.90), 0.000001)
        assertClose(1.0, SamsungLogProfile.encode(12.0), 0.000001)
    }

    @Test
    fun `Samsung Log transfer round trips its supported scene range`() {
        listOf(-0.049, 0.0, 0.01, 0.18, 0.9, 1.0, 6.0, 12.0).forEach { scene ->
            assertClose(scene, SamsungLogProfile.decode(SamsungLogProfile.encode(scene)), 0.00001)
        }
    }

    @Test
    fun `HLG editor transform maps full scene domain to published anchors`() {
        listOf(0.0, 0.01, 0.18, 0.9, 1.0, 6.0, 12.0).forEach { scene ->
            val hlg = SamsungLogProfile.sceneLinearToHlg(scene / SamsungLogProfile.MAX_SCENE_LINEAR)
            assertClose(
                SamsungLogProfile.encode(scene),
                SamsungLogProfile.hlgToSamsungLog(hlg),
                0.000001,
            )
        }
    }

    @Test
    fun `S24 calibration reserves Samsung full range without lying about manual controls`() {
        assertClose(-1.4432072500844577, SamsungLogProfile.S24_1X_ACQUISITION_OFFSET_EV, 1e-12)
        val calibration = SamsungLogProfile.acquisitionCalibration("SM-S921W", "1x")
        assertEquals("SM-S921-1x-2026-08-24", calibration?.id)
        assertEquals(-1.5, calibration?.commandedOffsetEv)
        assertEquals(-1.5, SamsungLogProfile.effectiveAutoExposureEv(0.0, calibration))
        assertEquals(-1.2, SamsungLogProfile.effectiveAutoExposureEv(0.3, calibration))
        assertEquals(0.3, SamsungLogProfile.effectiveAutoExposureEv(0.3, null))
        assertEquals(0, SamsungLogProfile.protectedManualMeterTenths(-15, calibration))
        assertEquals(-15, SamsungLogProfile.protectedManualMeterTenths(-15, null))
    }

    @Test
    fun `unmeasured models and lenses never inherit S24 calibration`() {
        assertEquals(null, SamsungLogProfile.acquisitionCalibration("SM-S928W", "1x"))
        assertEquals(null, SamsungLogProfile.acquisitionCalibration("SM-S921W", "3x"))
        assertEquals(null, SamsungLogProfile.acquisitionCalibration("unknown", "1x"))
    }

    @Test
    fun `legal range helper uses ten bit video endpoints`() {
        assertEquals(64.0, SamsungLogProfile.legalRange10Bit(0.0))
        assertEquals(940.0, SamsungLogProfile.legalRange10Bit(1.0))
        assertTrue(SamsungLogProfile.legalRange10Bit(SamsungLogProfile.encode(0.18)) in 526.0..527.0)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected=$expected actual=$actual tolerance=$tolerance")
    }
}
