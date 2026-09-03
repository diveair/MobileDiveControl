package com.mobiledivecontrol.ui.camera

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Filter
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HdrAuto
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobiledivecontrol.core.CameraCaptureType
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraFeatureStatus
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.CameraModeProfile
import com.mobiledivecontrol.core.CameraSettingKind
import com.mobiledivecontrol.core.CameraSettingSpec
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.core.CameraUiZone
import com.mobiledivecontrol.core.FocusCurveMode
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.PanoramaReviewAction
import com.mobiledivecontrol.core.RecordingPausedAction
import com.mobiledivecontrol.core.RecordingSaveConfirmationAction
import com.mobiledivecontrol.core.RecordingSaveLocation
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.core.SamsungLogProfile
import com.mobiledivecontrol.core.SliderEditTarget
import com.mobiledivecontrol.core.SliderSensitivity
import com.mobiledivecontrol.core.selectedSetting
import com.mobiledivecontrol.core.BottomBarItem
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.testing.CameraStressVisualStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

@Composable
fun CameraShellScreen(
    cameraState: CameraState,
    safetyState: SafetyState,
    cameraPermissionGranted: Boolean = false,
    locationPrerequisitesReady: Boolean = false,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
    effects: List<PlatformEffect> = emptyList(),
    onEffectsConsumed: () -> Unit = {},
    onDetectedLenses: ((List<String>) -> Unit)? = null,
    onCapabilities: ((com.mobiledivecontrol.core.CameraCapabilities) -> Unit)? = null,
    onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null,
    onPointingGesture: ((PointingGesture) -> Unit)? = null,
    onCameraCommand: (CameraCommand) -> Unit = {},
    headingDegrees: Double? = null,
    warningMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val settings = CameraCatalog.settingsFor(cameraState.activeMode, cameraState.deviceVariant)
    val settingsVisible = settings.isNotEmpty()

    LatestCaptureProvider(lifecycleOwner) {
    Box(modifier = modifier.fillMaxSize()) {
        // Full-screen camera preview
        if (cameraPermissionGranted && lifecycleOwner != null) {
            StateDrivenCameraPreview(
                lifecycleOwner = lifecycleOwner,
                cameraState = cameraState,
                safetyState = safetyState,
                locationPrerequisitesReady = locationPrerequisitesReady,
                effects = effects,
                onEffectsConsumed = onEffectsConsumed,
                onDetectedLenses = onDetectedLenses,
                onCapabilities = onCapabilities,
                onMeteredExposure = onMeteredExposure,
                onPointingGesture = onPointingGesture,
                onCameraCommand = onCameraCommand,
                headingDegrees = headingDegrees,
            )
        } else {
            CameraPreviewPlaceholder()
        }

        if (!cameraState.panoramaReviewAvailable) {
            CaptureGuideOverlay(
                cameraState = cameraState,
                modifier = Modifier.fillMaxSize(),
            )
            ModeGuideOverlay(
                cameraState = cameraState,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (cameraState.panoramaReviewAvailable) {
            PanoramaReviewPreview(modifier = Modifier.fillMaxSize())
        }

        // The runtime only publishes this URI after every paused segment has a complete MP4 index
        // and the cumulative, gap-free review container has been assembled.
        // Until then the overlay says exactly what is happening instead of opening a corrupt file.
        if (cameraState.recording && cameraState.recordingPaused && cameraState.recordingPreviewVisible) {
            RecordingSegmentPreview(modifier = Modifier.fillMaxSize())
        }

        // Samsung Hyperlapse centres one combined output / elapsed clock at the top.
        if (cameraState.activeMode == CameraModeId.Hyperlapse) {
            HyperlapseRecordingBadge(
                visible = cameraState.recording && !cameraState.recordingPaused,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // The native clock is top-centred; DiveControl's vacuum HUD occupies the
                    // first row, so place it immediately below that row instead of over it.
                    .padding(top = 56.dp),
            )
        } else {
            RecordingBadge(
                visible = cameraState.recording && !cameraState.recordingPaused,
                paused = false,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 72.dp),
            )
        }

        // Center: zoom overlay (fades after 1.4s)
        ZoomOverlay(
            zoomFactor = cameraState.zoomFactor,
            modifier = Modifier.align(Alignment.Center),
        )

        if (!warningMessage.isNullOrBlank()) {
            CameraFailureBanner(
                message = warningMessage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 82.dp),
            )
        }

        CameraStressVisualStatus.current.value?.let { stress ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .background(DiveColors.DeepBlack.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                    .border(1.dp, DiveColors.DiveCyan, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "STRESS ${stress.sequence} · ${stress.mode} · ${stress.status}",
                    color = DiveColors.DiveCyan,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = buildString {
                        append(stress.setting).append(": ").append(stress.requested)
                        if (stress.actual.isNotBlank()) append(" → ").append(stress.actual)
                    },
                    color = DiveColors.TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Right side: mode rail (only visible when in ModeRail zone)
        AnimatedVisibility(
            visible = cameraState.focusedZone == CameraUiZone.ModeRail &&
                !cameraState.panoramaReviewAvailable,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 18.dp, top = 56.dp, bottom = 56.dp),
        ) {
            RightModeRail(
                cameraState = cameraState,
                onCommand = onCameraCommand,
            )
        }

        // Bottom center: persistent mode-specific control bar
        AnimatedVisibility(
            visible = settingsVisible &&
                !PanoramaCaptureState.active.value &&
                !PanoramaCaptureState.finalizing.value &&
                !cameraState.panoramaReviewAvailable,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomSettingsTray(
                cameraState = cameraState,
                onCommand = onCameraCommand,
            )
        }

        AnimatedVisibility(
            visible = cameraState.showMoreSettings &&
                cameraState.focusedZone == CameraUiZone.SettingsPanel &&
                !cameraState.settingsEditing &&
                !cameraState.recordingPaused &&
                !cameraState.panoramaReviewAvailable,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                // The persistent rail is 48dp tall. Dock Options directly to its top so its
                // upper edge matches the Focus editor instead of floating a row too high.
                .padding(start = 12.dp, bottom = 48.dp),
        ) {
            ProOptionsPanel(
                cameraState = cameraState,
                onCommand = onCameraCommand,
            )
        }

        // Draw the paused-session controls last so their modal album grid owns the full z-plane.
        if (cameraState.recording && cameraState.recordingPaused) {
            RecordingPausedChooser(
                cameraState = cameraState,
                onCommand = onCameraCommand,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (cameraState.panoramaReviewAvailable) {
            PanoramaReviewChooser(
                cameraState = cameraState,
                onCommand = onCameraCommand,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    }
}

@Composable
private fun CameraFailureBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        color = DiveColors.TextPrimary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth(0.72f)
            .background(DiveColors.Warning.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
            .border(1.dp, DiveColors.DeepBlack.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Compact strip of mode parameter badges along the bottom edge.
 * Shows key settings for the current mode at a glance.
 */
@Composable
private fun ModeParametersStrip(
    settings: List<CameraSettingSpec>,
    cameraState: CameraState,
) {
    if (settings.isEmpty()) return

    // Show up to 6 key settings as compact badges
    val displaySettings = settings.take(6)

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                DiveColors.DeepBlack.copy(alpha = 0.55f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        displaySettings.forEach { spec ->
            val value = displaySettingValue(cameraState, spec, CameraCatalog.currentValue(cameraState, spec))
            ParameterBadge(label = spec.label, value = value)
        }
    }
}

@Composable
private fun ParameterBadge(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                DiveColors.SurfaceCard.copy(alpha = 0.7f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = DiveColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = value,
            color = DiveColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Preview-only composition guides. They never alter or burn into captured media. */
@Composable
private fun CaptureGuideOverlay(
    cameraState: CameraState,
    modifier: Modifier = Modifier,
) {
    val guideSpec = CameraCatalog.settingsFor(cameraState)
        .firstOrNull { it.id.endsWith(".guides") || it.id.endsWith(".grid") }
        ?: return
    val guide = CameraCatalog.currentValue(cameraState, guideSpec)
    if (guide == "Off") return
    val guideColor = Color.White.copy(alpha = 0.64f)
    val constructionColor = Color.White.copy(alpha = 0.46f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 0.85.dp.toPx()
        val guideDash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        val spiralDash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        fun guideLine(
            start: Offset,
            end: Offset,
            color: Color = guideColor,
            width: Float = stroke,
            pathEffect: PathEffect? = null,
        ) = drawLine(
            color = color,
            start = start,
            end = end,
            strokeWidth = width,
            pathEffect = pathEffect,
        )
        fun vertical(fraction: Float) = drawLine(
            guideColor, Offset(w * fraction, 0f), Offset(w * fraction, h), stroke,
        )
        fun horizontal(fraction: Float) = drawLine(
            guideColor, Offset(0f, h * fraction), Offset(w, h * fraction), stroke,
        )
        fun arrow(start: Offset, end: Offset) {
            guideLine(start, end, pathEffect = guideDash)
            val angle = kotlin.math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
            val headLength = 15.dp.toPx()
            val spread = Math.PI / 7.0
            listOf(angle - spread, angle + spread).forEach { headAngle ->
                guideLine(
                    start = end,
                    end = Offset(
                        x = end.x - headLength * kotlin.math.cos(headAngle).toFloat(),
                        y = end.y - headLength * kotlin.math.sin(headAngle).toFloat(),
                    ),
                )
            }
        }

        when (guide) {
            "Rule of Thirds + Center" -> {
                vertical(1f / 3f); vertical(2f / 3f)
                horizontal(1f / 3f); horizontal(2f / 3f)
                drawCircle(
                    color = guideColor,
                    radius = minOf(w, h) * 0.07f,
                    center = Offset(w / 2f, h / 2f),
                    style = Stroke(stroke),
                )
            }
            "Rule of Thirds", "3x3", "3×3 Grid" -> {
                vertical(1f / 3f); vertical(2f / 3f)
                horizontal(1f / 3f); horizontal(2f / 3f)
            }
            "4×4 Grid" -> {
                vertical(0.25f); vertical(0.5f); vertical(0.75f)
                horizontal(0.25f); horizontal(0.5f); horizontal(0.75f)
            }
            "Square" -> {
                val side = minOf(w, h) * 0.72f
                drawRect(
                    color = guideColor,
                    topLeft = Offset((w - side) / 2f, (h - side) / 2f),
                    size = androidx.compose.ui.geometry.Size(side, side),
                    style = Stroke(stroke),
                )
            }
            "Diagonal" -> {
                drawLine(guideColor, Offset.Zero, Offset(w, h), stroke)
                drawLine(guideColor, Offset(w, 0f), Offset(0f, h), stroke)
            }
            "Phi Grid", "Golden Ratio" -> {
                val major = 1f / 1.618033988749895f
                val minor = 1f - major
                vertical(minor); vertical(major)
                horizontal(minor); horizontal(major)
            }
            "Symmetry" -> {
                vertical(0.5f)
                horizontal(0.5f)
                drawCircle(
                    color = guideColor,
                    radius = 4.dp.toPx(),
                    center = Offset(w / 2f, h / 2f),
                    style = Stroke(stroke),
                )
            }
            "Fibonacci Spiral Left", "Fibonacci Spiral Right",
            "Fibonacci Spiral Top Left", "Fibonacci Spiral Top Right",
            "Fibonacci Left", "Fibonacci Right" -> {
                val geometry = fibonacciGuideGeometry(
                    eyeOnLeft = guide.endsWith("Left"),
                    eyeOnTop = guide.contains("Top"),
                )
                geometry.lines.forEach { line ->
                    guideLine(
                        start = Offset(w * line.startX, h * line.startY),
                        end = Offset(w * line.endX, h * line.endY),
                        color = constructionColor,
                        pathEffect = guideDash,
                    )
                }
                geometry.arcs.forEach { arc ->
                    drawArc(
                        color = guideColor,
                        startAngle = arc.startAngle,
                        sweepAngle = arc.sweepAngle,
                        useCenter = false,
                        topLeft = Offset(
                            w * (arc.centerX - arc.radiusX),
                            h * (arc.centerY - arc.radiusY),
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            w * arc.radiusX * 2f,
                            h * arc.radiusY * 2f,
                        ),
                        style = Stroke(stroke * 1.45f, pathEffect = spiralDash),
                    )
                }
            }
            "Golden Triangles" -> {
                val denominator = w * w + h * h
                val topRightProjection = w * w / denominator
                val bottomLeftProjection = h * h / denominator
                guideLine(Offset.Zero, Offset(w, h))
                guideLine(
                    Offset(w, 0f),
                    Offset(w * topRightProjection, h * topRightProjection),
                )
                guideLine(
                    Offset(0f, h),
                    Offset(w * bottomLeftProjection, h * bottomLeftProjection),
                )
            }
            "Vanishing Point" -> {
                val vanishingPoint = Offset(w * 0.5f, h * 0.42f)
                val edgePoints = listOf(
                    Offset.Zero,
                    Offset(w * 0.25f, 0f),
                    Offset(w * 0.5f, 0f),
                    Offset(w * 0.75f, 0f),
                    Offset(w, 0f),
                    Offset(w, h * 0.5f),
                    Offset(w, h),
                    Offset(w * 0.75f, h),
                    Offset(w * 0.5f, h),
                    Offset(w * 0.25f, h),
                    Offset(0f, h),
                    Offset(0f, h * 0.5f),
                )
                edgePoints.forEach { guideLine(vanishingPoint, it) }
                drawCircle(guideColor, 4.dp.toPx(), vanishingPoint)
            }
            "Framing Depth" -> {
                val center = Offset(w / 2f, h / 2f)
                val radius = minOf(w, h) * 0.24f
                drawCircle(
                    color = guideColor,
                    radius = radius,
                    center = center,
                    style = Stroke(stroke),
                )
                repeat(8) { index ->
                    val angle = index * Math.PI / 4.0
                    val dx = kotlin.math.cos(angle).toFloat()
                    val dy = kotlin.math.sin(angle).toFloat()
                    guideLine(
                        start = Offset(center.x + dx * radius, center.y + dy * radius),
                        end = Offset(center.x + dx * radius * 1.48f, center.y + dy * radius * 1.48f),
                    )
                }
            }
            "Landscape Depth" -> {
                val horizonY = h * 0.42f
                val vanishingPoint = Offset(w / 2f, horizonY)
                horizontal(0.42f)
                listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f).forEach { fraction ->
                    guideLine(vanishingPoint, Offset(w * fraction, h))
                }
                listOf(0.56f, 0.70f, 0.84f).forEach { yFraction ->
                    val progress = (yFraction - 0.42f) / (1f - 0.42f)
                    guideLine(
                        Offset(vanishingPoint.x * (1f - progress), h * yFraction),
                        Offset(vanishingPoint.x + (w - vanishingPoint.x) * progress, h * yFraction),
                    )
                }
            }
            "Leading Lines" -> {
                val target = Offset(w * 0.62f, h * 0.28f)
                arrow(Offset(w * 0.06f, h * 0.82f), target)
                arrow(Offset(w * 0.32f, h), target)
                arrow(Offset(w * 0.78f, h), target)
            }
            "Lines and Patterns" -> {
                repeat(7) { index ->
                    vertical(0.2f + index * 0.1f)
                }
            }
        }
    }
}

/** Samsung-style capture guides that belong to a mode rather than the global grid setting. */
@Composable
private fun ModeGuideOverlay(
    cameraState: CameraState,
    modifier: Modifier = Modifier,
) {
    val settings = CameraCatalog.settingsFor(cameraState)
    when (cameraState.activeMode) {
        CameraModeId.Panorama -> {
            val active by PanoramaCaptureState.active
            val finalizing by PanoramaCaptureState.finalizing
            val progress by PanoramaCaptureState.progress
            val movingTooFast by PanoramaCaptureState.movingTooFast
            val detectedDirection by PanoramaCaptureState.direction
            val directionLocked by PanoramaCaptureState.directionLocked
            val message by PanoramaCaptureState.message
            val crossAxisRadians by PanoramaCaptureState.crossAxisRadians
            val warningLevel by PanoramaCaptureState.warningLevel
            val correction by PanoramaCaptureState.correction
            val referenceFrame by PanoramaCaptureState.referenceFrame
            val liveThumbnail by PanoramaCaptureState.liveThumbnail
            val wideAngle = settings
                .firstOrNull { it.id == "panorama.lens" }
                ?.let { CameraCatalog.currentValue(cameraState, it) == "0.6x" }
                ?: false
            PanoramaGuideOverlay(
                direction = if (active || finalizing) detectedDirection else "Auto",
                active = active,
                finalizing = finalizing,
                progress = progress,
                movingTooFast = movingTooFast,
                message = message,
                directionLocked = directionLocked,
                crossAxisRadians = crossAxisRadians,
                warningLevel = warningLevel,
                correction = correction,
                referenceFrame = referenceFrame,
                liveThumbnail = liveThumbnail,
                wideAngle = wideAngle,
                modifier = modifier,
            )
        }
        CameraModeId.Food -> {
            val blur = settings.firstOrNull { it.id == "food.radial_blur" } ?: return
            if (CameraCatalog.currentValue(cameraState, blur) != "On") return
            FoodFocusGuideOverlay(modifier = modifier)
        }
        CameraModeId.ExpertRaw -> {
            val skyGuide = settings.firstOrNull { it.id == "expert.sky_guide" }
                ?.let { CameraCatalog.currentValue(cameraState, it) == "On" }
                ?: false
            ExpertRawGuideOverlay(
                skyGuideEnabled = skyGuide,
                modifier = modifier,
            )
        }
        CameraModeId.Pro -> ExpertRawGuideOverlay(
            skyGuideEnabled = false,
            modifier = modifier,
        )
        else -> Unit
    }
}

@Composable
private fun ExpertRawGuideOverlay(
    skyGuideEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val active by ExpertRawCaptureState.active
    val progress by ExpertRawCaptureState.progress
    val message by ExpertRawCaptureState.message
    val azimuth by ExpertRawCaptureState.skyAzimuthDegrees
    val altitude by ExpertRawCaptureState.skyAltitudeDegrees
    val latitude by ExpertRawCaptureState.observerLatitudeDegrees
    val longitude by ExpertRawCaptureState.observerLongitudeDegrees
    val projection = remember(latitude, longitude, azimuth, altitude, System.currentTimeMillis() / 60_000L) {
        if (skyGuideEnabled && latitude != null && longitude != null) {
            SkyGuideAstronomy.project(
                epochMillis = System.currentTimeMillis(),
                latitudeDegrees = latitude!!,
                longitudeDegrees = longitude!!,
                cameraAzimuthDegrees = azimuth.toDouble(),
                cameraAltitudeDegrees = altitude.toDouble(),
            )
        } else {
            SkyGuideProjection(emptyList(), emptyList())
        }
    }
    Box(modifier = modifier) {
        if (skyGuideEnabled) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 180.dp),
            ) {
                val guideColour = Color(0xFFD7ECFF)
                projection.segments.forEach { segment ->
                    drawLine(
                        color = guideColour.copy(alpha = 0.72f),
                        start = Offset(segment.from.x * size.width, segment.from.y * size.height),
                        end = Offset(segment.to.x * size.width, segment.to.y * size.height),
                        strokeWidth = 1.2.dp.toPx(),
                    )
                }
                projection.points.forEach { point ->
                    val centre = Offset(point.x * size.width, point.y * size.height)
                    drawCircle(guideColour, radius = 2.6.dp.toPx(), center = centre)
                    drawContext.canvas.nativeCanvas.drawText(
                        point.star,
                        centre.x + 5.dp.toPx(),
                        centre.y - 4.dp.toPx(),
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            alpha = 210
                            textSize = 10.dp.toPx()
                        },
                    )
                }
            }
        }
        if (active || message.isNotBlank()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (skyGuideEnabled) 112.dp else 72.dp)
                    .width(280.dp)
                    .background(DiveColors.DeepBlack.copy(alpha = 0.82f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (active && progress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    ) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.24f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                        )
                        drawRoundRect(
                            color = DiveColors.DiveCyan,
                            size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                        )
                    }
                }
            }
        }
    }
}

/** Samsung Panorama's observed idle, direction-lock, correction, stop and saving states. */
@Composable
private fun PanoramaGuideOverlay(
    direction: String,
    active: Boolean,
    finalizing: Boolean,
    progress: Float,
    movingTooFast: Boolean,
    message: String,
    directionLocked: Boolean,
    crossAxisRadians: Float,
    warningLevel: PanoramaWarningLevel,
    correction: PanoramaCorrection,
    referenceFrame: Bitmap?,
    liveThumbnail: Bitmap?,
    wideAngle: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val resolvedDirection = direction.takeUnless { it == "Auto" } ?: "Right"
        val horizontal = resolvedDirection == "Left" || resolvedDirection == "Right"
        val awaitingDirection = !directionLocked && !finalizing
        // The capture rectangle follows the camera frame orientation, not the sweep axis. For a
        // landscape 1920x1080 stream it remains landscape: horizontal travel exposes a 1080 px
        // cross-edge, while vertical travel exposes the 1920 px cross-edge. Only the lane rotates.
        val idlePreviewWidth = 248.dp
        val idlePreviewHeight = 88.dp
        val nativeFrameWidthDp by animateDpAsState(
            targetValue = 132.dp,
            animationSpec = tween(durationMillis = 220),
            label = "panorama-frame-width",
        )
        val nativeFrameHeightDp by animateDpAsState(
            targetValue = 88.dp,
            animationSpec = tween(durationMillis = 220),
            label = "panorama-frame-height",
        )
        val arrowTransition = rememberInfiniteTransition(label = "panorama-native-arrow")
        val arrowSwing by arrowTransition.animateFloat(
            initialValue = 0f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "panorama-native-arrow-swing",
        )
        val referenceImage = referenceFrame
            ?.takeUnless(Bitmap::isRecycled)
            ?.asImageBitmap()
        val capturedImage = liveThumbnail
            ?.takeUnless(Bitmap::isRecycled)
            ?.asImageBitmap()

        // Samsung centres Panorama in the preview surface, not in the full screen including its
        // control rail. DiveControl's rail is 180 dp wide, so the native guide belongs here.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 180.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val previewCenter = center
                val nativeFrameWidth = nativeFrameWidthDp.toPx()
                val nativeFrameHeight = nativeFrameHeightDp.toPx()
                val borderWidth = 0.5.dp.toPx()
                val guideWidth = 4.dp.toPx()
                val background = Color(0x3D222222)
                val white = Color.White
                val warning = Color(0xFFFFD90D)

                if (!active && !finalizing) {
                    val groupWidth = idlePreviewWidth.toPx()
                    val groupHeight = idlePreviewHeight.toPx()
                    val left = previewCenter.x - groupWidth / 2f
                    val top = previewCenter.y - groupHeight / 2f
                    drawRect(background, Offset(left, top), androidx.compose.ui.geometry.Size(groupWidth, groupHeight))
                    referenceImage?.let { image ->
                        val frameLeft = previewCenter.x - nativeFrameWidth / 2f
                        val frameTop = previewCenter.y - nativeFrameHeight / 2f
                        val crop = centerCropPanoramaPreview(
                            imageWidth = image.width,
                            imageHeight = image.height,
                            boxWidth = nativeFrameWidth,
                            boxHeight = nativeFrameHeight,
                        )
                        // Make containment an invariant in addition to supplying a destination
                        // size: no reference or replacement bitmap can draw outside the exact
                        // rectangle between the two inner divider lines.
                        clipRect(
                            left = frameLeft,
                            top = frameTop,
                            right = frameLeft + nativeFrameWidth,
                            bottom = frameTop + nativeFrameHeight,
                        ) {
                            drawImage(
                                image = image,
                                // The source bitmap may be a wide stitched thumbnail. Crop its
                                // centre to the guide's aspect ratio instead of drawing from (0,0).
                                srcOffset = IntOffset(crop.left, crop.top),
                                srcSize = IntSize(crop.width, crop.height),
                                dstOffset = IntOffset(
                                    frameLeft.roundToInt(),
                                    frameTop.roundToInt(),
                                ),
                                dstSize = IntSize(
                                    nativeFrameWidth.roundToInt().coerceAtLeast(1),
                                    nativeFrameHeight.roundToInt().coerceAtLeast(1),
                                ),
                            )
                        }
                    }
                    drawRect(
                        white,
                        Offset(left, top),
                        androidx.compose.ui.geometry.Size(groupWidth, groupHeight),
                        style = Stroke(borderWidth),
                    )
                    val frameLeft = previewCenter.x - nativeFrameWidth / 2f
                    val frameRight = previewCenter.x + nativeFrameWidth / 2f
                    drawLine(white, Offset(frameLeft, top), Offset(frameLeft, top + groupHeight), borderWidth)
                    drawLine(white, Offset(frameRight, top), Offset(frameRight, top + groupHeight), borderWidth)
                    drawPanoramaChevron(
                        Offset((left + frameLeft) / 2f - arrowSwing.dp.toPx(), previewCenter.y),
                        PanoramaCorrection.Left,
                        white,
                    )
                    drawPanoramaChevron(
                        Offset((frameRight + left + groupWidth) / 2f + arrowSwing.dp.toPx(), previewCenter.y),
                        PanoramaCorrection.Right,
                        white,
                    )
                } else {
                    val groupWidth = if (awaitingDirection) {
                        248.dp.toPx()
                    } else if (horizontal) {
                        (if (wideAngle) 272.dp else 340.dp).toPx()
                            .coerceAtMost((size.width - 32.dp.toPx()).coerceAtLeast(248.dp.toPx()))
                    } else {
                        nativeFrameWidth
                    }
                    val groupHeight = if (awaitingDirection) {
                        88.dp.toPx()
                    } else if (horizontal) {
                        nativeFrameHeight
                    } else {
                        (size.height - (if (wideAngle) 146.dp else 110.dp).toPx())
                            .coerceAtLeast(192.dp.toPx())
                    }
                    val groupLeft = previewCenter.x - groupWidth / 2f
                    val groupTop = previewCenter.y - groupHeight / 2f
                    val crossFraction = panoramaGuideCrossFraction(crossAxisRadians)
                    val liveStrip = if (directionLocked && capturedImage != null) {
                        panoramaLiveThumbnailRect(
                            direction = resolvedDirection,
                            groupLeft = groupLeft,
                            groupTop = groupTop,
                            groupWidth = groupWidth,
                            groupHeight = groupHeight,
                            bitmapWidth = capturedImage.width,
                            bitmapHeight = capturedImage.height,
                            inset = 2.dp.toPx(),
                        )
                    } else {
                        null
                    }
                    val thumbnailDrivenProgress = liveStrip?.let { strip ->
                        if (horizontal) {
                            ((strip.right - strip.left - nativeFrameWidth) /
                                (groupWidth - nativeFrameWidth).coerceAtLeast(1f))
                                .coerceIn(0f, 1f)
                        } else {
                            ((strip.bottom - strip.top - nativeFrameHeight) /
                                (groupHeight - nativeFrameHeight).coerceAtLeast(1f))
                                .coerceIn(0f, 1f)
                        }
                    } ?: progress
                    val track = if (!directionLocked) {
                        PanoramaGuideTrack(
                            startX = previewCenter.x,
                            startY = previewCenter.y,
                            currentX = previewCenter.x,
                            currentY = previewCenter.y,
                        )
                    } else {
                        panoramaGuideTrack(
                            direction = resolvedDirection,
                            groupLeft = groupLeft,
                            groupTop = groupTop,
                            groupWidth = groupWidth,
                            groupHeight = groupHeight,
                            frameWidth = nativeFrameWidth,
                            frameHeight = nativeFrameHeight,
                            progress = thumbnailDrivenProgress,
                            crossOffset = crossFraction * 44.dp.toPx(),
                        )
                    }
                    val startCenter = Offset(track.startX, track.startY)
                    val currentCenter = Offset(track.currentX, track.currentY)

                    val previewLeft = currentCenter.x - nativeFrameWidth / 2f
                    val previewTop = currentCenter.y - nativeFrameHeight / 2f
                    val previewRight = previewLeft + nativeFrameWidth
                    val previewBottom = previewTop + nativeFrameHeight

                    drawRect(background, Offset(groupLeft, groupTop), androidx.compose.ui.geometry.Size(groupWidth, groupHeight))
                    if (directionLocked && capturedImage != null && liveStrip != null) {
                        // Samsung supplies an already-merged bitmap and preserves its aspect
                        // ratio. Its long edge grows naturally; it is never stretched to a gyro
                        // progress rectangle.
                        clipRect(
                            left = groupLeft,
                            top = groupTop,
                            right = groupLeft + groupWidth,
                            bottom = groupTop + groupHeight,
                        ) {
                            drawImage(
                                image = capturedImage,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(capturedImage.width, capturedImage.height),
                                dstOffset = IntOffset(liveStrip.left.roundToInt(), liveStrip.top.roundToInt()),
                                dstSize = IntSize(
                                    (liveStrip.right - liveStrip.left).roundToInt().coerceAtLeast(1),
                                    (liveStrip.bottom - liveStrip.top).roundToInt().coerceAtLeast(1),
                                ),
                            )
                        }
                    } else {
                        (capturedImage ?: referenceImage)?.let { image ->
                        val crop = centerCropPanoramaPreview(
                            imageWidth = image.width,
                            imageHeight = image.height,
                            boxWidth = nativeFrameWidth,
                            boxHeight = nativeFrameHeight,
                        )
                        clipRect(
                            left = previewLeft,
                            top = previewTop,
                            right = previewRight,
                            bottom = previewBottom,
                        ) {
                            drawImage(
                                image = image,
                                srcOffset = IntOffset(crop.left, crop.top),
                                srcSize = IntSize(crop.width, crop.height),
                                dstOffset = IntOffset(
                                    previewLeft.roundToInt(),
                                    previewTop.roundToInt(),
                                ),
                                dstSize = IntSize(
                                    nativeFrameWidth.roundToInt().coerceAtLeast(1),
                                    nativeFrameHeight.roundToInt().coerceAtLeast(1),
                                ),
                            )
                        }
                    }
                    }
                    drawRect(
                        white.copy(alpha = 0.72f),
                        Offset(groupLeft, groupTop),
                        androidx.compose.ui.geometry.Size(groupWidth, groupHeight),
                        style = Stroke(borderWidth),
                    )

                    if (!finalizing) {
                        val guideColor = if (warningLevel == PanoramaWarningLevel.None) white else warning
                        drawRect(
                            guideColor,
                            Offset(currentCenter.x - nativeFrameWidth / 2f, currentCenter.y - nativeFrameHeight / 2f),
                            androidx.compose.ui.geometry.Size(nativeFrameWidth, nativeFrameHeight),
                            style = Stroke(guideWidth),
                        )
                        when {
                            correction != PanoramaCorrection.None -> {
                                val arrowCenter = when (correction) {
                                    PanoramaCorrection.Up -> Offset(currentCenter.x, currentCenter.y - nativeFrameHeight / 2f - 14.dp.toPx())
                                    PanoramaCorrection.Down -> Offset(currentCenter.x, currentCenter.y + nativeFrameHeight / 2f + 14.dp.toPx())
                                    PanoramaCorrection.Left -> Offset(currentCenter.x - nativeFrameWidth / 2f - 14.dp.toPx(), currentCenter.y)
                                    PanoramaCorrection.Right -> Offset(currentCenter.x + nativeFrameWidth / 2f + 14.dp.toPx(), currentCenter.y)
                                    PanoramaCorrection.None -> currentCenter
                                }
                                drawPanoramaChevron(arrowCenter, correction, warning)
                            }
                            !directionLocked -> {
                                drawPanoramaChevron(Offset(startCenter.x - nativeFrameWidth / 2f - 14.dp.toPx(), startCenter.y), PanoramaCorrection.Left, white)
                                drawPanoramaChevron(Offset(startCenter.x + nativeFrameWidth / 2f + 14.dp.toPx(), startCenter.y), PanoramaCorrection.Right, white)
                            }
                        }
                    }
                }
            }

            val guidanceText = when {
                finalizing -> ""
                !active -> "Tap the Camera button, then pan slowly in one direction."
                message.isNotBlank() -> message
                movingTooFast -> "Move slowly"
                else -> ""
            }
            if (guidanceText.isNotBlank()) {
                Text(
                    text = guidanceText,
                    color = if (warningLevel == PanoramaWarningLevel.None || !active) DiveColors.TextPrimary else Color(0xFFFFD90D),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 52.dp)
                        // The idle instruction belongs to the preview guide, so its outer panel
                        // uses the guide's exact orientation-aware width rather than intrinsic
                        // text width.
                        .then(
                            if (!active && !finalizing) Modifier.width(idlePreviewWidth)
                            else Modifier,
                        )
                        .background(Color(0x33000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

    }
}

internal data class PanoramaPreviewCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** Centre-crops a camera frame so it fills exactly the rectangle between the chevrons. */
internal fun centerCropPanoramaPreview(
    imageWidth: Int,
    imageHeight: Int,
    boxWidth: Float,
    boxHeight: Float,
): PanoramaPreviewCrop {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) {
        return PanoramaPreviewCrop(0, 0, 0, 0)
    }
    val imageAspect = imageWidth.toFloat() / imageHeight
    val boxAspect = boxWidth / boxHeight
    val cropWidth: Int
    val cropHeight: Int
    if (imageAspect > boxAspect) {
        cropHeight = imageHeight
        cropWidth = (imageHeight * boxAspect).roundToInt().coerceIn(1, imageWidth)
    } else {
        cropWidth = imageWidth
        cropHeight = (imageWidth / boxAspect).roundToInt().coerceIn(1, imageHeight)
    }
    return PanoramaPreviewCrop(
        left = (imageWidth - cropWidth) / 2,
        top = (imageHeight - cropHeight) / 2,
        width = cropWidth,
        height = cropHeight,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPanoramaChevron(
    center: Offset,
    direction: PanoramaCorrection,
    color: Color,
) {
    val half = 7.dp.toPx()
    val depth = 7.dp.toPx()
    val stroke = 3.dp.toPx()
    when (direction) {
        PanoramaCorrection.Left -> {
            drawLine(color, Offset(center.x + depth, center.y - half), center, stroke, cap = StrokeCap.Round)
            drawLine(color, center, Offset(center.x + depth, center.y + half), stroke, cap = StrokeCap.Round)
        }
        PanoramaCorrection.Right -> {
            drawLine(color, Offset(center.x - depth, center.y - half), center, stroke, cap = StrokeCap.Round)
            drawLine(color, center, Offset(center.x - depth, center.y + half), stroke, cap = StrokeCap.Round)
        }
        PanoramaCorrection.Up -> {
            drawLine(color, Offset(center.x - half, center.y + depth), center, stroke, cap = StrokeCap.Round)
            drawLine(color, center, Offset(center.x + half, center.y + depth), stroke, cap = StrokeCap.Round)
        }
        PanoramaCorrection.Down -> {
            drawLine(color, Offset(center.x - half, center.y - depth), center, stroke, cap = StrokeCap.Round)
            drawLine(color, center, Offset(center.x + half, center.y - depth), stroke, cap = StrokeCap.Round)
        }
        PanoramaCorrection.None -> Unit
    }
}

/** The movable native Food area is represented by a large, high-contrast radial focus ring. */
@Composable
private fun FoodFocusGuideOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = minOf(size.width, size.height) * 0.31f
        val outer = DiveColors.DiveCyan.copy(alpha = 0.90f)
        val inner = Color.White.copy(alpha = 0.52f)
        drawCircle(color = outer, radius = radius, style = Stroke(2.dp.toPx()))
        drawCircle(color = inner, radius = radius - 4.dp.toPx(), style = Stroke(1.dp.toPx()))
        val tick = 12.dp.toPx()
        drawLine(outer, Offset(center.x - tick, center.y), Offset(center.x + tick, center.y), 2.dp.toPx())
        drawLine(outer, Offset(center.x, center.y - tick), Offset(center.x, center.y + tick), 2.dp.toPx())
    }
}

@Composable
private fun ProOptionsPanel(
    cameraState: CameraState,
    onCommand: (CameraCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = CameraCatalog.optionsMenuSettings(cameraState)
    if (settings.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(cameraState.optionsMenuCursor, settings.size) {
        listState.animateScrollToItem(cameraState.optionsMenuCursor.coerceIn(0, settings.lastIndex))
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth(0.72f)
            .widthIn(min = 280.dp, max = 390.dp)
            // Together with the 48dp rail below, this reproduces the Focus editor's vertical
            // footprint while keeping the horizontal controls visible as originally required.
            .fillMaxHeight(0.60f)
            .background(DiveColors.DeepBlack.copy(alpha = 0.84f), RoundedCornerShape(22.dp))
            .border(1.5.dp, DiveColors.SurfaceBorder.copy(alpha = 0.78f), RoundedCornerShape(22.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = "${cameraState.activeMode.label.uppercase()} OPTIONS",
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            itemsIndexed(settings, key = { _, spec -> spec.id }) { index, spec ->
                val selected = index == cameraState.optionsMenuCursor
                val rawValue = CameraCatalog.currentValue(cameraState, spec)
                BottomEditCard(
                    title = spec.label,
                    value = displaySettingValue(cameraState, spec, rawValue),
                    selected = selected,
                    onClick = { onCommand(CameraCommand.SelectOptionsItem(index)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when {
                        spec.id.endsWith(".save_location") -> SaveLocationAlbumRail(
                            locations = cameraState.recordingSaveLocations,
                            highlightedIndex = cameraState.recordingSaveLocationIndex,
                            activeRelativePath = cameraState.recordingSaveLocation.relativePath,
                            onLocationClick = { locationIndex ->
                                onCommand(CameraCommand.SelectRecordingSaveLocation(locationIndex))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                        )
                        spec.options.isEmpty() -> Text(
                            text = "Unavailable",
                            color = DiveColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        spec.options.size <= 4 -> ToggleOptionDisplay(
                            currentValue = rawValue,
                            options = spec.options,
                            displayTransform = { option ->
                                displaySettingValue(cameraState, spec, option)
                            },
                        )
                        else -> SliderMeterAdjuster(spec = spec, value = rawValue)
                    }
                    when (spec.status) {
                        CameraFeatureStatus.NeedsVerification -> Text(
                            text = "DEVICE CHECK · ${spec.note.orEmpty()}",
                            color = DiveColors.Warning,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        CameraFeatureStatus.Unavailable -> Text(
                            text = "UNAVAILABLE · ${spec.note.orEmpty()}",
                            color = DiveColors.Warning,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        CameraFeatureStatus.Confirmed -> Unit
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "UP/DOWN Select  LEFT/RIGHT Adjust",
            color = DiveColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(start = 8.dp),
        )
    }
}

@Composable
private fun SaveLocationAlbumRail(
    locations: List<RecordingSaveLocation>,
    highlightedIndex: Int,
    activeRelativePath: String,
    onLocationClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(highlightedIndex, locations.size) {
        if (highlightedIndex in locations.indices) {
            listState.animateScrollToItem(highlightedIndex)
        }
    }

    if (locations.isEmpty()) {
        Text(
            text = "No writable media albums",
            color = DiveColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        return
    }

    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        itemsIndexed(
            items = locations,
            key = { _, location -> location.relativePath.lowercase(Locale.ROOT) },
        ) { index, location ->
            val highlighted = index == highlightedIndex
            val active = location.relativePath.trimEnd('/').equals(
                activeRelativePath.trimEnd('/'),
                ignoreCase = true,
            )
            Column(
                modifier = Modifier
                    .width(104.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (highlighted) DiveColors.DiveCyan.copy(alpha = 0.18f)
                        else DiveColors.SurfaceElevated,
                    )
                    .border(
                        width = if (highlighted) 1.5.dp else 1.dp,
                        color = when {
                            highlighted -> DiveColors.DiveCyan
                            active -> DiveColors.Success.copy(alpha = 0.85f)
                            else -> DiveColors.SurfaceBorder.copy(alpha = 0.65f)
                        },
                        shape = RoundedCornerShape(9.dp),
                    )
                    .clickable { onLocationClick(index) }
                    .padding(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(51.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(DiveColors.DeepBlack),
                ) {
                    SaveLocationAlbumCover(location = location, modifier = Modifier.fillMaxSize())
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = if (highlighted) DiveColors.DiveCyan else DiveColors.TextPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .size(17.dp),
                    )
                    if (active) {
                        Text(
                            text = "CURRENT",
                            color = DiveColors.DeepBlack,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(DiveColors.Success, RoundedCornerShape(bottomEnd = 5.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = location.name,
                    color = if (highlighted) DiveColors.DiveCyan else DiveColors.TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (location.mediaCount == 0) "Empty" else
                        "${location.mediaCount} ${if (location.mediaCount == 1) "item" else "items"}",
                    color = DiveColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SaveLocationAlbumCover(
    location: RecordingSaveLocation,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(location.coverContentUri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(context, location.coverContentUri) {
        bitmap = if (location.coverContentUri.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        Uri.parse(location.coverContentUri),
                        Size(256, 160),
                        null,
                    ).asImageBitmap()
                }.getOrNull()
            }
        }
    }

    val cover = bitmap
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = "${location.name} album cover",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.background(DiveColors.SurfaceCard))
    }
}

/**
 * Right-side mode rail using LazyColumn.
 * Always keeps the selected/highlighted item visible (scrolls to it).
 * Fills available vertical space.
 */
@Composable
private fun RightModeRail(
    cameraState: CameraState,
    onCommand: (CameraCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = CameraCatalog.primaryRailEntries
    val highlightedIndex = cameraState.highlightedPrimaryIndex
    val listState = rememberLazyListState()

    // Auto-scroll to keep highlighted item visible
    LaunchedEffect(highlightedIndex) {
        listState.animateScrollToItem(
            index = highlightedIndex.coerceIn(0, items.lastIndex),
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(162.dp)
            .fillMaxHeight()
            .background(DiveColors.DeepBlack.copy(alpha = 0.42f), RoundedCornerShape(24.dp))
            .border(1.dp, DiveColors.SurfaceBorder.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Modes",
            color = DiveColors.TextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(items) { index, entry ->
                val selected = index == highlightedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.16f)
                            else DiveColors.SurfaceCard.copy(alpha = 0.66f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .border(
                            width = if (selected) 1.dp else 0.dp,
                            color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable { onCommand(CameraCommand.ActivateModeRailEntry(index)) }
                        .padding(horizontal = 10.dp, vertical = if (selected) 10.dp else 8.dp),
                ) {
                    Text(
                        text = entry.label,
                        color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                        style = if (selected) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun BottomSettingsTrayLegacy(
    cameraState: CameraState,
    settings: List<CameraSettingSpec>,
    profile: CameraModeProfile,
    onCommand: (CameraCommand) -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .background(DiveColors.DeepBlack.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
            .border(1.5.dp, DiveColors.SurfaceBorder.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (cameraState.settingsEditing) {
            val spec = cameraState.selectedSetting
            if (spec != null) {
                val rawValue = CameraCatalog.currentValue(cameraState, spec)
                val value = displaySettingValue(cameraState, spec, rawValue)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "${spec.label.uppercase()} ADJUSTMENT",
                        color = DiveColors.DiveCyan,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // 1. Value Slider Row
                    val isValueFocused = cameraState.sliderEditTarget == SliderEditTarget.Value
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (isValueFocused) DiveColors.DiveCyan.copy(alpha = 0.12f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = if (isValueFocused) 1.dp else 0.dp,
                                color = if (isValueFocused) DiveColors.DiveCyan else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Value",
                                color = if (isValueFocused) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = value,
                                color = if (isValueFocused) DiveColors.DiveCyan else DiveColors.TextMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        SliderMeterAdjuster(spec = spec, value = rawValue)
                    }

                    // 2. Sensitivity Slider Row
                    if (spec.supportsSensitivity) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val isSensitivityFocused = cameraState.sliderEditTarget == SliderEditTarget.Sensitivity
                        val sensitivity = cameraState.sliderSensitivities[spec.id] ?: SliderSensitivity.DEFAULT
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isSensitivityFocused) DiveColors.DiveCyan.copy(alpha = 0.12f) else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = if (isSensitivityFocused) 1.dp else 0.dp,
                                    color = if (isSensitivityFocused) DiveColors.DiveCyan else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Sensitivity",
                                    color = if (isSensitivityFocused) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Level ${sensitivity.level} Hold",
                                    color = if (isSensitivityFocused) DiveColors.DiveCyan else DiveColors.TextMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            SliderSensitivityMeter(current = sensitivity)
                        }
                    }
                }
            }
        } else {
            val items = CameraCatalog.settingsBarItems(cameraState)
            val listState = rememberLazyListState()

            LaunchedEffect(cameraState.settingsCursor) {
                if (items.isNotEmpty()) {
                    listState.animateScrollToItem(cameraState.settingsCursor.coerceIn(0, items.lastIndex))
                }
            }

            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(items) { index, item ->
                    val selected = cameraState.settingsCursor == index
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.24f)
                                        else DiveColors.SurfaceCard.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        when (item) {
                            is BottomBarItem.ModesButton -> {
                                Text(
                                    text = "🔄 Modes",
                                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            is BottomBarItem.LensShortcut -> {
                                Text(
                                    text = if (item.value == "front") "Front" else item.value.removeSuffix("x"),
                                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            is BottomBarItem.GalleryShortcut -> {
                                LatestCaptureThumbnail(selected) { onCommand(CameraCommand.OpenGallery) }
                            }
                            is BottomBarItem.MoreSettings -> {
                                Text(
                                    text = if (cameraState.showMoreSettings) "⚙️ Less Settings" else "⚙️ More Settings",
                                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            is BottomBarItem.Setting -> {
                                val value = displaySettingValue(cameraState, item.spec, CameraCatalog.currentValue(cameraState, item.spec))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.spec.label,
                                        color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = value,
                                        color = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomSettingsTray(
    cameraState: CameraState,
    onCommand: (CameraCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(
                DiveColors.DeepBlack.copy(alpha = 0.84f),
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            )
            .border(
                1.5.dp,
                DiveColors.SurfaceBorder.copy(alpha = 0.78f),
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (cameraState.settingsEditing) {
            val spec = cameraState.selectedSetting
            if (spec != null) {
                val rawValue = CameraCatalog.currentValue(cameraState, spec)
                val value = displaySettingValue(cameraState, spec, rawValue)
                val sensitivity = cameraState.sliderSensitivities[spec.id] ?: SliderSensitivity.DEFAULT
                val focusAssistSpec = focusAssistSpec(cameraState, spec)
                val focusAssistValue = focusAssistSpec?.let { assistSpec ->
                    displaySettingValue(cameraState, assistSpec, CameraCatalog.currentValue(cameraState, assistSpec))
                }
                val focusCurveSpec = focusCurveSpec(cameraState, spec)
                val focusCurveValue = focusCurveSpec?.let { curveSpec ->
                    CameraCatalog.currentValue(cameraState, curveSpec)
                }
                val focusDirectionSpec = if (spec.id.endsWith(".manual_focus")) {
                    CameraCatalog.focusDirectionSpec(spec.id)
                } else {
                    null
                }
                val focusDirectionValue = focusDirectionSpec?.let { dirSpec ->
                    CameraCatalog.currentValue(cameraState, dirSpec)
                }
                val rampInSpec = if (spec.id.endsWith(".manual_focus")) {
                    CameraCatalog.focusRampSpec(spec.id, inward = true)
                } else {
                    null
                }
                val rampOutSpec = if (spec.id.endsWith(".manual_focus")) {
                    CameraCatalog.focusRampSpec(spec.id, inward = false)
                } else {
                    null
                }
                val rampInValue = rampInSpec?.let { CameraCatalog.currentValue(cameraState, it) }
                val rampOutValue = rampOutSpec?.let { CameraCatalog.currentValue(cameraState, it) }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = spec.label.uppercase(),
                        color = DiveColors.DiveCyan,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (spec.status == CameraFeatureStatus.Unavailable) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .background(DiveColors.Warning.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                .border(1.dp, DiveColors.Warning.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = "UNAVAILABLE",
                                color = DiveColors.Warning,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = spec.note ?: "No working camera pipeline is available.",
                                color = DiveColors.TextPrimary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    ) {
                        BottomEditCard(
                            title = "Value",
                            value = value,
                            selected = cameraState.sliderEditTarget == SliderEditTarget.Value,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SliderMeterAdjuster(spec = spec, value = rawValue)
                        }

                        if (spec.supportsSensitivity) {
                            BottomEditCard(
                                title = "Sensitivity",
                                value = "Level ${sensitivity.level}",
                                selected = cameraState.sliderEditTarget == SliderEditTarget.Sensitivity,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                SliderSensitivityMeter(current = sensitivity)
                            }
                        }

                        if (focusAssistSpec != null && focusAssistValue != null) {
                            BottomEditCard(
                                title = "Focus Assist",
                                value = focusAssistValue,
                                selected = cameraState.sliderEditTarget == SliderEditTarget.FocusAssist,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ToggleOptionDisplay(
                                    currentValue = focusAssistValue,
                                    options = focusAssistSpec.options,
                                )
                            }
                        }

                        if (focusCurveSpec != null && focusCurveValue != null) {
                            BottomEditCard(
                                title = "Focus Curve",
                                value = focusCurveDisplayName(focusCurveValue),
                                selected = cameraState.sliderEditTarget == SliderEditTarget.FocusCurve,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ToggleOptionDisplay(
                                    currentValue = focusCurveValue,
                                    options = focusCurveSpec.options,
                                    displayTransform = ::focusCurveDisplayName,
                                )
                            }
                        }

                        if (focusDirectionSpec != null && focusDirectionValue != null) {
                            BottomEditCard(
                                title = "Wheel Direction",
                                value = focusDirectionValue,
                                selected = cameraState.sliderEditTarget == SliderEditTarget.FocusDirection,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ToggleOptionDisplay(
                                    currentValue = focusDirectionValue,
                                    options = focusDirectionSpec.options,
                                )
                            }
                        }

                        // How quickly a focus PULL travels, per direction. Inward is far to
                        // near, outward is near to far.
                        if (rampInSpec != null && rampInValue != null) {
                            BottomEditCard(
                                title = "Inward Focus Ramp",
                                value = rampInValue,
                                selected = cameraState.sliderEditTarget == SliderEditTarget.FocusRampIn,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                SliderSensitivityMeter(current = SliderSensitivity.of(rampInValue.toIntOrNull() ?: 50))
                            }
                        }

                        if (rampOutSpec != null && rampOutValue != null) {
                            BottomEditCard(
                                title = "Outward Focus Ramp",
                                value = rampOutValue,
                                selected = cameraState.sliderEditTarget == SliderEditTarget.FocusRampOut,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                SliderSensitivityMeter(current = SliderSensitivity.of(rampOutValue.toIntOrNull() ?: 50))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "UP/DOWN Select  LEFT/RIGHT Adjust",
                        color = DiveColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            val items = CameraCatalog.settingsBarItems(cameraState)
            CenteredModesBar(
                items = items,
                cameraState = cameraState,
                onCommand = onCommand,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CenteredModesBar(
    items: List<BottomBarItem>,
    cameraState: CameraState,
    onCommand: (CameraCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modesIndex = items.indexOfFirst { it is BottomBarItem.ModesButton }.coerceAtLeast(0)
    val leftItems = items.take(modesIndex)
    val centerItem = items.getOrNull(modesIndex) ?: BottomBarItem.ModesButton
    val rightItems = items.drop(modesIndex + 1)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        ModesBarSide(
            items = leftItems,
            cameraState = cameraState,
            startIndex = 0,
            alignToEnd = true,
            onCommand = onCommand,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        BottomBarChip(
            item = centerItem,
            cameraState = cameraState,
            selected = cameraState.settingsCursor == modesIndex,
            compact = false,
            onClick = { onCommand(CameraCommand.OpenModeRail) },
        )
        Spacer(modifier = Modifier.width(4.dp))
        ModesBarSide(
            items = rightItems,
            cameraState = cameraState,
            startIndex = modesIndex + 1,
            alignToEnd = false,
            onCommand = onCommand,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModesBarSide(
    items: List<BottomBarItem>,
    cameraState: CameraState,
    startIndex: Int,
    alignToEnd: Boolean,
    onCommand: (CameraCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val packFromCenter = cameraState.activeMode in centerPackedModeBars
    val spreadAcrossSide = cameraState.activeMode in compactNativeModeBars && !packFromCenter
    Box(
        contentAlignment = if (alignToEnd) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = when {
                spreadAcrossSide -> Arrangement.SpaceBetween
                packFromCenter -> Arrangement.spacedBy(CENTER_PACKED_BAR_GAP)
                else -> Arrangement.Start
            },
            verticalAlignment = Alignment.CenterVertically,
            // These shorter rails grow away from the mode chip at one fixed interval on both
            // sides. SpaceBetween made unequal item counts look arbitrarily scattered.
            modifier = if (spreadAcrossSide || (!alignToEnd && !packFromCenter)) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
            },
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    if (spreadAcrossSide || packFromCenter) {
                        // The row arrangement owns every gap for these mode-specific rails.
                    } else if (!alignToEnd && item is BottomBarItem.GalleryShortcut) {
                        // The gallery is an edge action, not another variable-width setting.
                        // Consume whatever room remains so it is always visible at bottom-right.
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
                BottomBarChip(
                    item = item,
                    cameraState = cameraState,
                    selected = cameraState.settingsCursor == startIndex + index,
                    compact = true,
                    onClick = when (item) {
                        is BottomBarItem.MoreSettings -> { { onCommand(CameraCommand.ToggleOptionsMenu) } }
                        is BottomBarItem.GalleryShortcut -> { { onCommand(CameraCommand.OpenGallery) } }
                        else -> null
                    },
                )
            }
        }
    }
}

@Composable
private fun BottomEditCard(
    title: String,
    value: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .background(
                color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.14f) else DiveColors.SurfaceCard.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = if (selected) 5.dp else 3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = value,
                color = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        // Only the card being edited spends vertical space on its meter. With seven cards in
        // the focus menu, drawing them all would push the stack off screen — and a menu the
        // diver has to scroll with gloves on is a menu they cannot use.
        if (selected) {
            Spacer(modifier = Modifier.height(3.dp))
            content()
        }
    }
}

@Composable
private fun BottomBarChip(
    item: BottomBarItem,
    cameraState: CameraState,
    selected: Boolean,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (item is BottomBarItem.GalleryShortcut) {
        LatestCaptureThumbnail(selected, onClick)
        return
    }
    val icon = bottomBarIcon(item)
    val label = bottomBarLabel(item)
    val value = bottomBarValue(item, cameraState)
    val compactDirectExposure = compact &&
        (item as? BottomBarItem.Setting)?.spec?.id in compactExposureSettingIds
    val compactValueOnly = compact &&
        (item as? BottomBarItem.Setting)?.spec?.id in compactValueOnlySettingIds
    val compositionGuide = (item as? BottomBarItem.Setting)
        ?.takeIf { it.spec.id.endsWith(".grid") || it.spec.id.endsWith(".guides") }
        ?.let { CameraCatalog.currentValue(cameraState, it.spec) }
    val horizontalPadding = when {
        compact && cameraState.activeMode in compactNativeModeBars -> 5.dp
        compact -> 7.dp
        else -> 10.dp
    }
    val verticalPadding = if (compact) 3.dp else 5.dp
    val iconSize = when {
        item is BottomBarItem.GalleryShortcut && compact -> 28.dp
        item is BottomBarItem.GalleryShortcut -> 32.dp
        compositionGuide != null && compact -> 22.dp
        compositionGuide != null -> 26.dp
        compact -> 12.dp
        else -> 15.dp
    }
    val textStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val valueStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium

    val shouldStackValue = !value.isNullOrBlank() && " + " in value

    if (shouldStackValue) {
        // Vertical layout for multi-part values like "RAW + JPEG"
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.2f) else DiveColors.SurfaceCard.copy(alpha = 0.54f),
                    shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.46f),
                    shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                )
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                    modifier = Modifier.size(iconSize),
                )
                if (!label.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(if (compact) 4.dp else 6.dp))
                    Text(
                        text = label,
                        color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                        style = textStyle,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Stack each part of the value vertically
            value!!.split(" + ").forEach { part ->
                Text(
                    text = part.trim(),
                    color = if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    } else {
        // Standard horizontal layout
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(
                    if (compactDirectExposure) Modifier.widthIn(min = 54.dp) else Modifier,
                )
                .background(
                    color = if (selected) DiveColors.DiveCyan.copy(alpha = 0.2f) else DiveColors.SurfaceCard.copy(alpha = 0.54f),
                    shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.46f),
                    shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                )
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            val showLeadingGraphic = !compactDirectExposure && !compactValueOnly
            if (showLeadingGraphic) {
                if (compositionGuide != null) {
                    CompositionGuideTypeIcon(
                        guide = compositionGuide,
                        tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                        modifier = Modifier.size(iconSize),
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
            if (showLeadingGraphic && (!label.isNullOrBlank() || !value.isNullOrBlank())) {
                Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
            }
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                    style = textStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!value.isNullOrBlank()) {
                if (!label.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(if (compact) 4.dp else 6.dp))
                }
                Text(
                    text = value,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary,
                    style = valueStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun bottomBarIcon(item: BottomBarItem): ImageVector = when (item) {
    is BottomBarItem.ModesButton -> Icons.Rounded.Autorenew
    is BottomBarItem.GalleryShortcut -> Icons.Rounded.PhotoLibrary
    is BottomBarItem.LensShortcut -> Icons.Rounded.CameraAlt
    is BottomBarItem.MoreSettings -> Icons.Rounded.Tune
    is BottomBarItem.Setting -> when {
        item.spec.id.endsWith(".flash") -> Icons.Rounded.FlashOn
        item.spec.id.endsWith(".megapixels") -> Icons.Rounded.PhotoSizeSelectLarge
        item.spec.id.endsWith(".save_format") -> Icons.Rounded.Image
        item.spec.id.endsWith(".lens") -> Icons.Rounded.CameraAlt
        item.spec.id.endsWith(".manual_focus") -> Icons.Rounded.CenterFocusStrong
        item.spec.id.endsWith(".exposure_compensation") ||
            item.spec.id.endsWith(".exposure_value") ||
            item.spec.id.endsWith(".exposure") -> Icons.Rounded.Exposure
        item.spec.id.endsWith(".guides") || item.spec.id.endsWith(".grid") -> Icons.Rounded.GridOn
        item.spec.id.endsWith(".hdr") || item.spec.id.endsWith(".hdr_log") || item.spec.id.endsWith(".log") -> Icons.Rounded.HdrAuto
        item.spec.id.endsWith(CameraCatalog.SLIDER_ASSIGNMENT_SUFFIX) &&
            item.spec.defaultValue == CameraCatalog.SLIDER_TARGET_ZOOM -> Icons.Rounded.ZoomIn
        item.spec.id.endsWith(".filters") -> Icons.Rounded.Filter
        else -> Icons.Rounded.Tune
    }
}

private fun bottomBarLabel(item: BottomBarItem): String? = when (item) {
    is BottomBarItem.ModesButton -> null
    is BottomBarItem.GalleryShortcut -> null
    is BottomBarItem.LensShortcut -> null
    is BottomBarItem.MoreSettings -> null
    is BottomBarItem.Setting -> if (item.spec.id in compactExposureSettingIds) "EV" else null
}

private fun bottomBarValue(item: BottomBarItem, cameraState: CameraState): String? = when (item) {
    is BottomBarItem.ModesButton -> cameraState.activeMode.label
    is BottomBarItem.GalleryShortcut -> null
    is BottomBarItem.LensShortcut -> formatLensValue(item.value)
    is BottomBarItem.MoreSettings -> null
    is BottomBarItem.Setting -> if (
        item.spec.id.endsWith(".grid") || item.spec.id.endsWith(".guides")
    ) {
        null
    } else {
        val value = CameraCatalog.currentValue(cameraState, item.spec)
        // Editors retain their full names; the quick bar uses compact canonical spellings so
        // every promoted control and Gallery remain visible on the S24 landscape viewport.
        when {
            item.spec.id == "hyperlapse.video_format" && value == "HEVC / H.265" -> "HEVC"
            item.spec.id.endsWith(".resolution") && value == "UHD 4K" -> "4K"
            item.spec.id == "slow_motion.focus_mode" && value == "Continuous AF" -> "AF-C"
            item.spec.id == "slow_motion.focus_mode" && value == "Single AF" -> "AF-S"
            item.spec.id.endsWith(".video_stabilization") && value == "Standard" -> "STD"
            item.spec.id.endsWith(".background_effect") && value == "Big circle" -> "Circle"
            item.spec.id.endsWith(".background_effect") && value in setOf("Color point", "Colour point") -> "Colour"
            item.spec.id.endsWith(".background_effect") && value == "High-key mono" -> "High-key"
            item.spec.id.endsWith(".background_effect") && value == "Low-key mono" -> "Low-key"
            else -> displaySettingValue(cameraState, item.spec, value)
        }
    }
}

private val compactNativeModeBars = setOf(
    CameraModeId.Panorama,
    CameraModeId.Hyperlapse,
    CameraModeId.SlowMotion,
    CameraModeId.PortraitVideo,
    CameraModeId.Night,
    CameraModeId.Food,
    CameraModeId.Video,
    CameraModeId.Portrait,
    CameraModeId.Photo,
)

/** These shorter rails stay visually attached to their centre mode instead of touching the edges. */
private val centerPackedModeBars = setOf(
    CameraModeId.Food,
    CameraModeId.Night,
    CameraModeId.Panorama,
)

private val CENTER_PACKED_BAR_GAP = 6.dp

private val compactExposureSettingIds = setOf(
    "hyperlapse.exposure",
    "slow_motion.exposure",
    "portrait_video.exposure",
    "night.exposure",
    "food.exposure",
    "video.exposure",
    "portrait.exposure",
    "photo.exposure_compensation",
)

private val compactValueOnlySettingIds = setOf(
    "slow_motion.resolution",
    "slow_motion.focus_mode",
    "slow_motion.frame_rate",
    "portrait_video.resolution",
    "portrait_video.manual_focus",
    "portrait_video.background_effect",
    "portrait_video.effect_strength",
    "portrait_video.audio_recording",
    "portrait_video.frame_rate",
    "night.capture_time",
    "night.aspect_ratio",
    "food.color_temperature",
    "food.radial_blur",
    "food.aspect_ratio",
    "video.frame_rate",
    "video.resolution",
    "video.video_format",
    "video.video_stabilization",
    "video.audio_recording",
    "portrait.beauty",
    "portrait.background_effect",
    "portrait.effect_strength",
    "portrait.lighting",
    "portrait.aspect_ratio",
    "photo.save_format",
    "photo.manual_focus",
    "photo.megapixels",
    "photo.aspect_ratio",
    "photo.filters",
)

/** Compact, value-specific glyphs shared by every promoted composition-guide control. */
@Composable
private fun CompositionGuideTypeIcon(
    guide: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (size.minDimension * 0.075f).coerceAtLeast(1f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color = tint,
            start = Offset(w * x1, h * y1),
            end = Offset(w * x2, h * y2),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        fun thirds(phi: Boolean = false) {
            val low = if (phi) 0.382f else 1f / 3f
            val high = if (phi) 0.618f else 2f / 3f
            line(low, 0.08f, low, 0.92f)
            line(high, 0.08f, high, 0.92f)
            line(0.08f, low, 0.92f, low)
            line(0.08f, high, 0.92f, high)
        }

        when (guide) {
            "Off" -> {
                drawRect(tint, style = Stroke(stroke))
                line(0.12f, 0.88f, 0.88f, 0.12f)
            }
            "Rule of Thirds + Center" -> {
                thirds()
                drawCircle(tint, radius = size.minDimension * 0.12f, style = Stroke(stroke))
            }
            "Rule of Thirds" -> thirds()
            "Phi Grid" -> thirds(phi = true)
            "Symmetry" -> {
                line(0.5f, 0.08f, 0.5f, 0.92f)
                line(0.08f, 0.5f, 0.92f, 0.5f)
            }
            "Golden Triangles" -> {
                line(0.05f, 0.08f, 0.95f, 0.92f)
                line(0.05f, 0.92f, 0.49f, 0.49f)
                line(0.95f, 0.08f, 0.69f, 0.69f)
            }
            "Vanishing Point" -> {
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { x ->
                    line(x, 0.05f, 0.5f, 0.52f)
                    line(x, 0.95f, 0.5f, 0.52f)
                }
            }
            "Framing Depth" -> {
                drawRect(
                    color = tint,
                    topLeft = Offset(w * 0.27f, h * 0.25f),
                    size = androidx.compose.ui.geometry.Size(w * 0.46f, h * 0.5f),
                    style = Stroke(stroke),
                )
                line(0.02f, 0.02f, 0.27f, 0.25f)
                line(0.98f, 0.02f, 0.73f, 0.25f)
                line(0.02f, 0.98f, 0.27f, 0.75f)
                line(0.98f, 0.98f, 0.73f, 0.75f)
            }
            "Landscape Depth" -> {
                line(0.05f, 0.38f, 0.95f, 0.38f)
                listOf(0.05f, 0.25f, 0.75f, 0.95f).forEach { x ->
                    line(x, 0.95f, 0.5f, 0.38f)
                }
                line(0.2f, 0.68f, 0.8f, 0.68f)
            }
            "Leading Lines" -> {
                line(0.05f, 0.92f, 0.72f, 0.12f)
                line(0.38f, 0.92f, 0.72f, 0.12f)
                line(0.72f, 0.92f, 0.72f, 0.12f)
            }
            "Lines and Patterns" -> repeat(6) { index ->
                val x = 0.12f + index * 0.15f
                line(x, 0.08f, x, 0.92f)
            }
            "4×4 Grid" -> {
                listOf(0.25f, 0.5f, 0.75f).forEach { position ->
                    line(position, 0.05f, position, 0.95f)
                    line(0.05f, position, 0.95f, position)
                }
            }
            "Square" -> drawRect(
                color = tint,
                topLeft = Offset(w * 0.2f, h * 0.2f),
                size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.6f),
                style = Stroke(stroke),
            )
            "Diagonal" -> {
                line(0.08f, 0.08f, 0.92f, 0.92f)
                line(0.92f, 0.08f, 0.08f, 0.92f)
            }
            else -> {
                // Four Fibonacci choices share the same curve and differ by eye orientation.
                // Keep a small stroke-safe margin, but use the rest of the icon canvas. The old
                // 0.035 / 0.22 curve topped out at a 0.166 radius, so the spiral occupied only
                // one third of an already compact rail glyph and looked like a tiny curl.
                val flipX = guide.contains("Left")
                val flipY = guide.contains("Top")
                val path = Path()
                val sampleCount = 72
                val sweep = (PI * 2.25).toFloat()
                val growth = 0.265f
                val outerRadius = 0.46f
                val innerRadius = outerRadius / exp((growth * sweep).toDouble()).toFloat()
                repeat(sampleCount) { index ->
                    val t = index / (sampleCount - 1f) * sweep
                    val radius = innerRadius * exp((growth * t).toDouble()).toFloat()
                    var x = 0.5f + radius * cos(t)
                    var y = 0.5f + radius * sin(t)
                    if (flipX) x = 1f - x
                    if (flipY) y = 1f - y
                    if (index == 0) path.moveTo(w * x, h * y) else path.lineTo(w * x, h * y)
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

private fun displaySettingValue(cameraState: CameraState?, spec: CameraSettingSpec, value: String): String {
    val logCalibration = cameraState?.takeIf {
        it.activeMode == CameraModeId.ProVideo &&
        cameraState.settingValues["pro_video.log"] == "On" &&
        spec.id == "pro_video.exposure_value"
    }?.let {
        SamsungLogProfile.acquisitionCalibration(
            deviceModel = Build.MODEL.orEmpty(),
            lensValue = it.settingValues["pro_video.lens"],
        )
    }
    val protectedLogExposure = logCalibration != null
    return when {
        spec.id.endsWith(".lens") -> formatLensValue(value)
        spec.id.endsWith(".manual_focus") -> formatFocusValue(cameraState, spec, value)
        spec.id.endsWith(".focus_peaking") -> if (focusLensValue(cameraState, spec) == "0.6x") "Off" else if (value == "On") "On" else "Off"
        // The native Auto readouts: while a scale sits on Auto, the stock chips print what the
        // HAL is actually choosing — the raw metered ISO integer, the metered exposure snapped
        // to the shutter table, the AWB kelvin estimate at 100 K. The "A" prefix keeps the mode
        // legible where the native UI uses a lit Auto button instead. Value-slot text only; the
        // option lists (cameraState == null) still read plain "Auto".
        value == "Auto" && spec.id.endsWith(".iso") ->
            cameraState?.meteredExposure?.iso?.let { "A $it" } ?: value
        value == "Auto" && spec.id.endsWith(".shutter_speed") ->
            cameraState?.meteredExposure?.shutterNs
                ?.let { CameraCatalog.nearestShutterOption(it, spec.options) }
                ?.let { "A $it" } ?: value
        CameraCatalog.isWhiteBalanceAuto(value) && spec.id.endsWith(".white_balance") -> {
            val shutterMode = CameraCatalog.isWhiteBalanceAutoShutter(value)
            val underwaterMode = CameraCatalog.isWhiteBalanceAutoUnderwater(value)
            val shortMode = when {
                shutterMode -> "AS"
                underwaterMode -> "AU"
                else -> "AC"
            }
            cameraState?.meteredExposure?.wbKelvin
                ?.let { CameraCatalog.nearestWhiteBalanceOption(it, spec.options) }
                ?.let { kelvin ->
                    if (!underwaterMode) "$shortMode $kelvin" else {
                        val tint = cameraState.meteredExposure.wbTintDuv
                        if (tint == null) "$shortMode $kelvin" else {
                            "$shortMode $kelvin ${java.lang.String.format(java.util.Locale.US, "%+.3f", tint)}"
                        }
                    }
                }
                ?: when {
                    shutterMode -> "Auto S"
                    underwaterMode -> "Auto U"
                    else -> "Auto C"
                }
        }
        // The native EV meter: with both ISO and shutter manual the compensation index has no
        // authority, so the field turns into a read-only meter of the measured deviation —
        // or an em dash when the vendor meter tag is absent, never a dial that pretends to work.
        cameraState != null && protectedLogExposure && CameraCatalog.evMeterLocked(cameraState, spec) ->
            cameraState.meteredExposure.evTenths
                ?.let { SamsungLogProfile.protectedManualMeterTenths(it, logCalibration) }
                ?.let { "L ${CameraCatalog.evLabel(it.coerceIn(-20, 20))}" } ?: "L —"
        cameraState != null && protectedLogExposure -> "L $value"
        cameraState != null && CameraCatalog.evMeterLocked(cameraState, spec) ->
            cameraState.meteredExposure.evTenths
                ?.let { "M ${CameraCatalog.evLabel(it.coerceIn(-20, 20))}" } ?: "—"
        else -> value
    }
}

private fun formatLensValue(value: String): String = when (value) {
    "0.6x" -> "0.6"
    "1x" -> "1"
    "2x" -> "2"
    "3x" -> "3"
    "5x" -> "5"
    "front" -> "Front"
    else -> value
}

private fun formatFocusValue(cameraState: CameraState?, spec: CameraSettingSpec, value: String): String {
    if (focusLensValue(cameraState, spec) == "0.6x") {
        return "Fixed"
    }
    if (value == "AF") {
        return value
    }
    val numeric = value.toDoubleOrNull() ?: return value
    // Three decimals, matching the 0.005 ladder. At two, 0.005 / 0.010 / 0.015 all read "0.01"
    // and every second click would look like it did nothing.
    return String.format(Locale.US, "%.3f", numeric)
}

private fun focusLensValue(cameraState: CameraState?, spec: CameraSettingSpec): String? {
    if (cameraState == null || !spec.id.endsWith(".manual_focus") && !spec.id.endsWith(".focus_peaking")) {
        return null
    }
    val lensSettingId = spec.id.substringBeforeLast(".") + ".lens"
    return cameraState.settingValues[lensSettingId]
}

private fun focusAssistSpec(
    cameraState: CameraState,
    focusSpec: CameraSettingSpec,
): CameraSettingSpec? {
    val assistSettingId = CameraCatalog.focusAssistSettingId(focusSpec.id) ?: return null
    return CameraCatalog.settingsFor(cameraState.activeMode, cameraState.deviceVariant)
        .firstOrNull { it.id == assistSettingId }
}

private fun focusCurveSpec(
    cameraState: CameraState,
    focusSpec: CameraSettingSpec,
): CameraSettingSpec? {
    val curveSettingId = CameraCatalog.focusCurveSettingId(focusSpec.id) ?: return null
    return CameraCatalog.settingsFor(cameraState.activeMode, cameraState.deviceVariant)
        .firstOrNull { it.id == curveSettingId }
}

private fun focusCurveDisplayName(value: String): String = when (value) {
    "Linear" -> "Linear"
    "SquareRoot" -> "Sq Root"
    "Logarithmic" -> "Log"
    else -> value
}

@Composable
private fun VerticalSliderMeter(
    spec: CameraSettingSpec,
    value: String,
    modifier: Modifier = Modifier,
) {
    val options = spec.options
    val currentIndex = options.indexOf(value).coerceAtLeast(0)
    val visibleRange = visibleWindow(options.size, currentIndex, maxVisible = 5)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(DiveColors.DeepBlack.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
            .border(1.5.dp, DiveColors.SurfaceBorder.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .width(160.dp)
    ) {
        Text(
            text = spec.label.uppercase(),
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(180.dp)
        ) {
            // Sleek vertical track
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Gray.copy(alpha = 0.3f))
            ) {
                val fraction = if (options.size <= 1) 1f else currentIndex.toFloat() / (options.lastIndex.toFloat())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0.02f, 1f))
                        .background(DiveColors.DiveCyan, RoundedCornerShape(999.dp))
                )
            }

            // 5 closest options
            Column(
                verticalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxHeight()
            ) {
                for (i in visibleRange.reversed()) {
                    val opt = options[i]
                    val displayOpt = displaySettingValue(null, spec, opt)
                    val selected = i == currentIndex
                    Text(
                        text = displayOpt,
                        color = if (selected) DiveColors.DiveCyan else DiveColors.TextSecondary,
                        style = if (selected) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSensitivitySelector(
    current: SliderSensitivity,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 4.dp).fillMaxWidth(),
    ) {
        Text(
            text = "${current.level}",
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "/ ${SliderSensitivity.MAX.level}",
            color = DiveColors.TextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SliderMeterAdjuster(
    spec: CameraSettingSpec,
    value: String,
) {
    val index = spec.options.indexOf(value).coerceAtLeast(0)
    val totalSteps = spec.options.size
    val fraction = if (totalSteps <= 1) 1f else index.toFloat() / (spec.options.lastIndex.toFloat())

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(0.94f)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(DiveColors.DeepBlack.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(12.dp)
                    .background(DiveColors.DiveCyan, RoundedCornerShape(999.dp))
            )

            // Per-rung dots only while they stay legible at 4dp — sixteen across the meter is the
            // limit. This is now a pure legibility bound: the value ladders (ISO 86, white balance
            // 114, shutter 206) are far past it and render as a continuous bar, which is the right
            // read for a scale you sweep rather than count.
            if (totalSteps in 2..16) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                ) {
                    repeat(totalSteps) { step ->
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (step <= index) DiveColors.DeepBlack.copy(alpha = 0.5f)
                                    else DiveColors.TextMuted.copy(alpha = 0.6f)
                                )
                        )
                    }
                }
            }
        }

        if (totalSteps in 2..7) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp)
            ) {
                spec.options.forEachIndexed { optIndex, opt ->
                    Text(
                        text = displaySettingValue(null, spec, opt),
                        color = if (optIndex == index) DiveColors.DiveCyan else DiveColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (optIndex == index) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Toggle-style option display for binary/tri-state settings.
 * Shows all options as pills, with the selected one highlighted.
 * Used for Focus Assist (On/Off) and Focus Curve (Linear/SqRoot/Log).
 */
@Composable
private fun ToggleOptionDisplay(
    currentValue: String,
    options: List<String>,
    displayTransform: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == currentValue
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) DiveColors.DiveCyan.copy(alpha = 0.85f)
                        else DiveColors.DeepBlack.copy(alpha = 0.5f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            ) {
                Text(
                    text = displayTransform(option),
                    color = if (isSelected) DiveColors.DeepBlack else DiveColors.TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecordingBadge(
    visible: Boolean,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val durationMs by RecordingClock.durationMs
        val seconds = durationMs / 1000
        val clock = "%d:%02d".format(seconds / 60, seconds % 60)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    if (paused) DiveColors.Warning.copy(alpha = 0.92f)
                    else DiveColors.Critical.copy(alpha = 0.88f),
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FiberManualRecord,
                contentDescription = null,
                tint = if (paused) DiveColors.DeepBlack else DiveColors.TextPrimary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (paused) "PAUSED $clock" else "REC $clock",
                color = if (paused) DiveColors.DeepBlack else DiveColors.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HyperlapseRecordingBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val elapsedMs by RecordingClock.durationMs
        val outputMs by RecordingClock.playbackDurationMs
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(DiveColors.Critical.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = "${hyperlapseClockText(outputMs)} (${hyperlapseClockText(elapsedMs)})",
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal fun hyperlapseClockText(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d:%02d".format(
        seconds / 3_600L,
        (seconds / 60L) % 60L,
        seconds % 60L,
    )
}

/** Paused-session actions and save destination, with housing and touch sharing core selection. */
@Composable
private fun RecordingPausedChooser(
    cameraState: CameraState,
    onCommand: (CameraCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedAction = cameraState.recordingPausedAction
    val previewVisible = cameraState.recordingPreviewVisible
    Box(
        modifier = modifier.alpha(
            if (previewVisible) RECORDING_PREVIEW_MENU_ALPHA else 1f,
        ),
    ) {
        if (cameraState.recordingLocationChooserVisible) {
            if (cameraState.recordingSaveConfirmationVisible) {
                val destination = cameraState.recordingSaveLocations
                    .getOrNull(cameraState.recordingSaveLocationIndex)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(DiveColors.DeepBlack.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "SAVE TO ${destination?.name ?: "SELECTED ALBUM"}?",
                        color = DiveColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RecordingChoiceChip(
                            label = "BACK",
                            selected = cameraState.recordingSaveConfirmationAction ==
                                RecordingSaveConfirmationAction.Back,
                            accent = DiveColors.DiveCyan,
                            onClick = { onCommand(CameraCommand.Back) },
                        )
                        RecordingChoiceChip(
                            label = "CONFIRM",
                            selected = cameraState.recordingSaveConfirmationAction ==
                                RecordingSaveConfirmationAction.Confirm,
                            accent = DiveColors.Success,
                            onClick = {
                                onCommand(
                                    CameraCommand.SelectRecordingSaveLocation(
                                        cameraState.recordingSaveLocationIndex,
                                    ),
                                )
                            },
                        )
                    }
                }
                return@Box
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(DiveColors.DeepBlack.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "CHOOSE SAVE ALBUM",
                    color = DiveColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SaveLocationAlbumRail(
                    locations = cameraState.recordingSaveLocations,
                    highlightedIndex = cameraState.recordingSaveLocationIndex,
                    activeRelativePath = cameraState.recordingSaveLocation.relativePath,
                    onLocationClick = { index ->
                        onCommand(CameraCommand.OpenRecordingSaveLocationConfirmation(index))
                    },
                    modifier = Modifier
                        .width(720.dp)
                        .height(96.dp),
                )
            }
            return@Box
        }

        Layout(
            content = {
                RecordingBadge(visible = true, paused = true)

                Box(modifier = Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp)) {
                    RecordingChoiceChip(
                        label = "SAVE TO · ${cameraState.recordingSaveLocation.name}",
                        selected = cameraState.recordingLocationFocused,
                        accent = DiveColors.DiveCyan,
                        onClick = { onCommand(CameraCommand.OpenRecordingSaveLocationChooser) },
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    val actionFocused = !cameraState.recordingLocationFocused
                    RecordingChoiceChip(
                        label = if (previewVisible) "PAUSE" else "PREVIEW",
                        selected = actionFocused && selectedAction == RecordingPausedAction.Preview,
                        accent = DiveColors.DiveCyan,
                        onClick = { onCommand(CameraCommand.PreviewVideoRecording) },
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    RecordingChoiceChip(
                        label = "RESUME",
                        selected = actionFocused && selectedAction == RecordingPausedAction.Resume,
                        accent = DiveColors.Success,
                        onClick = { onCommand(CameraCommand.ResumeVideoRecording) },
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    RecordingChoiceChip(
                        label = "STOP",
                        selected = actionFocused && selectedAction == RecordingPausedAction.Stop,
                        accent = DiveColors.Warning,
                        onClick = { onCommand(CameraCommand.StopVideoRecording) },
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    RecordingChoiceChip(
                        label = "DELETE",
                        selected = actionFocused && selectedAction == RecordingPausedAction.Delete,
                        accent = DiveColors.Critical,
                        critical = true,
                        onClick = { onCommand(CameraCommand.DeleteVideoRecording) },
                    )
                }

                Box(
                    modifier = Modifier.background(
                        DiveColors.DeepBlack.copy(alpha = 0.88f),
                        RoundedCornerShape(14.dp),
                    ),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val pausedBadge = measurables[0].measure(looseConstraints)
            val savePanel = measurables[1].measure(looseConstraints)
            val actionRail = measurables[2].measure(looseConstraints)
            val panelWidth = maxOf(savePanel.width, actionRail.width)
            val panelHeight = savePanel.height + actionRail.height
            val panelBackground = measurables[3].measure(Constraints.fixed(panelWidth, panelHeight))
            val railX = (constraints.maxWidth - actionRail.width) / 2
            val railY = (constraints.maxHeight - actionRail.height) / 2
            val saveX = (constraints.maxWidth - savePanel.width) / 2
            val saveY = railY - savePanel.height
            val panelX = (constraints.maxWidth - panelWidth) / 2
            val badgeX = (constraints.maxWidth - pausedBadge.width) / 2
            val badgeY = saveY - 8.dp.roundToPx() - pausedBadge.height

            layout(constraints.maxWidth, constraints.maxHeight) {
                panelBackground.placeRelative(panelX, saveY)
                pausedBadge.placeRelative(badgeX, badgeY)
                savePanel.placeRelative(saveX, saveY)
                actionRail.placeRelative(railX, railY)
            }
        }
    }
}

/** Keep paused-recording actions locatable without obscuring the video being reviewed. */
private const val RECORDING_PREVIEW_MENU_ALPHA = 0.20f

@Composable
private fun RecordingChoiceChip(
    label: String,
    selected: Boolean,
    accent: Color,
    critical: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) accent else DiveColors.SurfaceElevated,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = if (selected) {
                if (critical) DiveColors.TextPrimary else DiveColors.DeepBlack
            } else {
                DiveColors.TextSecondary
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PanoramaReviewChooser(
    cameraState: CameraState,
    onCommand: (CameraCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(DiveColors.DeepBlack.copy(alpha = 0.9f), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(
                text = "PANORAMA READY",
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingChoiceChip(
                    label = "SAVE",
                    selected = cameraState.panoramaReviewAction == PanoramaReviewAction.Save,
                    accent = DiveColors.Success,
                    onClick = { onCommand(CameraCommand.SavePanorama) },
                )
                Spacer(modifier = Modifier.width(10.dp))
                RecordingChoiceChip(
                    label = "DELETE",
                    selected = cameraState.panoramaReviewAction == PanoramaReviewAction.Delete,
                    accent = DiveColors.Critical,
                    critical = true,
                    onClick = { onCommand(CameraCommand.DeletePanorama) },
                )
            }
        }
    }
}

@Composable
private fun PanoramaReviewPreview(modifier: Modifier = Modifier) {
    val bitmap by PanoramaCaptureState.reviewBitmap
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(DiveColors.DeepBlack),
    ) {
        bitmap?.takeUnless(Bitmap::isRecycled)?.let { panorama ->
            Image(
                bitmap = panorama.asImageBitmap(),
                contentDescription = "Completed panorama preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            text = "Preparing preview…",
            color = DiveColors.TextSecondary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun RecordingSegmentPreview(modifier: Modifier = Modifier) {
    val uri by RecordingClock.reviewUri
    val finalizing by RecordingClock.reviewFinalizing
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(DiveColors.DeepBlack),
    ) {
        if (uri != null) {
            LoopingVideo(uri = uri!!, modifier = Modifier.fillMaxSize(), playbackSpeed = RecordingClock.reviewPlaybackSpeed.value)
        } else {
            Text(
                text = if (finalizing) "Finalizing preview…" else "Preview unavailable",
                color = DiveColors.TextSecondary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
internal fun LoopingVideo(
    uri: android.net.Uri,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    playbackSpeed: Float = 1f,
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
) {
    AndroidView(
        factory = { context ->
            LoopingVideoTextureView(context).apply {
                setProgressListener(onProgress)
                play(uri, playing, playbackSpeed)
            }
        },
        update = { view ->
            view.setProgressListener(onProgress)
            view.play(uri, playing, playbackSpeed)
        },
        modifier = modifier,
    )
}

@Composable
private fun ZoomOverlay(
    zoomFactor: Double,
    modifier: Modifier = Modifier,
) {
    var showZoom by remember { mutableStateOf(false) }
    var lastZoom by remember { mutableStateOf(1.0) }

    LaunchedEffect(zoomFactor) {
        if (zoomFactor != lastZoom) {
            lastZoom = zoomFactor
            showZoom = true
            delay(1400)
            showZoom = false
        }
    }

    AnimatedVisibility(visible = showZoom, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            text = "x%.1f".format(zoomFactor),
            color = DiveColors.DiveCyan,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(DiveColors.DeepBlack.copy(alpha = 0.66f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CameraPreviewPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack),
    )
}

private fun visibleWindow(size: Int, highlightedIndex: Int, maxVisible: Int = 5): IntRange {
    if (size <= 0) {
        return IntRange.EMPTY
    }
    if (size <= maxVisible) {
        return 0..(size - 1)
    }
    val half = maxVisible / 2
    val start = (highlightedIndex - half).coerceIn(0, size - maxVisible)
    return start..(start + maxVisible - 1)
}

@Composable
private fun SliderSensitivityMeter(
    current: SliderSensitivity,
    modifier: Modifier = Modifier,
) {
    val totalSteps = SliderSensitivity.MAX.level
    val index = current.level - 1
    val fraction = index.toFloat() / (totalSteps - 1).toFloat()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(0.94f)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(DiveColors.DeepBlack.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                    .height(8.dp)
                    .background(DiveColors.DiveCyan, RoundedCornerShape(999.dp))
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        ) {
            Text(
                text = "1",
                color = if (current.level <= 10) DiveColors.DiveCyan else DiveColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (current.level <= 10) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = "${current.level}",
                color = DiveColors.DiveCyan,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "100",
                color = if (current.level >= 90) DiveColors.DiveCyan else DiveColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (current.level >= 90) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
