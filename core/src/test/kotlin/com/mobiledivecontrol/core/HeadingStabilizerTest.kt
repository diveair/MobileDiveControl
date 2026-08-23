package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadingStabilizerTest {
    @Test
    fun `stationary magnetometer drift is held exactly after fusion settles`() {
        val filter = HeadingStabilizer()
        filter.update(100.0, 0.0, 0L)
        filter.update(100.6, 0.0, 100L)
        val held = filter.update(100.8, 0.0, 500L)

        assertEquals(held, filter.update(101.7, 0.0, 900L), 0.0)
        assertEquals(held, filter.update(104.0, 0.0, 5_000L), 0.0)
    }

    @Test
    fun `real gyro rotation follows promptly and then establishes a new hold`() {
        val filter = HeadingStabilizer()
        filter.update(40.0, 0.0, 0L)
        filter.update(40.0, 0.0, 500L)

        var output = 40.0
        repeat(15) { index ->
            output = filter.update(
                rawHeadingDegrees = 65.0,
                angularSpeedRadPerSecond = 0.2,
                sampledAtMs = 520L + index * 20L,
            )
        }
        assertTrue(output > 63.0)

        val settled = filter.update(65.0, 0.0, 1_300L)
        assertEquals(settled, filter.update(67.0, 0.0, 2_000L), 0.0)
    }

    @Test
    fun `north wrap uses the short circular path`() {
        val filter = HeadingStabilizer()
        filter.update(359.0, 0.0, 0L)
        val moved = filter.update(1.0, 0.2, 100L)

        assertTrue(moved > 359.0 || moved < 1.0)
        assertTrue(kotlin.math.abs(HeadingMath.shortestDelta(moved, 1.0)) < 2.0)
    }

    @Test
    fun `sub-degree gyro tremor remains inside navigation deadband`() {
        val filter = HeadingStabilizer()
        val initial = filter.update(212.0, 0.0, 0L)
        repeat(30) { index ->
            val raw = if (index % 2 == 0) 212.25 else 211.75
            assertEquals(
                initial,
                filter.update(raw, 0.04, 20L + index * 20L),
                0.0,
            )
        }
    }
}
