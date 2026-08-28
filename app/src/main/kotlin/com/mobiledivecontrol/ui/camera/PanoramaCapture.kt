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
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

// Samsung's normal-lens Panorama presenter uses 330 degrees for a horizontal sweep and
// 380 degrees for a vertical sweep. The latter intentionally permits a little over one turn so
// the compositor can close the cylinder without leaving a gap.
internal const val PANORAMA_HORIZONTAL_TARGET_RADIANS: Float = 5.7595863f // 330 degrees
internal const val PANORAMA_VERTICAL_TARGET_RADIANS: Float = 6.6322513f // 380 degrees
internal const val PANORAMA_WIDE_HORIZONTAL_TARGET_RADIANS: Float = 5.5850534f // 320 degrees
internal const val PANORAMA_WIDE_VERTICAL_TARGET_RADIANS: Float = 6.1086526f // 350 degrees
internal const val PANORAMA_FRAME_STEP_RADIANS: Float = 0.17453292f // 10-degree keyframes

internal enum class PanoramaWarningLevel { None, Low, High }
internal enum class PanoramaCorrection { None, Up, Down, Left, Right }

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
    val directionLocked: MutableState<Boolean> = mutableStateOf(false)
    val message: MutableState<String> = mutableStateOf("")
    val crossAxisRadians: MutableState<Float> = mutableFloatStateOf(0f)
    val warningLevel: MutableState<PanoramaWarningLevel> =
        mutableStateOf(PanoramaWarningLevel.None)
    val correction: MutableState<PanoramaCorrection> =
        mutableStateOf(PanoramaCorrection.None)
    val canStop: MutableState<Boolean> = mutableStateOf(false)
    val referenceFrame: MutableState<Bitmap?> = mutableStateOf(null)

    fun reset() {
        active.value = false
        finalizing.value = false
        progress.value = 0f
        frameCount.value = 0
        movingTooFast.value = false
        direction.value = "Right"
        directionLocked.value = false
        message.value = ""
        crossAxisRadians.value = 0f
        warningLevel.value = PanoramaWarningLevel.None
        correction.value = PanoramaCorrection.None
        canStop.value = false
        referenceFrame.value = null
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

internal data class PanoramaProjectionSize(val width: Int, val height: Int)

internal fun panoramaCylindricalProjectionSize(
    sourceWidth: Int,
    sourceHeight: Int,
    horizontal: Boolean,
    horizontalFovRadians: Float,
): PanoramaProjectionSize {
    require(sourceWidth > 0 && sourceHeight > 0)
    val horizontalFov = horizontalFovRadians.coerceIn(0.61086524f, 2.0943952f)
    val focalPixels = sourceWidth / (2.0 * tan(horizontalFov / 2.0))
    val verticalFov = 2.0 * atan(sourceHeight / (2.0 * focalPixels))
    return if (horizontal) {
        PanoramaProjectionSize(
            width = sourceWidth,
            height = (sourceHeight * cos(horizontalFov / 2.0)).roundToInt().coerceAtLeast(1),
        )
    } else {
        PanoramaProjectionSize(
            width = (sourceWidth * cos(verticalFov / 2.0)).roundToInt().coerceAtLeast(1),
            height = sourceHeight,
        )
    }
}

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
    val (rightwardRate, upwardRate) = panoramaScreenAxisRates(displayRotation, gyroX, gyroY)
    return when (direction) {
        "Left" -> -rightwardRate
        "Up" -> upwardRate
        "Down" -> -upwardRate
        else -> rightwardRate
    }
}

internal data class PanoramaScreenAxisRates(
    val rightward: Float,
    val upward: Float,
)

internal fun panoramaScreenAxisRates(
    displayRotation: Int,
    gyroX: Float,
    gyroY: Float,
): PanoramaScreenAxisRates = when (displayRotation) {
    1 -> PanoramaScreenAxisRates(rightward = gyroX, upward = gyroY)
    3 -> PanoramaScreenAxisRates(rightward = -gyroX, upward = -gyroY)
    else -> PanoramaScreenAxisRates(rightward = -gyroY, upward = gyroX)
}

