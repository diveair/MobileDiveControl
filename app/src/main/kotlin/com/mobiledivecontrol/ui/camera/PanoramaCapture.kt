package com.mobiledivecontrol.ui.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val PANORAMA_TARGET_RADIANS: Float = 1.9198622f // 110 degrees
internal const val PANORAMA_FRAME_STEP_RADIANS: Float = 0.20943952f // 12 degrees

/**
 * Fast-changing Panorama telemetry stays outside CameraState for the same reason as the video
 * clock: sensor ticks and stitch progress must not enter the housing-control reducer.
 */
internal object PanoramaCaptureState {
    val active: MutableState<Boolean> = mutableStateOf(false)
    val finalizing: MutableState<Boolean> = mutableStateOf(false)
    val progress: MutableState<Float> = mutableFloatStateOf(0f)
    val frameCount: MutableState<Int> = mutableIntStateOf(0)
    val movingTooFast: MutableState<Boolean> = mutableStateOf(false)
    val direction: MutableState<String> = mutableStateOf("Right")
    val message: MutableState<String> = mutableStateOf("")

    fun reset() {
        active.value = false
        finalizing.value = false
        progress.value = 0f
        frameCount.value = 0
        movingTooFast.value = false
        direction.value = "Right"
        message.value = ""
    }
}

internal data class CapturedPanoramaFrame(
    val bitmap: Bitmap,
    val sweepRadians: Float,
)

/**
 * Selects and signs the gyro axis for the requested on-screen sweep. Android sensor coordinates
 * remain in the phone's natural portrait frame, so landscape rotations swap and/or invert them.
 * A positive result moves toward the selected direction; a negative result is reverse travel.
 */
internal fun panoramaSweepAxisRate(
    direction: String,
    displayRotation: Int,
    gyroX: Float,
    gyroY: Float,
): Float {
    val rightwardRate: Float
    val upwardRate: Float
    when (displayRotation) {
        1 -> {
            rightwardRate = gyroX
            upwardRate = gyroY
        }
        3 -> {
            rightwardRate = -gyroX
            upwardRate = -gyroY
        }
        else -> {
            rightwardRate = -gyroY
            upwardRate = gyroX
        }
    }
    return when (direction) {
        "Left" -> -rightwardRate
        "Up" -> upwardRate
        "Down" -> -upwardRate
        else -> rightwardRate
    }
}

internal fun panoramaDirectionFromGyro(
    displayRotation: Int,
    gyroX: Float,
    gyroY: Float,
): String {
    val rightwardRate: Float
    val upwardRate: Float
    when (displayRotation) {
        1 -> {
            rightwardRate = gyroX
            upwardRate = gyroY
        }
        3 -> {
            rightwardRate = -gyroX
            upwardRate = -gyroY
        }
        else -> {
            rightwardRate = -gyroY
            upwardRate = gyroX
        }
    }
    return if (abs(rightwardRate) >= abs(upwardRate)) {
        if (rightwardRate >= 0f) "Right" else "Left"
    } else {
        if (upwardRate >= 0f) "Up" else "Down"
    }
}

internal fun panoramaProgressFraction(sweepRadians: Float): Float =
    (sweepRadians / PANORAMA_TARGET_RADIANS).coerceIn(0f, 1f)

/**
 * App-owned panorama compositor. CameraX supplies full-resolution overlapping stills and gyro
 * motion supplies their angular spacing. Each new frame contributes only the newly exposed edge;
 * that preserves native-like sweep geometry without stretching a normal photograph wider.
 */
internal object PanoramaBitmapStitcher {
    private const val HORIZONTAL_FOV_RADIANS = 1.2217305f // 70 degrees
    private const val VERTICAL_FOV_RADIANS = 0.91629785f // 52.5 degrees

