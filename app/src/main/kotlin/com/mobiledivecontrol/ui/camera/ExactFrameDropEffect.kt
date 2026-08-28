package com.mobiledivecontrol.ui.camera

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram

/**
 * Drops an exact fractional share of input frames using a count-based accumulator.
 *
 * Media3's default frame dropper rounds non-integral ratios to a constant interval. That turns
 * 120 -> 48 into 120 -> 60. A Bresenham-style accumulator alternates the intervals instead, so
 * 120 -> 48 and similar rates retain the requested average cadence without interpolated frames.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ExactFrameDropEffect(
    private val inputFrameRate: Int,
    private val targetFrameRate: Int,
) : GlEffect {
    init {
        require(inputFrameRate > 0)
        require(targetFrameRate in 1..inputFrameRate)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : PassthroughShaderProgram() {
            private var accumulator = inputFrameRate - targetFrameRate

            override fun queueInputFrame(
                glObjectsProvider: GlObjectsProvider,
                inputTexture: GlTextureInfo,
                presentationTimeUs: Long,
            ) {
                accumulator += targetFrameRate
                if (accumulator >= inputFrameRate) {
                    accumulator -= inputFrameRate
                    super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
                } else {
                    inputListener.onInputFrameProcessed(inputTexture)
                    inputListener.onReadyToAcceptInputFrame()
                }
            }

            override fun signalEndOfCurrentInputStream() {
                super.signalEndOfCurrentInputStream()
                accumulator = inputFrameRate - targetFrameRate
            }

            override fun flush() {
                super.flush()
                accumulator = inputFrameRate - targetFrameRate
            }
        }
}
