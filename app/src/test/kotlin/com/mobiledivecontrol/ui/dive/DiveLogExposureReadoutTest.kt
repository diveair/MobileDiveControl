package com.mobiledivecontrol.ui.dive

import com.mobiledivecontrol.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiveLogExposureReadoutTest {
    @Test fun `saved log shows its own recorded maxima and never the current dive readings`() {
        val saved = DiveSession(1, DiveSettings(), exposure = DiveExposureSummary(1.21, 81.2, true, 140.5, 1.7))
        val profile = DiveProfileState(active = saved.copy(startedAtEpochMs = 2), sensorAvailable = true,
            decompression = DecompressionState(history = DecoHistory.Tracking,
                readings = DecoReadings(500, 0.0, 5.0, false, 20.0), ppO2Ata = .21))
        assertEquals(listOf("MAX CEILING 1.3m", "MAX CNS ≥82%", "MAX OTU 24h 141", "MAX ppO₂ 1.70 ATA"),
            diveLogExposureText(profile, saved, true))
        assertEquals(listOf("MAX CEILING —", "MAX CNS —", "MAX OTU 24h —", "MAX ppO₂ —"),
            diveLogExposureText(profile, saved.copy(exposure = null), true))
    }

    @Test fun `active profile shows fresh readings with conservative rounding and hides unavailable values`() {
        val active = DiveSession(1, DiveSettings())
        val profile = DiveProfileState(active = active, sensorAvailable = true,
            decompression = DecompressionState(history = DecoHistory.Tracking,
                readings = DecoReadings(500, .04, 1.2, false, 4.1), ppO2Ata = 1.34))
        assertEquals(listOf("CEILING 0.1m", "CNS 2%", "OTU 24h 5", "ppO₂ 1.34 ATA"),
            diveLogExposureText(profile, active, true))
        val unavailable = listOf("CEILING —", "CNS —", "OTU 24h —", "ppO₂ —")
        assertEquals(unavailable, diveLogExposureText(profile.copy(sensorAvailable = false), active, true))
        assertEquals(unavailable, diveLogExposureText(profile.copy(decompression = profile.decompression.copy(
            history = DecoHistory.Incomplete)), active, true))
    }
}
