package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PendingStillCaptureTest {
    @Test fun `request waits for asynchronous bind and completes only once`() {
        val pending = PendingStillCapture()
        var captures = 0
        var errors = 0
        pending.begin(32, { captures++ }, { errors++ })
        assertEquals(0, captures)
        assertEquals(0, errors)
        pending.bound(32)
        pending.bound(32)
        pending.fail("late timeout")
        assertEquals(1, captures)
        assertEquals(0, errors)
    }

    @Test fun `wrong or missing output fails without running shutter`() {
        for (format in listOf(null, 256)) {
            val pending = PendingStillCapture()
            var captures = 0
            var errors = 0
            pending.begin(32, { captures++ }, { errors++ })
            pending.bound(format)
            pending.bound(32)
            assertEquals(0, captures)
            assertEquals(1, errors)
        }
    }

    @Test fun `timeout or detach prevents a late bind from capturing`() {
        val pending = PendingStillCapture()
        var captures = 0
        var errors = 0
        pending.begin(32, { captures++ }, { errors++ })
        pending.fail("timeout")
        pending.bound(32)
        assertEquals(0, captures)
        assertEquals(1, errors)
        pending.begin(32, { captures++ }, { errors++ })
        pending.clear()
        pending.bound(32)
        assertEquals(0, captures)
        assertEquals(1, errors)
    }

    @Test fun `completion clears ownership before reentrant capture`() {
        val pending = PendingStillCapture()
        var captures = 0
        pending.begin(32, {
            captures++
            pending.begin(256, { captures++ }, { error(it) })
        }, { error(it) })
        pending.bound(32)
        pending.bound(256)
        assertEquals(2, captures)
    }
}
