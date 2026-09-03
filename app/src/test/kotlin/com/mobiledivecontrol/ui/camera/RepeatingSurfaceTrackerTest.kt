package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepeatingSurfaceTrackerTest {
    @Test fun `encoder start stop and resume update targets within the same session`() {
        val tracker = RepeatingSurfaceTracker<Any, String>()
        val session = Any()
        assertTrue(tracker.update(session, 1, true, listOf("preview", "analysis")))
        assertTrue(tracker.update(session, 2, true, listOf("preview", "analysis", "encoder")))
        assertTrue(tracker.update(session, 3, true, listOf("preview", "analysis")))
        assertFalse(tracker.update(session, 4, false, listOf("preview", "analysis", "encoder")))
        assertFalse(tracker.update(session, 2, true, listOf("preview", "analysis", "encoder")))
        assertTrue(tracker.update(session, 5, true, listOf("preview", "analysis", "newEncoder")))
    }

    @Test fun `unchanged routes and still requests do not steal repeating ownership`() {
        val tracker = RepeatingSurfaceTracker<Any, String>()
        val session = Any()
        assertTrue(tracker.update(session, 1, true, listOf("preview")))
        assertFalse(tracker.update(session, 2, false, listOf("jpeg")))
        assertFalse(tracker.update(session, 3, true, listOf("preview")))
        assertTrue(tracker.update(Any(), 0, true, listOf("preview")))
    }
}
