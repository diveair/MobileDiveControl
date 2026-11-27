package com.mobiledivecontrol.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.FirstLaunchSealStep
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.vacuumMenuAvailable
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.core.HousingSealTransitions.Banner
import com.mobiledivecontrol.core.DiagnosticsCommand
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.camera.CameraShellScreen
import com.mobiledivecontrol.ui.camera.PointingGesture
import com.mobiledivecontrol.platform.CompassReading
import kotlinx.coroutines.flow.StateFlow
import com.mobiledivecontrol.ui.diagnostics.DiagnosticsScreen
import com.mobiledivecontrol.ui.safety.SafetyScreen
import com.mobiledivecontrol.ui.tutorial.IntroCarouselScreen
import com.mobiledivecontrol.ui.tutorial.SealCapPromptScreen
import com.mobiledivecontrol.ui.welcome.WelcomeSplashScreen

/**
 * Root composable — routes to the active mode's screen
 * and wraps everything in the persistent HUD overlay.
 *
 * @param introVisible when true the intro carousel covers the whole screen. It is drawn over the
 *   HUD rather than instead of it so the camera and its state survive underneath: the intro runs
 *   until the diver presses something, and tearing the preview down for it would mean a cold camera
 *   start at the exact moment they want to shoot.
 * @param permissionsGranted everything the housing link and camera need. Android still owns every
 *   permission prompt, but the opaque tutorial layer remains composed behind those translucent
 *   system windows so a fresh install never exposes the camera controls before onboarding.
 */
