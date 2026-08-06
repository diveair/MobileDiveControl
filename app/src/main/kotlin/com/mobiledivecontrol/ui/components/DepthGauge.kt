package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.theme.DiveColors

/** ISA standard sea-level pressure. The reference when no surface baseline has been captured. */
const val STANDARD_ATMOSPHERE_KPA = 101.325

/**
 * Depth gauge — bottom center of camera UI.
 * No icon. Just clean text: "12.5 m" or "41.0 ft".
 * Color-coded by depth range.
 *
 * [surfaceAmbientKpa] is the barometric reading captured while the suction cover was open, *not*
 * the live one. Once the vacuum check pulls 20 kPa out of the shell the internal sensor reads
 * ~81 kPa, and subtracting that would put the gauge at roughly two metres while the housing is
 * still sitting on the boat. The captured baseline is the only atmospheric reference that survives
 * a pulled vacuum; when it has never been captured this falls back to the standard atmosphere,
 * which is wrong by altitude and weather but never by the whole vacuum.
 *
 * [temperatureCelsius] rides along in the same pill: depth and water temperature are the two
 * numbers a diver reads together every few minutes, and pairing them here freed the top-centre
 * slot for the vacuum cluster, which needs the width.
 */
@Composable
fun DepthGauge(
    waterPressureKpa: Double?,
    surfaceAmbientKpa: Double?,
    useMetric: Boolean = true,
    temperatureCelsius: Double? = null,
    modifier: Modifier = Modifier,
) {
    val depthMeters = waterPressureKpa?.let { water ->
        val surface = surfaceAmbientKpa ?: STANDARD_ATMOSPHERE_KPA
        // Salt-water density correction is a separate work item — do not fold it in here.
        (water - surface).coerceAtLeast(0.0) / 9.81
    }

    val displayText = when {
        depthMeters == null -> if (useMetric) "-- m" else "-- ft"
        useMetric -> "%.1f m".format(depthMeters)
        else -> "%.1f ft".format(depthMeters * 3.28084)
    }

    val color = when {
        depthMeters == null -> DiveColors.TextMuted
        depthMeters < 0.5 -> DiveColors.DiveCyan
        depthMeters < 30.0 -> DiveColors.DiveCyan
        depthMeters < 40.0 -> DiveColors.Warning
        else -> DiveColors.Critical
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(
            text = displayText,
            color = color,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (temperatureCelsius != null) {
            Text(
                // Middle dot, not a slash or a pipe: the two readings are peers, and a separator
                // that reads as "per" or as a table border would imply a relationship they do not
                // have. Muted so the pill still parses as a depth gauge at a glance.
                text = " $READOUT_SEPARATOR ",
                color = DiveColors.TextMuted,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                // Unit spelled out, not a bare degree sign: 29° reads as either scale, and a
                // reading whose scale must be guessed is not a reading. Follows the pill's own
                // unit system, same as the depth beside it.
                text = if (useMetric) {
                    "%.1f°C".format(temperatureCelsius)
                } else {
                    "%.1f°F".format(temperatureCelsius * 9.0 / 5.0 + 32.0)
                },
                color = temperatureTint(temperatureCelsius),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The same bands TemperatureDisplay used in the top slot, so the move changes nothing but place. */
private fun temperatureTint(celsius: Double) = when {
    celsius < 18.0 -> DiveColors.DiveCyan
    celsius < 22.0 -> DiveColors.TextPrimary
    celsius < 28.0 -> DiveColors.Success
    else -> DiveColors.Warning
}

/**
 * @param kpa how far below surface ambient the shell is sitting, always ≥ 0. Rendered negative.
 * @param passed true once the state machine has issued a verdict, which is the only thing that
 *   turns the number green. Amber up to that point: a housing that has been pumped down but not
 *   yet held for the manufacturer's minimum is not a housing that has passed anything.
 */
data class VacuumReadout(
    val kpa: Double,
    val passed: Boolean,
)

/**
 * The live vacuum, or null when the seal system is not engaged and the number would be noise.
 *
 * Engaged means either the workflow is past the pump (so a vacuum is supposed to exist and its
 * absence is itself information) or the shell is measurably down regardless of state — the second
 * clause catches a housing that still holds a vacuum from a previous check after an app restart,
 * where the state machine has no history but the physics has not changed.
 *
 * [VACUUM_ENGAGED_KPA] is above the barometric sensor's ~5 kPa quantisation floor only in the
 * sense that it will not trip on rounding of a vented shell; it is a display gate, not a
 * measurement threshold, and nothing safety-relevant hangs off it.
 */
fun vacuumReadout(safety: SafetyState): VacuumReadout? {
    val current = safety.barometricPressureKpa ?: return null
    // Same reference ladder the adoption logic uses: a captured baseline first, then the dry
    // water-pressure sensor (true local atmosphere), then assumed sea level. Requiring the
    // captured baseline alone made the readout vanish exactly when the vacuum was adopted rather
    // than pumped — adoption is the one path where no baseline can ever have been captured.
    val ambient = safety.surfaceAmbientKpa
        ?: safety.waterPressureKpa
        ?: STANDARD_ATMOSPHERE_KPA
    val kpa = (ambient - current).coerceAtLeast(0.0)
    val engaged = safety.sealState in VACUUM_ENGAGED_STATES && kpa > VACUUM_ENGAGED_KPA
    if (!engaged) return null
    return VacuumReadout(kpa = kpa, passed = safety.sealState == SealState.Passed)
}

/** Workflow stages in which the shell is meant to be holding a vacuum. */
private val VACUUM_ENGAGED_STATES = setOf(
    SealState.MotorStopping,
    SealState.WaitingForCoverClosed,
    SealState.LeakMonitoring,
    SealState.Passed,
)

/** Below this the reading is sensor noise around ambient, not a vacuum. */
private const val VACUUM_ENGAGED_KPA = 0.5

private const val READOUT_SEPARATOR = "·"
