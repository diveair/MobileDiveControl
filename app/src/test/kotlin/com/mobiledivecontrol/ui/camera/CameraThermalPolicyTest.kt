package com.mobiledivecontrol.ui.camera

import android.os.PowerManager.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CameraThermalPolicyTest {
    @Test fun `forecast reduces load before a warning and suspends an approaching severe workload`() {
        val policy = CameraThermalPolicy()
        assertEquals(CameraThermalLoad.Normal, policy.update(THERMAL_STATUS_NONE, Float.NaN, 0))
        assertEquals(CameraThermalLoad.Reduced, policy.update(THERMAL_STATUS_NONE, 0.86f, 10_000))
        assertEquals(CameraThermalLoad.Reduced, policy.update(THERMAL_STATUS_MODERATE, 0.96f, 20_000))
        assertEquals(CameraThermalLoad.Cooling, policy.update(THERMAL_STATUS_MODERATE, 1.01f, 30_000))
    }

    @Test fun `reported severity protects every mode without a usable forecast`() {
        for (hot in THERMAL_STATUS_SEVERE..THERMAL_STATUS_SHUTDOWN) {
            val policy = CameraThermalPolicy()
            assertEquals(CameraThermalLoad.Reduced, policy.update(THERMAL_STATUS_LIGHT, Float.NaN, 0))
            assertEquals(CameraThermalLoad.Cooling, policy.update(hot, Float.NaN, 1))
            assertEquals(CameraThermalLoad.Cooling, policy.update(THERMAL_STATUS_MODERATE, Float.NaN, 90_000))
        }
    }

    @Test fun `a new spike restarts the recovery clock`() {
        val policy = CameraThermalPolicy()
        policy.update(THERMAL_STATUS_CRITICAL, Float.NaN, 0)
        policy.update(THERMAL_STATUS_LIGHT, 0.84f, 10_000)
        policy.update(THERMAL_STATUS_LIGHT, 0.97f, 30_000)
        policy.update(THERMAL_STATUS_LIGHT, 0.84f, 40_000)
        assertEquals(CameraThermalLoad.Cooling, policy.update(THERMAL_STATUS_LIGHT, 0.84f, 69_999))
        assertEquals(CameraThermalLoad.Reduced, policy.update(THERMAL_STATUS_LIGHT, 0.84f, 70_000))
    }

    @Test fun `warm preview renders fifteen of sixty inputs and accepts a replacement stream immediately`() {
        var previous = 0L
        var rendered = 0
        for (i in 1..60) {
            val timestamp = i * 16_666_667L
            if (!skipWarmPreviewFrame(true, previous, timestamp)) {
                previous = timestamp
                rendered++
            }
        }
        assertEquals(15, rendered)
        assertFalse(skipWarmPreviewFrame(true, previous, 1))
        assertFalse(skipWarmPreviewFrame(false, previous, previous + 1))
    }
}
