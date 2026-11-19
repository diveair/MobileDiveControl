package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors

@Composable
internal fun AirMessageEditor(edit: AirTextEntry, send: (AirAlertCommand) -> Unit) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(edit.text, TextRange(0, edit.text.length))) }
    LaunchedEffect(edit.text) {
        if (fieldValue.text != edit.text) fieldValue = TextFieldValue(edit.text, TextRange(edit.text.length))
    }
    AlertDialog(onDismissRequest = { send(AirAlertCommand.CancelText) },
        title = { Text(when (edit.purpose) {
            AirTextPurpose.ValueLabel -> "Value label"
            AirTextPurpose.ValueUnit -> "Unit label"
            AirTextPurpose.Message -> edit.sequenceTime?.let { "Alert at ${it / 60} min" } ?: "Alert text"
        }) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = fieldValue, onValueChange = {
                val clean = cleanAirMessage(it.text)
                fieldValue = it.copy(text = clean, selection = TextRange(it.selection.start.coerceAtMost(clean.length), it.selection.end.coerceAtMost(clean.length)))
                send(AirAlertCommand.EditText(clean))
            }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 2,
                label = { Text("${if (edit.purpose == AirTextPurpose.Message) "Message" else "Label"} · ${edit.text.length}/$AIR_MESSAGE_LIMIT") }, isError = edit.error != null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                (-2..2).forEach { delta ->
                    val character = AIR_TEXT_ALPHABET[Math.floorMod(edit.character + delta, AIR_TEXT_ALPHABET.length)]
                    Box(Modifier.size(39.dp).padding(2.dp).background(DiveColors.SurfaceCard, RoundedCornerShape(5.dp))
                        .border(1.dp, if (delta == 0) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(5.dp))
                        .clickable { send(AirAlertCommand.MoveCharacter(delta)); send(AirAlertCommand.AddCharacter) }, contentAlignment = Alignment.Center) {
                        Text(if (character == ' ') "SPACE" else character.toString(), color = if (delta == 0) DiveColors.DiveCyan else DiveColors.TextSecondary,
                            fontSize = if (character == ' ') 9.sp else 17.sp)
                    }
                }
            }
            Text("← → choose · OK adds · ↓ deletes\nZ± jump letters · SHUTTER saves · ↑ cancels",
                color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            edit.error?.let { Text(it, color = DiveColors.Warning, fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(onClick = { send(AirAlertCommand.SaveText) }) { Text("SAVE · SHUTTER") } },
        dismissButton = { TextButton(onClick = { send(AirAlertCommand.CancelText) }) { Text("CANCEL · ↑") } })
}
