package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.HeadingMath
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.theme.DiveColors
import java.util.Locale

/** ISA standard sea-level pressure. The reference when no surface baseline has been captured. */
const val STANDARD_ATMOSPHERE_KPA = com.mobiledivecontrol.core.STANDARD_SURFACE_PRESSURE_KPA

/** DiveIT BLE depth reference, with the requested 9.81 kPa/m conversion. */
fun depthMetersFromPressure(waterPressureKpa: Double?): Double? =
    com.mobiledivecontrol.core.pressureDepthMeters(waterPressureKpa)

/** Camera depth uses the same live external pressure and reference as Diagnostics. */
@Composable
fun DepthGauge(
    waterPressureKpa: Double?,
    useMetric: Boolean = true,
    temperatureCelsius: Double? = null,
    headingDegrees: Double? = null,
    headingTargetSynchronized: Boolean = false,
    modifier: Modifier = Modifier,
    maxDepthMeters: Double? = null,
) {
    val depthMeters = depthMetersFromPressure(waterPressureKpa)

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
        DepthReadout(depthMeters, useMetric, "now", color)
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp)
            .background(DiveColors.TextMuted))
        DepthReadout(maxDepthMeters, useMetric, "max",
            if (maxDepthMeters != null) DiveColors.DiveCyan else DiveColors.TextMuted)
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

/** Units stay with each number; small labels underneath keep the pair narrow and unambiguous. */
@Composable
private fun DepthReadout(meters: Double?, metric: Boolean, label: String, color: Color) {
    val value = meters?.let { String.format(Locale.US, "%.1f", if (metric) it else it * 3.28084) } ?: "--"
    Column(
        // Align the smaller depth numerals with the heading/temperature glyph bottoms.
        modifier = Modifier.offset(y = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = value + if (metric) "m" else "ft", color = color,
            fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(text = label, color = color.copy(alpha = 0.8f),
            // Halve the visible gap left by the two text lines' font metrics.
            modifier = Modifier.offset(y = (-4).dp),
            fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
