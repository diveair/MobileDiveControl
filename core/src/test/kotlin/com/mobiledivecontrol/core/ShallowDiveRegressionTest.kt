package com.mobiledivecontrol.core

import kotlin.test.*

class ShallowDiveRegressionTest {
    private var now = 0L
    private var profile = DiveProfileState(settings = DiveSettings(stopDepthMeters = 4))
    private fun at(depth: Double, seconds: Int = 1) {
        repeat(seconds) {
            now += 1000
            profile = DiveProfileTracker.sample(profile, depth, now, 1_790_000_000_000L + now)
        }
    }

    @Test fun `October first shallow segments remain two dives and never request a stop`() {
        // Measured maxima/durations and surface gaps from the four Oct 1 records.
        // Constant-depth plateaus reproduce the boundaries, not a fabricated full trace.
        at(2.3379, 346); at(0.0, 181)
        assertEquals(1, profile.logs.size)
        at(2.5173, 197); at(0.0, 156); at(4.8242, 3185)
        at(0.0, 30); at(1.6366, 96); at(0.0, 181)
        assertNull(profile.active)
        assertEquals(2, profile.logs.size)
        assertEquals(0, profile.alertSequence)
        assertTrue(profile.logs.all { it.outcome == DiveLogOutcome.NoStopRecorded })
        assertEquals(4.8242, profile.logs.first().maxDepthMeters)
    }

    @Test fun `eleven metre eligibility is independent of duration and stop target`() {
        at(4.9, 3600); at(4.0)
        assertFalse(profile.stopActive)
        at(11.0); at(4.0)
        assertFalse(profile.stopActive)
        at(11.01); at(4.0)
        assertTrue(profile.stopActive)
        assertEquals(1, profile.alertSequence)
    }

    @Test fun `surface noise needs sustained confirmation and final wait is not dive time`() {
        at(3.0, 20); at(0.0); at(0.4, 100); at(0.9); at(0.3, 179)
        assertNotNull(profile.active)
        at(0.5, 2)
        assertNull(profile.active)
        assertEquals(122_000, profile.logs.single().elapsedMs)
        assertTrue(profile.logs.single().samples.last().depthMeters <= 0.8)
    }

    @Test fun `housing loss after confirmed surfacing closes but underwater loss does not`() {
        at(4.0, 20); at(0.3, 12)
        profile = DiveProfileTracker.unavailable(profile)
        assertNull(profile.active)
        assertEquals(1, profile.logs.size)
        at(4.0, 20)
        profile = DiveProfileTracker.unavailable(profile)
        assertNotNull(profile.active)
        assertTrue(profile.active!!.profileIncomplete)
        assertEquals(1, profile.logs.size)
    }

    @Test fun `missing surface samples do not earn surface confirmation`() {
        at(4.0); at(0.0)
        now += 200_000
        profile = DiveProfileTracker.sample(profile, 4.0, now, 1_790_000_000_000L + now)
        assertNotNull(profile.active)
        assertTrue(profile.logs.isEmpty())
        assertTrue(profile.active!!.profileIncomplete)
    }

    @Test fun `surface reconnect archives old surface endpoint without adding time on land`() {
        at(4.0, 30); at(0.3)
        val end = profile.active!!.elapsedMs
        profile = DiveProfileCodec.decode(DiveProfileCodec.encode(profile))
        now += 3_600_000
        at(0.2)
        assertNull(profile.active)
        assertEquals(end, profile.logs.single().elapsedMs)
        assertTrue(profile.logs.single().profileIncomplete)
    }

    @Test fun `known NDL obligation still qualifies a shallow profile`() {
        val session = DiveSession(0, DiveSettings(stopDepthMeters = 4), maxDepthMeters = 4.8, limitReached = true)
        assertTrue(session.stopRecommended)
    }
}
