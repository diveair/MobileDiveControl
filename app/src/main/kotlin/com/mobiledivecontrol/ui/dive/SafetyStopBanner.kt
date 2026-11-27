package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal enum class StopCue(val label: String, val pointsUp: Boolean) {
    Ascending("ASCENDING", true), Descending("DESCENDING", false), Ascend("ASCEND", true), Descend("DESCEND", false)
}

internal enum class StopNotice { Countdown, ShallowAscent, Shallow, Recovery, DeepPause, Continued, Complete, DepthLost, Ceiling }

internal data class StopBadge(
    val notice: StopNotice,
    val color: Color,
    val cue: StopCue? = null,
    val triangleColor: Color = color,
    /** Amplitude of the red-to-amber flash. Zero produces a stationary triangle. */
    val flash: Float = 0f,
    val status: String = "",
)

internal fun stopBadge(profile: DiveProfileState, dive: DiveSession): StopBadge {
    val red = DiveColors.Critical
    val amber = DiveColors.Warning
    val green = DiveColors.Success
    if (profile.decompressionHold) return StopBadge(StopNotice.Ceiling, red)
    if (dive.phase == DiveStopPhase.Complete) return StopBadge(StopNotice.Complete, green)
    val depth = profile.depthMeters
    if (depth == null || !profile.sensorAvailable) return StopBadge(StopNotice.DepthLost, amber)
    if (profile.continuationPending) return StopBadge(StopNotice.Continued, amber)

    val target = dive.settings.stopDepthMeters.toDouble()
    val motion = stopMotion(profile.verticalMetersPerMinute)
    val measuring = motion == StopMotion.Measuring
    val uncertain = if (measuring) "MEASURING MOTION" else ""
    if (dive.phase == DiveStopPhase.TooShallow) {
        val atLimit = compareStopDepth(depth, target - 1.5) <= 0
        if (atLimit && motion == StopMotion.Ascending)
            return StopBadge(StopNotice.ShallowAscent, red, StopCue.Ascending, amber)
        if (atLimit && motion == StopMotion.Steady)
            return StopBadge(StopNotice.Shallow, red, StopCue.Descend, red, flash = 1f)
        // Flash only the triangle. Fade its amplitude to zero as the stationary colour reaches yellow.
        val distance = (depth - (target - 1.5)).toFloat().coerceIn(0f, 1.5f)
        val shade = if (distance < .75f) lerp(red, Color.Yellow, distance / .75f)
            else lerp(Color.Yellow, green, (distance - .75f) / .75f)
        return StopBadge(StopNotice.Recovery, shade, when (motion) {
            StopMotion.Descending -> StopCue.Descending
            StopMotion.Steady -> StopCue.Descend
            else -> null
        }, flash = if (motion == StopMotion.Descending) (1f - distance / .75f).coerceAtLeast(0f) else 0f,
            status = uncertain)
    }
    if (dive.phase == DiveStopPhase.TooDeep) return StopBadge(StopNotice.DeepPause, amber,
        if (motion == StopMotion.Descending) StopCue.Descending else null,
        status = if (measuring) "TOO DEEP · MEASURING MOTION" else if (motion == StopMotion.Steady) "TOO DEEP" else "")
    val aboveQuietBand = compareStopDepth(depth, target + .5) > 0
    val returning = profile.returningFromDeep && compareStopDepth(depth, target) > 0 && motion == StopMotion.Ascending
    if (!aboveQuietBand && !returning) return StopBadge(StopNotice.Countdown, green)

    val tenths = ((depth - target - .5) * 10).roundToInt().coerceIn(0, 10)
    val accent = lerp(green, amber, tenths / 10f)
    val cue = when (motion) {
        StopMotion.Ascending -> StopCue.Ascending
        StopMotion.Descending -> StopCue.Descending
        StopMotion.Steady -> StopCue.Ascend
        StopMotion.Measuring -> null
    }
    return StopBadge(StopNotice.Countdown, accent, cue, when (motion) {
        StopMotion.Ascending -> green
        StopMotion.Descending -> amber
        else -> accent
    }, status = uncertain)
}

fun safetyStopAccent(profile: DiveProfileState): Color =
    profile.active?.let { stopBadge(profile, it).color } ?: DiveColors.Warning

internal fun stopCeilingLabel(meters: Double, metric: Boolean): String {
    val displayed = ceil(meters * (if (metric) 1.0 else 3.28084) * 10) / 10
    return "%.1f %s".format(Locale.US, displayed, if (metric) "m" else "ft")
}

