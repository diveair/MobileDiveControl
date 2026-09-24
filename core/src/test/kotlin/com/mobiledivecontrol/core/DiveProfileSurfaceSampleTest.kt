package com.mobiledivecontrol.core

import kotlin.test.*

class DiveProfileSurfaceSampleTest {
    @Test fun `surfacing between regular logging intervals retains the final depth and timestamp`() {
        var state = DiveProfileTracker.sample(DiveProfileState(), 1.5, 1000, 1000)
        state = DiveProfileTracker.sample(state, 0.4, 2000, 2000)
        state = DiveProfileTracker.sample(state, 0.0, 3000, 3000)
        assertNull(state.active)
        val log = state.logs.single()
        assertEquals(2000L, log.elapsedMs)
        assertEquals(DiveSample(2000, 0.0), log.samples.last())
        assertEquals(listOf(DiveSample(0, 1.5), DiveSample(2000, 0.0)), log.samples)
    }
}
