package com.mobiledivecontrol.core

import kotlin.test.*

class DiveLogExposureTest {
    @Test fun `dive records observed peaks through ascent and preserves them in its own saved log`() {
        var state = DiveProfileState()
        var time = 0L
        fun sample(depth: Double, reading: DecoReadings, ppO2: Double) {
            time += 1000
            state = state.copy(decompression = DecompressionState(history = DecoHistory.Tracking,
                readings = reading, ppO2Ata = ppO2))
            state = DiveProfileTracker.sample(state, depth, time, time)
        }
        sample(2.0, DecoReadings(5940, 0.0, 2.0, false, 3.0), .25)
        sample(30.0, DecoReadings(0, 3.3, 80.2, true, 140.5), 1.7)
        sample(5.0, DecoReadings(1800, 0.0, 75.0, true, 135.0), .4)
        sample(0.0, DecoReadings(5940, 0.0, 70.0, true, 130.0), .21)
        val expected = DiveExposureSummary(3.3, 80.2, true, 140.5, 1.7)
        assertEquals(expected, state.logs.single().exposure)
        val restored = DiveProfileCodec.decode(DiveProfileCodec.encode(state.copy(decompression = DecompressionState())))
        assertEquals(expected, restored.logs.single().exposure)
        sample(2.0, DecoReadings(5940, 0.0, 20.0, false, 50.0), .3)
        assertEquals(0.0, state.active!!.exposure!!.maxCeilingMeters)
        assertEquals(20.0, state.active!!.exposure!!.maxCnsPercent)
        assertEquals(expected, state.logs.single().exposure)
    }

    @Test fun `unknown history cannot invent exposure and later missing history cannot overwrite recorded peaks`() {
        val reading = DecoReadings(0, 3.3, 80.2, true, 140.5)
        var state = DiveProfileTracker.sample(DiveProfileState(decompression = DecompressionState(
            readings = reading, ppO2Ata = 1.7)), 10.0, 1000, 1000)
        assertNull(state.active!!.exposure)
        state = state.copy(decompression = state.decompression.copy(history = DecoHistory.Tracking))
        state = DiveProfileTracker.sample(state, 10.0, 2000, 2000)
        val recorded = state.active!!.exposure
        assertNotNull(recorded)
        state = state.copy(decompression = state.decompression.copy(history = DecoHistory.Incomplete,
            readings = DecoReadings(0, 9.0, 99.0, true, 999.0), ppO2Ata = 2.0))
        state = DiveProfileTracker.sample(state, 10.0, 3000, 3000)
        assertEquals(recorded, state.active!!.exposure)
    }
}
