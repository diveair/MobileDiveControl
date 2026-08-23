package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutofocusHoldPolicyTest {
    @Test
    fun `hand tremor does not release held focus`() {
        assertFalse(AutofocusHoldPolicy.isIntentionalCameraMotion(0.03f, 0.04f, 0.02f))
    }

    @Test
    fun `deliberate reframe releases held focus`() {
        assertTrue(AutofocusHoldPolicy.isIntentionalCameraMotion(0.20f, 0.02f, 0.01f))
    }

    @Test
    fun `slow deliberate reframe releases held focus`() {
        assertTrue(AutofocusHoldPolicy.isIntentionalCameraMotion(0.11f, 0.01f, 0.01f))
    }

    @Test
    fun `minor contrast variation does not restart autofocus`() {
        assertFalse(AutofocusHoldPolicy.isSustainedFocusLossSample(20.0, 17.0))
    }

    @Test
    fun `large sharpness loss can restart autofocus`() {
        assertTrue(AutofocusHoldPolicy.isSustainedFocusLossSample(20.0, 10.0))
    }

    @Test
    fun `stationary subject change detection budget stays below three hundred milliseconds`() {
        assertEquals(280L, AutofocusHoldPolicy.STATIC_REFOCUS_DETECTION_BUDGET_MS)
        assertTrue(AutofocusHoldPolicy.STATIC_REFOCUS_DETECTION_BUDGET_MS < 300L)
    }
}
