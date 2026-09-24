package com.mobiledivecontrol

import android.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `permission dialog launch waits until activity is resumed and focused`() {
        val request = StartupPermissionStep(
            StartupPermissionGate.Request,
            listOf("bluetooth.scan", "bluetooth.connect"),
        )

        assertEquals(
            false,
            canLaunchStartupPermissionDialog(
                request,
                lifecycleResumed = false,
                windowFocused = true,
                requestInFlight = false,
                onDemandRequestActive = false,
            ),
        )
        assertEquals(
            true,
            canLaunchStartupPermissionDialog(
                request,
                lifecycleResumed = true,
                windowFocused = true,
                requestInFlight = false,
                onDemandRequestActive = false,
            ),
        )
        assertFalse(canLaunchStartupPermissionDialog(request, true, false, false, false))
        assertFalse(canLaunchStartupPermissionDialog(request, true, true, true, false))
        assertFalse(canLaunchStartupPermissionDialog(request, true, true, false, true))
    }

    private val nativeBluetooth = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    @Test
    fun `production setup requests microphone before video can first be used`() {
        val nativeGroups = startupRuntimePermissionGroups(35, nativeBluetooth).map { group -> group.map { it.first } }
        val expected = listOf(nativeBluetooth, listOf(Manifest.permission.CAMERA),
            listOf(Manifest.permission.RECORD_AUDIO),
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            listOf(Manifest.permission.POST_NOTIFICATIONS))
        val granted = mutableSetOf<String>()
        expected.forEach { permissions ->
            val step = nextStartupPermissionStep(nativeGroups, nativeBluetooth.toSet(), granted,
                emptySet(), false, true)
            assertEquals(StartupPermissionGate.Request, step.gate)
            assertEquals(permissions, step.permissions)
            granted += step.permissions
        }
        assertEquals(StartupPermissionGate.Complete, nextStartupPermissionStep(nativeGroups,
            nativeBluetooth.toSet(), granted, emptySet(), false, true).gate)
    }

    @Test
    fun `existing installation missing only microphone is asked at startup`() {
        val catalog = startupRuntimePermissionGroups(35, nativeBluetooth)
        val granted = catalog.flatten().map { it.first }.toSet() - Manifest.permission.RECORD_AUDIO
        val step = nextStartupPermissionStep(catalog.map { it.map { pair -> pair.first } },
            nativeBluetooth.toSet(), granted, emptySet(), false, true)
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), step.permissions)
        assertEquals(listOf("Microphone  —  for video audio"), missingRuntimePermissionLabels(catalog.flatten(), granted))
    }

    @Test
    fun `denied microphone remains outstanding after other setup dialogs finish`() {
        val catalog = startupRuntimePermissionGroups(35, nativeBluetooth)
        val groups = catalog.map { it.map { pair -> pair.first } }
        val granted = groups.flatten().toSet() - Manifest.permission.RECORD_AUDIO
        val attempted = setOf(Manifest.permission.RECORD_AUDIO)
        assertEquals(StartupPermissionGate.Complete,
            nextStartupPermissionStep(groups, nativeBluetooth.toSet(), granted, attempted, false, true).gate)
        // Finishing the request sequence does not mark permission setup as granted.
        assertTrue(missingRuntimePermissionLabels(catalog.flatten(), granted).single().startsWith("Microphone"))
        assertEquals(RuntimePermissionRecoveryAction.RequestDialog,
            runtimePermissionRecoveryAction(groups.flatten().toSet() - granted))
    }

    @Test
    fun `storage and notification requests follow the supported Android version`() {
        for (sdk in 26..36) {
            val permissions = startupRuntimePermissionGroups(sdk, nativeBluetooth).flatten().map { it.first }
            assertTrue(Manifest.permission.CAMERA in permissions)
            assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
            assertEquals(sdk <= 28, Manifest.permission.READ_EXTERNAL_STORAGE in permissions, "read on API $sdk")
            assertEquals(sdk <= 28, Manifest.permission.WRITE_EXTERNAL_STORAGE in permissions, "write on API $sdk")
            assertEquals(sdk >= 33, Manifest.permission.POST_NOTIFICATIONS in permissions, "notifications on API $sdk")
            assertFalse(Manifest.permission.READ_MEDIA_IMAGES in permissions)
            assertFalse(Manifest.permission.READ_MEDIA_VIDEO in permissions)
        }
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
