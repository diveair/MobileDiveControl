package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

class HyperlapseNightMathTest {
    @Test
    fun `frames per output follows sensor rate over capture rate`() {
        assertEquals(45, HyperlapseNightMath.framesPerOutput(30.0, hyperlapseCaptureRateFps(45)))
        assertEquals(5, HyperlapseNightMath.framesPerOutput(30.0, hyperlapseCaptureRateFps(5)))
        assertEquals(8, HyperlapseNightMath.framesPerOutput(15.0, hyperlapseCaptureRateFps(15)))
        assertEquals(1, HyperlapseNightMath.framesPerOutput(30.0, 60.0))
        assertEquals(1, HyperlapseNightMath.framesPerOutput(0.0, 2.0))
        assertEquals(1, HyperlapseNightMath.framesPerOutput(30.0, 0.0))
        assertEquals(1_500_000_000L, HyperlapseNightMath.outputIntervalNs(hyperlapseCaptureRateFps(45)))
        assertEquals(0L, HyperlapseNightMath.outputIntervalNs(0.0))
    }

    @Test
    fun `tone curve lifts shadows by the gain and never clips highlights`() {
        assertEquals(0f, HyperlapseNightMath.toneCurveK(1f))
        assertEquals(0f, HyperlapseNightMath.toneCurveK(0.5f))
        assertEquals(0f, HyperlapseNightMath.toneCurveK(Float.NaN))
        for (gain in listOf(1.5f, 2f, 4f, 8f, 16f)) {
            val k = HyperlapseNightMath.toneCurveK(gain)
            val slope = k / (1.0 - exp(-k.toDouble()))
            assertEquals(gain.toDouble(), slope, 1e-4, "shadow slope for gain $gain")
            assertEquals(1f, HyperlapseNightMath.toneCurve(1f, k), 1e-6f)
            assertEquals(0f, HyperlapseNightMath.toneCurve(0f, k), 1e-6f)
            var previous = -1f
            for (i in 0..100) {
                val y = HyperlapseNightMath.toneCurve(i / 100f, k)
                // Non-decreasing (strong gains saturate to 1f in float precision near the top).
                assertTrue(y >= previous && y <= 1f)
                previous = y
            }
            assertTrue(HyperlapseNightMath.toneCurve(0.5f, k) > HyperlapseNightMath.toneCurve(0.25f, k))
            assertTrue(HyperlapseNightMath.toneCurve(0.25f, k) > 0.25f)
        }
        assertEquals(0.37f, HyperlapseNightMath.toneCurve(0.37f, 0f), 0f)
        assertEquals(1f, HyperlapseNightMath.toneCurveNorm(0f))
    }

    @Test
    fun `trails keep moving lights and reject noise excursions`() {
        assertEquals(0.10f, HyperlapseNightMath.trailsComposite(0.10f, 0.13f), 1e-6f)
        assertEquals(0.85f, HyperlapseNightMath.trailsComposite(0.10f, 0.85f), 1e-6f)
        assertEquals(0.40f, HyperlapseNightMath.trailsComposite(0.40f, 0.40f), 1e-6f)
        assertEquals(0.40f, HyperlapseNightMath.trailsComposite(0.40f, 0.30f), 1e-6f)
        val middle = HyperlapseNightMath.trailsComposite(0.1f, 0.23f)
        assertTrue(middle > 0.1f && middle < 0.23f)
    }

    @Test
    fun `preview viewport letterboxes or fills in view space`() {
        val out = IntArray(4)
        HyperlapseNightMath.previewViewport(out, 2340, 1080, 2340, 1080, 16f / 9f, NightPreviewScale.FIT)
        assertArrayEquals(intArrayOf(210, 0, 1920, 1080), out)
        HyperlapseNightMath.previewViewport(out, 2340, 1080, 2340, 1080, 16f / 9f, NightPreviewScale.FILL)
        assertArrayEquals(intArrayOf(0, -118, 2340, 1316), out)
        // Fixed 1920x1080 SurfaceView buffer stretched to a 2340x1080 view: fit in view, map to buffer.
        HyperlapseNightMath.previewViewport(out, 2340, 1080, 1920, 1080, 16f / 9f, NightPreviewScale.FIT)
        assertEquals(1080, out[3])
        assertEquals(1575, out[2])
        assertEquals(172, out[0])
        HyperlapseNightMath.previewViewport(out, 1000, 1000, 1000, 1000, 0f, NightPreviewScale.FIT)
        assertArrayEquals(intArrayOf(0, 0, 1000, 1000), out)
    }

    @Test
    fun `count mode closes every n-th frame`() {
        val cadence = NightOutputCadence().apply { framesPerOutput = 3 }
        val emitted = (1..9).map { frame ->
            assertEquals((frame - 1) % 3 + 1, cadence.beginFrame())
            cadence.endFrame(frame * 33_333_333L)
        }
        assertEquals(listOf(false, false, true, false, false, true, false, false, true), emitted)
        cadence.framesPerOutput = 0
        cadence.beginFrame()
        assertTrue(cadence.endFrame(10 * 33_333_333L))
    }

