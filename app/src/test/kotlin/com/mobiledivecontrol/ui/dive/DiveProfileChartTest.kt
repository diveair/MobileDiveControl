package com.mobiledivecontrol.ui.dive

import com.mobiledivecontrol.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiveProfileChartTest {
    @Test fun `descent bottom time ascent and stop use elapsed time rather than evenly spaced sample indices`() {
        val samples = listOf(DiveSample(0, 1.5), DiveSample(120000, 18.0), DiveSample(1200000, 18.0),
            DiveSample(1320000, 5.0), DiveSample(1500000, 5.0), DiveSample(1560000, 0.0))
        val scale = profileChartScale(DiveSession(0, DiveSettings(), samples = samples, elapsedMs = 1560000,
            maxDepthMeters = 18.0), true)
        assertEquals(20.0, scale.bottomUnits)
        assertEquals(120000.0 / 1560000, scale.timeFraction(samples[1].elapsedMs), 1e-9)
        assertEquals(1080000.0 / 1560000,
            scale.timeFraction(samples[2].elapsedMs) - scale.timeFraction(samples[1].elapsedMs), 1e-9)
        assertEquals(0.9, scale.depthFraction(samples[1].depthMeters), 1e-9)
        assertEquals(0.25, scale.depthFraction(samples[3].depthMeters), 1e-9)
        assertEquals(0.0, scale.depthFraction(samples.last().depthMeters))
        assertEquals(listOf(samples), profileChartSegments(samples))
    }

    @Test fun `shallow and deep profiles use readable depth scales with zero at the top`() {
        val shallow = profileChartScale(DiveSession(0, DiveSettings(), maxDepthMeters = 1.8), true)
        assertEquals(2.0, shallow.bottomUnits)
        assertEquals(0.0, shallow.depthFraction(0.0))
        assertEquals(0.9, shallow.depthFraction(1.8), 1e-9)
        val deep = profileChartScale(DiveSession(0, DiveSettings(), maxDepthMeters = 42.0), true)
        assertTrue(deep.bottomUnits > 42.0)
        assertTrue(deep.depthFraction(30.0) > deep.depthFraction(10.0))
    }

    @Test fun `imperial axis and elapsed time place the same readings accurately`() {
        val dive = DiveSession(0, DiveSettings(), elapsedMs = 3600000, maxDepthMeters = 30.0)
        val scale = profileChartScale(dive, false)
        assertTrue(scale.bottomUnits > 98.0)
        assertEquals(30.0 * 3.28084 / scale.bottomUnits, scale.depthFraction(30.0), 1e-9)
        assertEquals(0.5, scale.timeFraction(1800000))
        assertEquals(1.0, scale.timeFraction(3600000))
    }

    @Test fun `gaps and invalid points split both the line and shaded profile`() {
        val samples = listOf(DiveSample(0, 0.0), DiveSample(1000, 10.0), DiveSample(2000, 12.0, gapBefore = true),
            DiveSample(3000, Double.NaN), DiveSample(4000, 5.0))
        val segments = profileChartSegments(samples)
        assertEquals(listOf(listOf(samples[0], samples[1]), listOf(samples[2]), listOf(samples[4])), segments)
        val scale = profileChartScale(DiveSession(0, DiveSettings(), samples = samples), true)
        assertTrue(scale.bottomUnits >= 12.0)
        assertEquals(4000L, scale.durationMs)
    }
}
