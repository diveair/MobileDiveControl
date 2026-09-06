package com.mobiledivecontrol.ui.camera

/** Slow-motion policy only; ordinary Video, Pro Video and Hyperlapse keep their own contracts. */
internal object SlowMotionRecordingPolicy {
    fun sourceFrameRate(captureFps: Int): Int {
        require(captureFps in setOf(48, 60, 120, 240))
        return if (captureFps == 48) 60 else captureFps
    }

    /** Match Samsung's FHD HEVC budget per frame on the encoder's configured timestamp clock. */
    fun bitrate(width: Int, height: Int, encodedFps: Double, hevc: Boolean): Int {
        require(width > 0 && height > 0 && encodedFps.isFinite() && encodedFps > 0)
        val bitsPerFrame = 340_000.0 * width * height / (1920.0 * 1080.0)
        return (bitsPerFrame * encodedFps * if (hevc) 1.0 else 1.5)
            .coerceIn(8_000_000.0, Int.MAX_VALUE.toDouble()).toInt()
    }
}

/** Lens buttons establish an optical ratio; explicit wheel zoom overrides it until another lens. */
internal class SlowMotionZoomControl {
    private var lens: String? = null
    private var override: Float? = null

    fun resolve(selectedLens: String, opticalRatio: Float): Float {
        if (lens != selectedLens) { lens = selectedLens; override = null }
        return override ?: opticalRatio
    }

    fun set(selectedLens: String, ratio: Float) {
        lens = selectedLens
        override = ratio
    }
}
