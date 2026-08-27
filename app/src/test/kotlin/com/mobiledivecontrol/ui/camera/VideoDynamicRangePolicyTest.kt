package com.mobiledivecontrol.ui.camera

import android.hardware.camera2.CameraMetadata
import androidx.camera.core.DynamicRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoDynamicRangePolicyTest {
    @Test
    fun `legacy LOG selection chooses public ten bit HLG when supported`() {
        assertEquals(
            DynamicRange.HLG_10_BIT,
            VideoDynamicRangePolicy.select(
                requestedMode = "LOG",
                supported = setOf(DynamicRange.SDR, DynamicRange.HLG_10_BIT),
            ),
        )
    }

    @Test
    fun `unsupported or unrequested wide range stays SDR`() {
        assertEquals(
            DynamicRange.SDR,
            VideoDynamicRangePolicy.select("LOG", setOf(DynamicRange.SDR)),
        )
        assertEquals(
            DynamicRange.SDR,
            VideoDynamicRangePolicy.select("Off", setOf(DynamicRange.SDR, DynamicRange.HLG_10_BIT)),
        )
    }

    @Test
    fun `LOG requests native class encoder bitrate without affecting standard video`() {
        assertEquals(100_000_000, VideoDynamicRangePolicy.targetVideoBitrate("LOG"))
        assertEquals(null, VideoDynamicRangePolicy.targetVideoBitrate("Off"))
        assertEquals(null, VideoDynamicRangePolicy.targetVideoBitrate("HDR"))
    }

    @Test
    fun `LOG contract never accepts an SDR fallback`() {
        assertEquals(
            true,
            VideoDynamicRangePolicy.isCaptureContractSatisfied("LOG", DynamicRange.HLG_10_BIT),
        )
        assertEquals(
            false,
            VideoDynamicRangePolicy.isCaptureContractSatisfied("LOG", DynamicRange.SDR),
        )
        assertEquals(
            true,
            VideoDynamicRangePolicy.isCaptureContractSatisfied("Off", DynamicRange.SDR),
        )
    }

    @Test
    fun `only LOG uses the maximum information stream graph`() {
        assertEquals(true, VideoDynamicRangePolicy.usesMaximumInformationStreamGraph("LOG"))
        assertEquals(false, VideoDynamicRangePolicy.usesMaximumInformationStreamGraph("HDR"))
        assertEquals(false, VideoDynamicRangePolicy.usesMaximumInformationStreamGraph("Off"))
    }

    @Test
    fun `maximum information options prefer minimal processing and no electronic crop`() {
        val modes = VideoDynamicRangePolicy.maximumInformationRequestModes(
            availableNoiseReductionModes = intArrayOf(
                CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
            ),
            availableEdgeModes = intArrayOf(
                CameraMetadata.EDGE_MODE_FAST,
                CameraMetadata.EDGE_MODE_OFF,
            ),
            availableVideoStabilizationModes = intArrayOf(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            ),
        )

        assertEquals(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, modes.noiseReductionMode)
        assertEquals(CameraMetadata.EDGE_MODE_OFF, modes.edgeMode)
        assertEquals(
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            modes.videoStabilizationMode,
        )
    }

    @Test
    fun `unsupported ISP controls are not fabricated`() {
        val modes = VideoDynamicRangePolicy.maximumInformationRequestModes(
            availableNoiseReductionModes = intArrayOf(CameraMetadata.NOISE_REDUCTION_MODE_FAST),
            availableEdgeModes = intArrayOf(CameraMetadata.EDGE_MODE_FAST),
            availableVideoStabilizationModes = intArrayOf(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            ),
        )

        assertEquals(null, modes.noiseReductionMode)
        assertEquals(null, modes.edgeMode)
        assertEquals(null, modes.videoStabilizationMode)
    }
}
