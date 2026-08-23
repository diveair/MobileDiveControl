package com.mobiledivecontrol.ui

import androidx.compose.animation.AnimatedContent
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
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.camera.CameraShellScreen
import com.mobiledivecontrol.ui.diagnostics.DiagnosticsScreen
import com.mobiledivecontrol.ui.safety.SafetyScreen
import com.mobiledivecontrol.ui.tutorial.IntroCarouselScreen
import com.mobiledivecontrol.ui.tutorial.SealCapPromptScreen

/**
 * Root composable — routes to the active mode's screen
 * and wraps everything in the persistent HUD overlay.
 *
 * @param introVisible when true the intro carousel covers the whole screen. It is drawn over the
 *   HUD rather than instead of it so the camera and its state survive underneath: the intro runs
 *   until the diver presses something, and tearing the preview down for it would mean a cold camera
 *   start at the exact moment they want to shoot.
 * @param permissionsGranted everything the housing link and camera need. The intro reports what is
 *   still missing rather than letting the diver arrive at a viewfinder that cannot see.
 */
@Composable
fun DiveControlScreen(
    state: AppState,
    cameraPermissionGranted: Boolean = false,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
    useMetric: Boolean = true,
    effects: List<PlatformEffect> = emptyList(),
    onEffectsConsumed: () -> Unit = {},
    onDetectedLenses: ((List<String>) -> Unit)? = null,
    onCapabilities: ((com.mobiledivecontrol.core.CameraCapabilities) -> Unit)? = null,
    onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null,
    introVisible: Boolean = false,
    onIntroDismiss: () -> Unit = {},
    permissionsGranted: Boolean = false,
    missingPermissions: List<String> = emptyList(),
    capPromptVisible: Boolean = false,
    onCapPromptDismiss: () -> Unit = {},
    bluetoothEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        DiveControlContent(
            state = state,
            cameraPermissionGranted = cameraPermissionGranted,
            lifecycleOwner = lifecycleOwner,
            useMetric = useMetric,
            effects = effects,
            onEffectsConsumed = onEffectsConsumed,
            onDetectedLenses = onDetectedLenses,
            onCapabilities = onCapabilities,
            onMeteredExposure = onMeteredExposure,
            bluetoothEnabled = bluetoothEnabled,
        )

        // One screen for permissions, connection and the button map. It leaves composition the
        // moment it is dismissed, which is what stops its animations running behind the camera.
        if (introVisible) {
            IntroCarouselScreen(
                permissionsGranted = permissionsGranted,
                bleState = state.bleConnectionState,
                missingPermissions = missingPermissions,
                onDismiss = onIntroDismiss,
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
    }
}

@Composable
private fun DiveControlContent(
    state: AppState,
    cameraPermissionGranted: Boolean,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner?,
    useMetric: Boolean,
    effects: List<PlatformEffect>,
    onEffectsConsumed: () -> Unit,
    onDetectedLenses: ((List<String>) -> Unit)?,
    onCapabilities: ((com.mobiledivecontrol.core.CameraCapabilities) -> Unit)?,
    onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null,
    bluetoothEnabled: Boolean = true,
) {
    CameraHudOverlay(
        state = state,
        useMetric = useMetric,
        bluetoothEnabled = bluetoothEnabled,
    ) {
        AnimatedContent(
            targetState = state.mode,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            label = "mode_transition",
        ) { mode ->
            when (mode) {
                AppMode.CameraLive -> CameraShellScreen(
                    cameraState = state.camera,
                    safetyState = state.safety,
                    cameraPermissionGranted = cameraPermissionGranted,
                    lifecycleOwner = lifecycleOwner,
                    effects = effects,
                    onEffectsConsumed = onEffectsConsumed,
                    onDetectedLenses = onDetectedLenses,
                    onCapabilities = onCapabilities,
                    onMeteredExposure = onMeteredExposure,
                    housingLinkAlert = state.bleConnectionState != BleConnectionState.Ready,
                )
                AppMode.CameraAdjust -> CameraShellScreen(
                    cameraState = state.camera,
                    safetyState = state.safety,
                    cameraPermissionGranted = cameraPermissionGranted,
                    lifecycleOwner = lifecycleOwner,
                    effects = effects,
                    onEffectsConsumed = onEffectsConsumed,
                    onDetectedLenses = onDetectedLenses,
                    onCapabilities = onCapabilities,
                    onMeteredExposure = onMeteredExposure,
                    housingLinkAlert = state.bleConnectionState != BleConnectionState.Ready,
                )
                AppMode.Safety -> SafetyScreen(safety = state.safety)
                AppMode.Diagnostics -> DiagnosticsScreen(state = state)
                AppMode.PhoneCursor, AppMode.PhoneTarget -> PhoneControlPlaceholder(mode = mode)
                AppMode.Gallery -> com.mobiledivecontrol.ui.gallery.GalleryScreen(galleryState = state.gallery)
            }
        }
    }
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