@Composable
private fun manualStopClock(profile: DiveProfileState): String {
    val remaining by produceState(profile.manualRemainingSeconds(pressureMonotonicMs()),
        profile.depthLossStartedMs, profile.active?.stopElapsedMs) {
        if (profile.depthLossStartedMs != null) do {
            value = profile.manualRemainingSeconds(pressureMonotonicMs())
            if (value > 0) delay(250)
        } while (value > 0)
    }
    return diveTime(remaining)
}

@Composable
fun DiveStopBanner(profile: DiveProfileState, useMetric: Boolean, modifier: Modifier = Modifier,
    onCommand: (DiveSettingsCommand) -> Unit = {}, forceCompact: Boolean = false, recording: Boolean = false,
    continuationOwnsOk: Boolean = true) {
    val dive = profile.active ?: return
    if (!profile.stopBannerVisible) return
    val badge = stopBadge(profile, dive)
    val notice = badge.notice
    val criticalAscent = notice == StopNotice.ShallowAscent
    val compact = forceCompact || !profile.stopExpanded
    val target = diveDepth(dive.settings.stopDepthMeters.toDouble(), useMetric)
    val depth = profile.depthMeters?.takeIf { profile.sensorAvailable }?.let { diveDepth(it, useMetric).replace(" ", "") }
    val clock = if (notice == StopNotice.DepthLost) manualStopClock(profile) else diveTime(dive.remainingSeconds.toLong())
    val ceiling = profile.requiredDecompressionCeilingMeters?.let { stopCeilingLabel(it, useMetric) }
    val heading = when (notice) {
        StopNotice.ShallowAscent -> "STOP · TOO SHALLOW"
        StopNotice.Shallow, StopNotice.Recovery -> "TOO SHALLOW"
        StopNotice.DeepPause -> "SAFETY STOP PAUSED"
        StopNotice.Continued -> "DIVE CONTINUING - CONFIRM SUFFICIENT AIR RESERVE"
        StopNotice.Complete -> "SAFETY STOP COMPLETE"
        StopNotice.DepthLost -> "DEPTH LOST"
        StopNotice.Ceiling -> if (ceiling == null) "DECOMPRESSION" else "DECOMPRESSION CEILING"
        StopNotice.Countdown -> "SAFETY STOP"
    }
    val instruction = when {
        notice == StopNotice.DepthLost -> if (clock == "0:00") "VERIFY STOP ON BACKUP DIVE COMPUTER"
            else "HOLD $target ON BACKUP DEPTH GAUGE"
        notice == StopNotice.Ceiling -> if (ceiling == null) "FOLLOW BACKUP DIVE COMPUTER" else "STAY AT OR DEEPER THAN CEILING"
        notice == StopNotice.Recovery && badge.cue == null && badge.status.isEmpty() -> "RETURN TO $target"
        else -> badge.status
    }
    val color = if (criticalAscent) Color.Black else badge.color
    val shape = RoundedCornerShape(if (compact) 10.dp else 14.dp)
    val background = when {
        criticalAscent -> DiveColors.Critical
        recording && notice != StopNotice.Continued -> Color.Transparent
        else -> Color.Black.copy(alpha = .95f)
    }
    val card = modifier.widthIn(max = 430.dp).fillMaxWidth().background(background, shape)
        .border(if (compact) 1.dp else 2.dp, badge.color, shape)
    if (notice == StopNotice.Continued) {
        val direction = when (stopMotion(profile.verticalMetersPerMinute)) {
            StopMotion.Descending -> "↓ DESCENDING"
            StopMotion.Ascending -> "↑ ASCENDING"
            StopMotion.Steady -> "STEADY"
            StopMotion.Measuring -> "MEASURING MOTION"
        }
        Column(card.clickable(enabled = continuationOwnsOk) { onCommand(DiveSettingsCommand.ConfirmContinuationReserve) }
            .padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BadgeText(heading, color, Modifier.fitAlert(Orientation.Horizontal), size = 16)
            val actionText = if (continuationOwnsOk) "   OK / MENU to confirm" else ""
            BadgeText("$depth · $direction$actionText", Color.White, Modifier.fitAlert(Orientation.Horizontal))
        }
        return
    }
    val action = if (compact) DiveSettingsCommand.ExpandStop else DiveSettingsCommand.AcknowledgeStop
    val actionable = !forceCompact && notice != StopNotice.Ceiling && (!compact || profile.stopActive)
    val surface = card.clickable(enabled = actionable) { onCommand(action) }
    if (compact) {
        val line = when (notice) {
            StopNotice.Complete -> heading
            StopNotice.DepthLost -> "$heading · MANUAL $clock | $instruction"
            StopNotice.Ceiling -> if (ceiling == null) "$heading · $instruction" else "$heading $ceiling | DEPTH ${depth ?: "—"}"
            else -> "$heading: $clock | ${depth ?: "—"}"
        }
        Box(surface.height(36.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fitAlert(Orientation.Horizontal), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (criticalAscent) Icon(Icons.Default.Warning, null, Modifier.size(21.dp), tint = Color.Black)
                BadgeText(line, color, size = if (criticalAscent) 16 else 14)
                if (notice != StopNotice.DepthLost && notice != StopNotice.Ceiling && instruction.isNotEmpty())
                    BadgeText(instruction, color)
                if (badge.cue != null) BadgeTriangle(badge, compact = true)
            }
        }
        return
    }
    Column(surface.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (criticalAscent) Icon(Icons.Default.Warning, null, Modifier.size(23.dp), tint = Color.Black)
            BadgeText(heading, color, size = 19)
        }
        when (notice) {
            StopNotice.Complete -> BadgeText("✓", color, size = 44)
            StopNotice.Ceiling -> if (ceiling != null) BadgeText(ceiling, color, size = 32)
            StopNotice.DepthLost -> BadgeText("MANUAL $clock", color, size = 30)
            else -> Row(Modifier.heightIn(min = 68.dp).fitAlert(Orientation.Horizontal),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BadgeText(clock, color, size = 36)
                if (badge.cue != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BadgeTriangle(badge, compact = false)
                    val rate = profile.verticalMetersPerMinute
                    if (rate != null && (badge.cue == StopCue.Ascending || badge.cue == StopCue.Descending)) {
                        val speed = depthInDisplayUnits(kotlin.math.abs(rate) / 60, useMetric)
                        BadgeText("%.1f %s/s".format(Locale.US, speed, if (useMetric) "m" else "ft"), color)
                    }
                }
            }
        }
        Row(Modifier.fitAlert(Orientation.Horizontal), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (depth != null && notice != StopNotice.Complete) BadgeText("DEPTH $depth", color, size = 20)
            if (instruction.isNotEmpty()) BadgeText(instruction, color)
        }
        if (actionable) BadgeText(if (profile.stopActive) "OK  MINIMIZE" else "OK  DISMISS", color,
            Modifier.padding(top = 8.dp).border(1.dp, color, RoundedCornerShape(6.dp)).padding(horizontal = 16.dp, vertical = 5.dp))
    }
}

