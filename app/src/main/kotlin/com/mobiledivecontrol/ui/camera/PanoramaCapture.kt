package com.mobiledivecontrol.ui.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    val referenceFrame: MutableState<Bitmap?> = mutableStateOf(null)

    fun replaceReferenceFrame(bitmap: Bitmap?) {
        val oldReference = referenceFrame.value
        referenceFrame.value = bitmap
        oldReference?.let { oldBitmap ->
            if (oldBitmap !== referenceFrame.value && !oldBitmap.isRecycled) oldBitmap.recycle()
        }
    }

    fun reset() {
        replaceReferenceFrame(null)
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

/** Placement of the current frame relative to the previous frame. */
internal data class PanoramaFrameOffset(
    val x: Int,
    val y: Int,
    val correlation: Double,
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
 * Finds the translational overlap between two equally-sized luminance planes. The camera's gyro
 * provides the expected advance; normalized correlation corrects its accumulated integration
 * error and the small cross-axis drift that otherwise creates the conspicuous strip seams.
 */
internal fun panoramaFrameOffset(
    previousLuma: IntArray,
    currentLuma: IntArray,
    width: Int,
    height: Int,
    direction: String,
    expectedAdvance: Int,
): PanoramaFrameOffset {
    require(width > 2 && height > 2)
    require(previousLuma.size >= width * height && currentLuma.size >= width * height)

    val horizontal = direction == "Left" || direction == "Right"
    val mainDimension = if (horizontal) width else height
    val crossDimension = if (horizontal) height else width
    val expected = expectedAdvance.coerceIn(2, (mainDimension * 0.58f).roundToInt().coerceAtLeast(2))
    val minimumAdvance = (expected * 0.48f).roundToInt().coerceAtLeast(2)
    val maximumAdvance = (expected * 1.52f).roundToInt()
        .coerceIn(minimumAdvance, (mainDimension * 0.65f).roundToInt().coerceAtLeast(minimumAdvance))
    val maximumCrossDrift = (crossDimension / 10).coerceIn(2, 24)
    val mainSign = if (direction == "Left" || direction == "Up") -1 else 1

    fun offsetFor(advance: Int, cross: Int): Pair<Int, Int> = if (horizontal) {
        mainSign * advance to cross
    } else {
        cross to mainSign * advance
    }

    fun correlation(offsetX: Int, offsetY: Int, sampleStep: Int): Double {
        val left = maxOf(1, offsetX + 1)
        val right = minOf(width - 1, width + offsetX - 1)
        val top = maxOf(1, offsetY + 1)
        val bottom = minOf(height - 1, height + offsetY - 1)
        if (right - left < width / 5 || bottom - top < height / 5) return -1.0

        var count = 0
        var previousSum = 0.0
        var currentSum = 0.0
        var previousSquareSum = 0.0
        var currentSquareSum = 0.0
        var productSum = 0.0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val previous = previousLuma[y * width + x].toDouble()
                val current = currentLuma[(y - offsetY) * width + x - offsetX].toDouble()
                previousSum += previous
                currentSum += current
                previousSquareSum += previous * previous
                currentSquareSum += current * current
                productSum += previous * current
                count++
                x += sampleStep
            }
            y += sampleStep
        }
        if (count < 64) return -1.0
        val numerator = productSum - previousSum * currentSum / count
        val previousVariance = previousSquareSum - previousSum * previousSum / count
        val currentVariance = currentSquareSum - currentSum * currentSum / count
        val denominator = sqrt(previousVariance.coerceAtLeast(0.0) * currentVariance.coerceAtLeast(0.0))
        return if (denominator <= 1e-6) -1.0 else numerator / denominator
    }

    var bestAdvance = expected
    var bestCross = 0
    var bestScore = -1.0
    var advance = minimumAdvance
    while (advance <= maximumAdvance) {
        var cross = -maximumCrossDrift
        while (cross <= maximumCrossDrift) {
            val (offsetX, offsetY) = offsetFor(advance, cross)
            val match = correlation(offsetX, offsetY, sampleStep = 6)
            // Resolve repetitive textures in favour of the gyro estimate without overpowering
            // real image evidence.
            val penalty = abs(advance - expected) * 0.00035 + abs(cross) * 0.0002
            val score = match - penalty
            if (score > bestScore) {
                bestScore = score
                bestAdvance = advance
                bestCross = cross
            }
            cross++
        }
        advance++
    }

    var refinedScore = bestScore
    var refinedAdvance = bestAdvance
    var refinedCross = bestCross
    for (candidateAdvance in (bestAdvance - 3)..(bestAdvance + 3)) {
        if (candidateAdvance !in minimumAdvance..maximumAdvance) continue
        for (candidateCross in (bestCross - 3)..(bestCross + 3)) {
            if (candidateCross !in -maximumCrossDrift..maximumCrossDrift) continue
            val (offsetX, offsetY) = offsetFor(candidateAdvance, candidateCross)
            val match = correlation(offsetX, offsetY, sampleStep = 2)
            val penalty = abs(candidateAdvance - expected) * 0.00035 + abs(candidateCross) * 0.0002
            val score = match - penalty
            if (score > refinedScore) {
                refinedScore = score
                refinedAdvance = candidateAdvance
                refinedCross = candidateCross
            }
        }
    }

    val (fallbackX, fallbackY) = offsetFor(expected, 0)
    if (refinedScore < 0.16) return PanoramaFrameOffset(fallbackX, fallbackY, refinedScore)
    val (offsetX, offsetY) = offsetFor(refinedAdvance, refinedCross)
    return PanoramaFrameOffset(offsetX, offsetY, refinedScore)
}

