package com.mobiledivecontrol.core

import kotlin.math.*

data class TissuePressure(val nitrogenBar: Double, val heliumBar: Double)

/** Open-circuit ZH-L16C. Pressure is absolute bar, time is minutes. See docs/DIVE_PROFILE.md. */
object Buhlmann {
    const val SURFACE_BAR = STANDARD_SURFACE_PRESSURE_KPA / 100.0
    const val BAR_PER_METER = FRESHWATER_KPA_PER_METER / 100.0
    const val WATER_VAPOUR_BAR = 0.0627
    const val GF_LOW = 0.40
    const val GF_HIGH = 0.85
    const val ASCENT_METERS_PER_MINUTE = 9.0
    const val NDL_CAP_SECONDS = 99 * 60
    private val nHalf = doubleArrayOf(4.0,8.0,12.5,18.5,27.0,38.3,54.3,77.0,109.0,146.0,187.0,239.0,305.0,390.0,498.0,635.0)
    private val hHalf = doubleArrayOf(1.51,3.02,4.72,6.99,10.21,14.48,20.53,29.11,41.20,55.19,70.69,90.34,115.29,147.42,188.24,240.03)
    private val nA = doubleArrayOf(1.2599,1.0,0.8618,0.7562,0.6200,0.5043,0.4410,0.4000,0.3750,0.3500,0.3295,0.3065,0.2835,0.2610,0.2480,0.2327)
    private val nB = doubleArrayOf(0.5050,0.6514,0.7222,0.7825,0.8126,0.8434,0.8693,0.8910,0.9092,0.9222,0.9319,0.9403,0.9477,0.9544,0.9602,0.9653)
    private val hA = doubleArrayOf(1.7424,1.3830,1.1919,1.0458,0.9220,0.8205,0.7305,0.6502,0.5950,0.5545,0.5333,0.5189,0.5181,0.5176,0.5172,0.5119)
    private val hB = doubleArrayOf(0.4245,0.5747,0.6527,0.7223,0.7582,0.7957,0.8279,0.8553,0.8757,0.8903,0.8997,0.9073,0.9122,0.9171,0.9217,0.9267)

    fun pressure(depthMeters: Double) = SURFACE_BAR + depthMeters * BAR_PER_METER
    fun depth(pressureBar: Double) = ((pressureBar - SURFACE_BAR) / BAR_PER_METER).coerceAtLeast(0.0)
    fun equilibrium(): List<TissuePressure> = List(16) { TissuePressure((SURFACE_BAR - WATER_VAPOUR_BAR) * 0.79, 0.0) }

    fun load(tissues: List<TissuePressure>, startBar: Double, endBar: Double, minutes: Double, gas: DiveGas): List<TissuePressure> {
        require(tissues.size == 16 && minutes.isFinite() && minutes >= 0)
        require(startBar.isFinite() && endBar.isFinite() && startBar > WATER_VAPOUR_BAR && endBar > WATER_VAPOUR_BAR)
        if (minutes == 0.0) return tissues
        val nitrogen = (100 - gas.oxygenPercent - gas.heliumPercent) / 100.0
        val helium = gas.heliumPercent / 100.0
        fun compartment(initial: Double, fraction: Double, half: Double): Double {
            val k = ln(2.0) / half
            val inspired = (startBar - WATER_VAPOUR_BAR) * fraction
            val rate = (endBar - startBar) / minutes * fraction
            // expm1 avoids cancellation for short sensor intervals and slow compartments.
            val oneMinusDecay = -expm1(-k * minutes)
            return (initial + (inspired - initial) * oneMinusDecay +
                rate * (minutes - oneMinusDecay / k)).coerceAtLeast(0.0)
        }
        return tissues.mapIndexed { i, t -> TissuePressure(
            compartment(t.nitrogenBar, nitrogen, nHalf[i]), compartment(t.heliumBar, helium, hHalf[i])) }
    }

    fun ceilingPressure(tissues: List<TissuePressure>, gf: Double): Double {
        require(tissues.size == 16 && gf in 0.01..1.0)
        return tissues.mapIndexed { i, t ->
            val total = t.nitrogenBar + t.heliumBar
            if (total < 1e-12) 0.0 else {
                val a = (nA[i] * t.nitrogenBar + hA[i] * t.heliumBar) / total
                val b = (nB[i] * t.nitrogenBar + hB[i] * t.heliumBar) / total
                (total - gf * a) / (1 - gf + gf / b)
            }
        }.max()
    }

