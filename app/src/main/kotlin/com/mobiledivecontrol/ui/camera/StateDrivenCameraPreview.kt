package com.mobiledivecontrol.ui.camera

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.theme.DiveColors

@Composable
fun StateDrivenCameraPreview(
    lifecycleOwner: LifecycleOwner,
    cameraState: CameraState,
    safetyState: SafetyState,
    effects: List<PlatformEffect>,
    onEffectsConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(context) { CameraRuntimeController(context) }
    var cameraReady by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        controller.attach(
            previewView = previewView,
            lifecycleOwner = lifecycleOwner,
            initialState = cameraState,
        ) { ready ->
            cameraReady = ready
        }

        onDispose {
            controller.detach()
        }
    }

    LaunchedEffect(cameraState, safetyState) {
        controller.applyState(
            cameraState = cameraState,
            waterPressureKpa = safetyState.waterPressureKpa,
            atmosphericPressureKpa = safetyState.barometricPressureKpa,
        )
    }

    LaunchedEffect(effects) {
        if (effects.isEmpty()) {
            return@LaunchedEffect
        }
        effects.forEach { effect ->
            if (effect is PlatformEffect.ExecuteCamera) {
                controller.execute(effect.command)
            }
        }
        onEffectsConsumed()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        if (!cameraReady) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(DiveColors.DeepBlack),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoCamera,
                        contentDescription = null,
                        tint = DiveColors.DiveCyan,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Starting camera...",
                        color = DiveColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
