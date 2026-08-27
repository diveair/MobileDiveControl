package com.mobiledivecontrol.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SamsungLogAcquisitionCalibration(
    val id: String,
    val commandedOffsetEv: Double,
    val editorSceneScale: Double,
)

/**
 * Samsung Log's published transfer function plus DiveControl's measured S24 acquisition policy.
 *
 * Samsung Log is not available to third-party Camera2 clients. DiveControl therefore records an
 * untouched 10-bit BT.2020 HLG master, protects the extra highlight latitude at capture, then uses
 * [hlgToSamsungLog] as an editor input transform. The original HLG file remains standards-compliant
 * and is never falsely tagged as Samsung Log.
 */
object SamsungLogProfile {
    // Samsung Log White Paper, revision available 2026-08-24.
    const val SCENE_BLACK = -0.05
    const val SCENE_TOE_CROSSOVER = 0.01
    const val SIGNAL_TOE_CROSSOVER = 0.206561909
    const val MAX_SCENE_LINEAR = 12.0

    private const val A1 = 0.258984868
    private const val B1 = 0.0003645
    private const val G1 = 0.720504856
    private const val A2 = -0.20942
    private const val B2 = 0.016904
    private const val G2 = -0.24597

    private const val HLG_A = 0.17883277
    private const val HLG_B = 1.0 - 4.0 * HLG_A
    private val HLG_C = 0.5 - HLG_A * ln(4.0 * HLG_A)

    /**
     * Best scene-linear scale measured from the S24 1x chart pair at equal ISO/shutter/WB.
     * This is calibration evidence, not the editor transform scale: the transform uses the
     * Samsung white paper's full x=12 domain after capture exposure has been shifted accordingly.
     */
    const val S24_1X_EQUAL_EXPOSURE_SCENE_SCALE = 4.412986245322124

    /**
     * Exposure shift that maps the measured public-HLG allocation onto Samsung Log's x=12 limit.
     * Negative means less sensor exposure (more highlight headroom). Auto exposure receives this
     * physically; manual exposure receives a re-zeroed meter and never hidden ISO/shutter changes.
     */
    val S24_1X_ACQUISITION_OFFSET_EV: Double =
        ln(S24_1X_EQUAL_EXPOSURE_SCENE_SCALE / MAX_SCENE_LINEAR) / ln(2.0)

    /**
     * The S24 exposes exposure compensation in 0.1 EV steps. Round the analytic -1.443 EV target
     * outward to -1.5: this costs only 0.057 EV of shadow exposure and guarantees that the x=12
     * editor transform does not spend highlight codes the capture failed to reserve.
     */
    const val S24_1X_COMMANDED_OFFSET_EV = -1.5

    private val S24_BASE_1X_CALIBRATION = SamsungLogAcquisitionCalibration(
        id = "SM-S921-1x-2026-08-24",
        commandedOffsetEv = S24_1X_COMMANDED_OFFSET_EV,
        editorSceneScale = MAX_SCENE_LINEAR,
    )

    /** Returns a calibration only for a device/lens pair measured on metal. */
    fun acquisitionCalibration(deviceModel: String, lensValue: String?): SamsungLogAcquisitionCalibration? =
        S24_BASE_1X_CALIBRATION.takeIf {
            deviceModel.uppercase().startsWith("SM-S921") && lensValue == "1x"
        }

    /** User EV is relative to the protected Log baseline when a measured calibration exists. */
    fun effectiveAutoExposureEv(userEv: Double, calibration: SamsungLogAcquisitionCalibration?): Double =
        userEv + (calibration?.commandedOffsetEv ?: 0.0)

    /**
     * Converts Samsung's raw tenths-of-a-stop manual meter into a meter centred on the protected
     * Log baseline. For example, a raw -1.4 EV becomes approximately L 0.0.
     */
    fun protectedManualMeterTenths(
        rawTenths: Int,
        calibration: SamsungLogAcquisitionCalibration?,
    ): Int = rawTenths - ((calibration?.commandedOffsetEv ?: 0.0) * 10.0).roundToInt()

    /** ARIB STD-B67 HLG OETF inverse, producing normalized scene-linear light. */
    fun hlgToSceneLinear(signal: Double): Double {
        val encoded = signal.coerceIn(0.0, 1.0)
        return if (encoded <= 0.5) {
            encoded * encoded / 3.0
        } else {
            (exp((encoded - HLG_C) / HLG_A) + HLG_B) / 12.0
        }
    }

    /** ARIB STD-B67 HLG OETF, useful for validating the editor transform end to end. */
    fun sceneLinearToHlg(sceneLinear: Double): Double {
        val scene = sceneLinear.coerceAtLeast(0.0)
        return if (scene <= 1.0 / 12.0) {
            sqrt(3.0 * scene)
        } else {
            HLG_A * ln(12.0 * scene - HLG_B) + HLG_C
        }
    }

    /** Official Samsung Log scene-linear to signal transfer function. */
    fun encode(sceneLinear: Double): Double = when {
        sceneLinear >= SCENE_TOE_CROSSOVER ->
            A1 * log10(sceneLinear + B1) + G1
        sceneLinear >= SCENE_BLACK ->
            A2 * log10(-sceneLinear + B2) + G2
        else -> 0.0
    }

    /** Official Samsung Log signal to scene-linear inverse transfer function. */
    fun decode(signal: Double): Double = if (signal >= SIGNAL_TOE_CROSSOVER) {
        10.0.pow((signal - G1) / A1) - B1
    } else {
        -10.0.pow((signal - G2) / A2) + B2
    }

    /** Analytic editor input transform used by the bundled DCTL. */
    fun hlgToSamsungLog(signal: Double): Double =
        encode(MAX_SCENE_LINEAR * hlgToSceneLinear(signal))

    /** Normalized signal to legal-range 10-bit code value (64..940), for test and field reports. */
    fun legalRange10Bit(signal: Double): Double = 64.0 + 876.0 * signal.coerceIn(0.0, 1.0)
}
