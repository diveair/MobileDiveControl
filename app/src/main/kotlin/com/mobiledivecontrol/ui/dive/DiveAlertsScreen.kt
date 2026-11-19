package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.mobiledivecontrol.ui.components.AutoShrinkText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors

@Composable
fun DiveAlertsScreen(state: AppState, onCommand: (DiveSettingsCommand) -> Unit) {
    val air = state.airAlerts
    val send: (AirAlertCommand) -> Unit = { onCommand(DiveSettingsCommand.Alerts(it)) }
    val fields = airFields(air)
    val selected = fields.getOrElse(air.selectedRow) { AirField.Back }
    val picker = airShowsReceiverPicker(air)
    LaunchedEffect(picker, state.diveProfile.active != null) {
        if (picker) send(AirAlertCommand.DiscoverReceivers)
    }
    Column(Modifier.fillMaxSize().background(DiveColors.DeepBlack).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (air.page == AirPage.Home) "DIVE ALERTS" else air.page.label.uppercase(), color = DiveColors.DiveCyan,
                fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (air.page == AirPage.Home) "DIVE SETTINGS" else "DIVE ALERTS / ${air.page.label.uppercase()}",
                color = DiveColors.TextSecondary, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AirSetupContext(state, send)
                Spacer(Modifier.weight(1f))
                Text(if (airFieldLocked(state, selected) && state.diveProfile.active != null) "Available before the dive." else airHelp(air, selected),
                    color = if (airFieldLocked(state, selected)) DiveColors.Warning else DiveColors.TextSecondary,
                    fontSize = 11.sp, lineHeight = 16.sp)
            }
            if (picker) AirReceiverPicker(state, send, Modifier.width(384.dp).fillMaxHeight())
            else BoxWithConstraints(Modifier.width(384.dp).fillMaxHeight()) {
                val rowHeight = ((maxHeight - 4.dp * (fields.size - 1)) / fields.size).coerceAtMost(52.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    fields.forEachIndexed { index, field ->
                        AirSettingsRow(state, field, index, rowHeight, send)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (picker) "↑ ↓  Select device       OK  Connect       Tap a device to connect" else "↑ ↓  Select       ← →  Change       OK  Choose       Z±  Quick adjust", color = DiveColors.TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun AirSettingsRow(state: AppState, field: AirField, index: Int, rowHeight: Dp, send: (AirAlertCommand) -> Unit) {
    val air = state.airAlerts
    val locked = airFieldLocked(state, field)
    val focus = air.selectedRow == index
    val accent = DiveColors.DiveCyan
    val adjust = airFieldAdjustable(field) && !locked && !(field == AirField.Timing && air.settings.schedule == AirSchedule.AtTimes)
    fun act(command: AirAlertCommand) { send(AirAlertCommand.SelectRow(index)); send(command) }
    Row(Modifier.height(rowHeight).fillMaxWidth()
        .background(if (focus) DiveColors.SurfaceElevated else DiveColors.SurfaceCard, RoundedCornerShape(8.dp))
        .border(if (focus) 2.dp else 1.dp, if (focus) accent else Color.Transparent, RoundedCornerShape(8.dp))
        .clickable { act(AirAlertCommand.Activate) }.padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (field == AirField.Back) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("BACK", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            AutoShrinkText(if (field == AirField.Interval && air.settings.schedule == AirSchedule.Recurring) "Repeat every"
                else if (field == AirField.Interval) "At dive time" else if (field == AirField.ReceiverSetup && air.receiver.connected) "Receiver & data" else field.label,
                color = if (locked) DiveColors.TextSecondary else DiveColors.TextPrimary,
                style = TextStyle(fontSize = 13.sp, lineHeight = 14.sp,
                    fontWeight = if (focus) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = 2, modifier = Modifier.weight(1f))
            if (adjust) Box(Modifier.width(28.dp).fillMaxHeight().clickable { act(AirAlertCommand.Adjust(-1)) }, contentAlignment = Alignment.Center) {
                Text("‹", color = accent, fontSize = 22.sp)
            }
            Text(airFieldValue(air, field), color = if (locked) DiveColors.TextSecondary else accent,
                fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.25f), fontWeight = FontWeight.Medium)
            Box(Modifier.width(28.dp).fillMaxHeight().then(if (adjust) Modifier.clickable { act(AirAlertCommand.Adjust(1)) } else Modifier), contentAlignment = Alignment.Center) {
                Text(if (locked) "—" else "›", color = if (locked) DiveColors.TextSecondary else accent, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun AirReceiverPicker(state: AppState, send: (AirAlertCommand) -> Unit, modifier: Modifier = Modifier) {
    val air = state.airAlerts
    val fields = airFields(air)
    val devices = air.receiver.devices
    val firstDeviceRow = if (air.page == AirPage.Home) 1 else 0
    val listState = rememberLazyListState()
    val selectedDevice = air.selectedRow - firstDeviceRow
    LaunchedEffect(selectedDevice) {
        if (selectedDevice in devices.indices) listState.animateScrollToItem(selectedDevice)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (air.page == AirPage.Home) AirSettingsRow(state, AirField.Mode, 0, 44.dp, send)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)) {
            Text("AVAILABLE DEVICES", color = DiveColors.TextSecondary, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(if (devices.isEmpty()) "" else "${devices.size} found", color = DiveColors.TextSecondary, fontSize = 10.sp)
        }
        if (devices.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (air.receiver.phase == AirReceiverPhase.Scanning) "Devices will appear here" else "No devices listed",
                color = DiveColors.TextSecondary, fontSize = 12.sp)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(devices, key = { _, device -> device.address }) { index, device ->
                val focused = air.selectedRow == firstDeviceRow + index
                val locked = airFieldLocked(state, AirField.Device)
                val connecting = air.receiver.phase == AirReceiverPhase.Connecting && device.address.equals(air.settings.mapping.address, true)
                Row(Modifier.fillMaxWidth().height(48.dp)
                    .background(if (focused) DiveColors.SurfaceElevated else DiveColors.SurfaceCard, RoundedCornerShape(8.dp))
                    .border(if (focused) 2.dp else 1.dp, if (focused) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(enabled = !locked) { send(AirAlertCommand.ConnectReceiver(device.address)) }
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, color = if (locked) DiveColors.TextSecondary else DiveColors.TextPrimary,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.address, color = DiveColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(if (connecting) "Connecting…" else "›", color = DiveColors.DiveCyan, fontSize = if (connecting) 11.sp else 22.sp)
                }
            }
        }
        AirSettingsRow(state, AirField.Scan, fields.indexOf(AirField.Scan), 38.dp, send)
        AirSettingsRow(state, AirField.Back, fields.lastIndex, 38.dp, send)
    }
}

@Composable
private fun AirSetupContext(state: AppState, send: (AirAlertCommand) -> Unit) {
    val air = state.airAlerts
    val s = air.settings
    if (!s.enabled) {
        Text("AIR ALERTS OFF", color = DiveColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("No air reminders or pressure warnings.", color = DiveColors.TextSecondary, fontSize = 13.sp)
        return
    }
    if (airShowsReceiverPicker(air)) {
        Text("CONNECT YOUR RECEIVER", color = DiveColors.DiveCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(air.receiver.status, color = if (air.receiver.phase == AirReceiverPhase.Failed) DiveColors.Warning else DiveColors.TextPrimary,
            fontSize = 15.sp, lineHeight = 21.sp)
        Text("Choose your receiver from the nearby devices. Its signals and alert settings appear after it connects.",
            color = DiveColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        Text("Swift needs a compatible receiver bridge. Tern TX live forwarding is unverified.",
            color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        return
    }
    if (air.page in listOf(AirPage.Values, AirPage.ValueMapping)) {
        val value = s.receiverValues.getOrNull(air.valueSlot)
        Text(value?.label ?: "LABEL YOUR DATA", color = DiveColors.DiveCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (value != null) {
            val number = airReceiverValueNumber(air, value, pressureMonotonicMs())
            Text((number?.let { java.lang.String.format(java.util.Locale.US, "%.2f", it) } ?: "—") + " " + value.unit,
                color = DiveColors.TextPrimary, fontSize = 30.sp, fontFamily = FontFamily.Monospace)
            Text(if (number == null) "Waiting for a fresh signal" else "Live decoded value", color = DiveColors.TextSecondary, fontSize = 11.sp)
            val packet = air.receiver.signals[airSignalKey(value.mapping.service, value.mapping.characteristic)]
            packet?.let { Text(it.bytes.take(32).joinToString(" ") { byte -> "%02X".format(byte) },
                color = DiveColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 4) }
        }
        Text("Name a signal, choose its bytes and add a unit label. These values do not change pressure or dive calculations.",
            color = DiveColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        return
    }
    if (air.page == AirPage.Times) {
        Text("YOUR ALERT · ${air.timeSlot + 1} OF ${s.timesSeconds.size}", color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
        AirAlertPreview(air, state.diveProfile)
        Text("At ${s.timesSeconds[air.timeSlot] / 60} min into the dive", color = DiveColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
    } else if (air.page == AirPage.Confirmations) {
        Text("ASK ON THESE ALERTS", color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
        val entries = s.confirmationNumbers.map { "#$it" }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.chunked(4).forEachIndexed { row, chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    chunk.forEachIndexed { col, value ->
                        val index = row * 4 + col
                        val selected = index == air.confirmationSlot
                        Box(Modifier.width(50.dp).height(24.dp)
                            .background(if (selected) DiveColors.SurfaceElevated else DiveColors.SurfaceCard, RoundedCornerShape(5.dp))
                            .border(1.dp, if (selected) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(5.dp))
                            .clickable { send(AirAlertCommand.SelectSlot(index)); send(AirAlertCommand.SelectRow(0)); send(AirAlertCommand.Activate) }, contentAlignment = Alignment.Center) {
                            Text(value, color = if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    } else if (air.page in listOf(AirPage.Receiver, AirPage.Mapping, AirPage.Calibration)) {
        Text(if (s.mapping.verified) "PRESSURE MAPPED" else "RECEIVER CONNECTED", color = DiveColors.DiveCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(air.receiver.status, color = DiveColors.TextPrimary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (air.page == AirPage.Receiver) air.receiver.characteristics.getOrNull(air.receiver.selectedCharacteristic)?.let { candidate ->
            Text(candidate.uuid, color = DiveColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
        }
        val decoded = s.mapping.decode(air.receiver.payload)
        if (decoded != null) Text("${s.unit.display(decoded)} ${s.unit.label} candidate", color = DiveColors.Warning, fontSize = 25.sp, fontFamily = FontFamily.Monospace)
        if (air.receiver.payload.isNotEmpty()) Text(air.receiver.payload.take(32).joinToString(" ") { "%02X".format(it) },
            color = DiveColors.TextSecondary, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 4)
        Text(if (air.page == AirPage.Receiver) "Swift needs a compatible receiver bridge. Tern TX live forwarding is unverified."
            else if (air.page == AirPage.Calibration) "Match two fresh gauge readings at least 5 BAR apart."
            else "Candidate = (raw × multiplier) + offset", color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    } else {
        Text(if (air.page == AirPage.Gas) "GAS ESTIMATE" else "YOUR ALERT", color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
        AirAlertPreview(air, state.diveProfile)
        Text(airTiming(s), color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
        if (airTracksPressure(s) && s.source == AirSource.Manual) Text(
            if (air.startArmed) "✓ Start confirmed for next dive" else if (air.sessionId != null) "Use your actual gauge reading" else "Confirm starting pressure in Tank & display before diving.",
            color = if (air.startArmed) DiveColors.DiveCyan else DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

private fun airHelp(air: AirAlertState, field: AirField): String = when (field) {
    AirField.Mode -> "Choose a reminder, pressure tracking, a receiver, or Off."
    AirField.Timing, AirField.Interval -> if (field == AirField.Timing && air.settings.schedule == AirSchedule.AtTimes) "OK  Edit the alert sequence" else "← →  30 seconds\nZ±  1 minute\nOK  ${if (field == AirField.Timing) "repeat, once or a sequence" else "enter seconds directly"}"
    AirField.Confirmation -> if (air.settings.schedule == AirSchedule.Once) "Choose whether to confirm your pressure at this one-time alert." else "← →  Change how often\nOK  Choose specific alert numbers"
    AirField.Duration -> "← →  Adjust seconds\nOK  Toggle auto hide / stay visible"
    AirField.TimeSlot, AirField.ConfirmationSlot -> "← →  Select an entry\nZ±  Adjust its value\nOK  Enter a new value"
    AirField.Message, AirField.SequenceMessage -> "Save your own alert text. Use the phone keyboard or the housing character selector."
    AirField.SequenceAction -> "Use the default, show a reminder only, or ask for a pressure reading."
    AirField.Start -> "Read your gauge, enter the pressure, then save. This also confirms it for the next dive."
    AirField.Tank -> "Confirm your start and set the air to keep after ascent and the safety stop."
    AirField.SliderMinimum, AirField.SliderMaximum -> "Set the reminder slider range. OK opens pressure entry; Up opens the keypad."
    AirField.Reserve -> "Pressure to keep after ascent and the safety stop."
    AirField.ReceiverSetup -> if (air.receiver.connected) "View signals, map pressure or label other receiver values." else "Choose a nearby receiver to connect."
    AirField.Values, AirField.AddValue -> "Save a label and byte mapping for another value from your receiver."
    AirField.ValueSlot -> "← →  Choose a saved value"
    AirField.ValueLabel, AirField.ValueUnit -> "OK  Edit text with the phone keyboard or housing buttons."
    AirField.ValueSignal -> "← →  Choose a receiver signal; the live value updates when data arrives."
    AirField.Device -> "↑ ↓  Select device\nOK  Connect"
    AirField.Scan -> if (air.receiver.phase == AirReceiverPhase.Scanning) "Searching nearby. Select your receiver as soon as it appears." else if (air.receiver.phase == AirReceiverPhase.Connecting) "Waiting for the selected receiver." else "Search again for nearby Bluetooth devices."
    AirField.Characteristic -> "← →  Select signal\nOK  Read / subscribe"
    AirField.Calibrate -> "Enter the pressure shown on your actual gauge."
    AirField.Schedule -> "Repeat, once, or a sequence of messages at specific dive times."
    AirField.Times -> "Up to 24 alerts, each with its own time, message and input choice."
    AirField.Back -> "Return to ${airFieldValue(air, field)}. Changes are saved automatically."
    AirField.Hud -> "Choose one of six camera readouts. Pulse alternates every four seconds."
    AirField.Style -> "Save your message and set its colour, flashing, opacity and duration."
    else -> if (airFieldAdjustable(field)) "← →  Change value" else "OK  ${field.label}"
}

