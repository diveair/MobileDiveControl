package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RepeatingSurfaceTrackerTest {
    @Test fun `encoder start stop and resume update targets within the same session`() {
        val tracker = RepeatingSurfaceTracker<Any, String>()
        val session = Any()
        val preview = listOf("preview", "analysis")
        val recording = preview + "encoder"
        assertEquals(preview, tracker.changedTargets(session, 1, Any()) { preview })
        assertEquals(recording, tracker.changedTargets(session, 2, Any()) { recording })
        assertEquals(preview, tracker.changedTargets(session, 3, Any()) { preview })
        assertNull(tracker.changedTargets(session, 4, Any()) { emptyList() })
        assertNull(tracker.changedTargets(session, 2, Any()) { error("late frame") })
        assertEquals(preview + "newEncoder", tracker.changedTargets(session, 5, Any()) { preview + "newEncoder" })
    }

    @Test fun `unchanged routes and still requests do not steal repeating ownership`() {
        val tracker = RepeatingSurfaceTracker<Any, String>()
        val session = Any()
        val request = Any()
        val preview = listOf("preview")
        assertEquals(preview, tracker.changedTargets(session, 1, request) { preview })
        assertNull(tracker.changedTargets(session, 2, Any()) { emptyList() })
        assertNull(tracker.changedTargets(session, 4, request) { error("unchanged request targets read again") })
        assertNull(tracker.changedTargets(session, 3, Any()) { error("late different request read") })
        assertNull(tracker.changedTargets(session, 5, Any()) { preview })
        assertEquals(preview, tracker.changedTargets(Any(), 0, request) { preview })
    }
}
