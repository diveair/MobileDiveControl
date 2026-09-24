package com.mobiledivecontrol.ui.dive

import com.mobiledivecontrol.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiveLogNdlTextTest {
    private val dive = DiveSession(0, DiveSettings(), minimumNdlSeconds = 900)
    @Test fun `live NDL and historical minimum do not borrow each others values`() {
        val state = DiveProfileState(active = dive, sensorAvailable = true,
            decompression = DecompressionState(history = DecoHistory.Tracking, readings = DecoReadings(120, 0.0, 0.0, false, 0.0)))
        assertEquals("NDL 2 min", logNdlText(state, dive))
        assertEquals("MIN NDL 15 min", logNdlText(state.copy(active = null), dive))
        assertEquals("NDL —", logNdlText(state.copy(sensorAvailable = false), dive))
        assertEquals("MIN NDL —", logNdlText(state.copy(active = null), dive.copy(minimumNdlSeconds = null)))
    }

    @Test fun `NDL rounding never gives an extra minute and capped limits remain explicit`() {
        val state = DiveProfileState()
        assertEquals("MIN NDL 1 min", logNdlText(state, dive.copy(minimumNdlSeconds = 119)))
        assertEquals("MIN NDL 59 s", logNdlText(state, dive.copy(minimumNdlSeconds = 59)))
        assertEquals("MIN NDL 99+ min", logNdlText(state, dive.copy(minimumNdlSeconds = Buhlmann.NDL_CAP_SECONDS)))
    }
}
