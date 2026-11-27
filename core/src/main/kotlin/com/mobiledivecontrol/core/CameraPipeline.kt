package com.mobiledivecontrol.core

/** Session selection depends only on resolved settings, never on Android objects. */
enum class CameraPipeline { CameraX, Prepared, NativeLog, HighSpeed, EightK, RegularUhd }

data class PipelineCaps(
    val manualExposure: Boolean = true,
    val manualWhiteBalance: Boolean = true,
    val cropAspect: Boolean = true,
    val hdr: Boolean = true,
)

fun selectPipeline(mode: CameraModeId, resolution: String?, fps: Int?, log: Boolean,
    s24VideoModes: Boolean = false, lens: String? = null): CameraPipeline = when {
    log && mode in setOf(CameraModeId.Video, CameraModeId.ProVideo) -> CameraPipeline.NativeLog
    mode == CameraModeId.Hyperlapse -> CameraPipeline.Prepared
    resolution == "8K" -> CameraPipeline.EightK
    s24VideoModes && resolution == "UHD 4K" && fps == 60 && lens == "3x" &&
        mode in setOf(CameraModeId.Video, CameraModeId.ProVideo, CameraModeId.SlowMotion) -> CameraPipeline.RegularUhd
    (fps ?: 0) >= 100 || mode == CameraModeId.SlowMotion -> CameraPipeline.HighSpeed
    else -> CameraPipeline.CameraX
}

fun pipelineCaps(pipeline: CameraPipeline, fps: Int?): PipelineCaps = PipelineCaps(
    manualExposure = pipeline != CameraPipeline.HighSpeed || (fps ?: 0) < 100,
    manualWhiteBalance = pipeline != CameraPipeline.HighSpeed || (fps ?: 0) < 100,
    cropAspect = pipeline == CameraPipeline.CameraX,
    hdr = pipeline == CameraPipeline.CameraX,
)
