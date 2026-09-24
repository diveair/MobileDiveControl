package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import java.util.Locale
import kotlin.math.ceil

/** Live readings belong to the active profile; historical logs only show their own recorded peaks. */
internal fun diveLogExposureText(profile: DiveProfileState, selected: DiveSession, metric: Boolean): List<String> {
    val live = selected === profile.active
    val readings = profile.decompression.readings.takeIf {
        live && profile.sensorAvailable && profile.decompression.history == DecoHistory.Tracking
    }
    val exposure = if (live) readings?.let {
        DiveExposureSummary(it.ceilingMeters, it.cnsPercent, it.cnsOutsideTable,
            it.otu24Hours, profile.decompression.ppO2Ata)
    } else selected.exposure
    val prefix = if (live) "" else "MAX "
    val ceiling = exposure?.maxCeilingMeters?.let {
        val roundedUp = ceil(it * (if (metric) 1.0 else 3.28084) * 10) / 10
        String.format(Locale.US, "%.1f%s", roundedUp, if (metric) "m" else "ft")
    } ?: "—"
    val cns = exposure?.let { "${if (it.cnsOutsideTable) "≥" else ""}${ceil(it.maxCnsPercent).toInt()}%" } ?: "—"
    val otu = exposure?.let { ceil(it.maxOtu24Hours).toInt().toString() } ?: "—"
    val ppO2 = exposure?.maxPpO2Ata?.let { String.format(Locale.US, "%.2f ATA", it) } ?: "—"
    return listOf("${prefix}CEILING $ceiling", "${prefix}CNS $cns", "${prefix}OTU 24h $otu", "${prefix}ppO₂ $ppO2")
}

@Composable
internal fun DiveLogExposureReadout(profile: DiveProfileState, selected: DiveSession, metric: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        diveLogExposureText(profile, selected, metric).forEach { text ->
            Text(text, color = DiveColors.TextSecondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
        }
    }
}
