package com.mobiledivecontrol.core

import kotlin.math.ceil
import kotlin.test.*

class SurfaceIntervalTest {
    private val epoch = 1_800_000_000_000L
    private val air = DiveGas()

    private fun sample(state: DecompressionState, elapsedMs: Long, depth: Double) =
        DecompressionTracker.sample(state, depth, Buhlmann.pressure(depth), elapsedMs,
            epoch + elapsedMs, air)

    private fun initialized() = DecompressionTracker.initialize(sample(DecompressionState(), 0, 0.0), air)

    private fun checkpoint(state: DecompressionState) =
        DiveProfileCodec.decode(DiveProfileCodec.encode(DiveProfileState(decompression = state))).decompression

    private fun confirmAfter(state: DecompressionState, elapsedMs: Long) =
        DecompressionTracker.confirmSurfaceInterval(sample(checkpoint(state), elapsedMs, 0.0), air)

    @Test fun `monitored and restarted surface intervals match the independent repetitive dive fixture`() {
        val fixture = javaClass.getResourceAsStream("/decompression/decotengu-0.14.1.txt")!!
            .bufferedReader().use { it.readLines().first { row -> row.startsWith("repetitive_air|") } }.split('|')
        val expectedN = fixture[2].split(',').map(String::toDouble)
        val expectedH = fixture[3].split(',').map(String::toDouble)
        val results = listOf(false, true).map { restart ->
            var state = initialized()
            var elapsed = 0L
            var depth = 0.0
            fixture[1].split(';').forEachIndexed { index, segment ->
                val fields = segment.split(',')
                val endDepth = fields[0].toDouble()
                val durationMs = (fields[1].toDouble() * 60_000).toLong()
                if (restart && index == 5) {
                    // Monitor the first minute at the surface, then close the app for 59 minutes.
                    for (t in 2_000L..60_000L step 2_000L) state = sample(state, elapsed + t, 0.0)
                    state = confirmAfter(state, elapsed + durationMs)
                } else {
                    val steps = ceil(durationMs / 2_000.0).toInt()
                    for (step in 1..steps) {
                        val offset = durationMs * step / steps
                        state = sample(state, elapsed + offset,
                            depth + (endDepth - depth) * offset.toDouble() / durationMs)
                    }
                }
                elapsed += durationMs
                depth = endDepth
            }
            assertEquals(DecoHistory.Tracking, state.history)
            for (i in 0..15) {
                // The reference uses fractional seconds; sensor timestamps have millisecond precision.
                assertEquals(expectedN[i], state.tissues[i].nitrogenBar, 5e-6, "N2 compartment $i, restart=$restart")
                assertEquals(expectedH[i], state.tissues[i].heliumBar, 5e-6, "He compartment $i, restart=$restart")
            }
            assertEquals(fixture[6].toInt(), state.readings!!.ndlSeconds)
            state
        }
        results[0].tissues.zip(results[1].tissues).forEach { (live, restarted) ->
            assertEquals(live.nitrogenBar, restarted.nitrogenBar, 1e-10)
            assertEquals(live.heliumBar, restarted.heliumBar, 1e-10)
        }
    }

    @Test fun `longer elapsed intervals reduce residual gas and increase next dive NDL without resetting tissues`() {
        val loaded = initialized().copy(tissues = Buhlmann.load(Buhlmann.equilibrium(),
            Buhlmann.pressure(30.0), Buhlmann.pressure(30.0), 30.0, air))
        val intervals = listOf(30L, 60L, 120L).map { minutes -> confirmAfter(loaded, minutes * 60_000) }
        intervals.zipWithNext().forEach { (shorter, longer) ->
            for (i in 0..15) assertTrue(longer.tissues[i].nitrogenBar < shorter.tissues[i].nitrogenBar)
            assertTrue(Buhlmann.ndlSeconds(longer.tissues, 18.0, air) >
                Buhlmann.ndlSeconds(shorter.tissues, 18.0, air))
        }
        val baseline = Buhlmann.equilibrium()
        assertTrue(intervals.last().tissues.last().nitrogenBar > baseline.last().nitrogenBar)
        assertTrue(Buhlmann.ndlSeconds(intervals.last().tissues, 18.0, air) <
            Buhlmann.ndlSeconds(baseline, 18.0, air))
    }

    @Test fun `repeated confirmation and restarts never count a surface interval twice including helium and CNS`() {
        val loaded = initialized().copy(tissues = Buhlmann.load(Buhlmann.equilibrium(),
            Buhlmann.pressure(40.0), Buhlmann.pressure(40.0), 25.0, DiveGas(21, 35)), cnsPercent = 80.0)
        val hour = confirmAfter(loaded, 3_600_000)
        assertEquals(hour, DecompressionTracker.confirmSurfaceInterval(hour, air))
        val twoHours = confirmAfter(hour, 7_200_000)
        val uninterrupted = confirmAfter(loaded, 7_200_000)
        assertEquals(epoch + 7_200_000, twoHours.integratedEpochMs)
        twoHours.tissues.zip(uninterrupted.tissues).forEach { (split, whole) ->
            assertEquals(whole.nitrogenBar, split.nitrogenBar, 1e-10)
            assertEquals(whole.heliumBar, split.heliumBar, 1e-10)
        }
        assertTrue(twoHours.tissues.last().heliumBar > 0.0)
        assertTrue(twoHours.tissues.last().heliumBar < loaded.tissues.last().heliumBar)
        assertEquals(uninterrupted.cnsPercent, twoHours.cnsPercent, 1e-10)
        assertEquals(80.0 * Math.pow(0.5, 120.0 / 90.0), twoHours.cnsPercent, 1e-10)
    }
}
