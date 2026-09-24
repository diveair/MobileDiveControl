package com.mobiledivecontrol.core

import kotlin.test.*

class DecompressionTrackerTest {
    private val epoch = 1_800_000_000_000L
    private fun sample(s: DecompressionState, seconds: Long, depth: Double, gas: DiveGas = DiveGas()) =
        DecompressionTracker.sample(s, depth, Buhlmann.pressure(depth), seconds * 1000, epoch + seconds * 1000, gas)
    private fun initialized(gas: DiveGas = DiveGas()) =
        DecompressionTracker.initialize(sample(DecompressionState(), 0, 0.0, gas), gas)

    @Test fun `unknown history never supplies numerical limits and cannot initialize underwater`() {
        val untracked = sample(DecompressionState(), 1, 20.0)
        assertNull(untracked.readings)
        assertEquals(untracked, DecompressionTracker.initialize(untracked, DiveGas()))
        val ready = initialized()
        assertEquals(DecoHistory.Tracking, ready.history)
        assertEquals(Buhlmann.NDL_CAP_SECONDS, ready.readings!!.ndlSeconds)
    }

    @Test fun `sensor integration matches independently verified ramp model and preserves helium`() {
        val gas = DiveGas(21, 35)
        var s = initialized(gas)
        for (t in 1L..120L) s = sample(s, t, t / 4.0, gas)
        val expected = Buhlmann.load(Buhlmann.equilibrium(), Buhlmann.pressure(0.0), Buhlmann.pressure(30.0), 2.0, gas)
        expected.forEachIndexed { i, tissue ->
            assertEquals(tissue.nitrogenBar, s.tissues[i].nitrogenBar, 1e-10)
            assertEquals(tissue.heliumBar, s.tissues[i].heliumBar, 1e-10)
        }
        assertNotNull(s.readings)
        assertTrue(s.tissues.first().heliumBar > 0)
    }

    @Test fun `missing underwater sample invalidates outputs without erasing tissues or crediting gap`() {
        val before = sample(initialized(), 1, 30.0)
        val after = sample(before, 5, 30.0)
        assertEquals(DecoHistory.Incomplete, after.history)
        assertNull(after.readings)
        assertEquals(before.tissues, after.tissues)
        assertEquals(before.integratedEpochMs, after.integratedEpochMs)
        assertEquals(after, DecompressionTracker.confirmSurfaceInterval(after, DiveGas()))
        assertEquals(before, sample(before, 1, 10.0))
    }

    @Test fun `offline interval requires confirmation before offgassing credit`() {
        val loaded = initialized().copy(tissues = Buhlmann.load(Buhlmann.equilibrium(),
            Buhlmann.pressure(30.0), Buhlmann.pressure(30.0), 30.0, DiveGas()), cnsPercent = 80.0)
        val paused = DecompressionTracker.restored(loaded)
        assertEquals(DecoHistory.SurfaceIntervalUnconfirmed, paused.history)
        val fresh = sample(paused, 5400, 0.0)
        assertNull(fresh.readings)
        assertEquals(loaded.tissues, fresh.tissues)
        val resumed = DecompressionTracker.confirmSurfaceInterval(fresh, DiveGas())
        assertEquals(40.0, resumed.cnsPercent, 1e-9)
        assertTrue(resumed.tissues.first().nitrogenBar < loaded.tissues.first().nitrogenBar)
        assertNotNull(resumed.readings)
        assertEquals(DecoHistory.Incomplete, sample(paused, 5400, 5.0).history)
    }

    @Test fun `selected oxygen remains oxygen even at the surface and accumulates exact dose`() {
        val oxygen = DiveGas(100)
        var s = initialized(oxygen)
        for (t in 1L..120L) s = sample(s, t, 0.0, oxygen)
        assertEquals(2.0, s.readings!!.otu24Hours, 1e-9)
        assertEquals(2 * 100.0 / 300, s.cnsPercent, 1e-9)
        assertEquals(1.0, s.ppO2Ata!!, 1e-9)
    }

    @Test fun `pressure ramps integrate oxygen dose independently of sample subdivision`() {
        val start = .21
        val end = 1.7
        val cns = OxygenExposure.cnsDose(start, end, 10.0)
        val otu = OxygenExposure.otuDose(start, end, 10.0)
        var dividedCns = 0.0
        var dividedOtu = 0.0
        repeat(1000) { i ->
            val a = start + (end-start)*i/1000
            val b = start + (end-start)*(i+1)/1000
            dividedCns += OxygenExposure.cnsDose(a, b, .01)
            dividedOtu += OxygenExposure.otuDose(a, b, .01)
        }
        assertEquals(cns, dividedCns, 1e-9)
        assertEquals(otu, dividedOtu, 1e-9)
        assertEquals(otu, OxygenExposure.otuDose(end, start, 10.0), 1e-9)
    }

    @Test fun `persistence retains physiology independently of logs and invalidates restart readings`() {
        val s = sample(initialized(DiveGas(32)), 1, 30.0, DiveGas(32))
        val restored = DiveProfileCodec.decode(DiveProfileCodec.encode(DiveProfileState(decompression = s))).decompression
        assertEquals(s.tissues, restored.tissues)
        assertEquals(s.cnsPercent, restored.cnsPercent)
        assertEquals(s.otuMinutes, restored.otuMinutes)
        assertEquals(s.integratedEpochMs, restored.integratedEpochMs)
        assertEquals(DecoHistory.Incomplete, restored.history)
        assertNull(restored.readings)
        assertNull(restored.sampleMonotonicMs)
    }

    @Test fun `version one checkpoints migrate without inventing tissue history`() {
        // Independent legacy empty checkpoint: magic, version, air, 5 m, 180 s, planned=false,
        // active=false, zero saved logs. The current encoder must not manufacture this fixture.
        val bytes = java.io.ByteArrayOutputStream().also { stream ->
            java.io.DataOutputStream(stream).use { out ->
                listOf(0x44495645, 1, 21, 0, 5, 180).forEach(out::writeInt)
                out.writeBoolean(false); out.writeBoolean(false); out.writeInt(0)
            }
        }.toByteArray()
        val restored = DiveProfileCodec.decode(bytes)
        assertEquals(DiveSettings(), restored.settings)
        assertEquals(DecoHistory.Uninitialized, restored.decompression.history)
        assertNull(restored.decompression.readings)
    }

    @Test fun `wall clock reversal invalidates limits and cannot recover through interval confirmation`() {
        val before = initialized()
        val after = DecompressionTracker.sample(before, 0.0, Buhlmann.SURFACE_BAR, 1000, epoch - 60_000, DiveGas())
        assertEquals(DecoHistory.Incomplete, after.history)
        assertNull(after.readings)
    }
}
