package com.mobiledivecontrol.ui.dive

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.theme.DiveColors
import java.util.Locale
import kotlin.math.ceil

@Composable
fun DecompressionReadout(state: DecompressionState, metric: Boolean, compact: Boolean = false) {
    val readings = state.readings
    val ceiling = readings?.ceilingMeters ?: 0.0
    val color = when {
        ceiling > (state.depthMeters ?: Double.MAX_VALUE) || (state.ppO2Ata ?: 0.0) > 1.6 -> DiveColors.Critical
        readings == null || ceiling > 0 || (readings.cnsPercent >= 80) -> DiveColors.Warning
        else -> DiveColors.TextPrimary
    }
    Column {
        if (readings == null) {
            Text(when (state.history) {
                DecoHistory.Uninitialized -> "NDL / CEILING / CNS / OTU — initialize Dive history"
                DecoHistory.SurfaceIntervalUnconfirmed -> "NDL / CEILING / CNS / OTU — confirm surface interval"
                else -> "NDL / CEILING / CNS / OTU — history incomplete"
            }, color = color, fontSize = 11.sp)
        } else {
            val ndl = when {
                readings.ndlSeconds >= Buhlmann.NDL_CAP_SECONDS -> "99+ min"
                readings.ndlSeconds < 60 -> "${readings.ndlSeconds} sec"
                else -> "${readings.ndlSeconds / 60} min"
            }
            val ceilingUnits = ceil(ceiling * (if (metric) 1.0 else 3.28084) * 10) / 10
            Text("NDL $ndl  ·  CEILING ${"%.1f".format(Locale.US, ceilingUnits)} ${if (metric) "m" else "ft"}",
                color = color, fontSize = if (compact) 11.sp else 13.sp, fontFamily = FontFamily.Monospace)
            Text("CNS ${if (readings.cnsOutsideTable) "≥" else ""}${ceil(readings.cnsPercent).toInt()}%  ·  OTU 24h ${ceil(readings.otu24Hours).toInt()}",
                color = color, fontSize = 11.sp)
        }
        if (!compact) {
            val oxygen = state.ppO2Ata?.let { "%.2f ATA".format(Locale.US, it) } ?: "—"
            Text("ppO₂ $oxygen  ·  ZH-L16C GF 40/85  ·  Sea level / fresh water", color = color, fontSize = 10.sp)
            if (readings?.cnsOutsideTable == true) Text("CNS lower bound: exposure exceeded the 1.6 ATA table", color = DiveColors.Critical, fontSize = 10.sp)
        }
    }
}
