package com.mobiledivecontrol.core

import kotlin.test.*

class DiveLogCapacityTest {
    @Test fun `a thousand full profiles plus an active dive round trip without losing graph points`() {
        val samples = List(DiveProfileTracker.MAX_SAMPLES) { DiveSample(it * 5000L, 18.0) }
        val template = DiveSession(1, DiveSettings(), elapsedMs = samples.last().elapsedMs,
            maxDepthMeters = 18.0, samples = samples, outcome = DiveLogOutcome.CompletedStop, minimumNdlSeconds = 240,
            exposure = DiveExposureSummary(0.0, 12.5, false, 35.0, 1.4))
        val logs = List(1000) { template.copy(startedAtEpochMs = (1000 - it).toLong()) }
        val profile = DiveProfileState(logs = logs, active = template.copy(startedAtEpochMs = 2000, outcome = null))
        val bytes = DiveProfileCodec.encode(profile)
        assertTrue(bytes.size > 4_000_000, "Exercise the old byte limit, not just the log-count limit")
        assertTrue(bytes.size <= DiveProfileCodec.MAX_ENCODED_BYTES)
        val restored = DiveProfileCodec.decode(bytes)
        assertEquals(1000, restored.logs.size)
        restored.logs.forEachIndexed { index, log ->
            assertEquals(logs[index].startedAtEpochMs, log.startedAtEpochMs)
            assertEquals(3600, log.samples.size)
            assertEquals(samples.first(), log.samples.first())
            assertEquals(samples.last(), log.samples.last())
            assertEquals(240, log.minimumNdlSeconds)
            assertEquals(template.exposure, log.exposure)
        }
        assertEquals(samples, restored.active!!.samples)
    }

    @Test fun `archiving a new dive at capacity retains the newest thousand including the new dive`() {
        val logs = List(1000) { DiveSession((1000 - it).toLong(), DiveSettings(), maxDepthMeters = 2.0,
            outcome = DiveLogOutcome.NoStopRecorded) }
        val active = DiveSession(2000, DiveSettings(), maxDepthMeters = 2.0)
        val state = DiveProfileState(logs = logs, active = active, depthMeters = 0.2,
            sensorAvailable = true, lastSampleMs = 1000)
        val surfaced = DiveProfileTracker.sample(state, 0.0, 2000, 3000)
        assertNull(surfaced.active)
        assertEquals(1000, surfaced.logs.size)
        assertEquals(2000L, surfaced.logs.first().startedAtEpochMs)
        assertEquals(logs.dropLast(1), surfaced.logs.drop(1))
        assertEquals(2L, surfaced.logs.last().startedAtEpochMs)
    }

    @Test fun `legacy manual requirement flags are ignored and cannot force a shallow stop`() {
        val legacy = DiveProfileCodec.encode(DiveProfileState())
        legacy[24] = 1 // Existing version-four setting flag, preceding the active-session marker.
        var restored = DiveProfileCodec.decode(legacy)
        restored = DiveProfileTracker.sample(restored, 2.0, 1000, 1000)
        restored = DiveProfileTracker.sample(restored, 4.0, 2000, 2000)
        restored = DiveProfileTracker.sample(restored, 2.0, 3000, 3000)
        assertFalse(restored.active!!.requiredByKnownProfile)
        assertFalse(restored.stopActive)
        assertEquals(0, DiveProfileCodec.encode(restored)[24].toInt())
    }
}
