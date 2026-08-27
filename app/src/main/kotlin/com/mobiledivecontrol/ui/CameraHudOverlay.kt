package com.mobiledivecontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.core.NavigationArrowMesh
import com.mobiledivecontrol.core.ProjectedArrowPoint
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.components.ConnectionStatus
import com.mobiledivecontrol.ui.components.DepthGauge
import com.mobiledivecontrol.ui.components.DualBatteryIndicator
import com.mobiledivecontrol.ui.components.HousingLinkBanner
import com.mobiledivecontrol.ui.components.SealCheckIndicator
import com.mobiledivecontrol.ui.components.VacuumCountdownChip
import com.mobiledivecontrol.platform.CompassReading
import com.mobiledivecontrol.core.HeadingMath
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun CameraHudOverlay(
    state: AppState,
    useMetric: Boolean = true,
    modifier: Modifier = Modifier,
    bluetoothEnabled: Boolean = true,
    compassReading: CompassReading = CompassReading(),
    targetHeading: Double? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()

        // The save-album grid is a true modal owned by CameraShellScreen. Let it cover the
        // camera HUD cleanly; the paused action rail restores every status pill when it closes.
        if (state.camera.recordingLocationChooserVisible) return@Box

        OverlayPill(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DualBatteryIndicator(
                    housingPercent = state.housing.batteryPercent,
                    phonePercent = state.phoneBatteryPercent,
                )
                Spacer(modifier = Modifier.width(10.dp))
                ConnectionStatus(bleState = state.bleConnectionState)
            }
        }

        // The vacuum cluster owns top-centre: label, hold timer and the live reading in one
        // glance. Temperature moved down into the depth pill — the swap the diver asked for,
        // and the cluster needs the width more than a single degree figure ever did.
        //
        // The wrapper Box is measured even when the chip inside renders nothing, so the seal
        // chip's anchor below collapses back to the default the moment the cluster disappears.
        var clusterHeightPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .onSizeChanged { clusterHeightPx = it.height },
        ) {
            VacuumCountdownChip(
                safety = state.safety,
                housingConnected = state.housing.connected,
            )
        }

        OverlayPill(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 16.dp),
        ) {
            Text(
                text = rememberClockText(),
                color = DiveColors.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }

        val bottomControlMenuOpen = state.mode in setOf(AppMode.CameraLive, AppMode.CameraAdjust) &&
            (state.camera.settingsEditing || state.camera.showMoreSettings)
        val bottomPadding = cameraReadoutBottomPadding(state.mode, state.camera)

        // Produce the projected arrow and its lock state once. Both the arrow and numeric heading
        // consume this same result, so their colour cannot disagree at the tolerance boundary.
        val targetArrowMesh = if (
            state.mode == AppMode.CameraLive || state.mode == AppMode.CameraAdjust
        ) {
            compassReading.cameraBasis?.let { cameraBasis ->
                targetHeading?.let { heading ->
                    HeadingMath.navigationArrowMesh(cameraBasis, heading)
                }
            }
        } else {
            null
        }
        val headingTargetSynchronized = targetArrowMesh?.yawErrorDegrees?.let { turn ->
            abs(turn) < TARGET_HEADING_SYNC_TOLERANCE_DEGREES
        } == true

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (targetArrowMesh != null && targetHeading != null) {
                TargetHeadingArrow(
                    mesh = targetArrowMesh,
                    targetHeading = targetHeading,
                    onHeading = headingTargetSynchronized,
                    compact = bottomControlMenuOpen,
                )
                Spacer(modifier = Modifier.height(if (bottomControlMenuOpen) 1.dp else 3.dp))
            }
            OverlayPill(compact = bottomControlMenuOpen) {
                DepthGauge(
                    waterPressureKpa = state.safety.waterPressureKpa,
                    surfaceAmbientKpa = state.safety.surfaceAmbientKpa,
                    useMetric = useMetric,
                    temperatureCelsius = state.safety.waterTemperatureC,
                    headingDegrees = compassReading.headingDegrees,
                    headingTargetSynchronized = headingTargetSynchronized,
                )
            }
        }

        // Offset below the link banner rather than layered with it. Draw order would decide the
        // winner if these overlapped, and a seal failure is a higher-priority alert than a link
        // advisory — so they are given separate space instead and both stay readable. The
        // indicator now owns the whole frame because its two ask-me stages render dead-centre;
        // the top padding only steers its top-anchored elements.
        val linkBannerVisible = state.bleConnectionState != BleConnectionState.Ready
        // Anchor the seal chip to the vacuum cluster's real bottom edge, not a guess: the cluster
        // is one line tall in the cap wait and two lines while the hold counts, and "SEAL PASSED"
        // belongs directly under the reading it certifies in both shapes.
        val density = LocalDensity.current
        val clusterBottom = with(density) { clusterHeightPx.toDp() }
        val sealTop = if (clusterHeightPx > 0) 16.dp + clusterBottom + SEAL_CLUSTER_GAP else SEAL_TOP
        SealCheckIndicator(
            safety = state.safety,
            housingConnected = state.housing.connected,
            topPadding = if (linkBannerVisible) SEAL_STACKED_TOP else sealTop,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp),
        )

        // Drawn last so nothing can cover it, and it disappears entirely once the link is Ready.
        // Dead-centre when a vacuum is already established (held, restored, or primed from the
        // boot record awaiting its first sample): that boot moment has a nearly empty screen,
        // and the one thing happening — the link coming up — should sit where the eye rests.
        // Otherwise it keeps its slot below the status pills, out of the tutorial flows' way.
        val vacuumEstablished = state.safety.sealState == SealState.LeakMonitoring ||
            state.safety.sealState == SealState.Passed ||
            state.safety.verifiedVacuumKpa != null
        HousingLinkBanner(
            bleState = state.bleConnectionState,
            bluetoothEnabled = bluetoothEnabled,
            modifier = if (!bluetoothEnabled) {
                // The phone-radio state is a prerequisite failure, not a floating housing
                // advisory. Give it the complete display width so there is no ambiguous live
                // camera strip at either edge suggesting the alert is local or dismissible.
                Modifier
                    .align(if (vacuumEstablished) Alignment.Center else Alignment.TopCenter)
                    .then(if (vacuumEstablished) Modifier else Modifier.padding(top = 64.dp))
                    .fillMaxWidth()
            } else if (vacuumEstablished) {
                Modifier
                    .align(Alignment.Center)
                    .padding(start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
            } else {
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
            },
        )
    }
}

