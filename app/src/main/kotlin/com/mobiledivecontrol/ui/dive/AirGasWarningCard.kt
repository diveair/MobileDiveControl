package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.components.AutoShrinkText
import kotlinx.coroutines.delay

@Composable
internal fun AirGasWarningCard(air: AirAlertState, dive: DiveProfileState,
    send: (AirAlertCommand) -> Unit, modifier: Modifier = Modifier) {
    val warning = air.gasWarning ?: return
    val critical = warning.level.critical
    val accent = if (critical) DiveColors.Critical else DiveColors.Warning
    var bright by remember { mutableStateOf(true) }
    LaunchedEffect(critical) { while (critical) { delay(600); bright = !bright } }
    Column(modifier.widthIn(max = 560.dp).background(DiveColors.DeepBlack.copy(alpha = if (LocalAlertRecording.current) 0f else 1f), RoundedCornerShape(14.dp))
        .border(if (bright) 3.dp else 1.dp, accent, RoundedCornerShape(14.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        AutoShrinkText(airGasTitle(warning, dive), accent,
            TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(), maxLines = 2)
        Text(when (warning.level) {
            AirGasLevel.Empty -> "Check your gauge. Signal your buddy and follow your emergency training."
            AirGasLevel.Critical -> "Gas margin critically low. Check your gauge and signal your buddy. Optional safety stop only if gas allows."
            else -> "The estimate has reached your ascent and reserve budget. Check your gauge and follow your dive plan."
        }, color = DiveColors.TextPrimary, fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        // Keep the warning latched, but never show a stale exit forecast after losing its inputs.
        val surface = warning.projectedSurfaceBar.takeIf {
            AirAlertEngine.estimate(air, dive, pressureMonotonicMs())?.ascentAndStopBar != null
        }
        if (surface != null) Text("EST. AT SURFACE  " + air.settings.unit.display(surface.coerceAtLeast(0.0)) +
            " " + air.settings.unit.label + "  ·  RESERVE  " + air.settings.unit.display(air.settings.reserveBar) +
            " " + air.settings.unit.label, color = accent, fontSize = 12.sp, lineHeight = 16.sp)
        if (dive.decompressionHold || dive.active?.limitReached == true) Text(
            "Decompression obligation: follow your dive computer.", color = DiveColors.Warning, fontSize = 13.sp)
        if (air.tankNumber > 1) Text("TANK " + air.tankNumber + " · MANUAL PRESSURE", color = DiveColors.DiveCyan, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val actions = if (critical) listOf("CHECK PRESSURE", "SWITCH TANK", "BUDDY AIR") else listOf("CHECK PRESSURE", "SWITCH TANK", "BUDDY AIR", "SKIP")
            actions.forEachIndexed { index, text ->
                val selected = warning.selectedAction == index
                Box(Modifier.weight(1f).height(44.dp)
                    .background(if (selected) DiveColors.SurfaceElevated else DiveColors.SurfaceCard, RoundedCornerShape(6.dp))
                    .border(if (selected) 2.dp else 1.dp, if (selected) accent else DiveColors.TextSecondary, RoundedCornerShape(6.dp))
                    .clickable { send(AirAlertCommand.GasAction(index)) }, contentAlignment = Alignment.Center) {
                    Text(text, color = if (selected) accent else DiveColors.TextPrimary,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
        Text(if (critical) "← →  Select   ·   OK  Choose   ·   Alarm continues until surface or confirmed gas supply"
            else "← →  Select   ·   OK  Choose   ·   Skipping keeps frequent air checks active",
            color = DiveColors.TextSecondary, fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun AirBuddyConfirmation(send: (AirAlertCommand) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(540.dp).background(DiveColors.DeepBlack.copy(alpha = if (LocalAlertRecording.current) 0f else 1f), RoundedCornerShape(14.dp))
        .border(2.dp, DiveColors.DiveCyan, RoundedCornerShape(14.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("BUDDY AIR", color = DiveColors.DiveCyan, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("Confirm only once you are breathing from your buddy’s alternate air source. Use the breathing gas in your dive plan.",
            color = DiveColors.TextPrimary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Text("Check the buddy’s gauge together. Their remaining gas is unknown to this app. End the dive according to your training and dive computer.",
            color = DiveColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BuddyAirAction("OK  BUDDY AIR CONFIRMED") { send(AirAlertCommand.ConfirmBuddyAir) }
            BuddyAirAction("↓  CANCEL") { send(AirAlertCommand.CancelBuddyAir) }
        }
    }
}

@Composable
private fun BuddyAirAction(label: String, action: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).border(1.dp, DiveColors.DiveCyan, RoundedCornerShape(6.dp))
        .clickable(onClick = action).padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = DiveColors.DiveCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun AirSharedAirNotice(send: (AirAlertCommand) -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 540.dp).background(DiveColors.DeepBlack.copy(alpha = if (LocalAlertRecording.current) 0f else 1f), RoundedCornerShape(10.dp))
            .border(1.dp, DiveColors.DiveCyan, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text("BUDDY AIR · GAS UNKNOWN", color = DiveColors.DiveCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Check buddy’s gauge · Follow your dive computer", color = DiveColors.TextSecondary, fontSize = 11.sp)
            }
            BuddyAirAction("OK  OWN TANK") { send(AirAlertCommand.GasAction(1)) }
        }
    }
}
