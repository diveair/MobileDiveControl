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
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.camera.CameraShellScreen
import com.mobiledivecontrol.ui.diagnostics.DiagnosticsScreen
import com.mobiledivecontrol.ui.safety.SafetyScreen

/**
 * Root composable — routes to the active mode's screen
 * and wraps everything in the persistent HUD overlay.
 */
@Composable
fun DiveControlScreen(
    state: AppState,
    cameraPermissionGranted: Boolean = false,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
    useMetric: Boolean = true,
    effects: List<PlatformEffect> = emptyList(),
    onEffectsConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    CameraHudOverlay(
        state = state,
        useMetric = useMetric,
        modifier = modifier,
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
                )
                AppMode.CameraAdjust -> CameraShellScreen(
                    cameraState = state.camera,
                    safetyState = state.safety,
                    cameraPermissionGranted = cameraPermissionGranted,
                    lifecycleOwner = lifecycleOwner,
                    effects = effects,
                    onEffectsConsumed = onEffectsConsumed,
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