/**
 * A perspective-projected arrow prism lying on a virtual world-horizontal plane. The complete
 * face geometry comes from HeadingMath, so pitch and roll alter its vanishing point,
 * foreshortening and visible sides rather than merely rotating a flat icon.
 */
@Composable
private fun TargetHeadingArrow(
    mesh: NavigationArrowMesh,
    targetHeading: Double,
    onHeading: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val turn = mesh.yawErrorDegrees
    val direction = when {
        onHeading -> "on target heading"
        turn == null -> "target projected through camera perspective"
        turn > 0.0 -> "turn right ${abs(turn).roundToInt()} degrees"
        else -> "turn left ${abs(turn).roundToInt()} degrees"
    }
    val arrowColor = if (onHeading) DiveColors.Success else DiveColors.DiveCyan

    Canvas(
        modifier = modifier
            .size(if (compact) 16.dp else 30.dp)
            .semantics {
                contentDescription =
                    "Target ${targetHeading.roundToInt().mod(360)} degrees, $direction"
            },
    ) {
        val scale = minOf(size.width, size.height) * 0.48f
        fun screen(point: ProjectedArrowPoint) = Offset(
            x = center.x + point.x.toFloat() * scale,
            y = center.y + point.y.toFloat() * scale,
        )
        fun face(points: List<ProjectedArrowPoint>) = Path().apply {
            val first = screen(points.first())
            moveTo(first.x, first.y)
            points.drop(1).forEach { point ->
                val projected = screen(point)
                lineTo(projected.x, projected.y)
            }
            close()
        }

        // The lower silhouette and every connected perimeter wall make this a real projected
        // prism. Drawing far walls first prevents a near wall from being covered during roll.
        drawPath(face(mesh.lowerFace), DiveColors.DeepBlack.copy(alpha = 0.84f))
        val wallIndices = mesh.lowerFace.indices.sortedBy { index ->
            val next = (index + 1) % mesh.lowerFace.size
            (mesh.lowerFace[index].y + mesh.lowerFace[next].y +
                mesh.upperFace[index].y + mesh.upperFace[next].y) / 4.0
        }
        wallIndices.forEachIndexed { order, index ->
            val next = (index + 1) % mesh.lowerFace.size
            val wall = face(
                listOf(
                    mesh.lowerFace[index],
                    mesh.lowerFace[next],
                    mesh.upperFace[next],
                    mesh.upperFace[index],
                ),
            )
            drawPath(
                wall,
                arrowColor.copy(alpha = if (order % 2 == 0) 0.42f else 0.28f),
            )
        }

        val top = face(mesh.upperFace)
        drawPath(
            path = top,
            brush = Brush.linearGradient(
                colors = listOf(arrowColor.copy(alpha = 0.74f), arrowColor),
                start = Offset(size.width * 0.18f, size.height * 0.92f),
                end = Offset(size.width * 0.78f, size.height * 0.06f),
            ),
        )
        drawPath(
            path = top,
            color = DiveColors.TextPrimary.copy(alpha = 0.76f),
            style = Stroke(width = 0.8.dp.toPx()),
        )
    }
}


