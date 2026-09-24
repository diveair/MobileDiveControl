package com.mobiledivecontrol

import android.Manifest
import android.os.Build

/** The actual setup catalog, shared by the Activity and permission-sequence regression tests. */
internal fun startupRuntimePermissionGroups(
    sdkInt: Int,
    bluetoothPermissions: List<String>,
): List<List<Pair<String, String>>> = buildList {
    add(bluetoothPermissions.map { it to "Nearby devices  —  to find your housing" })
    add(listOf(Manifest.permission.CAMERA to "Camera  —  for photos and video"))
    add(listOf(Manifest.permission.RECORD_AUDIO to "Microphone  —  for video audio"))
    add(listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION to "Location/GPS  —  for dive position and Sky Guide",
        Manifest.permission.ACCESS_FINE_LOCATION to "Precise GPS  —  for accurate dive position and Sky Guide",
    ))
    // Android 10+ allows access to media created by this app without a library-wide grant.
    if (sdkInt <= Build.VERSION_CODES.P) {
        add(listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE to "Media  —  to view saved photos and videos",
            Manifest.permission.WRITE_EXTERNAL_STORAGE to "Save media  —  to save photos and videos",
        ))
    }
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(listOf(Manifest.permission.POST_NOTIFICATIONS to "Notifications  —  for housing connection status"))
    }
}.map { group -> group.distinctBy { it.first } }

internal enum class StartupPermissionGate {
    Request,
    WaitForBluetoothGrant,
    WaitForPopupControl,
    Complete,
}

internal data class StartupPermissionStep(
    val gate: StartupPermissionGate,
    val permissions: List<String> = emptyList(),
)

internal enum class RuntimePermissionRecoveryAction {
    None,
    RequestDialog,
}

/**
 * Chooses the only legal recovery surface for a permission the user wants to restore.
 *
 * Startup recovery never navigates away from DiveControl. Android may suppress the native dialog
 * after repeated denials; in that case the launcher returns immediately and the visible recovery
 * panel remains in place for another deliberate tap.
 */
internal fun runtimePermissionRecoveryAction(
    missingPermissions: Set<String>,
): RuntimePermissionRecoveryAction = when {
    missingPermissions.isEmpty() -> RuntimePermissionRecoveryAction.None
    else -> RuntimePermissionRecoveryAction.RequestDialog
}

/**
 * Chooses exactly one Android permission surface at a time.
 *
 * Nearby Devices is the bootstrap grant. Once it is granted, the remaining Android permission
 * groups are visited in their declared order without waiting for a housing connection. Popup
 * control is explained only during the first-run walkthrough. The explanation is satisfied inside
 * DiveControl and never navigates to Android's Accessibility Settings page.
 */
internal fun nextStartupPermissionStep(
    permissionGroups: List<List<String>>,
    bluetoothPermissions: Set<String>,
    grantedPermissions: Set<String>,
    attemptedPermissions: Set<String>,
    popupControlRequired: Boolean,
    popupControlSatisfied: Boolean,
): StartupPermissionStep {
    val missingBluetooth = bluetoothPermissions.filterNot(grantedPermissions::contains)
    if (missingBluetooth.isNotEmpty()) {
        val requestable = missingBluetooth.filterNot(attemptedPermissions::contains)
        return if (requestable.isNotEmpty()) {
            StartupPermissionStep(StartupPermissionGate.Request, requestable)
        } else {
            StartupPermissionStep(StartupPermissionGate.WaitForBluetoothGrant)
        }
    }

    permissionGroups.forEach { group ->
        val requestable = group
            .filterNot(grantedPermissions::contains)
            .filterNot(attemptedPermissions::contains)
        if (requestable.isNotEmpty()) {
            return StartupPermissionStep(StartupPermissionGate.Request, requestable)
        }
    }
    if (popupControlRequired && !popupControlSatisfied) {
        return StartupPermissionStep(StartupPermissionGate.WaitForPopupControl)
    }
    return StartupPermissionStep(StartupPermissionGate.Complete)
}

/**
 * A subsequent dialog must wait for the previous system window to close and for this Activity
 * to regain focus. An early launch can strand setup behind an invisible permission surface.
 */
internal fun canLaunchStartupPermissionDialog(
    step: StartupPermissionStep,
    lifecycleResumed: Boolean,
    windowFocused: Boolean,
    requestInFlight: Boolean,
    onDemandRequestActive: Boolean,
): Boolean = step.gate == StartupPermissionGate.Request &&
    step.permissions.isNotEmpty() &&
    lifecycleResumed &&
    windowFocused &&
    !requestInFlight &&
    !onDemandRequestActive

/** Feature permissions must not depend on the optional housing accessibility service. */
internal fun canLaunchFeaturePermissionDialog(
    lifecycleStarted: Boolean,
    startupSequenceActive: Boolean,
    bluetoothOnly: Boolean,
): Boolean = lifecycleStarted && (!startupSequenceActive || bluetoothOnly)

/**
 * User-facing labels for Android runtime grants that are still absent.
 *
 * Accessibility popup control is deliberately not part of this list. It is a one-time system
 * setup surface, not a runtime permission, and including it here makes the already-visible intro
 * believe a native permission dialog is still active and swallow every housing button press.
 */
internal fun missingRuntimePermissionLabels(
    requiredPermissions: List<Pair<String, String>>,
    grantedPermissions: Set<String>,
): List<String> = requiredPermissions
    .filterNot { (permission, _) -> permission in grantedPermissions }
    .map { (_, label) -> label }
    .distinct()
