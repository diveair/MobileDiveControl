package com.mobiledivecontrol.ui

import com.mobiledivecontrol.core.AppMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiveControlScreenPolicyTest {
    @Test
    fun `camera live and adjustment share one animated content identity`() {
        assertEquals(AppMode.CameraLive, animatedContentMode(AppMode.CameraLive))
        assertEquals(AppMode.CameraLive, animatedContentMode(AppMode.CameraAdjust))
        assertEquals(AppMode.Gallery, animatedContentMode(AppMode.Gallery))
    }
    @Test
    fun `first run housing layer covers camera behind native permission dialogs`() {
        assertTrue(shouldShowIntroLayer(introVisible = true))
    }

    @Test
    fun `completed tutorial removes the first run layer`() {
        assertFalse(shouldShowIntroLayer(introVisible = false))
    }
}