/** Same slot the link banner uses, since the two are never both relevant with a healthy link. */
private val SEAL_TOP = 64.dp

/** Breathing room between the vacuum cluster's bottom edge and the seal chip below it. */
private val SEAL_CLUSTER_GAP = 6.dp

/** Leaves only the final 15% of the former gap above the collapsed camera navigation tray. */
private val CAMERA_READOUT_LIVE_PADDING = 54.dp

/** Clears the shared top edge of Focus, ISO, shutter, Options and all other bottom editors. */
private val CAMERA_READOUT_MENU_PADDING = 235.dp

/** Existing lock tolerance, shared by arrow and numeric-heading colour. */
private const val TARGET_HEADING_SYNC_TOLERANCE_DEGREES = 3.0

/**
 * Lift the complete dive readout for every editor opened from a bottom control. The centre mode
 * button opens the side rail instead, so it deliberately keeps the live-view position.
 */
internal fun cameraReadoutBottomPadding(mode: AppMode, camera: CameraState) = when (mode) {
    AppMode.CameraLive, AppMode.CameraAdjust ->
        if (camera.settingsEditing || camera.showMoreSettings) {
            CAMERA_READOUT_MENU_PADDING
        } else {
            CAMERA_READOUT_LIVE_PADDING
        }
    // Gallery's preview actions and bottom-centre Back share one dock below the gauge.
    AppMode.Gallery -> 150.dp
    else -> 28.dp
}

/** Clears a two-line link banner so a seal failure and a link warning can coexist. */
private val SEAL_STACKED_TOP = 140.dp

@Composable
private fun OverlayPill(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                color = DiveColors.DeepBlack.copy(alpha = 0.62f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
    ) {
        content()
    }
}

@Composable
private fun rememberClockText(): String {
    var text by remember { mutableStateOf(formatTimestamp()) }

    LaunchedEffect(Unit) {
        while (true) {
            text = formatTimestamp()
            delay(1000)
        }
    }

    return text
}

private fun formatTimestamp(): String {
    return LocalDateTime.now(ZoneId.systemDefault()).format(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    )
}