/** Motion perpendicular to the selected sweep, used by the native-style pitch/yaw guide. */
internal fun panoramaCrossAxisRate(
    direction: String,
    displayRotation: Int,
    gyroX: Float,
    gyroY: Float,
): Float {
    val axes = panoramaScreenAxisRates(displayRotation, gyroX, gyroY)
    return if (direction == "Left" || direction == "Right") {
        axes.upward
    } else {
        axes.rightward
    }
}

internal fun panoramaGuideCrossFraction(crossAxisRadians: Float): Float =
    (crossAxisRadians / 0.13962634f).coerceIn(-1f, 1f) // ±8 degrees fills the guide

/** Samsung's guide promotes the largest normalized rect error at 30% and 50%. */
internal fun panoramaWarningLevel(crossAxisRadians: Float): PanoramaWarningLevel {
    val normalizedError = abs(panoramaGuideCrossFraction(crossAxisRadians))
    return when {
        normalizedError >= 0.5f -> PanoramaWarningLevel.High
        normalizedError >= 0.3f -> PanoramaWarningLevel.Low
        else -> PanoramaWarningLevel.None
    }
}

internal fun panoramaCorrection(
    direction: String,
    crossAxisRadians: Float,
): PanoramaCorrection {
    if (panoramaWarningLevel(crossAxisRadians) == PanoramaWarningLevel.None) {
        return PanoramaCorrection.None
    }
    return if (direction == "Left" || direction == "Right") {
        if (crossAxisRadians > 0f) PanoramaCorrection.Down else PanoramaCorrection.Up
    } else {
        if (crossAxisRadians > 0f) PanoramaCorrection.Left else PanoramaCorrection.Right
    }
}

internal fun panoramaDirectionFromGyro(
    displayRotation: Int,
    gyroX: Float,
    gyroY: Float,
): String {
    val (rightwardRate, upwardRate) = panoramaScreenAxisRates(displayRotation, gyroX, gyroY)
    return if (abs(rightwardRate) >= abs(upwardRate)) {
        if (rightwardRate >= 0f) "Right" else "Left"
    } else {
        if (upwardRate >= 0f) "Up" else "Down"
    }
}

internal fun panoramaTargetRadians(direction: String, wideAngle: Boolean = false): Float =
    when {
        direction == "Up" || direction == "Down" -> if (wideAngle) {
            PANORAMA_WIDE_VERTICAL_TARGET_RADIANS
        } else {
            PANORAMA_VERTICAL_TARGET_RADIANS
        }
        wideAngle -> PANORAMA_WIDE_HORIZONTAL_TARGET_RADIANS
        else -> PANORAMA_HORIZONTAL_TARGET_RADIANS
    }

