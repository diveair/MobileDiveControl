package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.delay
import com.mobiledivecontrol.ui.components.AutoShrinkText

internal fun diveDepth(meters: Double, metric: Boolean): String =
    String.format(Locale.US, "%.1f %s", if (metric) meters else meters * 3.28084, if (metric) "m" else "ft")

internal fun diveTime(seconds: Long): String = "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)

internal fun logNdlText(profile: DiveProfileState, selected: DiveSession): String {
    val live = selected === profile.active
    val seconds = if (live) profile.decompression.readings?.ndlSeconds.takeIf { profile.sensorAvailable &&
        profile.decompression.history == DecoHistory.Tracking } else selected.minimumNdlSeconds
    val value = when {
        seconds == null -> "—"
        seconds >= Buhlmann.NDL_CAP_SECONDS -> "99+ min"
        seconds < 60 -> "$seconds s"
        else -> "${seconds / 60} min"
    }
    return "${if (live) "NDL" else "MIN NDL"} $value"
}

@Composable
fun DiveSettingsScreen(
    profile: DiveProfileState,
    useMetric: Boolean,
    onCommand: (DiveSettingsCommand) -> Unit,
) {
    LaunchedEffect(profile.sensorAvailable, profile.active == null, profile.historyPromptOffered,
        profile.decompression.history, profile.gasMenuOpen, profile.pendingLogDeletion, profile.logMenuSession) {
        if (!profile.historyPromptOffered) onCommand(DiveSettingsCommand.RequestHistoryConfirmation)
    }
    if (profile.gasMenuOpen) GasSettingsDialog(profile, onCommand)
    if (profile.logMenuSession != null && profile.pendingLogDeletion == null) {
        DiveLogMenu(profile, useMetric, onCommand)
    }
    profile.pendingLogDeletion?.let { log ->
        AlertDialog(
            onDismissRequest = { onCommand(DiveSettingsCommand.CancelLogDeletion) },
            title = { Text("Delete dive log?") },
            text = { Column {
                Text(Instant.ofEpochMilli(log.startedAtEpochMs).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM d · HH:mm")))
                Text("MAX ${diveDepth(log.maxDepthMeters, useMetric)} · TIME ${diveTime(log.elapsedMs / 1000)}")
                Spacer(Modifier.height(12.dp))
                Text("LEFT / RIGHT select · OK confirm", fontSize = 12.sp)
            } },
            confirmButton = {
                TextButton(onClick = { onCommand(DiveSettingsCommand.ConfirmLogDeletion) },
                    modifier = Modifier.border(2.dp, if (profile.deleteLogSelected) DiveColors.Critical else Color.Transparent, RoundedCornerShape(8.dp))) {
                    Text("DELETE", color = DiveColors.Critical)
                }
            },
            dismissButton = {
                TextButton(onClick = { onCommand(DiveSettingsCommand.CancelLogDeletion) },
                    modifier = Modifier.border(2.dp, if (!profile.deleteLogSelected) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(8.dp))) {
                    Text("CANCEL", color = DiveColors.DiveCyan)
                }
            },
        )
    }
    profile.historyConfirmation?.let { history ->
        val interval = history == DecoHistory.SurfaceIntervalUnconfirmed
        AlertDialog(onDismissRequest = { onCommand(DiveSettingsCommand.DismissHistoryConfirmation) },
            title = { Text(if (interval) "Confirm surface interval" else "Before your first dive") },
            text = { Text(if (interval) "I stayed at the surface breathing air for the entire unmonitored interval."
                else "I have not dived or had supplemental oxygen exposure in the past 48 hours. Initialize tissue and oxygen tracking from a fresh surface reading.") },
            confirmButton = { TextButton(onClick = { onCommand(DiveSettingsCommand.Confirm) }) { Text("CONFIRM · OK") } },
            dismissButton = { TextButton(onClick = { onCommand(DiveSettingsCommand.DismissHistoryConfirmation) }) { Text("CANCEL") } })
    }
    val sessions = listOfNotNull(profile.active) + profile.logs
    val selected = sessions.getOrNull(profile.logIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0)))
    Column(Modifier.fillMaxSize().background(DiveColors.DeepBlack).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("DIVE SETTINGS", color = DiveColors.DiveCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (profile.active != null) Text("DIVE ACTIVE · SETTINGS LOCKED",
                color = DiveColors.Warning, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (selected === profile.active && selected != null) "LIVE PROFILE" else "DIVE LOG",
                        color = DiveColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Text("${profile.logs.size} / ${String.format(Locale.US, "%,d", DiveProfileTracker.MAX_LOGS)} saved",
                        color = DiveColors.TextSecondary, fontSize = 11.sp)
                }
                if (selected == null) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(DiveColors.SurfaceCard, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        Text("Your profile appears automatically\nonce depth reaches 1.5 m.", color = DiveColors.TextSecondary)
                    }
                } else {
                    Text(Instant.ofEpochMilli(selected.startedAtEpochMs).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MMM d · HH:mm")) + "  ·  " + selected.settings.gas.label,
                        color = DiveColors.TextSecondary, fontSize = 12.sp)
                    DiveProfileChart(selected, useMetric, Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("MAX ${diveDepth(selected.maxDepthMeters, useMetric)}", color = DiveColors.DiveCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(logNdlText(profile, selected), color = DiveColors.TextPrimary, fontSize = 14.sp,
                            modifier = Modifier.clickable { onCommand(DiveSettingsCommand.RequestHistoryConfirmation) })
                        Text("TIME ${diveTime(selected.elapsedMs / 1000)}", color = DiveColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text(when (selected.outcome) {
                            DiveLogOutcome.CompletedStop -> "Stop timed"
                            DiveLogOutcome.IncompleteStop -> "Stop incomplete"
                            DiveLogOutcome.NoStopRecorded -> "No stop recorded"
                            null -> "Monitoring"
                        }, color = if (selected.outcome == DiveLogOutcome.IncompleteStop) DiveColors.Warning else DiveColors.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
            SettingsList(profile, useMetric, sessions.size, onCommand, Modifier.width(126.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("↑/↓ select   ·   ←/→ browse / adjust   ·   OK open / select   ·   BACK camera",
            color = DiveColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsList(profile: DiveProfileState, metric: Boolean, sessionCount: Int,
    onCommand: (DiveSettingsCommand) -> Unit, modifier: Modifier) {
    val settings = profile.active?.settings ?: profile.settings
    // Share the available screen height so every option stays visible during housing navigation.
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DiveSettingsField.entries.forEach { field ->
            val focused = profile.selectedField == field
            val enabled = profile.active == null || field in setOf(DiveSettingsField.Log, DiveSettingsField.Back)
            val label = when (field) {
                DiveSettingsField.Gas -> "Breathing gas"
                DiveSettingsField.StopDepth -> "Stop depth"
                DiveSettingsField.StopDuration -> "Stop duration"
                DiveSettingsField.Log -> "Dive Logs"
                DiveSettingsField.Back -> "Back to camera"
            }
            val value = when (field) {
                DiveSettingsField.Gas -> settings.gas.label
                DiveSettingsField.StopDepth -> diveDepth(settings.stopDepthMeters.toDouble(), metric)
                DiveSettingsField.StopDuration -> "${settings.stopDurationSeconds / 60} min"
                DiveSettingsField.Log -> if (sessionCount == 0) "No dives" else "${profile.logIndex + 1} / $sessionCount"
                DiveSettingsField.Back -> "BACK"
            }
            val adjustable = enabled && field !in setOf(DiveSettingsField.Gas, DiveSettingsField.Back) &&
                (field != DiveSettingsField.Log || sessionCount > 0)
            Column(Modifier.fillMaxWidth().weight(1f)
                .background(if (focused) DiveColors.SurfaceElevated else DiveColors.SurfaceCard, RoundedCornerShape(8.dp))
                .border(1.dp, if (focused) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable {
                    onCommand(DiveSettingsCommand.Select(field))
                    if (field == DiveSettingsField.Back) onCommand(DiveSettingsCommand.Back)
                    if (field == DiveSettingsField.Gas && enabled) onCommand(DiveSettingsCommand.Confirm)
                    if (field == DiveSettingsField.Log) onCommand(DiveSettingsCommand.Confirm)
                }
                .padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text(label, color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (adjustable) SettingsChevron(label, -1, field, onCommand)
                    // Balance the submenu chevron so the gas value shares the card's centerline.
                    if (enabled && field == DiveSettingsField.Gas) Spacer(Modifier.width(14.dp))
                    AutoShrinkText(value, color = if (enabled) DiveColors.TextPrimary else DiveColors.TextSecondary,
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 17.sp,
                            textAlign = TextAlign.Center),
                        modifier = Modifier.weight(1f), maxLines = 1)
                    if (adjustable) SettingsChevron(label, 1, field, onCommand)
                    if (enabled && field == DiveSettingsField.Gas) {
                        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                            Text("›", color = DiveColors.DiveCyan, fontSize = 24.sp, lineHeight = 24.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsChevron(label: String, delta: Int, field: DiveSettingsField,
    onCommand: (DiveSettingsCommand) -> Unit) {
    Box(Modifier.width(24.dp).fillMaxHeight().clickable {
        onCommand(DiveSettingsCommand.Select(field))
        onCommand(DiveSettingsCommand.Adjust(delta))
    }.semantics { contentDescription = "$label ${if (delta < 0) "previous" else "next"}" },
        contentAlignment = Alignment.Center) {
        Text(if (delta < 0) "‹" else "›", color = DiveColors.DiveCyan, fontSize = 26.sp, lineHeight = 26.sp)
    }
}

@Composable
fun DiveStopBanner(profile: DiveProfileState, useMetric: Boolean, modifier: Modifier = Modifier,
    onCommand: (DiveSettingsCommand) -> Unit = {}) {
    val dive = profile.active ?: return
    val decompressionCeiling = profile.requiredDecompressionCeilingMeters
    val decompressionRequired = profile.decompressionHold
    if (profile.stopPresentation == DiveStopPresentation.Dismissed && !decompressionRequired) return
    val approaching = dive.phase == DiveStopPhase.Armed && profile.depthMeters?.let {
        it <= dive.settings.bandMax + 2 && dive.maxDepthMeters - it >= 0.5
    } == true
    if (profile.sensorAvailable && dive.phase in setOf(DiveStopPhase.Watching, DiveStopPhase.Armed) &&
        !approaching && !decompressionRequired) return
    val missing = !profile.sensorAvailable
    val complete = dive.phase == DiveStopPhase.Complete && !decompressionRequired
    val target = dive.settings.stopDepthMeters.toDouble()
    val zone = stopDepthZone(profile.depthMeters.takeIf { !missing }, target)
    val accent = safetyStopAccent(profile)
    val motion = profile.verticalMetersPerMinute?.takeIf { !missing && !complete && abs(it) >= 0.6 }
    val stopHeading = "${diveDepth(target, useMetric).replace(" ", "")} SAFETY STOP"
    if (profile.stopPresentation == DiveStopPresentation.Minimized && !complete && !decompressionRequired) {
        Column(modifier.widthIn(max = 290.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (motion != null && motion < 0) StopMotionTriangle(motion, useMetric, compact = true,
            warningColor = accent)
        Column(Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .border(2.dp, accent, RoundedCornerShape(10.dp))
            .clickable { onCommand(DiveSettingsCommand.ExpandStop) }.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$stopHeading  ${diveTime(dive.remainingSeconds.toLong())}", color = accent,
                fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(if (missing) "DEPTH LOST · PAUSED" else "DEPTH ${diveDepth(profile.depthMeters!!, useMetric)}",
                color = accent, fontSize = 12.sp)
        }
        if (motion != null && motion > 0) StopMotionTriangle(motion, useMetric, compact = true, warningColor = accent)
        }
        return
    }
    Column(modifier.widthIn(max = 430.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    if (motion != null && motion < 0) StopMotionTriangle(motion, useMetric,
        warningColor = accent)
    Column(Modifier.fillMaxWidth()
        .background(Color.Black, RoundedCornerShape(14.dp))
        .background(accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
        .border(2.dp, accent, RoundedCornerShape(14.dp))
        .clickable(enabled = dive.stopStarted) { onCommand(DiveSettingsCommand.AcknowledgeStop) }
        .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(when {
            missing -> "DEPTH LOST · TIMER PAUSED"
            decompressionRequired -> if (decompressionCeiling == null) "DECOMPRESSION STATUS UNKNOWN" else "DECOMPRESSION REQUIRED"
            complete -> "SAFETY STOP COMPLETE"
            approaching -> "APPROACHING SAFETY STOP"
            else -> stopHeading
        }, color = accent, fontWeight = FontWeight.Bold, fontSize = if (complete && !missing) 20.sp else 14.sp)
        if (complete && !missing) {
            Text("✓", color = DiveColors.Success, fontWeight = FontWeight.Bold, fontSize = 48.sp)
            Text("${dive.settings.stopDurationSeconds / 60} MIN STOP COMPLETED SUCCESSFULLY",
                color = DiveColors.Success, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (decompressionCeiling != null) {
                // Round the shown ceiling upward in the displayed units, never to a shallower depth.
                val unitsPerMeter = if (useMetric) 1.0 else 3.28084
                val ceilingText = "%.1f %s".format(Locale.US, ceil(decompressionCeiling * unitsPerMeter * 10) / 10,
                    if (useMetric) "m" else "ft")
                Text("CEILING $ceilingText", color = accent, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            } else Text(if (decompressionRequired) "CEILING —" else diveTime(dive.remainingSeconds.toLong()), color = accent,
                    fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(if (missing) "DEPTH —" else "DEPTH " + "%.1f %s".format(Locale.US,
                profile.depthMeters!! * if (useMetric) 1.0 else 3.28084, if (useMetric) "m" else "ft"),
                color = accent, fontSize = 23.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        val instruction = when {
            missing -> "Waiting for fresh depth readings"
            decompressionRequired -> if (decompressionCeiling == null) "Ceiling unavailable · Safety timer paused"
                else "Stay below ceiling · Safety timer paused"
            complete -> ""
            zone == StopDepthZone.TooShallow -> "TOO SHALLOW · Descend slowly to ${diveDepth(target, useMetric)}"
            else -> ""
        }
        if (instruction.isNotEmpty()) Text(instruction, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    if (motion != null && motion > 0) StopMotionTriangle(motion, useMetric, warningColor = accent)
    }
}

/** One colour decision drives the card, triangle and any visible backing edges. */
fun safetyStopAccent(profile: DiveProfileState): Color {
    val dive = profile.active ?: return DiveColors.Warning
    val zone = stopDepthZone(profile.depthMeters, dive.settings.stopDepthMeters.toDouble())
    return when {
        !profile.sensorAvailable -> DiveColors.Warning
        profile.decompressionHold -> DiveColors.Critical
        dive.phase == DiveStopPhase.Complete || zone == StopDepthZone.OnTarget -> DiveColors.Success
        zone == StopDepthZone.TooShallow && (profile.verticalMetersPerMinute ?: 0.0) < 0.6 -> DiveColors.Critical
        else -> DiveColors.Warning
    }
}

@Composable
private fun StopMotionTriangle(metersPerMinute: Double, metric: Boolean, compact: Boolean = false,
    warningColor: Color = DiveColors.Warning) {
    val ascending = metersPerMinute < 0
    val rate = abs(metersPerMinute) / 60.0 * if (metric) 1.0 else 3.28084
    var warningBright by remember(ascending) { mutableStateOf(true) }
    // A coroutine keeps the flash running even when the phone disables system animations.
    LaunchedEffect(ascending) {
        if (ascending) while (true) {
            delay(500)
            warningBright = !warningBright
        }
    }
    Box(Modifier.fillMaxWidth().height(if (compact) 60.dp else 84.dp)
        .semantics { contentDescription = if (ascending) "Ascending" else "Descending" }) {
        Canvas(Modifier.matchParentSize()) {
            val edgeY = if (ascending) size.height else 0f
            val pointY = if (ascending) 0f else size.height
            val path = Path().apply {
                moveTo(0f, edgeY)
                lineTo(size.width / 2, pointY)
                lineTo(size.width, edgeY)
                close()
            }
            drawPath(path, warningColor)
        }
        Column(Modifier.align(if (ascending) Alignment.BottomCenter else Alignment.TopCenter)
            .padding(vertical = if (compact) 3.dp else 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (ascending) "WARNING ASCENDING" else "WARNING DESCENDING",
                color = if (ascending && warningBright) DiveColors.Warning else Color.Black,
                fontSize = if (compact) 11.sp else 14.sp, lineHeight = if (compact) 13.sp else 16.sp,
                fontWeight = FontWeight.ExtraBold)
            Text("%.1f %s/sec".format(Locale.US, rate, if (metric) "m" else "ft"), color = Color.Black,
                fontSize = if (compact) 12.sp else 16.sp, lineHeight = if (compact) 14.sp else 18.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}
