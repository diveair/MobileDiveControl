package com.mobiledivecontrol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartupPermissionSequenceTest {
    @Test
    fun `feature permission can open without housing popup control after startup`() {
        assertEquals(true, canLaunchFeaturePermissionDialog(true, false, false))
        assertEquals(false, canLaunchFeaturePermissionDialog(false, false, false))
        assertEquals(false, canLaunchFeaturePermissionDialog(true, true, false))
        assertEquals(true, canLaunchFeaturePermissionDialog(true, true, true))
    }

    private val bluetooth = listOf("bluetooth.scan", "bluetooth.connect")
    private val groups = listOf(
        bluetooth,
        listOf("camera"),
        listOf("location.coarse", "location.fine"),
        listOf("microphone"),
        listOf("notifications"),
    )

    @Test
    fun `nearby devices is the only first permission request`() {
        val step = nextStartupPermissionStep(
            permissionGroups = groups,
            bluetoothPermissions = bluetooth.toSet(),
            grantedPermissions = emptySet(),
            attemptedPermissions = emptySet(),
            popupControlRequired = true,
            popupControlSatisfied = true,
        )

        assertEquals(StartupPermissionGate.Request, step.gate)
        assertEquals(bluetooth, step.permissions)
    }

    @Test
    fun `later runtime permissions do not wait for housing or popup control`() {
        val bluetoothGranted = bluetooth.toSet()

        assertEquals(
            listOf("camera"),
            nextStartupPermissionStep(
                groups,
                bluetooth.toSet(),
                bluetoothGranted,
                emptySet(),
                popupControlRequired = true,
                popupControlSatisfied = false,
            ).permissions,
        )
    }

    @Test
    fun `popup control explanation is shown only after runtime permissions finish`() {
        val allRuntimePermissions = groups.flatten().toSet()
        val step = nextStartupPermissionStep(
            groups,
            bluetooth.toSet(),
            allRuntimePermissions,
            emptySet(),
            popupControlRequired = true,
            popupControlSatisfied = false,
        )

        assertEquals(StartupPermissionGate.WaitForPopupControl, step.gate)
    }

    @Test
    fun `acknowledging the app owned popup explanation completes startup`() {
        val allRuntimePermissions = groups.flatten().toSet()
        val step = nextStartupPermissionStep(
            groups,
            bluetooth.toSet(),
            allRuntimePermissions,
            emptySet(),
            popupControlRequired = true,
            popupControlSatisfied = true,
        )

        assertEquals(StartupPermissionGate.Complete, step.gate)
    }

    @Test
    fun `completed onboarding never reopens popup control on later launches`() {
        val allRuntimePermissions = groups.flatten().toSet()
        val step = nextStartupPermissionStep(
            groups,
            bluetooth.toSet(),
            allRuntimePermissions,
            emptySet(),
            popupControlRequired = false,
            popupControlSatisfied = false,
        )

        assertEquals(StartupPermissionGate.Complete, step.gate)
    }

    @Test
    fun `remaining permission groups are requested one surface at a time`() {
        val camera = nextStartupPermissionStep(
            groups,
            bluetooth.toSet(),
            bluetooth.toSet(),
            emptySet(),
            popupControlRequired = true,
            popupControlSatisfied = true,
        )
        assertEquals(listOf("camera"), camera.permissions)

        val location = nextStartupPermissionStep(
            groups,
            bluetooth.toSet(),
            bluetooth.toSet() + "camera",
            emptySet(),
            popupControlRequired = true,
            popupControlSatisfied = true,
        )
        assertEquals(listOf("location.coarse", "location.fine"), location.permissions)

        val notifications = nextStartupPermissionStep(
            groups,
            bluetooth.toSet(),
            bluetooth.toSet() + "camera" + "location.coarse" + "location.fine",
            setOf("microphone"),
            popupControlRequired = true,
            popupControlSatisfied = true,
        )
        assertEquals(listOf("notifications"), notifications.permissions)
    }

    @Test
    fun `a rejected bluetooth bootstrap blocks all later dialogs`() {
        val step = nextStartupPermissionStep(
            groups,
            bluetooth.toSet(),
            emptySet(),
            bluetooth.toSet(),
            popupControlRequired = true,
            popupControlSatisfied = true,
        )

        assertEquals(StartupPermissionGate.WaitForBluetoothGrant, step.gate)
        assertEquals(emptyList<String>(), step.permissions)
    }

    @Test
    fun `permission dialog launch waits until activity is started`() {
        val request = StartupPermissionStep(
            StartupPermissionGate.Request,
            listOf("bluetooth.scan", "bluetooth.connect"),
        )

        assertEquals(
            false,
            canLaunchStartupPermissionDialog(
                request,
                lifecycleStarted = false,
                requestInFlight = false,
                onDemandRequestActive = false,
            ),
        )
        assertEquals(
            true,
            canLaunchStartupPermissionDialog(
                request,
                lifecycleStarted = true,
                requestInFlight = false,
                onDemandRequestActive = false,
            ),
        )
    }

    @Test
    fun `intro permission labels become empty once every runtime grant is present`() {
        val required = listOf(
            "bluetooth.scan" to "Nearby devices",
            "camera" to "Camera",
            "microphone" to "Microphone",
        )

        assertEquals(
            emptyList<String>(),
            missingRuntimePermissionLabels(
                requiredPermissions = required,
                grantedPermissions = required.mapTo(mutableSetOf()) { it.first },
            ),
        )
    }

    @Test
    fun `intro permission labels contain only missing runtime grants`() {
        assertEquals(
            listOf("Camera", "Microphone"),
            missingRuntimePermissionLabels(
                requiredPermissions = listOf(
                    "bluetooth.scan" to "Nearby devices",
                    "camera" to "Camera",
                    "microphone" to "Microphone",
                ),
                grantedPermissions = setOf("bluetooth.scan"),
            ),
        )
    }

    @Test
    fun `first request and ordinary denial retry use native permission dialog`() {
        assertEquals(
            RuntimePermissionRecoveryAction.RequestDialog,
            runtimePermissionRecoveryAction(
                missingPermissions = setOf("bluetooth.scan", "bluetooth.connect"),
            ),
        )
        assertEquals(
            RuntimePermissionRecoveryAction.RequestDialog,
            runtimePermissionRecoveryAction(
                missingPermissions = setOf("camera"),
            ),
        )
    }

    @Test
    fun `permanently denied permission remains in app retry loop`() {
        assertEquals(
            RuntimePermissionRecoveryAction.RequestDialog,
            runtimePermissionRecoveryAction(
                missingPermissions = setOf("bluetooth.scan", "bluetooth.connect"),
            ),
        )
    }

    @Test
    fun `no missing permission needs no recovery surface`() {
        assertEquals(
            RuntimePermissionRecoveryAction.None,
            runtimePermissionRecoveryAction(
                missingPermissions = emptySet(),
            ),
        )
    }
}
