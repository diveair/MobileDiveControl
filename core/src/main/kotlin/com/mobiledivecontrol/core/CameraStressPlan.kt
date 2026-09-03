package com.mobiledivecontrol.core

/**
 * Pure coverage policy shared by the on-device stress runner and its JVM tests.
 *
 * Camera modes and discrete choices are intentionally exhaustive: resolution, FPS, lens,
 * dynamic range, format, and every other finite selector are exactly where unsupported stream
 * combinations tend to freeze a camera graph. Very long manual dials are sampled at their
 * endpoints and quartiles so the run remains useful in the field instead of spending minutes
 * walking through 101 visually equivalent focus positions in every mode.
 */
object CameraStressPlan {
    val modes: List<CameraModeId> = CameraCatalog.centerModeCycle

    fun targetValues(spec: CameraSettingSpec, exhaustiveSliders: Boolean = true): List<String> {
        if (spec.options.isEmpty()) return emptyList()
        if (exhaustiveSliders || spec.kind != CameraSettingKind.Slider ||
            spec.options.size <= MAX_EXHAUSTIVE_SLIDER_VALUES
        ) {
            return spec.options.distinct()
        }

        val last = spec.options.lastIndex
        val indices = linkedSetOf(
            0,
            last / 4,
            last / 2,
            (last * 3) / 4,
            last,
        )
        spec.options.forEachIndexed { index, value ->
            if (value == "Auto" || value == "AF" || value.startsWith("Auto ")) indices += index
        }
        return indices.sorted().map(spec.options::get)
    }

    /** Changes to these fields replace at least one CameraX/Camera2 session surface or contract. */
    fun requiresCameraRebind(settingId: String): Boolean = when {
        settingId.endsWith(".aspect_ratio") -> VIDEO_MODE_PREFIXES.any(settingId::startsWith)
        else -> REBIND_SUFFIXES.any(settingId::endsWith)
    }

    private val REBIND_SUFFIXES = setOf(
        ".lens",
        ".resolution",
        ".frame_rate",
        ".hdr",
        ".log",
        ".hdr_log",
        ".video_stabilization",
        ".save_format",
    )

    private val VIDEO_MODE_PREFIXES = setOf(
        "pro_video.",
        "slow_motion.",
        "hyperlapse.",
        "video.",
        "portrait_video.",
    )

    private const val MAX_EXHAUSTIVE_SLIDER_VALUES = 25
}
