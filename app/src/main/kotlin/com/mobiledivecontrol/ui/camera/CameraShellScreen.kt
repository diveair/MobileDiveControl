package com.mobiledivecontrol.ui.camera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.rounded.HdrAuto
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import java.util.Locale

@Composable
fun CameraShellScreen(
    cameraState: CameraState,
    safetyState: SafetyState,
    cameraPermissionGranted: Boolean = false,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
    effects: List<PlatformEffect> = emptyList(),
    onEffectsConsumed: () -> Unit = {},
    onDetectedLenses: ((List<String>) -> Unit)? = null,
    onCapabilities: ((com.mobiledivecontrol.core.CameraCapabilities) -> Unit)? = null,
    onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null,
    onPointingGesture: ((PointingGesture) -> Unit)? = null,
    onCameraCommand: (CameraCommand) -> Unit = {},
    headingDegrees: Double? = null,
    modifier: Modifier = Modifier,
) {
    val settings = CameraCatalog.settingsFor(cameraState.activeMode, cameraState.deviceVariant)
    val settingsVisible = settings.isNotEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        // Full-screen camera preview
        if (cameraPermissionGranted && lifecycleOwner != null) {
            StateDrivenCameraPreview(
                lifecycleOwner = lifecycleOwner,
                cameraState = cameraState,
                safetyState = safetyState,
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

        CaptureGuideOverlay(
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize(),
        )
        ModeGuideOverlay(
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize(),
        )

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

        // Right side: mode rail (only visible when in ModeRail zone)
        AnimatedVisibility(
            visible = cameraState.focusedZone == CameraUiZone.ModeRail,
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
                !PanoramaCaptureState.finalizing.value,
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
                !cameraState.recordingPaused,
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
    }
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
            val guide = settings.firstOrNull { it.id == "panorama.guide" } ?: return
            val active by PanoramaCaptureState.active
            val finalizing by PanoramaCaptureState.finalizing
            val progress by PanoramaCaptureState.progress
            val movingTooFast by PanoramaCaptureState.movingTooFast
            val detectedDirection by PanoramaCaptureState.direction
            val message by PanoramaCaptureState.message
            val referenceFrame by PanoramaCaptureState.referenceFrame
            if (CameraCatalog.currentValue(cameraState, guide) != "On" && !active && !finalizing) return
            val direction = settings.firstOrNull { it.id == "panorama.direction" }
                ?.let { CameraCatalog.currentValue(cameraState, it) }
                ?: "Auto"
            PanoramaGuideOverlay(
                direction = if (active || finalizing) detectedDirection else direction,
                active = active,
                finalizing = finalizing,
                progress = progress,
                movingTooFast = movingTooFast,
                message = message,
                referenceFrame = referenceFrame,
                modifier = modifier,
            )
        }
        CameraModeId.Food -> {
            val blur = settings.firstOrNull { it.id == "food.radial_blur" } ?: return
            if (CameraCatalog.currentValue(cameraState, blur) != "On") return
            FoodFocusGuideOverlay(modifier = modifier)
        }
        else -> Unit
    }
}

/**
 * Start-frame window, live sweep progress and speed warning modelled on Samsung Camera's
 * Panorama guide. The first shutter starts frame collection; the second completes the stitch.
 */
