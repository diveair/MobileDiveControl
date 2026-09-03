package com.mobiledivecontrol

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
 * Activity-result launchers cannot reliably present a system dialog before the Activity reaches
 * STARTED. In particular, recording a request as attempted during onCreate can strand a clean
 * install behind the app UI without Android ever having shown the permission surface.
 */
internal fun canLaunchStartupPermissionDialog(
    step: StartupPermissionStep,
    lifecycleStarted: Boolean,
    requestInFlight: Boolean,
    onDemandRequestActive: Boolean,
): Boolean = step.gate == StartupPermissionGate.Request &&
    step.permissions.isNotEmpty() &&
    lifecycleStarted &&
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
