package com.mobiledivecontrol.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.theme.DiveColors

/**
 * Diagnostics screen — device info, sensor readouts, connection stats.
 * Press OK to export diagnostic bundle. Press Back to return to camera.
 */
@Composable
fun DiagnosticsScreen(
    state: AppState,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack)
            .padding(top = 48.dp, bottom = 72.dp, start = 24.dp, end = 24.dp),
    ) {
        // Left column: Device info
        Column(modifier = Modifier.weight(1f)) {
            SectionHeader("Device Info")
            InfoCard {
                InfoRow("Manufacturer", state.housing.manufacturerName ?: "—")
                InfoRow("Firmware", state.housing.firmwareVersion ?: "—")
                InfoRow("Hardware", state.housing.hardwareVersion ?: "—")
                InfoRow("Software", state.housing.softwareVersion ?: "—")
                InfoRow("Serial", state.housing.serialNumber ?: "—")
                InfoRow("Model", state.housing.modelNumber ?: "—")
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionHeader("Connection")
            InfoCard {
                InfoRow("BLE State", state.bleConnectionState.name)
                InfoRow("Connected", if (state.housing.connected) "Yes" else "No")
                InfoRow("Input", if (state.housing.inputEnabled) "Enabled" else "Disabled")
                InfoRow("Battery", "${state.housing.batteryPercent}%")
            }
        }

        // Right column: Sensor readouts + status
        Column(modifier = Modifier.weight(1f)) {
            SectionHeader("Sensors")
            InfoCard {
                InfoRow("Water Pressure", state.safety.waterPressureKpa?.let { "%.1f kPa".format(it) } ?: "—")
                InfoRow("Water Temp", state.safety.waterTemperatureC?.let { "%.1f°C".format(it) } ?: "—")
                InfoRow("Barometric", state.safety.barometricPressureKpa?.let { "%.1f kPa".format(it) } ?: "—")
                InfoRow("Cover", when (state.safety.coverOpen) {
                    true -> "Open"
                    false -> "Closed"
                    null -> "Unknown"
                })
                InfoRow("Seal State", state.safety.sealState.name)
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionHeader("App State")
            InfoCard {
                InfoRow("Mode", state.mode.name)
                InfoRow("Controls", if (state.controlsLocked) "Locked" else "Unlocked")
                InfoRow("Camera", state.camera.capabilityTier)
                InfoRow("Recording", if (state.camera.recording) "Yes" else "No")
                InfoRow("Last Button", state.housing.lastButton?.toString() ?: "—")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Export hint
            Text(
                text = "Press OK to export diagnostics",
                color = DiveColors.DiveCyan,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(DiveColors.DiveCyanGlow, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = DiveColors.DiveCyan,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun InfoCard(
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DiveColors.SurfaceCard, RoundedCornerShape(12.dp))
            .border(1.dp, DiveColors.SurfaceBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = DiveColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = DiveColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
