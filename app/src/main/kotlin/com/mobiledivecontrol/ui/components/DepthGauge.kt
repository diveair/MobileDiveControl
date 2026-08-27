package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobiledivecontrol.core.HeadingMath
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.theme.DiveColors

/** ISA standard sea-level pressure. The reference when no surface baseline has been captured. */
const val STANDARD_ATMOSPHERE_KPA = 101.325

/**
 * Converts absolute water pressure to gauge depth using the captured surface atmosphere.
 * The live internal barometer is intentionally not an input: pulling a housing vacuum changes it
 * without changing depth. The current `9.81 kPa/m` divisor is the freshwater approximation used
 * throughout the app; a salt-water density selector remains a separate calibration task.
 */
fun depthMetersFromPressure(
    waterPressureKpa: Double?,
    surfaceAmbientKpa: Double?,
): Double? {
    val water = waterPressureKpa ?: return null
    val surface = surfaceAmbientKpa ?: STANDARD_ATMOSPHERE_KPA
    return (water - surface).coerceAtLeast(0.0) / 9.81
}

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
 *
 * [headingDegrees] occupies the exact middle slot. Equal-width depth and temperature cells keep
 * the compass geometrically centred even as either reading gains digits. When
 * [headingTargetSynchronized] is true it shares the navigation arrow's success colour.
 */
@Composable
fun DepthGauge(
    waterPressureKpa: Double?,
    surfaceAmbientKpa: Double?,
    useMetric: Boolean = true,
    temperatureCelsius: Double? = null,
    headingDegrees: Double? = null,
    headingTargetSynchronized: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val depthMeters = depthMetersFromPressure(waterPressureKpa, surfaceAmbientKpa)

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
        Box(
            modifier = Modifier.width(SIDE_READOUT_WIDTH),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = displayText,
                color = color,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        ReadoutSeparator()
        Text(
            text = headingDegrees?.let(::formatHeading) ?: "---° --",
            color = headingReadoutColor(headingDegrees, headingTargetSynchronized),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        ReadoutSeparator()
        Box(
            modifier = Modifier.width(SIDE_READOUT_WIDTH),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                // Unit spelled out, not a bare degree sign: 29° reads as either scale, and a
                // reading whose scale must be guessed is not a reading. Follows the pill's own
                // unit system, same as the depth beside it.
                text = temperatureCelsius?.let { temperature ->
                    if (useMetric) {
                        "%.1f°C".format(temperature)
                    } else {
                        "%.1f°F".format(temperature * 9.0 / 5.0 + 32.0)
                    }
                } ?: if (useMetric) "--.-°C" else "--.-°F",
                color = temperatureCelsius?.let(::temperatureTint) ?: DiveColors.TextMuted,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal fun headingReadoutColor(
    headingDegrees: Double?,
    headingTargetSynchronized: Boolean,
) = when {
    headingDegrees == null -> DiveColors.TextMuted
    headingTargetSynchronized -> DiveColors.Success
    else -> DiveColors.HeadingViolet
}

@Composable
private fun ReadoutSeparator() {
    Text(
        text = " $READOUT_SEPARATOR ",
        color = DiveColors.TextMuted,
        style = MaterialTheme.typography.titleMedium,
    )
}

private fun formatHeading(degrees: Double): String {
    val normalized = HeadingMath.normalize(degrees)
    val value = normalized.toInt().let { whole ->
        // Round manually after normalisation so 359.6 becomes 000 rather than an invalid 360.
        if (normalized - whole >= 0.5) (whole + 1) % 360 else whole
    }
    return "${value.toString().padStart(3, '0')}° ${cardinal(normalized)}"
}

private fun cardinal(degrees: Double): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[((HeadingMath.normalize(degrees) + 22.5) / 45.0).toInt() % names.size]
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
private val SIDE_READOUT_WIDTH = 76.dp