/**
 * App-owned panorama compositor. CameraX supplies overlapping stills, the gyro supplies an initial
 * angular spacing estimate, and image correlation registers every adjacent overlap. Frames are
 * feathered at the registered seam and the common cross-axis area is cropped to remove drift edges.
 */
internal object PanoramaBitmapStitcher {
    private const val HORIZONTAL_FOV_RADIANS = 1.2217305f // 70 degrees
    private const val VERTICAL_FOV_RADIANS = 0.91629785f // 52.5 degrees
    private const val REGISTRATION_WIDTH = 320
    private const val REGISTRATION_HEIGHT = 240
    private const val FEATHER_PIXELS = 96

    private data class FramePlacement(val x: Int, val y: Int)

    fun stitch(frames: List<CapturedPanoramaFrame>, direction: String): Bitmap {
        require(frames.isNotEmpty()) { "A panorama needs at least one frame" }
        val first = frames.first().bitmap
        if (frames.size == 1) {
            return first.copy(Bitmap.Config.ARGB_8888, false)
        }

        val horizontal = direction == "Left" || direction == "Right"
        val placements = mutableListOf(FramePlacement(0, 0))
        for (index in 1 until frames.size) {
            val dimension = if (horizontal) first.width else first.height
            val fov = if (horizontal) HORIZONTAL_FOV_RADIANS else VERTICAL_FOV_RADIANS
            val delta = (frames[index].sweepRadians - frames[index - 1].sweepRadians)
                .coerceAtLeast(PANORAMA_FRAME_STEP_RADIANS * 0.35f)
            val expectedAdvance = (dimension * delta / fov)
                .roundToInt()
                .coerceIn((dimension / 32).coerceAtLeast(1), (dimension / 3).coerceAtLeast(1))
            val offset = estimateOffset(
                previous = frames[index - 1].bitmap,
                current = frames[index].bitmap,
                direction = direction,
                expectedAdvance = expectedAdvance,
            )
            val previousPlacement = placements.last()
            placements += FramePlacement(
                x = previousPlacement.x + offset.x,
                y = previousPlacement.y + offset.y,
            )
        }

        val cropLeft: Int
        val cropTop: Int
        val cropRight: Int
        val cropBottom: Int
        if (horizontal) {
            cropLeft = placements.minOf { it.x }
            cropRight = placements.maxOf { it.x + first.width }
            cropTop = placements.maxOf { it.y }
            cropBottom = placements.minOf { it.y + first.height }
        } else {
            cropLeft = placements.maxOf { it.x }
            cropRight = placements.minOf { it.x + first.width }
            cropTop = placements.minOf { it.y }
            cropBottom = placements.maxOf { it.y + first.height }
        }
        require(cropRight > cropLeft && cropBottom > cropTop) { "Panorama frames do not overlap" }
        val output = Bitmap.createBitmap(
            cropRight - cropLeft,
            cropBottom - cropTop,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val translated = placements.map { FramePlacement(it.x - cropLeft, it.y - cropTop) }
        canvas.drawBitmap(first, translated.first().x.toFloat(), translated.first().y.toFloat(), null)
        var occupiedLeft = translated.first().x
        var occupiedTop = translated.first().y
        var occupiedRight = translated.first().x + first.width
        var occupiedBottom = translated.first().y + first.height
        for (index in 1 until frames.size) {
            val placement = translated[index]
            drawFeatheredFrame(
                canvas = canvas,
                bitmap = frames[index].bitmap,
                placement = placement,
                direction = direction,
                occupiedLeft = occupiedLeft,
                occupiedTop = occupiedTop,
                occupiedRight = occupiedRight,
                occupiedBottom = occupiedBottom,
            )
            occupiedLeft = minOf(occupiedLeft, placement.x)
            occupiedTop = minOf(occupiedTop, placement.y)
            occupiedRight = maxOf(occupiedRight, placement.x + first.width)
            occupiedBottom = maxOf(occupiedBottom, placement.y + first.height)
        }
        return output
    }

    private fun estimateOffset(
        previous: Bitmap,
        current: Bitmap,
        direction: String,
        expectedAdvance: Int,
    ): PanoramaFrameOffset {
        val horizontal = direction == "Left" || direction == "Right"
        val scale = minOf(
            REGISTRATION_WIDTH.toFloat() / previous.width,
            REGISTRATION_HEIGHT.toFloat() / previous.height,
            1f,
        )
        val sampleWidth = (previous.width * scale).roundToInt().coerceAtLeast(3)
        val sampleHeight = (previous.height * scale).roundToInt().coerceAtLeast(3)
        val previousLuma = scaledLuma(previous, sampleWidth, sampleHeight)
        val currentLuma = scaledLuma(current, sampleWidth, sampleHeight)
        val sampleExpected = (expectedAdvance * if (horizontal) {
            sampleWidth.toFloat() / previous.width
        } else {
            sampleHeight.toFloat() / previous.height
        }).roundToInt().coerceAtLeast(2)
        val sampleOffset = panoramaFrameOffset(
            previousLuma = previousLuma,
            currentLuma = currentLuma,
            width = sampleWidth,
            height = sampleHeight,
            direction = direction,
            expectedAdvance = sampleExpected,
        )
        return PanoramaFrameOffset(
            x = (sampleOffset.x * previous.width.toFloat() / sampleWidth).roundToInt(),
            y = (sampleOffset.y * previous.height.toFloat() / sampleHeight).roundToInt(),
            correlation = sampleOffset.correlation,
        )
    }

    private fun scaledLuma(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        for (index in pixels.indices) {
            val pixel = pixels[index]
            pixels[index] = (
                77 * Color.red(pixel) +
                    150 * Color.green(pixel) +
                    29 * Color.blue(pixel)
                ) shr 8
        }
        return pixels
    }

    @Suppress("DEPRECATION")
    private fun drawFeatheredFrame(
        canvas: Canvas,
        bitmap: Bitmap,
        placement: FramePlacement,
        direction: String,
        occupiedLeft: Int,
        occupiedTop: Int,
        occupiedRight: Int,
        occupiedBottom: Int,
    ) {
        val left = placement.x.toFloat()
        val top = placement.y.toFloat()
        val right = left + bitmap.width
        val bottom = top + bitmap.height
        val horizontal = direction == "Left" || direction == "Right"
        val overlap = if (horizontal) {
            if (direction == "Left") right - occupiedLeft else occupiedRight - left
        } else {
            if (direction == "Up") bottom - occupiedTop else occupiedBottom - top
        }.roundToInt().coerceAtLeast(1)
        val mainDimension = if (horizontal) bitmap.width else bitmap.height
        val feather = minOf(FEATHER_PIXELS, overlap / 2, mainDimension / 12).coerceAtLeast(1)
        val edge = when (direction) {
            "Left" -> right - overlap
            "Up" -> bottom - overlap
            else -> if (horizontal) left + overlap else top + overlap
        }
        val gradient = if (horizontal) {
            if (direction == "Left") {
                LinearGradient(
                    edge,
                    0f,
                    edge + feather,
                    0f,
                    Color.WHITE,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            } else {
                LinearGradient(
                    edge - feather,
                    0f,
                    edge,
                    0f,
                    Color.TRANSPARENT,
                    Color.WHITE,
                    Shader.TileMode.CLAMP,
                )
            }
        } else {
            if (direction == "Up") {
                LinearGradient(
                    0f,
                    edge,
                    0f,
                    edge + feather,
                    Color.WHITE,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            } else {
                LinearGradient(
                    0f,
                    edge - feather,
                    0f,
                    edge,
                    Color.TRANSPARENT,
                    Color.WHITE,
                    Shader.TileMode.CLAMP,
                )
            }
        }
        val bounds = RectF(left, top, right, bottom)
        val layer = canvas.saveLayer(bounds, null)
        canvas.drawBitmap(bitmap, left, top, null)
        canvas.drawRect(
            bounds,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = gradient
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            },
        )
        canvas.restoreToCount(layer)
    }
}
