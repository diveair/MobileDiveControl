package com.mobiledivecontrol.ui.tutorial

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntroPermissionBackdropPolicyTest {
    @Test
    fun `native permission dialog gets bare housing artwork without app permission copy`() {
        assertFalse(shouldShowIntroMessage(IntroPhase.NeedsPermissions))
    }

    @Test
    fun `denial reveals recovery only after native dialog closes`() {
        assertFalse(
            shouldShowPermissionRecovery(
                IntroPhase.NeedsPermissions,
                permissionDialogVisible = true,
            ),
        )
        assertTrue(
            shouldShowPermissionRecovery(
                IntroPhase.NeedsPermissions,
                permissionDialogVisible = false,
            ),
        )
        assertFalse(
            shouldShowPermissionRecovery(
                IntroPhase.Connecting,
                permissionDialogVisible = false,
            ),
        )
    }

    @Test
    fun `permission recovery explicitly asks for a screen tap`() {
        assertEquals("ACCESS DENIED", permissionRecoveryTitle())
        assertEquals(
            "TAP ANYWHERE ON SCREEN TO CONTINUE",
            permissionRecoveryTapPrompt(),
        )
    }

    @Test
    fun `housing connection phases retain their instructional banners`() {
        assertTrue(shouldShowIntroMessage(IntroPhase.TurnOnHousing))
        assertTrue(shouldShowIntroMessage(IntroPhase.Connecting))
        assertTrue(shouldShowIntroMessage(IntroPhase.JustConnected))
        assertTrue(shouldShowIntroMessage(IntroPhase.LinkLost))
        assertFalse(shouldShowIntroMessage(IntroPhase.Carousel))
    }
}