    @Test
    fun `interval emissions survive media recorder time lapse snapping`() {
        for ((sensorFps, speed) in listOf(30.0 to 5, 30.0 to 15, 30.0 to 45, 15.0 to 15, 15.0 to 45, 24.0 to 5, 10.0 to 45)) {
            val result = simulate(sensorFps, speed, seconds = 180.0, jitterNs = 2_000_000L)
            val expected = (180.0 * hyperlapseCaptureRateFps(speed)).toInt()
            assertTrue(abs(result.emitted - expected) <= 2, "$sensorFps fps ${speed}x emitted ${result.emitted} of $expected")
            assertEquals(result.emitted, result.slots.size, "$sensorFps fps ${speed}x dropped by recorder")
            assertEquals(result.slots.indices.map { it.toLong() }, result.slots, "$sensorFps fps ${speed}x slots")
        }
    }

    @Test
    fun `interval mode absorbs an auto exposure frame rate change`() {
        val result = simulate(30.0, 45, seconds = 120.0, jitterNs = 1_000_000L, switchToFpsAtSeconds = 60.0 to 12.0)
        assertEquals(result.emitted, result.slots.size)
        assertEquals(result.slots.indices.map { it.toLong() }, result.slots)
        // Frames per window follow the actual sensor rate: ~45 before the switch, ~18 after.
        assertTrue(result.windowSizes.first() in 44..46)
        assertTrue(result.windowSizes.last() in 17..19)
    }

    @Test
    fun `a stall longer than an interval re-anchors instead of bursting`() {
        val cadence = NightOutputCadence().apply { outputIntervalNs = 500_000_000L }
        var t = 0L
        var emits = 0
        repeat(31) { cadence.beginFrame(); if (cadence.endFrame(t)) emits++; t += 33_333_333L }
        assertEquals(2, emits)
        t += 3_000_000_000L
        cadence.beginFrame()
        assertTrue(cadence.endFrame(t))
        // The next frame must not emit immediately for the missed grid points.
        cadence.beginFrame()
        assertFalse(cadence.endFrame(t + 33_333_333L))
    }

    private class Simulation(val emitted: Int, val slots: List<Long>, val windowSizes: List<Int>)

    private fun simulate(
        sensorFps: Double,
        speed: Int,
        seconds: Double,
        jitterNs: Long,
        switchToFpsAtSeconds: Pair<Double, Double>? = null,
    ): Simulation {
        val captureFps = hyperlapseCaptureRateFps(speed)
        val cadence = NightOutputCadence().apply {
            outputIntervalNs = HyperlapseNightMath.outputIntervalNs(captureFps)
            framesPerOutput = HyperlapseNightMath.framesPerOutput(sensorFps, captureFps)
        }
        val recorder = TimeLapseSnapModel(captureFps)
        val random = Random(42L + speed)
        val start = 5_000_000_000_000L
        var nominal = start.toDouble()
        var emitted = 0
        val windows = mutableListOf<Int>()
        while (nominal - start < seconds * 1e9) {
            val fps = switchToFpsAtSeconds?.let { (at, next) -> if (nominal - start >= at * 1e9) next else null } ?: sensorFps
            val timestamp = nominal.roundToLong() + (random.nextDouble() * 2 - 1).times(jitterNs).roundToLong()
            val n = cadence.beginFrame()
            if (cadence.endFrame(timestamp)) {
                emitted++
                windows += n
                recorder.offer(timestamp)
            }
            nominal += 1e9 / fps
        }
        return Simulation(emitted, recorder.slots, windows.drop(1))
    }

    /** GraphicBufferSource time-lapse timestamp snapping (frameworks/av, mSnapTimestamps = true). */
    private class TimeLapseSnapModel(captureFps: Double) {
        private val intervalUs = 1_000_000.0 / captureFps
        private var previousCaptureUs = -1L
        private var baseCaptureUs = 0L
        private var frameCount = 0L
        val slots = mutableListOf<Long>()

        fun offer(timestampNs: Long) {
            val timeUs = timestampNs / 1000
            if (previousCaptureUs < 0) {
                previousCaptureUs = timeUs
                baseCaptureUs = timeUs
                frameCount = 0
                slots += 0L
                return
            }
            var frames = (timeUs - previousCaptureUs) / intervalUs
            if (frames < 0.5 - 0.05) return
            if (frames <= 1.0) frames = 1.0
            frameCount += Math.round(frames)
            previousCaptureUs = baseCaptureUs + (frameCount * intervalUs).roundToLong()
            slots += frameCount
        }
    }
}
