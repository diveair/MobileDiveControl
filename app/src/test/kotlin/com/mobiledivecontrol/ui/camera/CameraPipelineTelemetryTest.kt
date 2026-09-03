package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CameraPipelineTelemetryTest {
    @BeforeEach
    fun reset() {
        CameraPipelineTelemetry.resetForStressRun()
    }

    @Test
    fun `advancing source timestamps are not reported as frozen textures`() {
        CameraPipelineTelemetry.recordDisplayedFrame(7L, 100L, 1_000L)
        CameraPipelineTelemetry.recordDisplayedFrame(7L, 101L, 2_000L)

        assertEquals(0L, CameraPipelineTelemetry.snapshot(200L).sourceTimestampStallCount)
        assertEquals(2_000L, CameraPipelineTelemetry.snapshot(200L).lastSourceFrameTimestampNs)
    }

    @Test
    fun `repeated source timestamp is reported within the same binding only`() {
        CameraPipelineTelemetry.recordDisplayedFrame(7L, 100L, 1_000L)
        CameraPipelineTelemetry.recordDisplayedFrame(7L, 101L, 1_000L)
        CameraPipelineTelemetry.recordDisplayedFrame(8L, 102L, 1_000L)

        assertEquals(1L, CameraPipelineTelemetry.snapshot(200L).sourceTimestampStallCount)
    }
}
