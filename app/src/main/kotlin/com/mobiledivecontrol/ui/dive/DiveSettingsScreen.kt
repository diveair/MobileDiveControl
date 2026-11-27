package com.mobiledivecontrol.ui.dive

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            if (profile.active != null) Text("DIVE ACTIVE · GAS / STOP LOCKED",
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
                    selected.lastReserveConfirmationElapsedMs?.let { elapsed ->
                        Text("Reserve confirmed by diver ×${selected.diverReserveConfirmations} · Last at ${diveTime(elapsed / 1000)}",
                            color = DiveColors.TextSecondary, fontSize = 12.sp)
                    }
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
                    DiveLogExposureReadout(profile, selected, useMetric)
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
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DiveSettingsField.entries.forEach { field ->
            val focused = profile.selectedField == field
            val enabled = profile.active == null || field in setOf(DiveSettingsField.Log, DiveSettingsField.Alerts, DiveSettingsField.Back)
            val label = when (field) {
                DiveSettingsField.Gas -> "Breathing gas"
                DiveSettingsField.StopDepth -> "Safety Stop Depth"
                DiveSettingsField.StopDuration -> "Safety Stop Time"
                DiveSettingsField.Log -> "Dive Logs"
                DiveSettingsField.Alerts -> "Dive Alerts"
                DiveSettingsField.Back -> "Back to camera"
            }
            val value = when (field) {
                DiveSettingsField.Gas -> settings.gas.label
                DiveSettingsField.StopDepth -> diveDepth(settings.stopDepthMeters.toDouble(), metric)
                DiveSettingsField.StopDuration -> "${settings.stopDurationSeconds / 60} min"
                DiveSettingsField.Log -> if (sessionCount == 0) "No dives" else "${profile.logIndex + 1} / $sessionCount"
                DiveSettingsField.Alerts -> "CONFIGURE"
                DiveSettingsField.Back -> "BACK"
            }
            val adjustable = enabled && field !in setOf(DiveSettingsField.Gas, DiveSettingsField.Alerts, DiveSettingsField.Back) &&
                (field != DiveSettingsField.Log || sessionCount > 0)
            Column(Modifier.fillMaxWidth().weight(1f)
                .background(if (focused) DiveColors.SurfaceElevated else DiveColors.SurfaceCard, RoundedCornerShape(8.dp))
                .border(1.dp, if (focused) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable {
                    onCommand(DiveSettingsCommand.Select(field))
                    if (field == DiveSettingsField.Back) onCommand(DiveSettingsCommand.Back)
                    if (field == DiveSettingsField.Gas && enabled) onCommand(DiveSettingsCommand.Confirm)
                    if (field == DiveSettingsField.Log || field == DiveSettingsField.Alerts) onCommand(DiveSettingsCommand.Confirm)
                }
                .padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text(label, color = DiveColors.TextSecondary, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 1,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (adjustable) SettingsChevron(label, -1, field, onCommand)
                    // Balance the submenu chevron so the gas value shares the card's centerline.
                    if (enabled && field == DiveSettingsField.Gas) Spacer(Modifier.width(14.dp))
                    AutoShrinkText(value, color = if (enabled) DiveColors.TextPrimary else DiveColors.TextSecondary,
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 14.sp,
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
