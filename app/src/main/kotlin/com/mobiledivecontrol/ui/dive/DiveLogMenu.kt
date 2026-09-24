package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DiveLogMenu(profile: DiveProfileState, metric: Boolean, onCommand: (DiveSettingsCommand) -> Unit) {
    val log = profile.logMenuSession ?: return
    AlertDialog(
        onDismissRequest = { onCommand(DiveSettingsCommand.CloseLogMenu) },
        title = { Text("Dive Log") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Instant.ofEpochMilli(log.startedAtEpochMs).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM d · HH:mm")))
                Text("MAX ${diveDepth(log.maxDepthMeters, metric)} · TIME ${diveTime(log.elapsedMs / 1000)}", fontSize = 13.sp)
                for (delete in listOf(true, false)) {
                    Row(Modifier.fillMaxWidth().background(DiveColors.SurfaceCard, RoundedCornerShape(8.dp))
                        .border(1.dp, if (profile.logMenuDeleteSelected == delete) DiveColors.DiveCyan else Color.Transparent,
                            RoundedCornerShape(8.dp))
                        .clickable {
                            onCommand(DiveSettingsCommand.SelectLogMenuAction(delete))
                            onCommand(DiveSettingsCommand.Confirm)
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (delete) "Delete Dive Log" else "Back", color = DiveColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text("›", color = DiveColors.DiveCyan, fontSize = 24.sp)
                    }
                }
                Text("↑/↓ select · OK open · BACK close", fontSize = 11.sp)
            }
        },
        confirmButton = {},
    )
}