    fun stitch(frames: List<CapturedPanoramaFrame>, direction: String): Bitmap {
        require(frames.isNotEmpty()) { "A panorama needs at least one frame" }
        val first = frames.first().bitmap
        if (frames.size == 1) {
            return first.copy(Bitmap.Config.ARGB_8888, false)
        }

        val horizontal = direction == "Left" || direction == "Right"
        val advances = frames.indices.drop(1).map { index ->
            val dimension = if (horizontal) first.width else first.height
            val fov = if (horizontal) HORIZONTAL_FOV_RADIANS else VERTICAL_FOV_RADIANS
            val delta = (frames[index].sweepRadians - frames[index - 1].sweepRadians)
                .coerceAtLeast(PANORAMA_FRAME_STEP_RADIANS * 0.35f)
            (dimension * delta / fov)
                .roundToInt()
                .coerceIn((dimension / 32).coerceAtLeast(1), (dimension / 3).coerceAtLeast(1))
        }
        val added = advances.sum()
        val output = if (horizontal) {
            Bitmap.createBitmap(first.width + added, first.height, Bitmap.Config.ARGB_8888)
        } else {
            Bitmap.createBitmap(first.width, first.height + added, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(output)

        when (direction) {
            "Left" -> {
                var cursor = added
                canvas.drawBitmap(first, added.toFloat(), 0f, null)
                for (index in 1 until frames.size) {
                    val advance = advances[index - 1]
                    cursor -= advance
                    drawHorizontalStrip(canvas, frames[index].bitmap, cursor, advance, fromRight = false, first.height)
                }
            }
            "Right" -> {
                canvas.drawBitmap(first, 0f, 0f, null)
                var cursor = first.width
                for (index in 1 until frames.size) {
                    val advance = advances[index - 1]
                    drawHorizontalStrip(canvas, frames[index].bitmap, cursor, advance, fromRight = true, first.height)
                    cursor += advance
                }
            }
            "Up" -> {
                var cursor = added
                canvas.drawBitmap(first, 0f, added.toFloat(), null)
                for (index in 1 until frames.size) {
                    val advance = advances[index - 1]
                    cursor -= advance
                    drawVerticalStrip(canvas, frames[index].bitmap, cursor, advance, fromBottom = false, first.width)
                }
            }
            else -> { // Down
                canvas.drawBitmap(first, 0f, 0f, null)
                var cursor = first.height
                for (index in 1 until frames.size) {
                    val advance = advances[index - 1]
                    drawVerticalStrip(canvas, frames[index].bitmap, cursor, advance, fromBottom = true, first.width)
                    cursor += advance
                }
            }
        }
        return output
    }

    private fun drawHorizontalStrip(
        canvas: Canvas,
        bitmap: Bitmap,
        destinationLeft: Int,
        destinationWidth: Int,
        fromRight: Boolean,
        outputHeight: Int,
    ) {
        val sourceWidth = destinationWidth.coerceIn(1, bitmap.width)
        val source = if (fromRight) {
            Rect(bitmap.width - sourceWidth, 0, bitmap.width, bitmap.height)
        } else {
            Rect(0, 0, sourceWidth, bitmap.height)
        }
        canvas.drawBitmap(
            bitmap,
            source,
            Rect(destinationLeft, 0, destinationLeft + destinationWidth, outputHeight),
            null,
        )
    }

    private fun drawVerticalStrip(
        canvas: Canvas,
        bitmap: Bitmap,
        destinationTop: Int,
        destinationHeight: Int,
        fromBottom: Boolean,
        outputWidth: Int,
    ) {
        val sourceHeight = destinationHeight.coerceIn(1, bitmap.height)
        val source = if (fromBottom) {
            Rect(0, bitmap.height - sourceHeight, bitmap.width, bitmap.height)
        } else {
            Rect(0, 0, bitmap.width, sourceHeight)
        }
        canvas.drawBitmap(
            bitmap,
            source,
            Rect(0, destinationTop, outputWidth, destinationTop + destinationHeight),
            null,
        )
    }
}
