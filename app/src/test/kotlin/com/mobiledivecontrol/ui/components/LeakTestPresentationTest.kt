package com.mobiledivecontrol.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeakTestPresentationTest {
    @Test fun holdIntroductionAndChipUseTheExistingCountdownClock() {
        val start = 10_000L
        assertEquals(0, leakTestPhase(null, start))
        assertEquals(0, leakTestPhase(start, start + 4_499))
        assertEquals(1, leakTestPhase(start, start + 4_500))
        assertEquals(1, leakTestPhase(start, start + 179_999))
        assertEquals(2, leakTestPhase(start, start + 180_000))
        assertEquals(2, leakTestPhase(start, start + 300_000)) // A verdict still requires verified pressure.
    }
}