@Composable
private fun BadgeText(label: String, color: Color, modifier: Modifier = Modifier, size: Int = 14) {
    Text(label, modifier = modifier, color = color, fontSize = size.sp, lineHeight = (size + 2).sp,
        fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
}

@Composable
private fun BadgeTriangle(badge: StopBadge, compact: Boolean) {
    val cue = badge.cue ?: return
    var bright by remember(cue) { mutableStateOf(false) }
    LaunchedEffect(cue, badge.flash > 0) {
        bright = false
        if (badge.flash > 0) while (true) { delay(600); bright = !bright }
    }
    val fill = if (bright && badge.flash > 0) lerp(badge.triangleColor, DiveColors.Warning, badge.flash) else badge.triangleColor
    val ink = if (badge.notice == StopNotice.ShallowAscent) DiveColors.Critical else fill
    Box {
        Canvas(Modifier.matchParentSize()) {
            val base = if (cue.pointsUp) size.height else 0f
            val tip = if (cue.pointsUp) 0f else size.height
            drawPath(Path().apply { moveTo(0f, base); lineTo(size.width / 2, tip); lineTo(size.width, base); close() }, fill)
        }
        BadgeText(cue.label, ink, Modifier.layout { measurable, constraints ->
            val label = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val inset = 1.dp.roundToPx()
            val width = 2 * (label.width + inset)
            val height = 2 * (label.height + inset)
            layout(width, height) {
                label.placeRelative((width - label.width) / 2, if (cue.pointsUp) height - label.height - inset else inset)
            }
        }.background(Color.Black).padding(horizontal = 3.dp), size = if (compact) 11 else 16)
    }
}
