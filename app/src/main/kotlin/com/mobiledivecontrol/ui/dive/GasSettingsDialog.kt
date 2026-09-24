package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors

@Composable
internal fun GasSettingsDialog(profile: DiveProfileState, onCommand: (DiveSettingsCommand) -> Unit) {
    val gas = profile.active?.settings?.gas ?: profile.settings.gas
    val editable = profile.active == null
    val fields = gasMenuFields(gas)
    AlertDialog(
        onDismissRequest = { onCommand(DiveSettingsCommand.CloseGasMenu) },
        title = { Text("Breathing gas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (field in listOf(GasMenuField.Type, GasMenuField.Oxygen, GasMenuField.Helium)) {
                    val enabled = editable && field in fields
                    val focused = profile.gasMenuField == field
                    Row(Modifier.fillMaxWidth().background(DiveColors.SurfaceCard, RoundedCornerShape(8.dp))
                        .border(1.dp, if (focused) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onCommand(DiveSettingsCommand.SelectGasField(field)) }
                        .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(when (field) { GasMenuField.Type -> "Gas"; GasMenuField.Oxygen -> "Oxygen"; else -> "Helium" },
                                color = DiveColors.TextSecondary, fontSize = 11.sp)
                            Text(when (field) {
                                GasMenuField.Type -> when { gas == DiveGas() -> "Air"; gas.heliumPercent == 0 -> "Nitrox"; else -> "Trimix" }
                                GasMenuField.Oxygen -> "${gas.oxygenPercent}%"
                                else -> "${gas.heliumPercent}%"
                            }, color = if (enabled) DiveColors.TextPrimary else DiveColors.TextSecondary, fontWeight = FontWeight.Bold)
                        }
                        if (enabled) for (delta in listOf(-1, 1)) {
                            Box(Modifier.size(44.dp).clickable {
                                onCommand(DiveSettingsCommand.SelectGasField(field))
                                onCommand(DiveSettingsCommand.Adjust(delta))
                            }, contentAlignment = Alignment.Center) {
                                Text(if (delta < 0) "‹" else "›", color = DiveColors.DiveCyan, fontSize = 26.sp)
                            }
                        } else Text("FIXED", color = DiveColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                    }
                }
                Text("↑/↓ select · ←/→ adjust · BACK close", fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCommand(DiveSettingsCommand.CloseGasMenu) },
                modifier = Modifier.border(1.dp, if (profile.gasMenuField == GasMenuField.Done) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(8.dp))) {
                Text("DONE", color = DiveColors.DiveCyan)
            }
        },
    )
}
