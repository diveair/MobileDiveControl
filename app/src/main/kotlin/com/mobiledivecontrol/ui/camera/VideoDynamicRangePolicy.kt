package com.mobiledivecontrol.ui.camera

import android.hardware.camera2.CameraMetadata
import androidx.camera.core.DynamicRange

/**
 * Maps the legacy UI's `LOG` selection to the strongest public 10-bit profile the S24 exposes.
 * Samsung Log itself is a privileged Samsung Camera pipeline, not a CameraX dynamic range.
 */
internal object VideoDynamicRangePolicy {
    /** Native Samsung Log measured 98.9 Mbit/s at UHD30; request the nearest round target. */
    const val LOG_TARGET_VIDEO_BITRATE_BPS = 100_000_000

    fun select(requestedMode: String, supported: Set<DynamicRange>): DynamicRange =
        if (requestedMode == "LOG" && DynamicRange.HLG_10_BIT in supported) {
            DynamicRange.HLG_10_BIT
        } else {
            DynamicRange.SDR
        }

    fun targetVideoBitrate(requestedMode: String): Int? =
        LOG_TARGET_VIDEO_BITRATE_BPS.takeIf { requestedMode == "LOG" }

    /** Log is an acquisition contract, not permission to silently record an 8-bit substitute. */
    fun isCaptureContractSatisfied(requestedMode: String, selected: DynamicRange): Boolean =
        requestedMode != "LOG" || selected == DynamicRange.HLG_10_BIT

    /**
     * Log drops still capture and 8-bit RGBA analysis surfaces from the bound session. Live
     * exposure, focus and white-balance controls remain Camera2 repeating-request parameters and
     * therefore do not depend on either surface.
     */
    fun usesMaximumInformationStreamGraph(requestedMode: String): Boolean = requestedMode == "LOG"

    fun maximumInformationRequestModes(
        availableNoiseReductionModes: IntArray?,
        availableEdgeModes: IntArray?,
        availableVideoStabilizationModes: IntArray?,
    ): MaximumInformationRequestModes = MaximumInformationRequestModes(
        // MINIMAL keeps raw-domain defect/demosaic cleanup without the stronger temporal
        // filtering that can erase weak texture. OFF is the honest second choice when exposed.
        noiseReductionMode = when {
            availableNoiseReductionModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true ->
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            availableNoiseReductionModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) == true ->
                CameraMetadata.NOISE_REDUCTION_MODE_OFF
            else -> null
        },
        edgeMode = CameraMetadata.EDGE_MODE_OFF.takeIf {
            availableEdgeModes?.contains(CameraMetadata.EDGE_MODE_OFF) == true
        },
        // EIS crops and resamples. Optical stabilisation remains under the HAL's lens policy.
        videoStabilizationMode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF.takeIf {
            availableVideoStabilizationModes?.contains(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            ) == true
        },
    )
}

internal data class MaximumInformationRequestModes(
    val noiseReductionMode: Int? = null,
    val edgeMode: Int? = null,
    val videoStabilizationMode: Int? = null,
)
