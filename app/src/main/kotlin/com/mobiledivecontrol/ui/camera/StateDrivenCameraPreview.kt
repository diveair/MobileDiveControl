package com.mobiledivecontrol.ui.camera

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.theme.DiveColors
import kotlinx.coroutines.launch

@Composable
fun StateDrivenCameraPreview(
    lifecycleOwner: LifecycleOwner,
    cameraState: CameraState,
    safetyState: SafetyState,
    locationPrerequisitesReady: Boolean = false,
    effects: List<PlatformEffect>,
    onEffectsConsumed: () -> Unit,
    onDetectedLenses: ((List<String>) -> Unit)? = null,
    onCapabilities: ((com.mobiledivecontrol.core.CameraCapabilities) -> Unit)? = null,
    onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null,
    onPointingGesture: ((PointingGesture) -> Unit)? = null,
    onCameraCommand: ((CameraCommand) -> Unit)? = null,
    headingDegrees: Double? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(context) { CameraRuntimeController(context) }
    val coroutineScope = rememberCoroutineScope()
    var cameraReady by remember { mutableStateOf(false) }
    var lastStablePreviewFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var heldTransitionFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var awaitingReplacementStream by remember { mutableStateOf(false) }
    var replacementStreamWentIdle by remember { mutableStateOf(false) }
    var transitionGeneration by remember { mutableStateOf(0L) }
    var previewStreamState by remember {
        mutableStateOf(PreviewView.StreamState.IDLE)
    }

    // Keep one PreviewView for the controller's whole lifetime. Re-keying this object when
    // entering or leaving Panorama disposed the controller, unbound CameraX, released the GL
    // processor, and immediately attached it again while old surface callbacks were still in
    // flight. That full teardown was the repeatable Panorama-switch freeze.
    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Keep CameraX's transformed preview as the one full-screen viewfinder in every
            // mode. COMPATIBLE uses a TextureView, which retains the last composed frame while a
            // genuinely different CameraX stream graph is replacing the old one. PERFORMANCE's
            // SurfaceView clears its independent surface to black as soon as the producer is
            // disconnected, exposing a brief black flash on every photo/video/panorama switch.
            // The PreviewView object itself still remains stable for the controller's lifetime.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        cameraReady = false
        controller.attach(
            previewView = previewView,
            lifecycleOwner = lifecycleOwner,
            initialState = cameraState,
            onReady = { ready ->
                cameraReady = ready
            },
            onDetectedLenses = onDetectedLenses,
            onCapabilities = onCapabilities,
            onMeteredExposure = onMeteredExposure,
            onPointingGesture = onPointingGesture,
            onCameraCommand = onCameraCommand,
            onGraphReplacementRequired = { onContinuityFramePresented ->
                // The controller invokes this at the exact teardown boundary, after all graph
                // debounce/coalescing has completed. Capture the still-live TextureView now and
                // acknowledge only after Compose has submitted the covering bitmap twice.
                transitionGeneration++
                awaitingReplacementStream = true
                replacementStreamWentIdle =
                    previewStreamState == PreviewView.StreamState.IDLE
                heldTransitionFrame = previewView.bitmap ?: lastStablePreviewFrame
                coroutineScope.launch {
                    withFrameNanos { }
                    withFrameNanos { }
                    onContinuityFramePresented()
                }
            },
            onDirectPreviewPresented = {
                cameraReady = true
                // Hyperlapse replaces CameraX with a direct Camera2 SurfaceView while recording.
                // PreviewView cannot report STREAMING for that producer, so release any mode
                // transition cover from the recorder's own first-presented-frame handshake.
                transitionGeneration++
                heldTransitionFrame = null
                awaitingReplacementStream = false
                replacementStreamWentIdle = false
            },
        )

        onDispose {
            controller.detach()
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val observer = Observer<PreviewView.StreamState> { state ->
            previewStreamState = state
            if (state == PreviewView.StreamState.STREAMING) cameraReady = true
            if (awaitingReplacementStream && state == PreviewView.StreamState.IDLE) {
                replacementStreamWentIdle = true
            }
        }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose {
            previewView.previewStreamState.removeObserver(observer)
        }
    }

    LaunchedEffect(
        previewStreamState,
        awaitingReplacementStream,
        replacementStreamWentIdle,
        transitionGeneration,
    ) {
        if (awaitingReplacementStream && replacementStreamWentIdle &&
            previewStreamState == PreviewView.StreamState.STREAMING
        ) {
            val completingGeneration = transitionGeneration
            // STREAMING is posted when PreviewView has consumed the replacement producer. Keep
            // the held buffer for one display beat so its removal and the new frame cannot land
            // on opposite sides of a compositor transaction.
            kotlinx.coroutines.delay(80L)
            if (transitionGeneration == completingGeneration) {
                heldTransitionFrame = null
                awaitingReplacementStream = false
                replacementStreamWentIdle = false
            }
        }
    }

    LaunchedEffect(previewStreamState, awaitingReplacementStream) {
        if (previewStreamState == PreviewView.StreamState.STREAMING &&
            !awaitingReplacementStream
        ) {
            // Keep one known-good frame outside PreviewView's surface lifetime. CameraX destroys
            // and recreates TextureView's SurfaceTexture for several graph changes, so querying
            // PreviewView only at switch time is not a guarantee: the state can arrive after the
            // surface-release callback during rapid navigation.
            kotlinx.coroutines.delay(120L)
            previewView.bitmap?.let { lastStablePreviewFrame = it }
        }
    }

    LaunchedEffect(cameraState, safetyState, headingDegrees, locationPrerequisitesReady) {
        controller.applyState(
            cameraState = cameraState,
            waterPressureKpa = safetyState.waterPressureKpa,
            atmosphericPressureKpa = safetyState.barometricPressureKpa,
            surfaceAmbientKpa = safetyState.surfaceAmbientKpa,
            waterTemperatureC = safetyState.waterTemperatureC,
            headingDegrees = headingDegrees,
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

    // Front camera preview appears upside down in landscape dive housing
    val isFrontCamera = cameraState.settingValues.entries
        .any { it.key.endsWith(".lens") && it.value == "front" }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isFrontCamera) {
                        Modifier.graphicsLayer {
                            rotationZ = 180f
                        }
                    } else {
                        Modifier
                    }
                ),
        )

        heldTransitionFrame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

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
