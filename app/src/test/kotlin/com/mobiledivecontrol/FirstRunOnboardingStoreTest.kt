package com.mobiledivecontrol

import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.core.ParseResult
import com.mobiledivecontrol.core.ProtocolParser
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirstRunOnboardingStoreTest {
    @Test
    fun `fresh install requires onboarding and completion survives a new store instance`() {
        val directory = Files.createTempDirectory("dive-onboarding").toFile()
        try {
            val marker = File(directory, "completion-marker")
            val firstLaunch = FirstRunOnboardingStore(marker)
            assertFalse(firstLaunch.isComplete())

            firstLaunch.markComplete()

            assertTrue(FirstRunOnboardingStore(marker).isComplete())
            // Existing installs may have only the original completion marker.
            assertFalse(FirstRunOnboardingStore(marker).beginLaunch(permissionsGranted = true))
            assertTrue(FirstRunOnboardingStore(marker).beginLaunch(permissionsGranted = false))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reopening permission setup keeps the seal off the welcome until every permission is granted`() {
        val directory = Files.createTempDirectory("dive-first-launch").toFile()
        try {
            val marker = File(directory, "completion-marker")
            assertTrue(FirstRunOnboardingStore(marker).beginLaunch(permissionsGranted = false))
            val reopened = FirstRunOnboardingStore(marker)
            assertTrue(reopened.beginLaunch(permissionsGranted = false))
            assertFalse(reopened.beginLaunch(permissionsGranted = true))
            assertFalse(reopened.isComplete())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `seal display waits for the permission window and stays finished after focus changes`() {
        val waiting = FirstLaunchSealStep.WaitingForDialogs
        val visible = FirstLaunchSealStep.OnHousingAnimation
        val done = FirstLaunchSealStep.Done
        assertEquals(waiting, waiting.permissionWindowChanged(false))
        assertEquals(visible, waiting.permissionWindowChanged(true))
        assertEquals(waiting, visible.permissionWindowChanged(false))
        val power = visible.afterPrompt(BleConnectionState.Scanning)
        assertEquals(FirstLaunchSealStep.PowerOn, power.permissionWindowChanged(true))
        assertEquals(FirstLaunchSealStep.PowerOn, power.permissionWindowChanged(false))
        assertEquals(done, done.permissionWindowChanged(false).permissionWindowChanged(true))
    }

    @Test
    fun `only four directions and menu ok confirm insertion`() {
        val parser = ProtocolParser()
        fun acceptedBy(step: FirstLaunchSealStep) = (0..255).filter { wire ->
            val packet = parser.decodeButtonPacket(byteArrayOf(wire.toByte()))
            packet is ParseResult.Success && step.onButton(packet.value.event) != step
        }
        assertEquals(listOf(0x10, 0x30, 0x40, 0x50, 0x61), acceptedBy(FirstLaunchSealStep.OnHousingAnimation))
        assertEquals(listOf(0x20), acceptedBy(FirstLaunchSealStep.PowerOn))
        assertTrue(acceptedBy(FirstLaunchSealStep.Connecting).isEmpty())
        assertTrue(acceptedBy(FirstLaunchSealStep.Connected).isEmpty())
    }
}