internal fun panoramaProgressFraction(
    sweepRadians: Float,
    direction: String = "Right",
    wideAngle: Boolean = false,
): Float = (sweepRadians / panoramaTargetRadians(direction, wideAngle)).coerceIn(0f, 1f)

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
    private const val REGISTRATION_WIDTH = 320
    private const val REGISTRATION_HEIGHT = 240
    private const val FEATHER_PIXELS = 96

    private data class FramePlacement(val x: Int, val y: Int)

    fun stitch(
        frames: List<CapturedPanoramaFrame>,
        direction: String,
        horizontalFovRadians: Float = HORIZONTAL_FOV_RADIANS,
    ): Bitmap {
        require(frames.isNotEmpty()) { "A panorama needs at least one frame" }
        if (frames.size == 1) {
            return frames.first().bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val horizontal = direction == "Left" || direction == "Right"
        val clampedHorizontalFov = horizontalFovRadians.coerceIn(0.61086524f, 2.0943952f)
        val firstSource = frames.first().bitmap
        val focalPixels = firstSource.width / (2.0 * tan(clampedHorizontalFov / 2.0))
        val verticalFov = (2.0 * atan(firstSource.height / (2.0 * focalPixels))).toFloat()
        val projectedFrames = frames.map { frame ->
            CapturedPanoramaFrame(
                bitmap = cylindricalWarp(
                    source = frame.bitmap,
                    horizontal = horizontal,
                    horizontalFovRadians = clampedHorizontalFov,
                ),
                sweepRadians = frame.sweepRadians,
            )
        }
        return try {
            stitchProjected(
                frames = projectedFrames,
                direction = direction,
                projectionFovRadians = if (horizontal) clampedHorizontalFov else verticalFov,
            )
        } finally {
            projectedFrames.forEach { frame -> runCatching { frame.bitmap.recycle() } }
        }
    }

    private fun stitchProjected(
        frames: List<CapturedPanoramaFrame>,
        direction: String,
        projectionFovRadians: Float,
    ): Bitmap {
        val first = frames.first().bitmap
        val horizontal = direction == "Left" || direction == "Right"
        val placements = mutableListOf(FramePlacement(0, 0))
        for (index in 1 until frames.size) {
            val dimension = if (horizontal) first.width else first.height
            val delta = (frames[index].sweepRadians - frames[index - 1].sweepRadians)
                .coerceAtLeast(PANORAMA_FRAME_STEP_RADIANS * 0.35f)
            val expectedAdvance = (dimension * delta / projectionFovRadians)
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
                output = output,
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

    /**
     * Projects each still onto the same cylinder before registration. A pure yaw/pitch rotation
     * then becomes translation instead of the perspective expansion that produced doubled edges
     * in the former flat-frame compositor. The inscribed crop contains no black projection wedges.
     */
    private fun cylindricalWarp(
        source: Bitmap,
        horizontal: Boolean,
        horizontalFovRadians: Float,
    ): Bitmap {
        val outputSize = panoramaCylindricalProjectionSize(
            sourceWidth = source.width,
            sourceHeight = source.height,
            horizontal = horizontal,
            horizontalFovRadians = horizontalFovRadians,
        )
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val outputPixels = IntArray(outputSize.width * outputSize.height)
        val sourceCenterX = (source.width - 1) / 2.0
        val sourceCenterY = (source.height - 1) / 2.0
        val outputCenterX = (outputSize.width - 1) / 2.0
        val outputCenterY = (outputSize.height - 1) / 2.0
        val horizontalFov = horizontalFovRadians.coerceIn(0.61086524f, 2.0943952f).toDouble()
        val focalPixels = source.width / (2.0 * tan(horizontalFov / 2.0))
        val verticalFov = 2.0 * atan(source.height / (2.0 * focalPixels))

        if (horizontal) {
            val sourceX = DoubleArray(outputSize.width)
            val cosine = DoubleArray(outputSize.width)
            for (x in 0 until outputSize.width) {
                val theta = (x - outputCenterX) * horizontalFov / outputSize.width
                cosine[x] = cos(theta)
                sourceX[x] = sourceCenterX + focalPixels * tan(theta)
            }
            for (y in 0 until outputSize.height) {
                for (x in 0 until outputSize.width) {
                    val sampleY = sourceCenterY + (y - outputCenterY) / cosine[x]
                    outputPixels[y * outputSize.width + x] = bilinearPixel(
                        sourcePixels,
                        source.width,
                        source.height,
                        sourceX[x],
                        sampleY,
                    )
                }
            }
        } else {
            val sourceY = DoubleArray(outputSize.height)
            val cosine = DoubleArray(outputSize.height)
            for (y in 0 until outputSize.height) {
                val theta = (y - outputCenterY) * verticalFov / outputSize.height
                cosine[y] = cos(theta)
                sourceY[y] = sourceCenterY + focalPixels * tan(theta)
            }
            for (y in 0 until outputSize.height) {
                for (x in 0 until outputSize.width) {
                    val sampleX = sourceCenterX + (x - outputCenterX) / cosine[y]
                    outputPixels[y * outputSize.width + x] = bilinearPixel(
                        sourcePixels,
                        source.width,
                        source.height,
                        sampleX,
                        sourceY[y],
                    )
                }
            }
        }
        return Bitmap.createBitmap(
            outputPixels,
            outputSize.width,
            outputSize.height,
            Bitmap.Config.ARGB_8888,
        )
    }

    private fun bilinearPixel(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ): Int {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - x0).coerceIn(0.0, 1.0)
        val fy = (y - y0).coerceIn(0.0, 1.0)
        val topLeft = pixels[y0 * width + x0]
        val topRight = pixels[y0 * width + x1]
        val bottomLeft = pixels[y1 * width + x0]
        val bottomRight = pixels[y1 * width + x1]

        fun channel(shift: Int): Int {
            val top = ((topLeft shr shift) and 0xff) * (1.0 - fx) +
                ((topRight shr shift) and 0xff) * fx
            val bottom = ((bottomLeft shr shift) and 0xff) * (1.0 - fx) +
                ((bottomRight shr shift) and 0xff) * fx
            return (top * (1.0 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
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
        output: Bitmap,
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
        val edge = findLowestDifferenceSeam(
            output = output,
            bitmap = bitmap,
            placement = placement,
            horizontal = horizontal,
            occupiedLeft = occupiedLeft,
            occupiedTop = occupiedTop,
            occupiedRight = occupiedRight,
            occupiedBottom = occupiedBottom,
        )
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

    /**
     * Selects a straight seam through the least-visible part of the registered overlap. This is
     * deliberately content-aware: a fixed seam through a foreground edge produces the doubled
     * subjects and hard joins that make a panorama look unlike the native result.
     */
    private fun findLowestDifferenceSeam(
        output: Bitmap,
        bitmap: Bitmap,
        placement: FramePlacement,
        horizontal: Boolean,
        occupiedLeft: Int,
        occupiedTop: Int,
        occupiedRight: Int,
        occupiedBottom: Int,
    ): Float {
        val overlapLeft = maxOf(placement.x, occupiedLeft).coerceAtLeast(0)
        val overlapTop = maxOf(placement.y, occupiedTop).coerceAtLeast(0)
        val overlapRight = minOf(placement.x + bitmap.width, occupiedRight)
            .coerceAtMost(output.width)
        val overlapBottom = minOf(placement.y + bitmap.height, occupiedBottom)
            .coerceAtMost(output.height)
        val mainStart = if (horizontal) overlapLeft else overlapTop
        val mainEnd = if (horizontal) overlapRight else overlapBottom
        val crossStart = if (horizontal) overlapTop else overlapLeft
        val crossEnd = if (horizontal) overlapBottom else overlapRight
        val overlapLength = mainEnd - mainStart
        val crossLength = crossEnd - crossStart
        if (overlapLength < 12 || crossLength < 12) return (mainStart + mainEnd) / 2f

        val margin = (overlapLength / 5).coerceAtLeast(2)
        val candidateStart = mainStart + margin
        val candidateEnd = mainEnd - margin
        val candidateStep = (overlapLength / 72).coerceAtLeast(1)
        val crossStep = (crossLength / 120).coerceAtLeast(1)
        val centre = (mainStart + mainEnd) / 2.0
        var bestPosition = centre.roundToInt()
        var bestScore = Double.POSITIVE_INFINITY
        var candidate = candidateStart
        while (candidate <= candidateEnd) {
            var difference = 0.0
            var samples = 0
            var cross = crossStart
            while (cross < crossEnd) {
                val globalX = if (horizontal) candidate else cross
                val globalY = if (horizontal) cross else candidate
                val currentX = globalX - placement.x
                val currentY = globalY - placement.y
                if (currentX in 0 until bitmap.width && currentY in 0 until bitmap.height) {
                    val existing = output.getPixel(globalX, globalY)
                    val incoming = bitmap.getPixel(currentX, currentY)
                    difference += abs(Color.red(existing) - Color.red(incoming)) +
                        abs(Color.green(existing) - Color.green(incoming)) +
                        abs(Color.blue(existing) - Color.blue(incoming))
                    samples++
                }
                cross += crossStep
            }
            if (samples > 0) {
                val normalizedDifference = difference / samples
                val centrePenalty = abs(candidate - centre) / overlapLength * 3.0
                val score = normalizedDifference + centrePenalty
                if (score < bestScore) {
                    bestScore = score
                    bestPosition = candidate
                }
            }
            candidate += candidateStep
        }
        return bestPosition.toFloat()
    }
}
