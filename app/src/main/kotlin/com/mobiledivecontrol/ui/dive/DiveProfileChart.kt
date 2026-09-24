package com.mobiledivecontrol.ui.dive

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.DiveSample
import com.mobiledivecontrol.core.DiveSession
import com.mobiledivecontrol.theme.DiveColors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

internal data class ProfileChartScale(val bottomUnits: Double, val depthStepUnits: Double,
    val unitsPerMeter: Double, val durationMs: Long) {
    fun depthFraction(meters: Double) = (meters.coerceAtLeast(0.0) * unitsPerMeter / bottomUnits).coerceIn(0.0, 1.0)
    fun timeFraction(ms: Long) = (ms.toDouble() / durationMs).coerceIn(0.0, 1.0)
}

internal fun profileChartScale(dive: DiveSession, metric: Boolean): ProfileChartScale {
    val units = if (metric) 1.0 else 3.28084
    val observedMax = dive.samples.filter { it.depthMeters.isFinite() }.maxOfOrNull { it.depthMeters } ?: 0.0
    val paddedDepth = maxOf(if (metric) 2.0 else 10.0, maxOf(dive.maxDepthMeters, observedMax) * units * 1.1)
    val roughStep = paddedDepth / 4.0
    val magnitude = 10.0.pow(floor(log10(roughStep)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= roughStep } * magnitude
    return ProfileChartScale(ceil(paddedDepth / step) * step, step, units,
        maxOf(1_000L, dive.elapsedMs, dive.samples.maxOfOrNull { it.elapsedMs } ?: 0L))
}

/** Each monitoring gap gets its own line and fill; never draw an invented connection across it. */
internal fun profileChartSegments(samples: List<DiveSample>): List<List<DiveSample>> {
    val segments = mutableListOf<MutableList<DiveSample>>()
    var current: MutableList<DiveSample>? = null
    var lastTime = -1L
    for (sample in samples) {
        if (!sample.depthMeters.isFinite() || sample.elapsedMs < 0 || sample.elapsedMs < lastTime) {
            current = null
            continue
        }
        if (current == null || sample.gapBefore) {
            current = mutableListOf()
            segments.add(current)
        }
        current.add(sample)
        lastTime = sample.elapsedMs
    }
    return segments
}

@Composable
fun DiveProfileChart(dive: DiveSession, metric: Boolean, modifier: Modifier = Modifier) {
    val scale = remember(dive, metric) { profileChartScale(dive, metric) }
    val segments = remember(dive.samples) { profileChartSegments(dive.samples) }
    val axisPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    Column(modifier.background(DiveColors.SurfaceCard, RoundedCornerShape(12.dp)).padding(12.dp)
        .semantics { contentDescription = "Dive depth over elapsed time. Depth increases downward. Maximum ${diveDepth(dive.maxDepthMeters, metric)}. Duration ${diveTime(dive.elapsedMs / 1000)}. Monitoring gaps remain blank." }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("DEPTH (${if (metric) "m" else "ft"})", color = DiveColors.TextSecondary, fontSize = 10.sp)
            Text("ELAPSED TIME (min:sec)", color = DiveColors.TextSecondary, fontSize = 10.sp)
        }
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val left = 32.dp.toPx()
            val right = size.width - 6.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 25.dp.toPx()
            if (right <= left || bottom <= top) return@Canvas
            fun x(ms: Long) = left + (scale.timeFraction(ms) * (right - left)).toFloat()
            fun y(meters: Double) = top + (scale.depthFraction(meters) * (bottom - top)).toFloat()
            axisPaint.color = DiveColors.TextSecondary.toArgb()
            axisPaint.textSize = 10.sp.toPx()
            axisPaint.textAlign = Paint.Align.RIGHT
            val depthTicks = (scale.bottomUnits / scale.depthStepUnits).toInt()
            for (i in 0..depthTicks) {
                val depth = i * scale.depthStepUnits
                val yy = top + ((depth / scale.bottomUnits) * (bottom - top)).toFloat()
                drawLine(DiveColors.SurfaceBorder, Offset(left, yy), Offset(right, yy), 1.dp.toPx())
                val label = if (depth % 1.0 < 1e-6) depth.toInt().toString() else String.format(Locale.US, "%.1f", depth)
                drawContext.canvas.nativeCanvas.drawText(label, left - 8.dp.toPx(), yy - (axisPaint.ascent() + axisPaint.descent()) / 2, axisPaint)
            }
            val timeTicks = minOf(4, (scale.durationMs / 1000).toInt()).coerceAtLeast(1)
            for (i in 0..timeTicks) {
                val ms = scale.durationMs * i / timeTicks
                val xx = x(ms)
                drawLine(DiveColors.SurfaceBorder.copy(alpha = 0.6f), Offset(xx, top), Offset(xx, bottom), 1.dp.toPx())
                axisPaint.textAlign = when (i) { 0 -> Paint.Align.LEFT; timeTicks -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                drawContext.canvas.nativeCanvas.drawText(diveTime(ms / 1000), xx, bottom + 17.dp.toPx(), axisPaint)
            }
            val fill = Brush.verticalGradient(listOf(DiveColors.DiveCyan.copy(alpha = 0.05f),
                DiveColors.DiveCyan.copy(alpha = 0.3f)), startY = top, endY = bottom)
            for (segment in segments) {
                val first = segment.first()
                val line = Path().apply {
                    moveTo(x(first.elapsedMs), y(first.depthMeters))
                    segment.drop(1).forEach { lineTo(x(it.elapsedMs), y(it.depthMeters)) }
                }
                val area = Path().apply {
                    moveTo(x(first.elapsedMs), top)
                    segment.forEach { lineTo(x(it.elapsedMs), y(it.depthMeters)) }
                    lineTo(x(segment.last().elapsedMs), top)
                    close()
                }
                drawPath(area, fill)
                drawPath(line, DiveColors.DiveCyan, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                if (segment.size == 1) drawCircle(DiveColors.DiveCyan, 2.dp.toPx(), Offset(x(first.elapsedMs), y(first.depthMeters)))
            }
            drawLine(DiveColors.TextSecondary.copy(alpha = 0.65f), Offset(left, top), Offset(right, top), 1.dp.toPx())
        }
    }
}