@Composable
private fun PanoramaGuideOverlay(
    direction: String,
    active: Boolean,
    finalizing: Boolean,
    progress: Float,
    movingTooFast: Boolean,
    message: String,
    referenceFrame: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val horizontal = direction == "Auto" || direction == "Left" || direction == "Right"
        if (active && referenceFrame != null) {
            Image(
                bitmap = referenceFrame.asImageBitmap(),
                contentDescription = "Panorama starting frame",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(
                        if (horizontal) {
                            Modifier.width(136.dp).height(82.dp)
                        } else {
                            Modifier.width(82.dp).height(136.dp)
                        },
                    )
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val guideWidth = if (horizontal) {
                minOf(260.dp.toPx(), size.width * 0.58f)
            } else {
                minOf(96.dp.toPx(), size.width * 0.30f)
            }
            val guideHeight = if (horizontal) {
                minOf(96.dp.toPx(), size.height * 0.42f)
            } else {
                minOf(260.dp.toPx(), size.height * 0.68f)
            }
            val left = (size.width - guideWidth) / 2f
            val top = (size.height - guideHeight) / 2f
            val side = if (horizontal) guideWidth * 0.23f else guideWidth
            val end = if (horizontal) guideHeight else guideHeight * 0.19f
            val stroke = 2.dp.toPx()
            val border = Color.White.copy(alpha = 0.90f)
            val shade = DiveColors.DeepBlack.copy(alpha = 0.48f)

            drawRoundRect(
                color = border,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(guideWidth, guideHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                style = Stroke(stroke),
            )
            if (horizontal) {
                drawRect(shade, Offset(left, top), androidx.compose.ui.geometry.Size(side, guideHeight))
                drawRect(
                    shade,
                    Offset(left + guideWidth - side, top),
                    androidx.compose.ui.geometry.Size(side, guideHeight),
                )
                drawLine(border, Offset(left + side, top), Offset(left + side, top + guideHeight), stroke)
                drawLine(
                    border,
                    Offset(left + guideWidth - side, top),
                    Offset(left + guideWidth - side, top + guideHeight),
                    stroke,
                )
                fun chevron(cx: Float, pointsRight: Boolean, emphasized: Boolean) {
                    val dx = 14.dp.toPx() * if (pointsRight) 1f else -1f
                    val dy = 18.dp.toPx()
                    val colour = if (emphasized) DiveColors.DiveCyan else border
                    drawLine(colour, Offset(cx - dx, center.y - dy), Offset(cx, center.y), stroke * 2f)
                    drawLine(colour, Offset(cx, center.y), Offset(cx - dx, center.y + dy), stroke * 2f)
                }
                chevron(
                    left + side * 0.52f,
                    pointsRight = false,
                    emphasized = direction == "Auto" || direction == "Left",
                )
                chevron(
                    left + guideWidth - side * 0.52f,
                    pointsRight = true,
                    emphasized = direction == "Auto" || direction == "Right",
                )
            } else {
                drawRect(shade, Offset(left, top), androidx.compose.ui.geometry.Size(guideWidth, end))
                drawRect(
                    shade,
                    Offset(left, top + guideHeight - end),
                    androidx.compose.ui.geometry.Size(guideWidth, end),
                )
                drawLine(border, Offset(left, top + end), Offset(left + guideWidth, top + end), stroke)
                drawLine(
                    border,
                    Offset(left, top + guideHeight - end),
                    Offset(left + guideWidth, top + guideHeight - end),
                    stroke,
                )
                fun chevron(cy: Float, pointsDown: Boolean, emphasized: Boolean) {
                    val dx = 18.dp.toPx()
                    val dy = 14.dp.toPx() * if (pointsDown) 1f else -1f
                    val colour = if (emphasized) DiveColors.DiveCyan else border
                    drawLine(colour, Offset(center.x - dx, cy - dy), Offset(center.x, cy), stroke * 2f)
                    drawLine(colour, Offset(center.x, cy), Offset(center.x + dx, cy - dy), stroke * 2f)
                }
                chevron(top + end * 0.52f, pointsDown = false, emphasized = direction == "Up")
                chevron(top + guideHeight - end * 0.52f, pointsDown = true, emphasized = direction == "Down")
            }

            if (active) {
                val trackStart = if (horizontal) {
                    Offset(left + side, center.y)
                } else {
                    Offset(center.x, top + end)
                }
                val trackEnd = if (horizontal) {
                    Offset(left + guideWidth - side, center.y)
                } else {
                    Offset(center.x, top + guideHeight - end)
                }
                val directedProgress = if (direction == "Left" || direction == "Up") 1f - progress else progress
                val marker = Offset(
                    trackStart.x + (trackEnd.x - trackStart.x) * directedProgress,
                    trackStart.y + (trackEnd.y - trackStart.y) * directedProgress,
                )
                drawLine(border.copy(alpha = 0.45f), trackStart, trackEnd, stroke * 2f)
                drawLine(DiveColors.DiveCyan, if (direction == "Left" || direction == "Up") trackEnd else trackStart, marker, stroke * 3f)
                drawCircle(
                    color = if (movingTooFast) DiveColors.Warning else DiveColors.DiveCyan,
                    radius = 8.dp.toPx(),
                    center = marker,
                )
            }
        }
        if (active || finalizing) {
            Text(
                text = message,
                color = if (movingTooFast) DiveColors.Warning else DiveColors.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(DiveColors.DeepBlack.copy(alpha = 0.78f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
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
                    if (spec.status == CameraFeatureStatus.NeedsVerification) {
                        Text(
                            text = "DEVICE CHECK · ${spec.note.orEmpty()}",
                            color = DiveColors.Warning,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 3.dp),
                        )
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
            val items = CameraCatalog.settingsBarItems(
                cameraState.activeMode,
                cameraState.deviceVariant,
                cameraState.showMoreSettings,
                detectedLenses = cameraState.detectedLenses,
            )
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
                                Text(
                                    text = "Gallery",
                                    color = if (selected) DiveColors.TextPrimary else DiveColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
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

                    Column(
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
            val items = CameraCatalog.settingsBarItems(
                cameraState.activeMode,
                cameraState.deviceVariant,
                cameraState.showMoreSettings,
                detectedLenses = cameraState.detectedLenses,
            )
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
    Box(
        contentAlignment = if (alignToEnd) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                BottomBarChip(
                    item = item,
                    cameraState = cameraState,
                    selected = cameraState.settingsCursor == startIndex + index,
                    compact = true,
                    onClick = if (item is BottomBarItem.MoreSettings) {
                        { onCommand(CameraCommand.ToggleOptionsMenu) }
                    } else {
                        null
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
    val icon = bottomBarIcon(item)
    val label = bottomBarLabel(item)
    val value = bottomBarValue(item, cameraState)
    val horizontalPadding = if (compact) 7.dp else 10.dp
    val verticalPadding = if (compact) 3.dp else 5.dp
    val iconSize = when {
        item is BottomBarItem.GalleryShortcut && compact -> 28.dp
        item is BottomBarItem.GalleryShortcut -> 32.dp
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
            if (item is BottomBarItem.GalleryShortcut) {
                GalleryChipPreview(
                    selected = selected,
                    size = iconSize,
                    captureCounter = cameraState.captureCounter,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
                    modifier = Modifier.size(iconSize),
                )
            }
            if (!label.isNullOrBlank() || !value.isNullOrBlank()) {
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
        item.spec.id.endsWith(".exposure_compensation") || item.spec.id.endsWith(".exposure_value") -> Icons.Rounded.Exposure
        item.spec.id.endsWith(".hdr") || item.spec.id.endsWith(".hdr_log") || item.spec.id.endsWith(".log") -> Icons.Rounded.HdrAuto
        item.spec.id.endsWith(".filters") -> Icons.Rounded.Filter
        else -> Icons.Rounded.Tune
    }
}

private fun bottomBarLabel(item: BottomBarItem): String? = when (item) {
    is BottomBarItem.ModesButton -> null
    is BottomBarItem.GalleryShortcut -> null
    is BottomBarItem.LensShortcut -> null
    is BottomBarItem.MoreSettings -> null
    is BottomBarItem.Setting -> null
}

private fun bottomBarValue(item: BottomBarItem, cameraState: CameraState): String? = when (item) {
    is BottomBarItem.ModesButton -> cameraState.activeMode.label
    is BottomBarItem.GalleryShortcut -> null
    is BottomBarItem.LensShortcut -> formatLensValue(item.value)
    is BottomBarItem.MoreSettings -> null
    is BottomBarItem.Setting -> displaySettingValue(cameraState, item.spec, CameraCatalog.currentValue(cameraState, item.spec))
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
private fun GalleryChipPreview(
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    captureCounter: Int = 0,
) {
    val thumbnail = rememberLatestGalleryThumbnail(captureCounter)

    if (thumbnail != null) {
        Image(
            bitmap = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder.copy(alpha = 0.6f),
                    shape = CircleShape,
                ),
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            tint = if (selected) DiveColors.DiveCyan else DiveColors.TextMuted,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun rememberLatestGalleryThumbnail(refreshKey: Int = 0): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    var thumbnail by remember(context) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(context, refreshKey) {
        // Small delay after capture to let MediaStore index the new file
        if (refreshKey > 0) kotlinx.coroutines.delay(500)
        thumbnail = loadLatestGalleryThumbnail(context)?.asImageBitmap()
    }

    return thumbnail
}

private fun loadLatestGalleryThumbnail(context: Context): Bitmap? {
    return try {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = buildString {
            append("(")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=? OR ")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=?)")
        }
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        context.contentResolver.query(filesUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
            val contentUri = ContentUris.withAppendedId(filesUri, id)
            context.contentResolver.loadThumbnail(contentUri, Size(96, 96), null)
        }
    } catch (_: Exception) {
        null
    }
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
    Box(modifier = modifier) {
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
private fun RecordingSegmentPreview(modifier: Modifier = Modifier) {
    val uri by RecordingClock.reviewUri
    val finalizing by RecordingClock.reviewFinalizing
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(DiveColors.DeepBlack),
    ) {
        if (uri != null) {
            LoopingVideo(uri = uri!!, modifier = Modifier.fillMaxSize())
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
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
) {
    AndroidView(
        factory = { context ->
            LoopingVideoTextureView(context).apply {
                setProgressListener(onProgress)
                play(uri, playing)
            }
        },
        update = { view ->
            view.setProgressListener(onProgress)
            view.play(uri, playing)
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
