package com.mobiledivecontrol.ui.camera

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnderwaterFrameAnalyzerTest {
    @Test
    fun `sRGB analysis transfer remains exact`() {
        assertTrue(UnderwaterFrameAnalyzer.linearizeByte(0) == 0.0)
        assertTrue(UnderwaterFrameAnalyzer.linearizeByte(255) == 1.0)
        assertTrue(abs(UnderwaterFrameAnalyzer.linearizeByte(128) - 0.21586) < 0.0001)
    }
}
