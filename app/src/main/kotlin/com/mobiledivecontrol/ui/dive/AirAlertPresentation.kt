package com.mobiledivecontrol.ui.dive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.mobiledivecontrol.ui.components.AutoShrinkText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

/** Render the actual alert at its actual layout width, then scale the result. No parallel mock UI. */
@Composable
internal fun AirAlertPreview(air: AirAlertState, dive: DiveProfileState) {
    val number = if (air.page == AirPage.Times) air.settings.timesSeconds.distinct().sorted().indexOf(air.settings.timesSeconds[air.timeSlot]) + 1 else air.delivered + 1
    val preview = air.copy(settings = air.settings.copy(enabled = true), entry = null, simulation = null,
        elapsedMs = air.settings.scheduledAtMs(number) ?: 0, delivered = number,
        prompt = AirPrompt(number, air.settings.needsConfirmation(number), 0))
    Layout(modifier = Modifier.heightIn(max = 132.dp),
        content = { AirAlertCard(preview, dive, {}, Modifier.width(540.dp)) }) { children, constraints ->
        val width = 540.dp.roundToPx()
        val card = children.single().measure(Constraints.fixedWidth(width))
        val scale = minOf(constraints.maxWidth.toFloat() / width, constraints.maxHeight.toFloat() / card.height)
        layout(constraints.maxWidth, (card.height * scale).roundToInt()) {
            card.placeWithLayer(((constraints.maxWidth - width * scale) / 2).roundToInt(), 0) {
                scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
fun AirAlertOverlay(state: AppState, onCommand: (DiveSettingsCommand) -> Unit, modifier: Modifier = Modifier,
    drawScrim: Boolean = true) {
    if (airAlertPresentationBlocked(state)) return
    if (state.airAlerts.textEntry != null && state.airAlerts.gasWarning?.visible != true) {
        AirMessageEditor(state.airAlerts.textEntry!!) { onCommand(DiveSettingsCommand.Alerts(it)) }
        return
    }
    val test = state.airAlerts.simulation
    val air = test?.air ?: state.airAlerts
    val dive = test?.profile ?: state.diveProfile
    val send: (AirAlertCommand) -> Unit = { onCommand(DiveSettingsCommand.Alerts(it)) }
    BackHandler(enabled = test != null || air.entry != null || air.buddyConfirmation) {
        send(if (test != null) AirAlertCommand.StopTest else if (air.buddyConfirmation) AirAlertCommand.CancelBuddyAir else AirAlertCommand.CancelEntry)
    }
    if (drawScrim && !LocalAlertRecording.current && (air.entry != null || air.buddyConfirmation || test != null)) Box(Modifier.fillMaxSize().background(Color.Black.copy(
        alpha = if (air.entry != null || air.buddyConfirmation) 0.7f else 0f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { })
    if (air.buddyConfirmation) { AirBuddyConfirmation(send, modifier); return }
    if (air.sharedAir && air.entry == null) { AirSharedAirNotice(send); return }
    if (air.entry?.keypad == true) { PressureKeypad(air, send, modifier); return }
    if (air.entry == null && air.gasWarning?.visible == true) {
        AirGasWarningCard(air, dive, send, modifier)
        return
    }
    if (air.entry != null || air.prompt != null) {
        AirAlertCard(air, dive, send, modifier.width(540.dp))
        return
    }
}

private fun airIsSimpleReminder(air: AirAlertState): Boolean {
    val prompt = air.prompt ?: return false
    if (air.entry != null || prompt.confirmation) return false
    val action = air.settings.actionFor(prompt.number)
    return action == AirSequenceAction.Reminder ||
        (air.settings.source == AirSource.Manual && air.settings.confirmation == AirConfirmation.Never)
}

/** This is shared verbatim by live reminders, pressure entry, the simulator and the settings preview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirAlertCard(air: AirAlertState, dive: DiveProfileState, send: (AirAlertCommand) -> Unit,
    modifier: Modifier) {
    val prompt = air.prompt
    val now = pressureMonotonicMs()
    val pressure = air.entry != null || prompt?.confirmation == true
    val entry = air.entry ?: if (pressure) airSuggestedEntry(air, dive, now) else null
    val alert = prompt != null
    val s = air.settings
    val reminderOnly = airIsSimpleReminder(air)
    val message = s.messageFor(prompt?.number ?: 1).let {
        if (reminderOnly && it == "CHECK AIR REMINDER") "CHECK YOUR AIR" else it
    }
    val colour = if (alert) Color(s.colour.argb) else DiveColors.DiveCyan
    var pulse by remember(prompt?.number, s.flash) { mutableStateOf(true) }
    LaunchedEffect(alert, s.flash) { while (alert && s.flash) { delay(750); pulse = !pulse } }
    val estimate = AirAlertEngine.estimate(air, dive, now)
    val readingEstimate = AirAlertEngine.readingEstimate(air.copy(entry = entry), dive, now)
    Column(modifier
        .background(DiveColors.DeepBlack.copy(alpha = if (LocalAlertRecording.current) 0f else if (alert) s.opacityPercent / 100f else 1f), RoundedCornerShape(14.dp))
        .border(if (pulse || !alert || !s.flash) 3.dp else 1.dp,
            colour.copy(alpha = if (pulse || !alert || !s.flash) 1f else 0.35f), RoundedCornerShape(14.dp))
        .padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutoShrinkText(when (entry?.purpose) {
            AirEntryPurpose.NextTank -> if (air.sharedAir) "OWN TANK" else if (air.tankNumber == 1) "SECOND TANK" else "NEXT TANK"
            AirEntryPurpose.TestStart -> "START TEST DIVE"
            AirEntryPurpose.Start -> "STARTING PRESSURE"
            AirEntryPurpose.SliderMinimum -> "SLIDER MINIMUM"
            AirEntryPurpose.SliderMaximum -> "SLIDER MAXIMUM"
            AirEntryPurpose.Reserve -> "SURFACE RESERVE"
            AirEntryPurpose.Calibration -> "GAUGE READING"
            else -> message
        }, color = colour, style = TextStyle(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(), minFontSize = 14.sp, maxLines = 2)
        if (reminderOnly) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                ReminderTime("DIVE TIME", airDiveClock(air.elapsedMs), Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(36.dp).background(colour.copy(alpha = 0.4f)))
                ReminderTime("NEXT ALERT", airNextAlertAtMs(air)?.let(::airDiveClock) ?: "None", Modifier.weight(1f))
            }
            AirAction("OK", colour) { send(AirAlertCommand.PromptAction(2)) }
            return@Column
        }
        if (entry != null) {
            val value = entry.text.toIntOrNull() ?: 0
            val range = s.pressureRange(entry.purpose)
            if (entry.purpose == AirEntryPurpose.Reading) {
                val expected = estimate?.takeIf { it.projected || it.live }?.let { s.unit.display(it.pressureBar).toString() } ?: "—"
                PressureComparison(air, expected, if (estimate?.live == true) "LIVE PRESSURE" else "EXPECTED", colour, entry)
                EstimatedBottomTime(readingEstimate?.bottomMinutes, colour)
            } else {
                PressureValue(entry.text, s.unit)
                Text(when (entry.purpose) {
                    AirEntryPurpose.NextTank -> "Pressure Now"
                    AirEntryPurpose.Start, AirEntryPurpose.TestStart -> if (entry.edited) "Pressure Now" else "Default Start Pressure"
                    AirEntryPurpose.SliderMinimum -> "Lowest slider pressure"
                    AirEntryPurpose.SliderMaximum -> "Highest slider pressure"
                    AirEntryPurpose.Reserve -> "Surface reserve"
                    else -> "Pressure Now"
                }, color = colour, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Slider(value = value.coerceIn(range).toFloat(), onValueChange = { send(AirAlertCommand.SetPressure(it.roundToInt())) },
                valueRange = range.first.toFloat()..range.last.toFloat(), steps = 0,
                thumb = { PressureSliderGrip() },
                colors = SliderDefaults.colors(thumbColor = DiveColors.CoolBlue, activeTrackColor = DiveColors.CoolBlue, inactiveTrackColor = DiveColors.SurfaceElevated),
                modifier = Modifier.fillMaxWidth().height(38.dp).semantics { contentDescription = "Reported tank pressure, Z plus or minus" })
            entry.error?.let { Text(it, color = DiveColors.Warning, fontSize = 12.sp, lineHeight = 16.sp) }
            if (entry.purpose == AirEntryPurpose.NextTank) Text(
                "Confirm only after switching and verifying gas delivery. Use the same breathing gas as your current dive plan.",
                color = DiveColors.TextPrimary, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AirAction(when (entry.purpose) { AirEntryPurpose.NextTank -> "OK  SWITCH CONFIRMED"; AirEntryPurpose.TestStart -> "OK  START TEST"; AirEntryPurpose.Reading -> "OK  REPORT PRESSURE"; else -> "OK  SAVE" }, colour) {
                    send(if (air.entry != null) AirAlertCommand.SavePressure else AirAlertCommand.PromptAction(0))
                }
                AirAction("↑  KEYPAD", DiveColors.TextSecondary) { send(AirAlertCommand.UseKeypad(true)) }
                AirAction(if (alert) "↓  SKIP" else "↓  CANCEL", DiveColors.TextSecondary) {
                    send(if (air.entry != null) AirAlertCommand.CancelEntry else AirAlertCommand.PromptAction(2))
                }
            }
        } else {
            if (airTracksPressure(s) && s.actionFor(prompt?.number ?: 1) != AirSequenceAction.Reminder) {
                val expected = estimate?.takeIf { it.projected || it.live }?.let { s.unit.display(it.pressureBar).toString() } ?: "—"
                PressureComparison(air, expected, if (estimate?.live == true) "LIVE PRESSURE" else "EXPECTED", colour)
                EstimatedBottomTime(estimate?.bottomMinutes, colour)
            } else if (s.messageFor(prompt?.number ?: 1) == "CHECK AIR REMINDER") {
                Text("Check your tank pressure", color = DiveColors.TextPrimary, fontSize = 17.sp, lineHeight = 22.sp)
            }
            if (s.source == AirSource.Receiver && estimate?.live != true) Text("Signal unavailable · use your gauge", color = DiveColors.Warning, fontSize = 12.sp, lineHeight = 16.sp)
            AirAction("OK  DISMISS", colour) { send(AirAlertCommand.PromptAction(2)) }
        }
    }
}

@Composable
private fun ReminderTime(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = DiveColors.TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
        Text(value, color = DiveColors.TextPrimary, fontSize = 22.sp, lineHeight = 28.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

/** Rounded, ribbed handle matching the housing's horizontal Z control. Slider owns its gestures. */
@Composable
private fun PressureSliderGrip() {
    Box(Modifier.size(width = 108.dp, height = 26.dp)
        .background(DiveColors.CoolBlue, RoundedCornerShape(6.dp))
        .border(1.dp, DiveColors.TextPrimary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
        .drawBehind {
            val ink = Color.Black.copy(alpha = 0.3f)
            repeat(4) { index ->
                val inset = (8 + index * 4).dp.toPx()
                drawLine(ink, Offset(inset, 5.dp.toPx()), Offset(inset, size.height - 5.dp.toPx()), 1.5.dp.toPx())
                drawLine(ink, Offset(size.width - inset, 5.dp.toPx()), Offset(size.width - inset, size.height - 5.dp.toPx()), 1.5.dp.toPx())
            }
        }, contentAlignment = Alignment.Center) {
        Text("Z+/\u2212", color = DiveColors.DeepBlack, fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PressureValue(value: String, unit: AirPressureUnit, colour: Color = DiveColors.TextPrimary) {
    Text(buildAnnotatedString {
        append(value.ifBlank { "—" })
        withStyle(SpanStyle(fontSize = 14.sp, fontFamily = FontFamily.Default)) {
            append(" ${unit.label}")
        }
    }, color = colour, fontSize = 36.sp, lineHeight = 43.sp,
        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
}

@Composable
private fun PressureComparison(air: AirAlertState, expected: String, expectedLabel: String, colour: Color,
    entry: AirPressureEntry? = null) {
    val editing = entry?.edited == true
    val last = if (editing) entry!!.text else air.observation?.let { air.settings.unit.display(it.bar).toString() } ?: entry?.text ?: "—"
    val label = when {
        editing -> "TANK PRESSURE"
        air.observation == null && entry != null -> "Default Start Pressure"
        else -> "LAST ALERT"
    }
    Row(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            PressureValue(last, air.settings.unit, if (editing) DiveColors.CoolBlue else DiveColors.TextPrimary)
            Text(label, color = if (editing) DiveColors.CoolBlue else DiveColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Box(Modifier.width(1.dp).height(54.dp).background(colour.copy(alpha = 0.45f)))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            PressureValue(expected, air.settings.unit)
            Text(expectedLabel, color = colour, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun EstimatedBottomTime(minutes: Double?, colour: Color) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ESTIMATED BOTTOM TIME", color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        Text(minutes?.let { "${floor(it).toInt()} min" } ?: "—", color = colour,
            fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AirAction(text: String, colour: Color, onClick: () -> Unit) {
    Text(text, color = colour, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, colour.copy(alpha = 0.5f), RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(10.dp))
}

@Composable
private fun PressureKeypad(air: AirAlertState, send: (AirAlertCommand) -> Unit, modifier: Modifier) {
    val entry = air.entry ?: return
    val unit = when (entry.purpose) { AirEntryPurpose.Interval -> "SEC"; AirEntryPurpose.Time -> "MIN"; AirEntryPurpose.ConfirmationNumber -> ""; else -> air.settings.unit.label }
    val numeric = entry.purpose in setOf(AirEntryPurpose.Interval, AirEntryPurpose.Time, AirEntryPurpose.ConfirmationNumber)
    Row(modifier.widthIn(max = 570.dp).fillMaxWidth(0.8f).heightIn(max = 320.dp)
        .background(DiveColors.DeepBlack.copy(alpha = if (LocalAlertRecording.current) 0f else 1f), RoundedCornerShape(14.dp)).border(2.dp, DiveColors.DiveCyan, RoundedCornerShape(14.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (entry.purpose) {
                AirEntryPurpose.Reading -> "TANK PRESSURE"
                AirEntryPurpose.NextTank -> "CONFIRM TANK SWITCH"
                AirEntryPurpose.Start -> "CONFIRM START"
                AirEntryPurpose.TestStart -> "TEST START PRESSURE"
                AirEntryPurpose.SliderMinimum -> "SLIDER MINIMUM"
                AirEntryPurpose.SliderMaximum -> "SLIDER MAXIMUM"
                AirEntryPurpose.Reserve -> "SURFACE RESERVE"
                AirEntryPurpose.Calibration -> "GAUGE READING"
                AirEntryPurpose.Interval -> "DIVE TIME"
                AirEntryPurpose.Time -> if (entry.adding) "ADD DIVE TIME" else "EDIT DIVE TIME"
                AirEntryPurpose.ConfirmationNumber -> if (entry.adding) "ADD ALERT NUMBER" else "EDIT ALERT NUMBER"
            }, color = DiveColors.DiveCyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("${entry.text.ifBlank { "—" }} $unit", color = DiveColors.TextPrimary, fontSize = 34.sp, fontFamily = FontFamily.Monospace)
            Text("Z±  Adjust${if (numeric) "" else " by ${air.settings.unit.step}"}\nArrows + OK  Type\nSAVE + OK  Confirm", color = DiveColors.TextPrimary, fontSize = 13.sp, lineHeight = 21.sp)
            Text(if (entry.purpose == AirEntryPurpose.Start) "Save confirms the gauge reading for your next dive."
                else if (entry.purpose == AirEntryPurpose.SliderMinimum || entry.purpose == AirEntryPurpose.SliderMaximum) "Set the range used by reminder sliders."
                else if (entry.purpose == AirEntryPurpose.NextTank) "Save confirms you switched, verified gas delivery and kept the same breathing gas."
                else if (numeric) "The first digit replaces this value." else "Enter the reading on your gauge.", color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            entry.error?.let { Text(it, color = DiveColors.Warning, fontSize = 12.sp) }
        }
        Column(Modifier.width(218.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { col ->
                        val index = row * 3 + col
                        val label = when (index) { in 0..8 -> "${index + 1}"; 9 -> "0"; 10 -> "⌫"; else -> "SAVE" }
                        PressureKey(label, index == entry.selected, Modifier.weight(1f).fillMaxHeight()) { send(AirAlertCommand.Key(index)) }
                    }
                }
            }
            PressureKey(if (entry.purpose.isPressure()) "BACK TO SLIDER" else "CANCEL", entry.selected == 12, Modifier.fillMaxWidth().height(28.dp)) { send(if (entry.purpose.isPressure()) AirAlertCommand.UseKeypad(false) else AirAlertCommand.CancelEntry) }
        }
    }
}

@Composable
private fun PressureKey(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.background(if (selected) DiveColors.SurfaceElevated else DiveColors.SurfaceCard, RoundedCornerShape(7.dp))
        .border(if (selected) 2.dp else 1.dp, if (selected) DiveColors.DiveCyan else DiveColors.TextSecondary.copy(alpha = 0.25f), RoundedCornerShape(7.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary, fontSize = if (label.length > 1) 12.sp else 23.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AirPressureHud(air: AirAlertState, dive: DiveProfileState, modifier: Modifier = Modifier) {
    if (!airTracksPressure(air.settings) || air.settings.hudMode == AirHudMode.Off) return
    // Keep alternating values and receiver freshness current even before the dive clock starts.
    var nowMs by remember { mutableLongStateOf(pressureMonotonicMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250L)
            nowMs = pressureMonotonicMs()
        }
    }
    val readout = airCameraReadout(air, dive, nowMs) ?: return
    Crossfade(targetState = readout, label = "Air camera readout",
        modifier = modifier.padding(horizontal = 8.dp, vertical = 1.dp)) { current ->
        Text(current.text, color = if (current.unavailable) DiveColors.Warning else DiveColors.DiveCyan,
            fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
