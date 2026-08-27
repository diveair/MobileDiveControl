package com.mobiledivecontrol.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.DiagnosticsAction
import com.mobiledivecontrol.core.DiagnosticsCommand
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.components.STANDARD_ATMOSPHERE_KPA
import com.mobiledivecontrol.ui.components.depthMetersFromPressure
import com.mobiledivecontrol.ui.components.vacuumReadout

/**
 * Diagnostics screen — device info, sensor readouts, connection stats.
 * The bottom action row is shared by touch and the housing-button focus model.
 */
@Composable
fun DiagnosticsScreen(
    state: AppState,
    onCommand: (DiagnosticsCommand) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val depthMeters = depthMetersFromPressure(
        waterPressureKpa = state.safety.waterPressureKpa,
        surfaceAmbientKpa = state.safety.surfaceAmbientKpa,
    )
    val depthBaselineKpa = state.safety.surfaceAmbientKpa ?: STANDARD_ATMOSPHERE_KPA
    val pressureDeltaKpa = state.safety.waterPressureKpa?.let { water ->
        water - depthBaselineKpa
    }
    val vacuum = vacuumReadout(state.safety)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DiveColors.DeepBlack)
            .padding(16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Left column: identity and link health share one compact card.
            Column(modifier = Modifier.weight(1f)) {
                SectionHeader("Device & Connection")
                InfoCard {
                    InfoRow("Manufacturer", state.housing.manufacturerName ?: "—")
                    InfoRow("Firmware", state.housing.firmwareVersion ?: "—")
                    InfoRow("Hardware", state.housing.hardwareVersion ?: "—")
                    InfoRow("Software", state.housing.softwareVersion ?: "—")
                    InfoRow("Serial", state.housing.serialNumber ?: "—")
                    InfoRow("Model", state.housing.modelNumber ?: "—")
                    InfoRow("BLE State", state.bleConnectionState.name)
                    InfoRow("Connected", if (state.housing.connected) "Yes" else "No")
                    InfoRow("Input", if (state.housing.inputEnabled) "Enabled" else "Disabled")
                    InfoRow("Battery", state.housing.batteryPercent?.let { "$it%" } ?: "—")
                }
            }

            // Middle column: live pressure inputs and the exact derived depth value.
            Column(modifier = Modifier.weight(1f)) {
                SectionHeader("Sensors")
                InfoCard {
                    InfoRow("Water Pressure", state.safety.waterPressureKpa?.let { "%.1f kPa".format(it) } ?: "—")
                    InfoRow(
                        "Surface Baseline",
                        if (state.safety.surfaceAmbientKpa != null) {
                            "%.1f kPa captured".format(depthBaselineKpa)
                        } else {
                            "%.1f kPa standard".format(depthBaselineKpa)
                        },
                    )
                    InfoRow("Depth ΔP", pressureDeltaKpa?.let { "%.1f kPa".format(it) } ?: "—")
                    InfoRow("Calculated Depth", depthMeters?.let { "%.1f m".format(it) } ?: "—")
                    InfoRow("Water Temp", state.safety.waterTemperatureC?.let { "%.1f°C".format(it) } ?: "—")
                    InfoRow("Barometric", state.safety.barometricPressureKpa?.let { "%.1f kPa".format(it) } ?: "—")
                    InfoRow("Vacuum", vacuum?.let { "−%.1f kPa".format(it.kpa) } ?: "—")
                    InfoRow("Cover", when (state.safety.coverOpen) {
                        true -> "Open"
                        false -> "Closed"
                        null -> "Unknown"
                    })
                    InfoRow("Seal State", state.safety.sealState.name)
                }
            }

            // Right column: state and the pressure-to-depth explanation.
            Column(modifier = Modifier.weight(1f)) {
                SectionHeader("App State")
                InfoCard {
                    InfoRow("Mode", state.mode.name)
                    InfoRow("Controls", if (state.controlsLocked) "Locked" else "Unlocked")
                    InfoRow("Camera", state.camera.capabilityTier)
                    InfoRow("Recording", if (state.camera.recording) "Yes" else "No")
                    InfoRow("Last Button", state.housing.lastButton?.toString() ?: "—")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "DEPTH  max(0, water − surface) ÷ 9.81 kPa/m",
                        color = DiveColors.DiveCyan,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        text = "Barometric + vacuum excluded",
                        color = DiveColors.TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Housing: ←/→ select · OK activate · BACK camera",
                color = DiveColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            DiagnosticsActionTile(
                label = "Back",
                selected = state.diagnosticsAction == DiagnosticsAction.BackToCamera,
                onClick = { onCommand(DiagnosticsCommand.Activate(DiagnosticsAction.BackToCamera)) },
                modifier = Modifier.width(150.dp),
            )
            DiagnosticsActionTile(
                label = "Export",
                selected = state.diagnosticsAction == DiagnosticsAction.Export,
                onClick = { onCommand(DiagnosticsCommand.Activate(DiagnosticsAction.Export)) },
                modifier = Modifier.width(150.dp),
            )
        }
    }
}

@Composable
private fun DiagnosticsActionTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(
                color = if (selected) DiveColors.DiveCyanGlow else DiveColors.SurfaceCard,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) DiveColors.DiveCyan else DiveColors.SurfaceBorder,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
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
            // Ten device rows must coexist with the persistent bottom navigation bar on the
            // housing's landscape display. One dp keeps the rows legible without clipping Battery.
            .padding(vertical = 1.dp),
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
