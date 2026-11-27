package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UhdHighSpeedRouteTest {
    @Test fun alternatingDuplicatesCannotQualifyAsHighSpeedVideo() {
        assertEquals(UhdMotionCheck.RepeatedFrames, classifyUhdMotion(List(240) { if (it % 2 == 0) 0.0 else 5.0 }, 240))
        assertEquals(UhdMotionCheck.TooLittleMotion, classifyUhdMotion(List(240) { 0.6 }, 240))
        assertEquals(UhdMotionCheck.Inconclusive, classifyUhdMotion(List(20) { 5.0 }, 240))
        assertEquals(UhdMotionCheck.Passed, classifyUhdMotion(List(240) { if (it < 12) 0.0 else 5.0 }, 240))
        assertEquals(UhdMotionCheck.RepeatedFrames, classifyUhdMotion(List(240) { if (it < 13) 0.0 else 5.0 }, 240))
    }
    @Test fun sensorRateCountsExposuresNotDuplicateBurstTimestamps() {
        // Astra's S24 Pro Video 1x take: frame number advanced 296 across 2.497 s of sensor time.
        assertEquals(118.54, sensorFrameRate(1000, 1296, 0, 2_497_047_000), 0.01)
        assertEquals(0.0, sensorFrameRate(-1, 10, 0, 1_000_000_000))
        assertEquals(0.0, sensorFrameRate(5, 5, 0, 1_000_000_000))
        assertEquals(0.0, sensorFrameRate(5, 10, 10, 10))
    }
}