@Composable
fun DiveControlScreen(
    state: AppState,
    cameraPermissionGranted: Boolean = false,
    locationPrerequisitesReady: Boolean = false,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
    useMetric: Boolean = true,
    effects: List<PlatformEffect> = emptyList(),
    onEffectsConsumed: () -> Unit = {},
    onDetectedLenses: ((List<String>) -> Unit)? = null,
    onCapabilities: ((com.mobiledivecontrol.core.CameraCapabilities) -> Unit)? = null,
    onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null,
    onPointingGesture: ((PointingGesture) -> Unit)? = null,
    compassReadings: StateFlow<CompassReading>,
    onCompassMonitoring: (Boolean) -> Unit,
    targetHeading: Double? = null,
    onCameraCommand: (com.mobiledivecontrol.core.CameraCommand) -> Unit = {},
    onGalleryCommand: (com.mobiledivecontrol.core.GalleryCommand) -> Unit = {},
    onDiagnosticsCommand: (DiagnosticsCommand) -> Unit = {},
    onDiveSettingsCommand: (com.mobiledivecontrol.core.DiveSettingsCommand) -> Unit = {},
    onSafetyCommand: (com.mobiledivecontrol.core.SafetyCommand) -> Unit = {},
    introVisible: Boolean = false,
    onIntroDismiss: () -> Unit = {},
    permissionsGranted: Boolean = false,
    missingPermissions: List<String> = emptyList(),
    permissionDialogVisible: Boolean = false,
    onPermissionsSetup: () -> Unit = {},
    capPromptVisible: Boolean = false,
    onCapPromptDismiss: () -> Unit = {},
    welcomeVisible: Boolean = false,
    showSealOnWelcome: Boolean = true,
    firstLaunchSealStep: FirstLaunchSealStep = FirstLaunchSealStep.Done,
    onIntroPromptFinished: (FirstLaunchSealStep) -> Unit = {},
    onWelcomeFinished: () -> Unit = {},
    bluetoothEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        DiveControlContent(
            state = state,
            cameraPermissionGranted = cameraPermissionGranted,
            locationPrerequisitesReady = locationPrerequisitesReady,
            lifecycleOwner = lifecycleOwner,
            useMetric = useMetric,
            effects = effects,
            onEffectsConsumed = onEffectsConsumed,
            onDetectedLenses = onDetectedLenses,
            onCapabilities = onCapabilities,
            onMeteredExposure = onMeteredExposure,
            onPointingGesture = onPointingGesture,
            compassReadings = compassReadings,
            compassCovered = welcomeVisible || introVisible || capPromptVisible,
            onCompassMonitoring = onCompassMonitoring,
            targetHeading = targetHeading,
            onCameraCommand = onCameraCommand,
            onGalleryCommand = onGalleryCommand,
            onDiagnosticsCommand = onDiagnosticsCommand,
            onDiveSettingsCommand = onDiveSettingsCommand,
            onSafetyCommand = onSafetyCommand,
            bluetoothEnabled = bluetoothEnabled,
        )

        // Android owns the permission dialog itself. Keep the opaque housing artwork underneath it
        // from the first frame; otherwise the system dialog's dimmed transparency exposes a live
        // camera mode that the user cannot operate yet.
        if (shouldShowIntroLayer(introVisible)) {
            IntroCarouselScreen(
                firstLaunchSealStep = firstLaunchSealStep,
                onIntroPromptFinished = onIntroPromptFinished,
                permissionsGranted = permissionsGranted,
                missingPermissions = missingPermissions,
                permissionDialogVisible = permissionDialogVisible,
                bleState = state.bleConnectionState,
                onDismiss = onIntroDismiss,
                onPermissionsSetup = onPermissionsSetup,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (capPromptVisible) {
            // The sealing hand-off follows the intro and never competes with it. The view model
            // drops it on its own when the housing reports the cap off or a vacuum present.
            SealCapPromptScreen(
                onDismiss = onCapPromptDismiss,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Welcome card, shown first on every launch, above everything. It fades out onto what is
        // already composed underneath: the intro's housing artwork on a first launch (ready for the
        // permission prompts that follow), the live camera otherwise.
        AnimatedVisibility(
            visible = welcomeVisible,
            enter = EnterTransition.None,
            exit = fadeOut(tween(WELCOME_FADE_OUT_MS)),
        ) {
            WelcomeSplashScreen(
                showSealInstruction = showSealOnWelcome,
                onFinished = onWelcomeFinished,
                modifier = Modifier.fillMaxSize(),
            )
        }

    }
}

internal fun shouldShowIntroLayer(introVisible: Boolean): Boolean = introVisible

private const val WELCOME_FADE_OUT_MS = 300

/** Camera adjustment is an overlay state, not a different screen or preview lifetime. */
internal fun animatedContentMode(mode: AppMode): AppMode = when (mode) {
    AppMode.CameraLive, AppMode.CameraAdjust -> AppMode.CameraLive
    else -> mode
}

@Composable
private fun DiveControlContent(
    state: AppState,
    cameraPermissionGranted: Boolean,
    locationPrerequisitesReady: Boolean,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner?,
    useMetric: Boolean,
    effects: List<PlatformEffect>,
    onEffectsConsumed: () -> Unit,
    onDetectedLenses: ((List<String>) -> Unit)?,
    onCapabilities: ((com.mobiledivecontrol.core.CameraCapabilities) -> Unit)?,
    onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null,
    onPointingGesture: ((PointingGesture) -> Unit)? = null,
    compassReadings: StateFlow<CompassReading>,
    compassCovered: Boolean,
    onCompassMonitoring: (Boolean) -> Unit,
    targetHeading: Double? = null,
    onCameraCommand: (com.mobiledivecontrol.core.CameraCommand) -> Unit,
    onGalleryCommand: (com.mobiledivecontrol.core.GalleryCommand) -> Unit,
    onDiagnosticsCommand: (DiagnosticsCommand) -> Unit,
    onDiveSettingsCommand: (com.mobiledivecontrol.core.DiveSettingsCommand) -> Unit,
    onSafetyCommand: (com.mobiledivecontrol.core.SafetyCommand) -> Unit,
    bluetoothEnabled: Boolean = true,
) {
    val liveWaterPressure = com.mobiledivecontrol.ui.components.rememberLivePressure(
        state.safety.waterPressureKpa, state.waterPressureTelemetry, state.housing.connected,
    )
    // Show test alerts over the real camera without changing the active mode or real dive/safety state.
    val displayMode = if (state.mode == AppMode.DiveSettings && state.airAlerts.simulation != null)
        AppMode.CameraLive else state.mode
    CameraHudOverlay(
        state = state.copy(mode = displayMode),
        lifecycleOwner = lifecycleOwner,
        liveWaterPressureKpa = liveWaterPressure,
        useMetric = useMetric,
        bluetoothEnabled = bluetoothEnabled,
        compassReadings = compassReadings,
        compassCovered = compassCovered,
        onCompassMonitoring = onCompassMonitoring,
        targetHeading = targetHeading,
        hudVisible = displayMode != AppMode.Diagnostics && displayMode != AppMode.DiveSettings,
        onSafetyCommand = onSafetyCommand,
        onDiveSettingsCommand = onDiveSettingsCommand.takeUnless { compassCovered },
    ) {
        AnimatedContent(
            // CameraLive <-> CameraAdjust used to create two CameraShellScreen instances during
            // mode selection. The outgoing shell detached CameraX while the incoming shell built
            // a new controller, so even the last-frame hold was disposed and the crossfade showed
            // black. Both states are the same visual/camera destination; keep one identity.
            targetState = animatedContentMode(displayMode),
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            label = "mode_transition",
        ) { mode ->
            when (mode) {
                AppMode.CameraLive, AppMode.CameraAdjust -> CameraShellScreen(
                    safetyStopActive = state.diveProfile.stopActive,
                    vacuumMenuAvailable = state.vacuumMenuAvailable,
                    cameraState = state.camera,
                    safetyState = state.safety.copy(waterPressureKpa = liveWaterPressure),
                    cameraPermissionGranted = cameraPermissionGranted,
                    locationPrerequisitesReady = locationPrerequisitesReady,
                    lifecycleOwner = lifecycleOwner,
                    effects = effects,
                    onEffectsConsumed = onEffectsConsumed,
                    onDetectedLenses = onDetectedLenses,
                    onCapabilities = onCapabilities,
                    onMeteredExposure = onMeteredExposure,
                    onPointingGesture = onPointingGesture,
                    onCameraCommand = onCameraCommand,
                    compassReadings = compassReadings,
                    warningMessage = cameraFailureBannerMessage(state.lastWarning, state.safety.sealMonitor.banner, state.safety.warning),
                )
                AppMode.Safety -> SafetyScreen(safety = state.safety)
                AppMode.Diagnostics -> DiagnosticsScreen(
                    state = state,
                    onCommand = onDiagnosticsCommand,
                )
                AppMode.PhoneCursor, AppMode.PhoneTarget -> PhoneControlPlaceholder(mode = mode)
                AppMode.DiveSettings -> if (state.airAlerts.menuOpen) {
                    com.mobiledivecontrol.ui.dive.DiveAlertsScreen(state, onDiveSettingsCommand)
                } else com.mobiledivecontrol.ui.dive.DiveSettingsScreen(
                    profile = state.diveProfile,
                    useMetric = useMetric,
                    onCommand = onDiveSettingsCommand,
                )
                AppMode.Gallery -> com.mobiledivecontrol.ui.gallery.GalleryScreen(
                    galleryState = state.gallery,
                    onCommand = onGalleryCommand,
                )
            }
        }
    }
}

/**
 * The camera's amber banner is reserved for failures the camera controls can resolve. Connection,
 * permission, and seal states already have dedicated UI; repeating them here creates the exact
 * stacked "HOUSING DISCONNECTED" / "HOUSING NOT CONNECTED" duplication the user cannot dismiss.
 */
internal fun cameraFailureBannerMessage(message: String?, sealBanner: Banner = Banner.B00, sealWarning: String? = null): String? {
    if (sealBanner == Banner.ConfirmRelease || sealBanner == Banner.Release) return null
    val value = message?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (sealBanner == Banner.B17 && value == sealWarning) return null
    val duplicateStatus = value.startsWith("Housing ", ignoreCase = true) ||
        value.startsWith("Bluetooth ", ignoreCase = true) ||
        value.startsWith("GATT discovery:", ignoreCase = true) ||
        (value.startsWith("Pressure unstable after ") && value.endsWith("s: leak detected.")) ||
        (value.startsWith("Pressure back to ambient after ") && value.endsWith("s: cover open or seal lost.")) ||
        value.contains(" Permission:", ignoreCase = true) ||
        value.startsWith("Verified vacuum held across restart", ignoreCase = true)
    return value.takeUnless { duplicateStatus }
}

/**
 * Placeholder for Phone Control modes.
 */
@Composable
private fun PhoneControlPlaceholder(mode: AppMode) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = DiveColors.SurfaceBorder,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (mode == AppMode.PhoneCursor) "Cursor Control" else "Target Control",
                color = DiveColors.TextSecondary,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Available on land • Requires Accessibility Service",
                color = DiveColors.TextMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
