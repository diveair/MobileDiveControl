package com.mobiledivecontrol.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

class DiveLogNdlTest {
    @Test fun `lowest observed NDL survives ascent archival and persistence`() {
        var state = DiveProfileState(decompression = DecompressionState(history = DecoHistory.Tracking))
        var time = 0L
        fun sample(depth: Double, ndl: Int) {
            time += 1000
            state = state.copy(decompression = state.decompression.copy(readings = DecoReadings(ndl, 0.0, 0.0, false, 0.0)))
            state = DiveProfileTracker.sample(state, depth, time, time)
        }
        sample(2.0, 5940); sample(20.0, 1234); sample(20.0, 900); sample(5.0, 1600); sample(0.0, 5940)
        assertEquals(900, state.logs.single().minimumNdlSeconds)
        state = state.copy(decompression = DecompressionState())
        assertEquals(900, DiveProfileCodec.decode(DiveProfileCodec.encode(state)).logs.single().minimumNdlSeconds)
    }

    @Test fun `unknown history cannot record a fabricated NDL and unavailable time does not change the minimum`() {
        var state = DiveProfileTracker.sample(DiveProfileState(decompression = DecompressionState(
            readings = DecoReadings(0, 0.0, 0.0, false, 0.0))), 10.0, 1000, 1000)
        assertNull(state.active!!.minimumNdlSeconds)
        state = state.copy(active = state.active!!.copy(minimumNdlSeconds = 300))
        state = DiveProfileTracker.sample(state, 5.0, 2000, 2000)
        assertEquals(300, state.active!!.minimumNdlSeconds)
    }

    @Test fun `version three and four logs migrate without inventing past exposure`() {
        for (version in 3..4) {
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            fun settings() { out.writeInt(21); out.writeInt(0); out.writeInt(5); out.writeInt(180); out.writeBoolean(false) }
            out.writeInt(0x44495645); out.writeInt(version); settings(); out.writeBoolean(false); out.writeInt(1)
            out.writeLong(1000); settings(); out.writeLong(60000); out.writeDouble(10.0)
            out.writeInt(DiveStopPhase.Armed.ordinal); out.writeLong(0); out.writeBoolean(false)
            out.writeBoolean(false); out.writeInt(DiveLogOutcome.IncompleteStop.ordinal); out.writeInt(0)
            out.writeBoolean(false); out.writeDouble(-1000.0)
            if (version >= 4) out.writeInt(900)
            out.writeInt(DecoHistory.Uninitialized.ordinal); out.writeInt(0); out.writeDouble(0.0); out.writeBoolean(false)
            out.writeDouble(0.0); out.writeLong(0); out.writeLong(-1); out.writeDouble(-1.0); out.writeInt(0)
            out.writeInt(DiveStopPresentation.Dismissed.ordinal)
        } }.toByteArray()
        val migrated = DiveProfileCodec.decode(bytes)
        assertEquals(10.0, migrated.logs.single().maxDepthMeters)
        assertEquals(if (version >= 4) 900 else null, migrated.logs.single().minimumNdlSeconds)
        assertNull(migrated.logs.single().exposure)
        }
    }
}
