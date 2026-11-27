package com.mobiledivecontrol.ui.tutorial

import com.mobiledivecontrol.FirstLaunchSealStep
import com.mobiledivecontrol.core.BleConnectionState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntroPermissionBackdropPolicyTest {
    @Test
    fun `first launch finishes dialogs then shows seal on artwork before any housing link banner`() {
        for (link in listOf(BleConnectionState.Ready, BleConnectionState.Connecting, BleConnectionState.Scanning)) {
            fun phase(step: FirstLaunchSealStep) = introPhase(
                step, permissionsGranted = true, bleState = link, carouselStarted = false, everConnected = false,
            )
            assertFalse(shouldShowIntroMessage(phase(FirstLaunchSealStep.WaitingForDialogs)))
            val seal = phase(FirstLaunchSealStep.OnHousingAnimation)
            assertEquals(IntroPhase.InsertAndSeal, seal)
            assertTrue(shouldShowIntroMessage(seal))
            val powerOn = FirstLaunchSealStep.OnHousingAnimation.afterPrompt(link)
            assertEquals(IntroPhase.TurnOnHousing, phase(powerOn))
            assertEquals(powerOn, powerOn.afterPrompt(link))
        }
    }

    @Test
    fun `power waits through empty scans and failed attempts until the live housing becomes ready`() {
        val power = FirstLaunchSealStep.PowerOn
        assertEquals(power, power.linkChanged(BleConnectionState.Scanning).linkChanged(BleConnectionState.Reconnecting))
        val connecting = power.linkChanged(BleConnectionState.Connecting)
        fun phase(step: FirstLaunchSealStep, link: BleConnectionState, carousel: Boolean = false) =
            introPhase(step, true, link, carousel, everConnected = false)
        assertEquals(IntroPhase.Connecting, phase(connecting, BleConnectionState.Connecting))
        assertEquals(connecting, connecting.afterPrompt(BleConnectionState.Subscribing))
        assertEquals(power, connecting.linkChanged(BleConnectionState.Failed))
        assertEquals(IntroPhase.Connecting, phase(connecting.linkChanged(BleConnectionState.Ready), BleConnectionState.Ready))
        val connected = connecting.afterPrompt(BleConnectionState.Ready)
        assertEquals(IntroPhase.JustConnected, phase(connected, BleConnectionState.Ready))
        assertEquals(power, connected.linkChanged(BleConnectionState.Reconnecting))
        assertEquals(IntroPhase.Carousel, phase(connected.afterPrompt(BleConnectionState.Ready), BleConnectionState.Ready, true))
    }

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

    @Test
    fun `turn on housing banner uses the single combined instruction`() {
        assertEquals("HOLD SHUTTER TO POWER ON & CONNECT BLUETOOTH", TURN_ON_HOUSING_PROMPT)
    }
}