    /** GF increases linearly from the retained first-stop anchor to GF high at the surface. */
    fun ceilingMeters(tissues: List<TissuePressure>, anchorMeters: Double): Double {
        if (ceilingPressure(tissues, GF_HIGH) <= SURFACE_BAR) return 0.0
        if (anchorMeters <= 0) return depth(ceilingPressure(tissues, GF_HIGH))
        var shallow = 0.0
        var deep = max(anchorMeters, depth(ceilingPressure(tissues, GF_LOW)))
        repeat(40) {
            val mid = (shallow + deep) / 2
            val gf = GF_HIGH + (GF_LOW - GF_HIGH) * (mid / anchorMeters).coerceIn(0.0, 1.0)
            if (ceilingPressure(tissues, gf) > pressure(mid)) shallow = mid else deep = mid
        }
        return deep
    }

    /** Additional whole seconds at current depth before a 9 m/min ascent on the same gas fails GF high. */
    fun ndlSeconds(tissues: List<TissuePressure>, depthMeters: Double, gas: DiveGas): Int {
        val start = pressure(depthMeters)
        fun canAscend(holdSeconds: Int): Boolean {
            val held = load(tissues, start, start, holdSeconds / 60.0, gas)
            // Check the path as well as surface arrival; no off-gassing credit from an instant ascent.
            val steps = max(1, ceil(depthMeters / 3.0).toInt())
            for (step in 1..steps) {
                val travel = depthMeters * step / steps
                val p = pressure(depthMeters - travel)
                val atDepth = load(held, start, p, travel / ASCENT_METERS_PER_MINUTE, gas)
                if (ceilingPressure(atDepth, GF_HIGH) > p + 1e-10) return false
            }
            return true
        }
        if (!canAscend(0)) return 0
        if (canAscend(NDL_CAP_SECONDS)) return NDL_CAP_SECONDS
        var low = 0
        var high = NDL_CAP_SECONDS
        while (high - low > 1) {
            val mid = (low + high) / 2
            if (canAscend(mid)) low = mid else high = mid
        }
        return low
    }
}

/** NOAA single-exposure CNS clock and pulmonary oxygen units; neither is a probability of injury. */
object OxygenExposure {
    private val pressures = doubleArrayOf(0.5,0.6,0.7,0.8,0.9,1.0,1.1,1.2,1.3,1.4,1.5,1.6)
    private val minutes = doubleArrayOf(Double.POSITIVE_INFINITY,720.0,570.0,450.0,360.0,300.0,240.0,210.0,180.0,150.0,120.0,45.0)
    fun ppO2(absoluteBar: Double, gas: DiveGas) = absoluteBar / Buhlmann.SURFACE_BAR * gas.oxygenPercent / 100.0
    fun cnsPercentPerMinute(ata: Double): Double {
        require(ata.isFinite() && ata >= 0)
        if (ata <= 0.5) return 0.0
        // No invented NOAA limit beyond the table. The tracker records an out-of-range exposure.
        if (ata > 1.6) return 100.0 / 45.0
        val i = pressures.indexOfFirst { it >= ata }
        val fraction = (ata - pressures[i - 1]) / (pressures[i] - pressures[i - 1])
        return (100 / minutes[i - 1]) * (1 - fraction) + (100 / minutes[i]) * fraction
    }
    fun otuPerMinute(ata: Double) = if (ata <= 0.5) 0.0 else ((ata - 0.5) / 0.5).pow(5.0 / 6.0)
    fun surfaceCns(cns: Double, minutes: Double) = cns * exp(-ln(2.0) * minutes / 90.0)

    /** Exact integral of the piecewise linear NOAA clock rate along a pressure ramp. */
    fun cnsDose(startAta: Double, endAta: Double, durationMinutes: Double): Double {
        if (abs(endAta - startAta) < 1e-10) return cnsPercentPerMinute(startAta) * durationMinutes
        val low = min(startAta, endAta)
        val high = max(startAta, endAta)
        val points = listOf(low) + pressures.filter { it > low && it < high } + high
        val area = points.zipWithNext().sumOf { (a, b) -> (b-a)*(cnsPercentPerMinute(a)+cnsPercentPerMinute(b))/2 }
        return area / (high-low) * durationMinutes
    }

    fun otuDose(startAta: Double, endAta: Double, durationMinutes: Double): Double {
        if (abs(endAta-startAta) < 1e-10) return otuPerMinute(startAta) * durationMinutes
        fun integral(p: Double) = if (p <= .5) 0.0 else .5 / (11.0/6) * ((p-.5)/.5).pow(11.0/6)
        return (integral(endAta)-integral(startAta))/(endAta-startAta)*durationMinutes
    }
}
