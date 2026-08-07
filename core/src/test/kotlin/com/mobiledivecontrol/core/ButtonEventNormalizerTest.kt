package com.mobiledivecontrol.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ButtonEventNormalizerTest {
    @Test
    fun `separate taps on the same button reset repeat count`() {
        val normalizer = ButtonEventNormalizer()
        val start = Instant.parse("2026-05-27T12:00:00Z")

        val first = assertNotNull(normalizer.accept(HousingButtonEvent.Right, start))
        val second = assertNotNull(normalizer.accept(HousingButtonEvent.Right, start.plusMillis(250)))

        assertEquals(0, first.repeatCount)
        assertEquals(0, second.repeatCount)
    }

    @Test
    fun `hold cadence on the same button increments repeat count`() {
        val normalizer = ButtonEventNormalizer()
        val start = Instant.parse("2026-05-27T12:00:00Z")

        val first = assertNotNull(normalizer.accept(HousingButtonEvent.Right, start))
        val second = assertNotNull(normalizer.accept(HousingButtonEvent.Right, start.plusMillis(60)))
        val third = assertNotNull(normalizer.accept(HousingButtonEvent.Right, start.plusMillis(120)))

        assertEquals(0, first.repeatCount)
        assertEquals(1, second.repeatCount)
        assertEquals(2, third.repeatCount)
    }

    @Test
    fun `duplicate packets inside duplicate window are filtered`() {
        val normalizer = ButtonEventNormalizer()
        val start = Instant.parse("2026-05-27T12:00:00Z")

        assertNotNull(normalizer.accept(HousingButtonEvent.Right, start))
        assertNull(normalizer.accept(HousingButtonEvent.Right, start.plusMillis(10)))
    }

    @Test
    fun `wheel detents are never dropped, whatever the spin rate`() {
        val normalizer = ButtonEventNormalizer()
        val start = Instant.parse("2026-05-27T12:00:00Z")

        // 60 detents/sec: every byte is a physical click of the wheel and every one must
        // land, with the repeat chain marking the continuous spin.
        val first = assertNotNull(normalizer.accept(HousingButtonEvent.ZoomIn, start))
        val second = assertNotNull(normalizer.accept(HousingButtonEvent.ZoomIn, start.plusMillis(16)))
        val third = assertNotNull(normalizer.accept(HousingButtonEvent.ZoomIn, start.plusMillis(32)))
        assertEquals(0, first.repeatCount)
        assertEquals(1, second.repeatCount)
        assertEquals(2, third.repeatCount)

        // Pushbuttons keep their bounce suppression at the same cadence.
        assertNotNull(normalizer.accept(HousingButtonEvent.Ok, start.plusMillis(200)))
        assertNull(normalizer.accept(HousingButtonEvent.Ok, start.plusMillis(210)))
    }
}
