package com.mobiledivecontrol.core

import kotlin.test.*

class DiveStopDecompressionTest {
    private var now = 0L
    private var profile = DiveProfileState()
    private fun at(depth: Double, ceiling: Double) {
        now += 1000
        profile = profile.copy(decompression = DecompressionState(history = DecoHistory.Tracking,
            readings = DecoReadings(0, ceiling, 0.0, false, 0.0)))
        profile = DiveProfileTracker.sample(profile, depth, now, now)
    }

    @Test fun `a computed ceiling pauses the safety timer and clearance does not credit the paused interval`() {
        at(2.0, 0.0); at(20.0, 2.0); at(5.0, 2.0)
        repeat(200) { at(5.0, 2.0) }
        assertTrue(profile.decompressionHold)
        assertEquals(0L, profile.active!!.stopElapsedMs)
        assertEquals(1, profile.alertSequence)
        assertNotEquals(DiveStopPhase.Complete, profile.active!!.phase)
        at(5.0, 0.0)
        assertFalse(profile.decompressionHold)
        assertEquals(0L, profile.active!!.stopElapsedMs)
        repeat(179) { at(5.0, 0.0) }
        assertEquals(1, profile.active!!.remainingSeconds)
        at(5.0, 0.0)
        assertEquals(DiveStopPhase.Complete, profile.active!!.phase)
        assertEquals(DiveStopAlert.Completed, profile.lastAlert)
    }

    @Test fun `unknown readings cannot silently clear a previously known ceiling`() {
        at(2.0, 0.0); at(20.0, 2.0); at(5.0, 2.0)
        profile = DiveProfileTracker.unavailable(profile)
        repeat(200) {
            now += 1000
            profile = DiveProfileTracker.sample(profile, 5.0, now, now)
        }
        assertNull(profile.requiredDecompressionCeilingMeters)
        assertTrue(profile.decompressionHold)
        assertEquals(0L, profile.active!!.stopElapsedMs)
        assertNotEquals(DiveStopPhase.Complete, profile.active!!.phase)
        assertEquals(1, profile.alertSequence)
    }

    @Test fun `OK cannot hide a computed ceiling behind a previously completed or dismissed safety stop`() {
        profile = DiveProfileState(active = DiveSession(0, DiveSettings(), stopStarted = true,
            phase = DiveStopPhase.Complete, stopElapsedMs = 180000), stopPresentation = DiveStopPresentation.Dismissed,
            decompression = DecompressionState(history = DecoHistory.Tracking,
                readings = DecoReadings(0, 2.0, 0.0, false, 0.0)))
        assertTrue(profile.stopExpanded)
        val state = AppState(diveProfile = profile)
        assertEquals(state, reduceDiveSettings(state, DiveSettingsCommand.AcknowledgeStop).state)
    }

    @Test fun `restarting with a loaded tissue checkpoint cannot clear its unresolved ceiling`() {
        val state = DiveProfileState(active = DiveSession(1000, DiveSettings(), maxDepthMeters = 30.0,
            phase = DiveStopPhase.Holding, stopStarted = true), decompression = DecompressionState(
            history = DecoHistory.Tracking, tissues = List(16) { TissuePressure(3.0, 0.0) },
            integratedEpochMs = 1000, depthMeters = 5.0, anchorMeters = 21.0))
        val restored = DiveProfileCodec.decode(DiveProfileCodec.encode(state))
        assertNull(restored.requiredDecompressionCeilingMeters)
        assertTrue(restored.decompressionHold)
        val resumed = DiveProfileTracker.sample(restored, 5.0, 2000, 2000)
        assertTrue(resumed.decompressionHold)
        assertEquals(0L, resumed.active!!.stopElapsedMs)
    }
}
