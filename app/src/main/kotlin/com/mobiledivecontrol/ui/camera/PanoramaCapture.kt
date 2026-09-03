package com.mobiledivecontrol.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
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
internal const val PANORAMA_REVERSE_FINISH_RADIANS: Float = 0.08726646f // 5 degrees
internal const val PANORAMA_REVERSE_MIN_SWEEP_RADIANS: Float = 0.34906584f // 20 degrees
internal const val PANORAMA_DIRECTION_LOCK_RADIANS: Float = 0.05235988f // 3 degrees
private const val PANORAMA_GUIDE_CROSS_RANGE_RADIANS: Float = 0.2443461f // +/-14 degrees

internal enum class PanoramaWarningLevel { None, Low, High }
internal enum class PanoramaCorrection { None, Up, Down, Left, Right }

/**
 * Panorama is backed by CameraX's 8-bit YUV analysis stream, not the video encoder's 10-bit
 * dynamic-range surface. Keep its three user-visible looks explicit so the live viewfinder and
 * final JPEG use the same deterministic transform even when a lens ignores Samsung's HDR scene
 * request. LOG is therefore a flat, grading-oriented still profile rather than a claim that the
 * JPEG contains Samsung Log's 10-bit video transfer function.
 */
internal enum class PanoramaDynamicRangeProfile {
    Off,
    Hdr,
    Log;

    companion object {
        fun fromSetting(value: String?): PanoramaDynamicRangeProfile = when (value) {
            "HDR" -> Hdr
            "LOG" -> Log
            else -> Off
        }
    }
}

private fun interpolatePanoramaTone(
    value: Int,
    inputLow: Int,
    outputLow: Int,
    inputHigh: Int,
    outputHigh: Int,
): Int = outputLow +
    (value - inputLow) * (outputHigh - outputLow) / (inputHigh - inputLow)

/** Publicly testable transfer curves used by both the live feed and the stitched output. */
internal fun panoramaProfileTone(
    value: Int,
    profile: PanoramaDynamicRangeProfile,
): Int {
    val input = value.coerceIn(0, 255)
    return when (profile) {
        PanoramaDynamicRangeProfile.Off -> input
        PanoramaDynamicRangeProfile.Hdr -> when (input) {
            in 0..31 -> interpolatePanoramaTone(input, 0, 0, 32, 24)
            in 32..63 -> interpolatePanoramaTone(input, 32, 24, 64, 60)
            in 64..127 -> interpolatePanoramaTone(input, 64, 60, 128, 136)
            in 128..191 -> interpolatePanoramaTone(input, 128, 136, 192, 205)
            in 192..223 -> interpolatePanoramaTone(input, 192, 205, 224, 236)
            else -> interpolatePanoramaTone(input, 224, 236, 255, 255)
        }
        PanoramaDynamicRangeProfile.Log -> when (input) {
            in 0..63 -> interpolatePanoramaTone(input, 0, 24, 64, 77)
            in 64..127 -> interpolatePanoramaTone(input, 64, 77, 128, 133)
            in 128..191 -> interpolatePanoramaTone(input, 128, 133, 192, 184)
            else -> interpolatePanoramaTone(input, 192, 184, 255, 232)
        }
    }.coerceIn(0, 255)
}

/** Applies the selected luma curve while preserving hue and deliberately controlling chroma. */
internal fun panoramaProfileArgb(
    pixel: Int,
    profile: PanoramaDynamicRangeProfile,
): Int {
    if (profile == PanoramaDynamicRangeProfile.Off) return pixel
    val red = pixel ushr 16 and 0xff
    val green = pixel ushr 8 and 0xff
    val blue = pixel and 0xff
    val luma = (54 * red + 183 * green + 19 * blue + 128) shr 8
    val mappedLuma = panoramaProfileTone(luma, profile)
    val saturation = when (profile) {
        PanoramaDynamicRangeProfile.Hdr -> 282 // 1.10x: vivid HDR presentation
        PanoramaDynamicRangeProfile.Log -> 184 // 0.72x: flat grading profile
        PanoramaDynamicRangeProfile.Off -> 256
    }
    val lumaShift = mappedLuma - luma
    fun channel(value: Int): Int = (
        luma + (value - luma) * saturation / 256 + lumaShift
        ).coerceIn(0, 255)
    return (pixel and -0x1000000) or
        (channel(red) shl 16) or
        (channel(green) shl 8) or
        channel(blue)
}

internal fun applyPanoramaProfileInPlace(
    bitmap: Bitmap,
    profile: PanoramaDynamicRangeProfile,
) {
    if (profile == PanoramaDynamicRangeProfile.Off) return
    val stripRows = 48.coerceAtMost(bitmap.height)
    val pixels = IntArray(bitmap.width * stripRows)
    var top = 0
    while (top < bitmap.height) {
        val rows = minOf(stripRows, bitmap.height - top)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
        val count = bitmap.width * rows
        for (index in 0 until count) {
            pixels[index] = panoramaProfileArgb(pixels[index], profile)
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
        top += rows
    }
}

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
    val direction: MutableState<String> = mutableStateOf("Auto")
    val directionLocked: MutableState<Boolean> = mutableStateOf(false)
    val message: MutableState<String> = mutableStateOf("")
    val crossAxisRadians: MutableState<Float> = mutableFloatStateOf(0f)
    val warningLevel: MutableState<PanoramaWarningLevel> =
        mutableStateOf(PanoramaWarningLevel.None)
    val correction: MutableState<PanoramaCorrection> =
        mutableStateOf(PanoramaCorrection.None)
    val canStop: MutableState<Boolean> = mutableStateOf(false)
    /** Cropped live frame shown only inside the idle chevron guide. */
    val referenceFrame: MutableState<Bitmap?> = mutableStateOf(null)
    val liveThumbnail: MutableState<Bitmap?> = mutableStateOf(null)
    /** Screen-sized rendering of the private stitched JPEG while Preview owns the viewfinder. */
    val reviewBitmap: MutableState<Bitmap?> = mutableStateOf(null)
    val savingProgress: MutableState<Int> = mutableIntStateOf(0)

    fun reset() {
        active.value = false
        finalizing.value = false
        progress.value = 0f
        frameCount.value = 0
        movingTooFast.value = false
        direction.value = "Auto"
        directionLocked.value = false
        message.value = ""
        crossAxisRadians.value = 0f
        warningLevel.value = PanoramaWarningLevel.None
        correction.value = PanoramaCorrection.None
        canStop.value = false
        referenceFrame.value = null
        liveThumbnail.value = null
        reviewBitmap.value = null
        savingProgress.value = 0
    }
}

internal data class CapturedPanoramaFrame(
    val bitmap: Bitmap,
    val sweepRadians: Float,
    /** Rotation in which [bitmap] is already physically upright. */
    val physicalDisplayRotation: Int = 1,
)

/** A full-resolution keyframe persisted between capture and the bounded-memory stitch pass. */
internal data class StoredPanoramaFrame(
    val file: File,
    val sweepRadians: Float,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val maximumWidth: Int = 0,
    val maximumHeight: Int = 0,
    /** Capture-result telemetry sampled with this RGBA keyframe. */
    val sensitivityIso: Int? = null,
    val exposureTimeNs: Long? = null,
    /** Sampled edge energy used to retain the sharper frame in an overlap. */
    val sharpness: Double = 1.0,
    /** Physical phone rotation (0/90/180/270), independent of the landscape-locked activity. */
    val physicalDisplayRotation: Int = 1,
)

/** Placement of the current frame relative to the previous frame. */
internal data class PanoramaFrameOffset(
    val x: Int,
    val y: Int,
    val correlation: Double,
)

/**
 * Scores an image-registration candidate against the gyro-predicted frame advance.
 *
 * The previous pixel-based penalty was effectively resolution-dependent: at the 320 px
 * registration size, a visually convincing repeated edge could move a frame by half of its
 * expected advance for a cost of only a few thousandths.  That is exactly how repeated blinds,
 * railings, or shelves became duplicated bands in the finished panorama.  Expressing the error
 * as a fraction of the expected motion keeps the prior equally strong at every working size while
 * still allowing a high-confidence correlation peak to correct gyro/FOV error.
 */
internal fun panoramaRegistrationScore(
    correlation: Double,
    advance: Int,
    expectedAdvance: Int,
    crossDrift: Int,
    maximumCrossDrift: Int,
): Double {
    if (!correlation.isFinite() || correlation < -0.999) return -1.0
    val expected = expectedAdvance.coerceAtLeast(1)
    val advanceError = abs(advance - expected).toDouble() / expected
    val crossError = abs(crossDrift).toDouble() / maximumCrossDrift.coerceAtLeast(1)
    return correlation - advanceError * 0.22 - crossError * 0.035
}

internal data class PanoramaProjectionSize(val width: Int, val height: Int)

/**
 * Gamma that makes an incoming overlap meet the already-rendered overlap without sacrificing its
 * independently exposed white point. Unlike a linear gain, the curve leaves black and white fixed,
 * so a darker HDR keyframe can contribute highlight detail without drawing a brightness seam.
 */
internal fun panoramaExposureMatchGamma(incomingLuma: Int, referenceLuma: Int): Float {
    if (incomingLuma !in 8..247 || referenceLuma !in 8..247) return 1f
    if (abs(incomingLuma - referenceLuma) <= 3) return 1f
    val incoming = incomingLuma / 255.0
    val reference = referenceLuma / 255.0
    return (ln(reference) / ln(incoming)).toFloat().coerceIn(0.72f, 1.38f)
}

/** Signed preference for keeping more of the sharper frame in an overlapping panorama region. */
internal fun panoramaSharpnessRetentionBias(
    incomingSharpness: Double,
    existingSharpness: Double,
): Float {
    if (!incomingSharpness.isFinite() || !existingSharpness.isFinite()) return 0f
    val scale = maxOf(incomingSharpness, existingSharpness, 1e-6)
    return (((incomingSharpness - existingSharpness) / scale) * 0.22)
        .toFloat()
        .coerceIn(-0.18f, 0.18f)
}

/**
 * Produces the small, continuously growing capture strip shown by Samsung Panorama while the
 * full-resolution frames remain owned by the final stitcher. Only newly revealed edge pixels are
 * appended, so updating this preview is bounded and never stalls CameraX's visible preview stream.
 */
/** Angular extent of the centre crop used by the miniature, in the selected sweep axis. */
internal fun panoramaThumbnailFov(
    sourceWidth: Int,
    sourceHeight: Int,
    frameWidth: Int,
    frameHeight: Int,
    horizontal: Boolean,
    horizontalFovRadians: Float,
): Float {
    val focal = maxOf(sourceWidth, sourceHeight) /
        (2.0 * tan(horizontalFovRadians.coerceIn(0.61086524f, 2.0943952f) / 2.0))
    val cropWidth = minOf(sourceWidth.toDouble(), sourceHeight.toDouble() * frameWidth / frameHeight)
    val cropHeight = minOf(sourceHeight.toDouble(), sourceWidth.toDouble() * frameHeight / frameWidth)
    return (2.0 * atan((if (horizontal) cropWidth else cropHeight) / (2.0 * focal))).toFloat()
}

/** Round absolute positions so slow movement cannot accumulate one forced pixel per callback. */
internal fun panoramaThumbnailAdvance(
    previousSweepRadians: Float,
    sweepRadians: Float,
    framePixels: Int,
    fieldOfViewRadians: Float,
): Int {
    if (sweepRadians <= previousSweepRadians) return 0
    val pixelsPerRadian = framePixels / fieldOfViewRadians.coerceAtLeast(0.01f)
    return ((sweepRadians.coerceAtLeast(0f) * pixelsPerRadian).roundToInt() -
        (previousSweepRadians.coerceAtLeast(0f) * pixelsPerRadian).roundToInt())
        .coerceIn(0, framePixels)
}

internal object PanoramaLiveThumbnailBuilder {
    // The S24 trace shows a 132x88 dp 3:2 centre window inside the 248x88 dp idle guide.
    private const val HORIZONTAL_FRAME_WIDTH = 198
    private const val HORIZONTAL_FRAME_HEIGHT = 132
    private const val MAX_LONG_EDGE = 1_024

    private fun normalizedFrame(source: Bitmap, frameWidth: Int, frameHeight: Int): Bitmap {
        val targetRatio = frameWidth.toFloat() / frameHeight
        val sourceRatio = source.width.toFloat() / source.height
        val cropWidth = if (sourceRatio > targetRatio) {
            (source.height * targetRatio).roundToInt()
        } else source.width
        val cropHeight = if (sourceRatio > targetRatio) {
            source.height
        } else (source.width / targetRatio).roundToInt()
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - cropWidth) / 2,
            (source.height - cropHeight) / 2,
            cropWidth,
            cropHeight,
        )
        return Bitmap.createScaledBitmap(cropped, frameWidth, frameHeight, true).also { result ->
            if (cropped !== source && cropped !== result) cropped.recycle()
        }
    }

    fun build(
        frames: List<CapturedPanoramaFrame>,
        direction: String,
        portraitFrame: Boolean = false,
        horizontalFovRadians: Float,
    ): Bitmap? {
        if (frames.isEmpty()) return null
        val horizontal = direction == "Left" || direction == "Right"
        val frameWidth = if (portraitFrame) HORIZONTAL_FRAME_HEIGHT else HORIZONTAL_FRAME_WIDTH
        val frameHeight = if (portraitFrame) HORIZONTAL_FRAME_WIDTH else HORIZONTAL_FRAME_HEIGHT
        val scaled = frames.map { frame -> normalizedFrame(frame.bitmap, frameWidth, frameHeight) }
        return try {
            val longDimension = if (horizontal) frameWidth else frameHeight
            val fieldOfView = panoramaThumbnailFov(
                frames.first().bitmap.width, frames.first().bitmap.height,
                frameWidth, frameHeight, horizontal, horizontalFovRadians,
            )
            val advances = frames.zipWithNext { previous, current ->
                panoramaThumbnailAdvance(previous.sweepRadians, current.sweepRadians, longDimension, fieldOfView)
            }
            val requestedLongEdge = longDimension + advances.sum()
            val scale = minOf(1f, MAX_LONG_EDGE.toFloat() / requestedLongEdge)
            val outputWidth = if (horizontal) {
                (requestedLongEdge * scale).roundToInt().coerceAtLeast(1)
            } else {
                (frameWidth * scale).roundToInt().coerceAtLeast(1)
            }
            val outputHeight = if (horizontal) {
                (frameHeight * scale).roundToInt().coerceAtLeast(1)
            } else {
                (requestedLongEdge * scale).roundToInt().coerceAtLeast(1)
            }
            val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            fun scaledRect(left: Int, top: Int, right: Int, bottom: Int): Rect = Rect(
                (left * scale).roundToInt(),
                (top * scale).roundToInt(),
                (right * scale).roundToInt(),
                (bottom * scale).roundToInt(),
            )

            when (direction) {
                "Left" -> {
                    var cursor = requestedLongEdge - frameWidth
                    canvas.drawBitmap(scaled.first(), null, scaledRect(cursor, 0, cursor + frameWidth, frameHeight), paint)
                    scaled.drop(1).forEachIndexed { index, bitmap ->
                        val advance = advances[index]
                        cursor -= advance
                        canvas.drawBitmap(
                            bitmap,
                            Rect(0, 0, advance.coerceAtMost(bitmap.width), bitmap.height),
                            scaledRect(cursor, 0, cursor + advance, frameHeight),
                            paint,
                        )
                    }
                }

                "Up" -> {
                    var cursor = requestedLongEdge - frameHeight
                    canvas.drawBitmap(scaled.first(), null, scaledRect(0, cursor, frameWidth, cursor + frameHeight), paint)
                    scaled.drop(1).forEachIndexed { index, bitmap ->
                        val advance = advances[index]
                        cursor -= advance
                        canvas.drawBitmap(
                            bitmap,
                            Rect(0, 0, bitmap.width, advance.coerceAtMost(bitmap.height)),
                            scaledRect(0, cursor, frameWidth, cursor + advance),
                            paint,
                        )
                    }
                }

                "Down" -> {
                    canvas.drawBitmap(scaled.first(), null, scaledRect(0, 0, frameWidth, frameHeight), paint)
                    var cursor = frameHeight
                    scaled.drop(1).forEachIndexed { index, bitmap ->
                        val advance = advances[index]
                        canvas.drawBitmap(
                            bitmap,
                            Rect(0, bitmap.height - advance.coerceAtMost(bitmap.height), bitmap.width, bitmap.height),
                            scaledRect(0, cursor, frameWidth, cursor + advance),
                            paint,
                        )
                        cursor += advance
                    }
                }

                else -> {
                    canvas.drawBitmap(scaled.first(), null, scaledRect(0, 0, frameWidth, frameHeight), paint)
                    var cursor = frameWidth
                    scaled.drop(1).forEachIndexed { index, bitmap ->
                        val advance = advances[index]
                        canvas.drawBitmap(
                            bitmap,
                            Rect(bitmap.width - advance.coerceAtMost(bitmap.width), 0, bitmap.width, bitmap.height),
                            scaledRect(cursor, 0, cursor + advance, frameHeight),
                            paint,
                        )
                        cursor += advance
                    }
                }
            }
            output
        } finally {
            scaled.forEach { bitmap ->
                if (frames.none { frame -> frame.bitmap === bitmap }) bitmap.recycle()
            }
        }
    }

    /**
     * Adds exactly one keyframe to an existing miniature. Long captures therefore do constant
     * work per frame instead of repeatedly scaling every full-resolution source frame.
     * Ownership of [existing] stays with the caller; no new pixels returns [existing] unchanged.
     */
    fun append(
        existing: Bitmap?,
        frame: CapturedPanoramaFrame,
        direction: String,
        previousSweepRadians: Float?,
        portraitFrame: Boolean = false,
        horizontalFovRadians: Float,
    ): Bitmap {
        val horizontal = direction == "Left" || direction == "Right"
        val frameWidth = if (portraitFrame) HORIZONTAL_FRAME_HEIGHT else HORIZONTAL_FRAME_WIDTH
        val frameHeight = if (portraitFrame) HORIZONTAL_FRAME_WIDTH else HORIZONTAL_FRAME_HEIGHT
        val normalized = normalizedFrame(frame.bitmap, frameWidth, frameHeight)
        if (existing == null) return normalized

        val longDimension = if (horizontal) frameWidth else frameHeight
        val fieldOfView = panoramaThumbnailFov(
            frame.bitmap.width, frame.bitmap.height, frameWidth, frameHeight,
            horizontal, horizontalFovRadians,
        )
        val advance = panoramaThumbnailAdvance(
            previousSweepRadians ?: frame.sweepRadians, frame.sweepRadians,
            longDimension, fieldOfView,
        )
        if (advance == 0) {
            if (normalized !== frame.bitmap) normalized.recycle()
            return existing
        }
        val requestedLongEdge = (if (horizontal) existing.width else existing.height) + advance
        val outputLongEdge = requestedLongEdge.coerceAtMost(MAX_LONG_EDGE)
        val output = Bitmap.createBitmap(
            if (horizontal) outputLongEdge else frameWidth,
            if (horizontal) frameHeight else outputLongEdge,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val keptOldEdge = outputLongEdge - advance
        when (direction) {
            "Left" -> {
                canvas.drawBitmap(
                    normalized,
                    Rect(0, 0, advance.coerceAtMost(normalized.width), normalized.height),
                    Rect(0, 0, advance, frameHeight),
                    paint,
                )
                canvas.drawBitmap(
                    existing,
                    Rect(0, 0, keptOldEdge.coerceAtMost(existing.width), existing.height),
                    Rect(advance, 0, outputLongEdge, frameHeight),
                    paint,
                )
            }
            "Up" -> {
                canvas.drawBitmap(
                    normalized,
                    Rect(0, 0, normalized.width, advance.coerceAtMost(normalized.height)),
                    Rect(0, 0, frameWidth, advance),
                    paint,
                )
                canvas.drawBitmap(
                    existing,
                    Rect(0, 0, existing.width, keptOldEdge.coerceAtMost(existing.height)),
                    Rect(0, advance, frameWidth, outputLongEdge),
                    paint,
                )
            }
            "Down" -> {
                val oldTop = (existing.height - keptOldEdge).coerceAtLeast(0)
                canvas.drawBitmap(
                    existing,
                    Rect(0, oldTop, existing.width, existing.height),
                    Rect(0, 0, frameWidth, keptOldEdge),
                    paint,
                )
                canvas.drawBitmap(
                    normalized,
                    Rect(0, normalized.height - advance.coerceAtMost(normalized.height), normalized.width, normalized.height),
                    Rect(0, keptOldEdge, frameWidth, outputLongEdge),
                    paint,
                )
            }
            else -> {
                val oldLeft = (existing.width - keptOldEdge).coerceAtLeast(0)
                canvas.drawBitmap(
                    existing,
                    Rect(oldLeft, 0, existing.width, existing.height),
                    Rect(0, 0, keptOldEdge, frameHeight),
                    paint,
                )
                canvas.drawBitmap(
                    normalized,
                    Rect(normalized.width - advance.coerceAtMost(normalized.width), 0, normalized.width, normalized.height),
                    Rect(keptOldEdge, 0, outputLongEdge, frameHeight),
                    paint,
                )
            }
        }
        normalized.recycle()
        return output
    }
}

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
    // Galaxy S24 empirical calibration: a clockwise/rightward landscape sweep reports -gyroX.
    1 -> PanoramaScreenAxisRates(rightward = -gyroX, upward = gyroY)
    2 -> PanoramaScreenAxisRates(rightward = -gyroY, upward = -gyroX)
    3 -> PanoramaScreenAxisRates(rightward = gyroX, upward = -gyroY)
    else -> PanoramaScreenAxisRates(rightward = gyroY, upward = gyroX)
}

/**
 * Resolves how the phone is physically being held even when Android keeps the activity locked to
 * landscape. Native Panorama rotates its capture lane in this situation; relying on
 * Display.getRotation() instead made a portrait rightward sweep look like an upward sweep and the
 * stitcher consequently appended the same scene along the wrong bitmap axis.
 */
internal fun panoramaPhysicalDisplayRotation(
    gravityX: Float,
    gravityY: Float,
    fallbackDisplayRotation: Int,
): Int {
    val horizontal = abs(gravityX)
    val vertical = abs(gravityY)
    if (maxOf(horizontal, vertical) < 2.5f) return fallbackDisplayRotation
    if (vertical > horizontal * 1.15f) return if (gravityY >= 0f) 0 else 2
    // Landscape is already calibrated against the S24's locked activity rotation. Gravity sign
    // varies with which housing edge is down, so changing a known-good landscape mapping here
    // would make the two housing orientations disagree.
    if (horizontal > vertical * 1.15f) return fallbackDisplayRotation
    return fallbackDisplayRotation
}

/** Maps a guide direction on the locked activity to the physically oriented source bitmap. */
internal fun panoramaBitmapDirection(
    guideDirection: String,
    physicalDisplayRotation: Int,
    guideDisplayRotation: Int,
): String = panoramaDirectionBetweenDisplays(
    // The gyro direction is expressed in the activity's locked-landscape coordinates. Portrait
    // presentation reverses its vertical sign; applying that correction only to the guide made
    // the guide travel Down while the physical strip and final registration still travelled
    // Right. That disagreement selected the already-seen edge of each frame and produced the
    // repeated/mangled columns visible in both the live strip and the saved portrait panorama.
    direction = panoramaPreviewDirection(guideDirection, physicalDisplayRotation),
    sourceDisplayRotation = guideDisplayRotation,
    targetDisplayRotation = physicalDisplayRotation,
)

/** Rotates a cardinal image direction with the same transform used for its bitmap pixels. */
internal fun panoramaDirectionBetweenDisplays(
    direction: String,
    sourceDisplayRotation: Int,
    targetDisplayRotation: Int,
): String {
    val directions = listOf("Up", "Right", "Down", "Left")
    val index = directions.indexOf(direction)
    if (index < 0) return direction
    val clockwiseQuarterTurns = (sourceDisplayRotation - targetDisplayRotation + 4) % 4
    return directions[(index + clockwiseQuarterTurns) % directions.size]
}

/**
 * Direction presented on the locked-landscape guide. When the phone is upright in portrait, the
 * guide's vertical canvas axis is viewed horizontally by the user and its sign is reversed.
 * This is presentation-only; raw-frame registration keeps [panoramaBitmapDirection].
 */
internal fun panoramaPreviewDirection(
    guideDirection: String,
    physicalDisplayRotation: Int,
): String = if (physicalDisplayRotation == 0 || physicalDisplayRotation == 2) {
    when (guideDirection) {
        "Up" -> "Down"
        "Down" -> "Up"
        else -> guideDirection
    }
} else {
    guideDirection
}

/**
 * Preserves the source frame edge perpendicular to travel. For a 1920x1080 stream this is 1080
 * for a landscape horizontal sweep and 1920 for a landscape vertical sweep; portrait swaps those
 * cases exactly as Samsung's guide and saved panorama do.
 */
internal fun panoramaTargetCrossPixels(
    sourceWidth: Int,
    sourceHeight: Int,
    normalizedShortEdge: Int,
    bitmapDirection: String,
    physicalDisplayRotation: Int,
): Int {
    val short = minOf(sourceWidth, sourceHeight).coerceAtLeast(1)
    val long = maxOf(sourceWidth, sourceHeight).coerceAtLeast(short)
    val normalizedLongEdge = (normalizedShortEdge.coerceAtLeast(1) * long.toFloat() / short)
        .roundToInt()
    val portrait = physicalDisplayRotation == 0 || physicalDisplayRotation == 2
    val horizontal = bitmapDirection == "Left" || bitmapDirection == "Right"
    return if (portrait == horizontal) normalizedLongEdge else normalizedShortEdge.coerceAtLeast(1)
}

/** Normalizes CameraX's target-rotation bitmap to the phone's physical capture orientation. */
internal fun orientPanoramaBitmapForPhysicalRotation(
    source: Bitmap,
    physicalDisplayRotation: Int,
): Bitmap = rotatePanoramaBitmapBetweenDisplays(
    source = source,
    // CameraX rotationDegrees has already normalized the analysis buffer to the app's locked
    // landscape target. Convert that upright guide image to the phone's actual physical hold.
    sourceDisplayRotation = 1,
    targetDisplayRotation = physicalDisplayRotation,
)

/** Rotates a physically oriented live strip into the landscape-locked guide coordinates. */
internal fun rotatePanoramaBitmapBetweenDisplays(
    source: Bitmap,
    sourceDisplayRotation: Int,
    targetDisplayRotation: Int,
): Bitmap {
    val quarterTurns = (sourceDisplayRotation - targetDisplayRotation + 4) % 4
    if (quarterTurns == 0) return source
    val degrees = when (quarterTurns) {
        1 -> 90f
        2 -> 180f
        else -> -90f
    }
    return Bitmap.createBitmap(
        source,
        0,
        0,
        source.width,
        source.height,
        Matrix().apply { postRotate(degrees) },
        true,
    ).also { if (it !== source) source.recycle() }
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
    (crossAxisRadians / PANORAMA_GUIDE_CROSS_RANGE_RADIANS).coerceIn(-1f, 1f)

/** Absolute camera elevation derived from gravity; unlike gyro integration this cannot drift. */
internal fun panoramaGravityElevationRadians(
    gravityX: Float,
    gravityY: Float,
    gravityZ: Float,
): Float {
    val magnitude = sqrt(
        gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ,
    )
    if (magnitude <= 0.001f) return 0f
    return asin((gravityZ / magnitude).coerceIn(-1f, 1f))
}

/** Positive means the capture rectangle must move up and the user must tilt back down. */
internal fun panoramaGravityCrossAxisRadians(
    baselineElevationRadians: Float,
    currentElevationRadians: Float,
): Float = baselineElevationRadians - currentElevationRadians

internal fun panoramaShouldFinishOnReverse(
    reverseRadians: Float,
    sweepRadians: Float,
    capturedFrames: Int,
): Boolean = capturedFrames >= 2 &&
    sweepRadians >= PANORAMA_REVERSE_MIN_SWEEP_RADIANS &&
    reverseRadians >= PANORAMA_REVERSE_FINISH_RADIANS

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
    val axes = panoramaScreenAxisRates(displayRotation, gyroX, gyroY)
    return if (abs(axes.rightward) >= abs(axes.upward)) {
        if (axes.rightward >= 0f) "Right" else "Left"
    } else {
        if (axes.upward >= 0f) "Up" else "Down"
    }
}

/**
 * Samsung does not commit the sweep direction from the shutter tap's first gyro sample. A small
 * net rotation must accumulate first, so button movement and hand-settling cannot flip a whole
 * panorama. The dominant screen axis wins only after it is clearly stronger than the other axis;
 * this prevents a diagonal hand-settling movement from choosing the wrong orientation.
 */
internal fun panoramaDirectionFromAccumulatedMotion(
    rightwardRadians: Float,
    upwardRadians: Float,
): String? {
    val horizontal = abs(rightwardRadians)
    val vertical = abs(upwardRadians)
    val dominant = maxOf(horizontal, vertical)
    if (dominant < PANORAMA_DIRECTION_LOCK_RADIANS) return null
    if (horizontal < vertical * 1.15f && vertical < horizontal * 1.15f) return null
    return if (horizontal > vertical) {
        if (rightwardRadians >= 0f) "Right" else "Left"
    } else {
        if (upwardRadians >= 0f) "Up" else "Down"
    }
}

/** Compatibility overload for callers that intentionally constrain capture to a horizontal axis. */
internal fun panoramaDirectionFromAccumulatedMotion(rightwardRadians: Float): String? =
    panoramaDirectionFromAccumulatedMotion(rightwardRadians, 0f)

/** Geometry occupied by the complete live stitch between the fixed start and moving guide frame. */
internal data class PanoramaLiveStripRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Samsung sizes the already-merged live thumbnail by its bitmap aspect ratio. The cross axis
 * always fills the capture lane (minus the 2 dp inset on each edge); the long axis is allowed to
 * grow until it reaches the lane boundary. This preserves image geometry and makes the stitched
 * preview visibly extend with every newly revealed slice.
 */
internal fun panoramaLiveThumbnailRect(
    direction: String,
    groupLeft: Float,
    groupTop: Float,
    groupWidth: Float,
    groupHeight: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
    inset: Float,
): PanoramaLiveStripRect {
    val availableWidth = (groupWidth - inset * 2f).coerceAtLeast(1f)
    val availableHeight = (groupHeight - inset * 2f).coerceAtLeast(1f)
    val safeBitmapWidth = bitmapWidth.coerceAtLeast(1).toFloat()
    val safeBitmapHeight = bitmapHeight.coerceAtLeast(1).toFloat()
    return if (direction == "Left" || direction == "Right") {
        val width = (availableHeight * safeBitmapWidth / safeBitmapHeight)
            .coerceIn(1f, availableWidth)
        val left = if (direction == "Left") {
            groupLeft + groupWidth - inset - width
        } else {
            groupLeft + inset
        }
        PanoramaLiveStripRect(
            left = left,
            top = groupTop + inset,
            right = left + width,
            bottom = groupTop + inset + availableHeight,
        )
    } else {
        val height = (availableWidth * safeBitmapHeight / safeBitmapWidth)
            .coerceIn(1f, availableHeight)
        val top = if (direction == "Up") {
            groupTop + groupHeight - inset - height
        } else {
            groupTop + inset
        }
        PanoramaLiveStripRect(
            left = groupLeft + inset,
            top = top,
            right = groupLeft + inset + availableWidth,
            bottom = top + height,
        )
    }
}

/**
 * Position of Samsung's fixed start frame and moving capture frame inside the expanded guide.
 *
 * The compact guide is centred before direction lock. Once the first deliberate motion selects
 * an axis, Samsung re-anchors the start frame at the trailing edge of the available lane and lets
 * the moving frame traverse the complete lane. Keeping the start frame at screen centre only
 * reveals half of the available display and is why the old live stitch never stretched across it.
 */
internal data class PanoramaGuideTrack(
    val startX: Float,
    val startY: Float,
    val currentX: Float,
    val currentY: Float,
)

internal fun panoramaGuideTrack(
    direction: String,
    groupLeft: Float,
    groupTop: Float,
    groupWidth: Float,
    groupHeight: Float,
    frameWidth: Float,
    frameHeight: Float,
    progress: Float,
    crossOffset: Float = 0f,
): PanoramaGuideTrack {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val centreX = groupLeft + groupWidth / 2f
    val centreY = groupTop + groupHeight / 2f
    val horizontalTravel = (groupWidth - frameWidth).coerceAtLeast(0f)
    val verticalTravel = (groupHeight - frameHeight).coerceAtLeast(0f)
    return when (direction) {
        "Left" -> {
            val startX = groupLeft + groupWidth - frameWidth / 2f
            PanoramaGuideTrack(
                startX = startX,
                startY = centreY,
                currentX = startX - horizontalTravel * clampedProgress,
                currentY = centreY - crossOffset,
            )
        }

        "Up" -> {
            val startY = groupTop + groupHeight - frameHeight / 2f
            PanoramaGuideTrack(
                startX = centreX,
                startY = startY,
                currentX = centreX + crossOffset,
                currentY = startY - verticalTravel * clampedProgress,
            )
        }

        "Down" -> {
            val startY = groupTop + frameHeight / 2f
            PanoramaGuideTrack(
                startX = centreX,
                startY = startY,
                currentX = centreX + crossOffset,
                currentY = startY + verticalTravel * clampedProgress,
            )
        }

        else -> {
            val startX = groupLeft + frameWidth / 2f
            PanoramaGuideTrack(
                startX = startX,
                startY = centreY,
                currentX = startX + horizontalTravel * clampedProgress,
                currentY = centreY - crossOffset,
            )
        }
    }
}

internal fun panoramaLiveStripRect(
    direction: String,
    startX: Float,
    startY: Float,
    currentX: Float,
    currentY: Float,
    frameWidth: Float,
    frameHeight: Float,
): PanoramaLiveStripRect = if (direction == "Left" || direction == "Right") {
    PanoramaLiveStripRect(
        left = minOf(startX, currentX) - frameWidth / 2f,
        top = currentY - frameHeight / 2f,
        right = maxOf(startX, currentX) + frameWidth / 2f,
        bottom = currentY + frameHeight / 2f,
    )
} else {
    PanoramaLiveStripRect(
        left = currentX - frameWidth / 2f,
        top = minOf(startY, currentY) - frameHeight / 2f,
        right = currentX + frameWidth / 2f,
        bottom = maxOf(startY, currentY) + frameHeight / 2f,
    )
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
    // Camera motion is the authoritative coarse placement. Image registration may refine it,
    // but it must not jump to a distant copy of a repetitive feature.
    val minimumAdvance = (expected * 0.64f).roundToInt().coerceAtLeast(2)
    val maximumAdvance = (expected * 1.36f).roundToInt()
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
    var bestCorrelation = -1.0
    var advance = minimumAdvance
    while (advance <= maximumAdvance) {
        var cross = -maximumCrossDrift
        while (cross <= maximumCrossDrift) {
            val (offsetX, offsetY) = offsetFor(advance, cross)
            val match = correlation(offsetX, offsetY, sampleStep = 6)
            val score = panoramaRegistrationScore(
                correlation = match,
                advance = advance,
                expectedAdvance = expected,
                crossDrift = cross,
                maximumCrossDrift = maximumCrossDrift,
            )
            if (score > bestScore) {
                bestScore = score
                bestCorrelation = match
                bestAdvance = advance
                bestCross = cross
            }
            cross++
        }
        advance++
    }

    var refinedScore = bestScore
    var refinedCorrelation = bestCorrelation
    var refinedAdvance = bestAdvance
    var refinedCross = bestCross
    for (candidateAdvance in (bestAdvance - 3)..(bestAdvance + 3)) {
        if (candidateAdvance !in minimumAdvance..maximumAdvance) continue
        for (candidateCross in (bestCross - 3)..(bestCross + 3)) {
            if (candidateCross !in -maximumCrossDrift..maximumCrossDrift) continue
            val (offsetX, offsetY) = offsetFor(candidateAdvance, candidateCross)
            val match = correlation(offsetX, offsetY, sampleStep = 2)
            val score = panoramaRegistrationScore(
                correlation = match,
                advance = candidateAdvance,
                expectedAdvance = expected,
                crossDrift = candidateCross,
                maximumCrossDrift = maximumCrossDrift,
            )
            if (score > refinedScore) {
                refinedScore = score
                refinedCorrelation = match
                refinedAdvance = candidateAdvance
                refinedCross = candidateCross
            }
        }
    }

    val (fallbackX, fallbackY) = offsetFor(expected, 0)
    if (refinedScore < 0.16) return PanoramaFrameOffset(fallbackX, fallbackY, refinedCorrelation)
    val (offsetX, offsetY) = offsetFor(refinedAdvance, refinedCross)
    return PanoramaFrameOffset(offsetX, offsetY, refinedCorrelation)
}

/**
 * App-owned panorama compositor. CameraX supplies overlapping stills, the gyro supplies an initial
 * angular spacing estimate, and image correlation registers every adjacent overlap. Frames are
 * feathered at the registered seam and the common cross-axis area is cropped to remove drift edges.
 */
internal object PanoramaBitmapStitcher {
    private const val TAG = "PanoramaStitcher"
    private const val HORIZONTAL_FOV_RADIANS = 1.2217305f // 70 degrees
    private const val REGISTRATION_WIDTH = 320
    private const val REGISTRATION_HEIGHT = 240
    private const val FEATHER_PIXELS = 96

    private data class FramePlacement(val x: Int, val y: Int)

    fun stitch(
        frames: List<CapturedPanoramaFrame>,
        direction: String,
        horizontalFovRadians: Float = HORIZONTAL_FOV_RADIANS,
        onProgress: ((Int) -> Unit)? = null,
    ): Bitmap {
        require(frames.isNotEmpty()) { "A panorama needs at least one frame" }
        onProgress?.invoke(2)
        if (frames.size == 1) {
            return frames.first().bitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                onProgress?.invoke(95)
            }
        }

        val horizontal = direction == "Left" || direction == "Right"
        val clampedHorizontalFov = horizontalFovRadians.coerceIn(0.61086524f, 2.0943952f)
        val firstSource = frames.first().bitmap
        val focalPixels = firstSource.width / (2.0 * tan(clampedHorizontalFov / 2.0))
        val verticalFov = (2.0 * atan(firstSource.height / (2.0 * focalPixels))).toFloat()
        val projectedFrames = frames.mapIndexed { index, frame ->
            CapturedPanoramaFrame(
                bitmap = cylindricalWarp(
                    source = frame.bitmap,
                    horizontal = horizontal,
                    horizontalFovRadians = clampedHorizontalFov,
                ),
                sweepRadians = frame.sweepRadians,
            ).also {
                onProgress?.invoke(5 + ((index + 1) * 30 / frames.size))
            }
        }
        return try {
            stitchProjected(
                frames = projectedFrames,
                direction = direction,
                projectionFovRadians = if (horizontal) clampedHorizontalFov else verticalFov,
                onProgress = onProgress,
            )
        } finally {
            projectedFrames.forEach { frame -> runCatching { frame.bitmap.recycle() } }
        }
    }

    /** Full-resolution composition advances on the capture worker, once per accepted keyframe. */
    class Incremental(
        private val direction: String,
        private val horizontalFovRadians: Float,
        private val targetCrossPixels: Int,
        private val profile: PanoramaDynamicRangeProfile,
    ) : AutoCloseable {
        private val horizontal = direction == "Left" || direction == "Right"
        private var mosaic: Bitmap? = null
        private var previousRegistration: Bitmap? = null
        private var previousSweep = 0f
        private var previousSharpness = 1.0
        private var position = FramePlacement(0, 0)
        private var originX = 0
        private var originY = 0
        private var commonLow = Int.MIN_VALUE
        private var commonHigh = Int.MAX_VALUE
        private var captureFov = horizontalFovRadians
        private var projectionFov = horizontalFovRadians
        private var scaleX = 1f
        private var scaleY = 1f
        private var registrationX = 0
        private var registrationY = 0
        var frameCount = 0
            private set

        fun append(frame: StoredPanoramaFrame) {
            val source = decodeStoredFrame(frame)
            if (frameCount == 0) {
                val portrait = frame.physicalDisplayRotation == 0 || frame.physicalDisplayRotation == 2
                val aspect = minOf(frame.rawWidth, frame.rawHeight).toDouble() / maxOf(frame.rawWidth, frame.rawHeight)
                captureFov = if (portrait) (2 * atan(tan(horizontalFovRadians / 2.0) * aspect)).toFloat()
                    else horizontalFovRadians
            }
            var registration: Bitmap? = null
            var full: Bitmap? = null
            try {
                val sampleScale = minOf(1f, 480f / maxOf(source.width, source.height))
                val sample = Bitmap.createScaledBitmap(source,
                    (source.width * sampleScale).roundToInt().coerceAtLeast(1),
                    (source.height * sampleScale).roundToInt().coerceAtLeast(1), true)
                registration = try { cylindricalWarp(sample, horizontal, captureFov) }
                    finally { if (sample !== source) sample.recycle() }
                val warped = cylindricalWarp(source, horizontal, captureFov)
                val cross = if (horizontal) warped.height else warped.width
                full = if (targetCrossPixels < cross) {
                    val scale = targetCrossPixels.toFloat() / cross
                    Bitmap.createScaledBitmap(warped, (warped.width * scale).roundToInt().coerceAtLeast(1),
                        (warped.height * scale).roundToInt().coerceAtLeast(1), true)
                        .also { if (it !== warped) warped.recycle() }
                } else warped
                // Register neutral frames, then prepare their colour/detail at capture resolution.
                // Processing the final, upscaled panorama on Stop was the remaining multi-second stall.
                applyPanoramaProfileInPlace(checkNotNull(full), profile)
                if (profile == PanoramaDynamicRangeProfile.Hdr) enhanceLocalToneAndDetail(checkNotNull(full))
                val registrationImage = checkNotNull(registration)
                val old = mosaic
                if (old == null) {
                    scaleX = full.width.toFloat() / registrationImage.width
                    scaleY = full.height.toFloat() / registrationImage.height
                    val focal = registrationImage.width / (2.0 * tan(captureFov / 2.0))
                    projectionFov = if (horizontal) captureFov
                        else (2.0 * atan(registrationImage.height / (2.0 * focal))).toFloat()
                    mosaic = full
                    full = null
                } else {
                    val previous = checkNotNull(previousRegistration)
                    val dimension = if (horizontal) registrationImage.width else registrationImage.height
                    val delta = (frame.sweepRadians - previousSweep).coerceAtLeast(PANORAMA_FRAME_STEP_RADIANS * 0.35f)
                    val expected = (dimension * delta / projectionFov).roundToInt()
                        .coerceIn((dimension / 32).coerceAtLeast(1), (dimension / 3).coerceAtLeast(1))
                    val offset = estimateOffset(previous, registrationImage, direction, expected)
                    val crossLimit = ((if (horizontal) registrationImage.height else registrationImage.width) * 0.15f).roundToInt()
                    registrationX += offset.x
                    registrationY += offset.y
                    if (horizontal) registrationY = registrationY.coerceIn(-crossLimit, crossLimit)
                    else registrationX = registrationX.coerceIn(-crossLimit, crossLimit)
                    position = FramePlacement((registrationX * scaleX).roundToInt(), (registrationY * scaleY).roundToInt())
                    val left = minOf(originX, position.x)
                    val top = minOf(originY, position.y)
                    val right = maxOf(originX + old.width, position.x + full.width)
                    val bottom = maxOf(originY + old.height, position.y + full.height)
                    val expanded = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(expanded)
                        canvas.drawBitmap(old, (originX - left).toFloat(), (originY - top).toFloat(), null)
                        drawFeatheredFrame(expanded, canvas, full,
                            FramePlacement(position.x - left, position.y - top), direction,
                            originX - left, originY - top, originX - left + old.width, originY - top + old.height,
                            frame.sharpness, previousSharpness)
                    } catch (error: Throwable) { expanded.recycle(); throw error }
                    mosaic = expanded
                    old.recycle()
                    originX = left
                    originY = top
                }
                val image = checkNotNull(mosaic)
                val crossSize = if (horizontal) full?.height ?: image.height else full?.width ?: image.width
                val crossPosition = if (horizontal) position.y else position.x
                commonLow = maxOf(commonLow, crossPosition)
                commonHigh = minOf(commonHigh, crossPosition + crossSize)
                previousRegistration?.recycle()
                previousRegistration = registration
                registration = null
                previousSweep = frame.sweepRadians
                previousSharpness = frame.sharpness
                frameCount++
            } finally {
                source.recycle()
                registration?.recycle()
                full?.recycle()
            }
        }

        fun finish(): Bitmap {
            val image = checkNotNull(mosaic) { "No panorama frames" }
            check(commonHigh > commonLow) { "Panorama frames do not overlap" }
            val cropped = if (horizontal) Bitmap.createBitmap(image, 0, commonLow - originY, image.width, commonHigh - commonLow)
                else Bitmap.createBitmap(image, commonLow - originX, 0, commonHigh - commonLow, image.height)
            mosaic = null
            if (cropped !== image) image.recycle()
            val cross = if (horizontal) cropped.height else cropped.width
            val scale = targetCrossPixels.toFloat() / cross
            val result = if (cross == targetCrossPixels) cropped else Bitmap.createScaledBitmap(cropped,
                (cropped.width * scale).roundToInt().coerceAtLeast(1),
                (cropped.height * scale).roundToInt().coerceAtLeast(1), true)
                .also { if (it !== cropped) cropped.recycle() }
            try {
                check(projectionHasOpaqueCoverage(result)) { "Panorama compositor left unmapped pixels" }
                result.setHasAlpha(false)
                return result
            } catch (error: Throwable) { result.recycle(); throw error }
        }

        override fun close() {
            mosaic?.recycle()
            mosaic = null
            previousRegistration?.recycle()
            previousRegistration = null
        }
    }

    /**
     * Full-resolution variant used on-device. Pass one registers adjacent projected frames while
     * retaining only two bitmaps; pass two renders them into the final canvas one at a time. This
     * keeps the 3648x2736 ultrawide stream below the app heap limit without lowering resolution.
     */
    fun stitchStored(
        frames: List<StoredPanoramaFrame>,
        direction: String,
        horizontalFovRadians: Float = HORIZONTAL_FOV_RADIANS,
        targetCrossPixels: Int? = null,
        dynamicRangeProfile: PanoramaDynamicRangeProfile = PanoramaDynamicRangeProfile.Off,
        registrationFrames: List<CapturedPanoramaFrame>? = null,
        onProgress: ((Int) -> Unit)? = null,
    ): Bitmap {
        require(frames.isNotEmpty()) { "A panorama needs at least one frame" }
        val horizontal = direction == "Left" || direction == "Right"
        val clampedHorizontalFov = horizontalFovRadians.coerceIn(0.61086524f, 2.0943952f)
        val portraitCapture = frames.first().physicalDisplayRotation == 0 ||
            frames.first().physicalDisplayRotation == 2
        val sensorAspect = frames.first().let { frame ->
            if (frame.rawWidth > 0 && frame.rawHeight > 0) {
                minOf(frame.rawWidth, frame.rawHeight).toFloat() /
                    maxOf(frame.rawWidth, frame.rawHeight)
            } else {
                0.75f
            }
        }
        // In portrait, bitmap X spans the sensor's short edge rather than its advertised
        // landscape horizontal FOV. Deriving that short-edge FOV keeps gyro placement metrically
        // correct after the physical-orientation normalization.
        val captureHorizontalFov = if (portraitCapture) {
            (2.0 * atan(tan(clampedHorizontalFov / 2.0) * sensorAspect)).toFloat()
        } else {
            clampedHorizontalFov
        }

        fun projected(frame: StoredPanoramaFrame, registrationOnly: Boolean = false): Bitmap {
            val source = decodeStoredFrame(frame)
            val work = if (registrationOnly) {
                val scale = minOf(1f, 1_024f / maxOf(source.width, source.height))
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * scale).roundToInt().coerceAtLeast(1),
                        (source.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    ).also { source.recycle() }
                } else {
                    source
                }
            } else {
                source
            }
            return try {
                val warped = cylindricalWarp(work, horizontal, captureHorizontalFov)
                val requestedCross = targetCrossPixels?.coerceAtLeast(1)
                val currentCross = if (horizontal) warped.height else warped.width
                // Composite at the requested output resolution instead of building an oversized
                // multi-frame canvas and shrinking it only after every blend. The saved pixel
                // dimensions are identical, but feathering, gamma matching and JPEG staging touch
                // substantially fewer pixels after Shutter stops.
                if (!registrationOnly && requestedCross != null && requestedCross < currentCross) {
                    val scale = requestedCross.toFloat() / currentCross
                    Bitmap.createScaledBitmap(
                        warped,
                        (warped.width * scale).roundToInt().coerceAtLeast(1),
                        (warped.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    ).also { scaled ->
                        if (scaled !== warped) warped.recycle()
                    }
                } else {
                    warped
                }
            } finally {
                work.recycle()
            }
        }

        val registrationCandidates = registrationFrames?.takeIf { candidates ->
            candidates.size == frames.size && candidates.none { it.bitmap.isRecycled }
        }
        fun registrationProjected(index: Int): Bitmap {
            val candidate = registrationCandidates?.get(index)
                ?: return projected(frames[index], registrationOnly = true)
            val copied = candidate.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return projected(frames[index], registrationOnly = true)
            val physicallyNormalized = rotatePanoramaBitmapBetweenDisplays(
                source = copied,
                sourceDisplayRotation = candidate.physicalDisplayRotation,
                targetDisplayRotation = frames[index].physicalDisplayRotation,
            )
            return try {
                cylindricalWarp(physicallyNormalized, horizontal, captureHorizontalFov)
            } finally {
                physicallyNormalized.recycle()
            }
        }

        onProgress?.invoke(2)
        var previous = registrationProjected(0)
        val firstWidth = previous.width
        val firstHeight = previous.height
        val focalPixels = firstWidth / (2.0 * tan(captureHorizontalFov / 2.0))
        val verticalFov = (2.0 * atan(firstHeight / (2.0 * focalPixels))).toFloat()
        val projectionFov = if (horizontal) captureHorizontalFov else verticalFov
        val maximumGlobalCrossDrift = (
            (if (horizontal) firstHeight else firstWidth) * 0.15f
            ).roundToInt().coerceAtLeast(1)
        val placements = mutableListOf(FramePlacement(0, 0))
        try {
            for (index in 1 until frames.size) {
                val current = registrationProjected(index)
                try {
                    val dimension = if (horizontal) firstWidth else firstHeight
                    val delta = (frames[index].sweepRadians - frames[index - 1].sweepRadians)
                        .coerceAtLeast(PANORAMA_FRAME_STEP_RADIANS * 0.35f)
                    val expectedAdvance = (dimension * delta / projectionFov)
                        .roundToInt()
                        .coerceIn(
                            (dimension / 32).coerceAtLeast(1),
                            (dimension / 3).coerceAtLeast(1),
                        )
                    val offset = estimateOffset(previous, current, direction, expectedAdvance)
                    val selectedAdvance = if (horizontal) abs(offset.x) else abs(offset.y)
                    val selectedCross = if (horizontal) offset.y else offset.x
                    Log.i(
                        TAG,
                        "register frame=$index direction=$direction delta=$delta " +
                            "expected=$expectedAdvance selected=$selectedAdvance " +
                            "cross=$selectedCross correlation=${offset.correlation}",
                    )
                    val prior = placements.last()
                    val nextX = prior.x + offset.x
                    val nextY = prior.y + offset.y
                    placements += if (horizontal) {
                        FramePlacement(
                            nextX,
                            nextY.coerceIn(-maximumGlobalCrossDrift, maximumGlobalCrossDrift),
                        )
                    } else {
                        FramePlacement(
                            nextX.coerceIn(-maximumGlobalCrossDrift, maximumGlobalCrossDrift),
                            nextY,
                        )
                    }
                } finally {
                    previous.recycle()
                    previous = current
                }
                onProgress?.invoke(4 + index * 38 / (frames.size - 1).coerceAtLeast(1))
            }
        } finally {
            previous.recycle()
        }

        val firstFull = projected(frames.first())
        val scaleX = firstFull.width.toFloat() / firstWidth
        val scaleY = firstFull.height.toFloat() / firstHeight
        val fullPlacements = placements.map { placement ->
            FramePlacement(
                (placement.x * scaleX).roundToInt(),
                (placement.y * scaleY).roundToInt(),
            )
        }
        val cropLeft: Int
        val cropTop: Int
        val cropRight: Int
        val cropBottom: Int
        if (horizontal) {
            cropLeft = fullPlacements.minOf { it.x }
            cropRight = fullPlacements.maxOf { it.x + firstFull.width }
            cropTop = fullPlacements.maxOf { it.y }
            cropBottom = fullPlacements.minOf { it.y + firstFull.height }
        } else {
            cropLeft = fullPlacements.maxOf { it.x }
            cropRight = fullPlacements.minOf { it.x + firstFull.width }
            cropTop = fullPlacements.minOf { it.y }
            cropBottom = fullPlacements.maxOf { it.y + firstFull.height }
        }
        if (cropRight <= cropLeft || cropBottom <= cropTop) {
            firstFull.recycle()
            error("Panorama frames do not overlap")
        }

        val output = Bitmap.createBitmap(
            cropRight - cropLeft,
            cropBottom - cropTop,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val translated = fullPlacements.map { FramePlacement(it.x - cropLeft, it.y - cropTop) }
        var occupiedLeft = 0
        var occupiedTop = 0
        var occupiedRight = 0
        var occupiedBottom = 0
        var previousSharpness = frames.first().sharpness
        frames.forEachIndexed { index, stored ->
            val bitmap = if (index == 0) firstFull else projected(stored)
            try {
                val placement = translated[index]
                if (index == 0) {
                    canvas.drawBitmap(bitmap, placement.x.toFloat(), placement.y.toFloat(), null)
                    occupiedLeft = placement.x
                    occupiedTop = placement.y
                    occupiedRight = placement.x + bitmap.width
                    occupiedBottom = placement.y + bitmap.height
                } else {
                    drawFeatheredFrame(
                        output = output,
                        canvas = canvas,
                        bitmap = bitmap,
                        placement = placement,
                        direction = direction,
                        occupiedLeft = occupiedLeft,
                        occupiedTop = occupiedTop,
                        occupiedRight = occupiedRight,
                        occupiedBottom = occupiedBottom,
                        incomingSharpness = stored.sharpness,
                        existingSharpness = previousSharpness,
                    )
                    occupiedLeft = minOf(occupiedLeft, placement.x)
                    occupiedTop = minOf(occupiedTop, placement.y)
                    occupiedRight = maxOf(occupiedRight, placement.x + bitmap.width)
                    occupiedBottom = maxOf(occupiedBottom, placement.y + bitmap.height)
                }
                previousSharpness = stored.sharpness
            } finally {
                bitmap.recycle()
            }
            onProgress?.invoke(42 + (index + 1) * 48 / frames.size)
        }
        check(projectionHasOpaqueCoverage(output)) {
            "Panorama compositor left unmapped pixels"
        }
        output.setHasAlpha(false)

        val requestedCross = targetCrossPixels?.coerceAtLeast(1)
        val currentCross = if (horizontal) output.height else output.width
        val finished = if (requestedCross == null || requestedCross == currentCross) {
            output
        } else {
            val scale = requestedCross.toFloat() / currentCross
            val scaledWidth = (output.width * scale).roundToInt().coerceAtLeast(1)
            val scaledHeight = (output.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(output, scaledWidth, scaledHeight, true).also { scaled ->
                if (scaled !== output) output.recycle()
            }
        }
        // Apply the profile once, after registration and feathering. Registration therefore sees
        // the camera-neutral keyframes while the review/save result matches the live profile.
        applyPanoramaProfileInPlace(finished, dynamicRangeProfile)
        if (dynamicRangeProfile == PanoramaDynamicRangeProfile.Hdr) {
            enhanceLocalToneAndDetail(finished)
        }
        onProgress?.invoke(95)
        return finished
    }

    /**
     * Raw RGBA keyframes avoid doing a multi-megapixel JPEG encode while the diver is panning.
     * The packed sensor buffer is mapped directly into a Bitmap only during the bounded-memory
     * stitch pass, then normalized to the safe ultrawide working size.
     */
    private fun decodeStoredFrame(frame: StoredPanoramaFrame): Bitmap {
        if (frame.rawWidth <= 0 || frame.rawHeight <= 0) {
            return BitmapFactory.decodeFile(
                frame.file.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ) ?: error("Could not decode panorama keyframe ${frame.file.name}")
        }
        val expectedBytes = frame.rawWidth.toLong() * frame.rawHeight.toLong() * 4L
        require(frame.file.length() == expectedBytes) {
            "Incomplete panorama keyframe ${frame.file.name}"
        }
        var decoded = Bitmap.createBitmap(frame.rawWidth, frame.rawHeight, Bitmap.Config.ARGB_8888)
        FileInputStream(frame.file).channel.use { channel ->
            val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, expectedBytes)
                .order(ByteOrder.nativeOrder())
            decoded.copyPixelsFromBuffer(mapped)
        }
        // CameraX analysis frames are always opaque. Keeping an incidental alpha channel here
        // lets a device-specific RGBA/Bitmap packing quirk turn valid image pixels into transparent
        // holes; JPEG then flattens those holes to the black bands seen in saved panoramas.
        decoded.setHasAlpha(false)
        if (frame.rotationDegrees != 0) {
            val rotated = Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) },
                true,
            )
            if (rotated !== decoded) decoded.recycle()
            decoded = rotated
        }
        decoded = orientPanoramaBitmapForPhysicalRotation(
            decoded,
            frame.physicalDisplayRotation,
        )
        val scale = if (frame.maximumWidth > 0 && frame.maximumHeight > 0) {
            minOf(
                1f,
                maxOf(frame.maximumWidth, frame.maximumHeight).toFloat() / maxOf(decoded.width, decoded.height),
                minOf(frame.maximumWidth, frame.maximumHeight).toFloat() / minOf(decoded.width, decoded.height),
            )
        } else {
            1f
        }
        if (scale >= 1f) {
            decoded.setHasAlpha(false)
            return decoded
        }
        val normalized = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (normalized !== decoded) decoded.recycle()
        normalized.setHasAlpha(false)
        return normalized
    }

    private fun stitchProjected(
        frames: List<CapturedPanoramaFrame>,
        direction: String,
        projectionFovRadians: Float,
        onProgress: ((Int) -> Unit)?,
    ): Bitmap {
        val first = frames.first().bitmap
        val horizontal = direction == "Left" || direction == "Right"
        val maximumGlobalCrossDrift = (
            (if (horizontal) first.height else first.width) * 0.15f
            ).roundToInt().coerceAtLeast(1)
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
            val nextX = previousPlacement.x + offset.x
            val nextY = previousPlacement.y + offset.y
            placements += if (horizontal) {
                FramePlacement(
                    nextX,
                    nextY.coerceIn(-maximumGlobalCrossDrift, maximumGlobalCrossDrift),
                )
            } else {
                FramePlacement(
                    nextX.coerceIn(-maximumGlobalCrossDrift, maximumGlobalCrossDrift),
                    nextY,
                )
            }
            onProgress?.invoke(35 + index * 30 / (frames.size - 1))
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
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
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
            onProgress?.invoke(65 + index * 30 / (frames.size - 1))
        }
        check(projectionHasOpaqueCoverage(output)) {
            "Panorama compositor left unmapped pixels"
        }
        output.setHasAlpha(false)
        onProgress?.invoke(95)
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
        val sourceCenterX = (source.width - 1) / 2.0
        val sourceCenterY = (source.height - 1) / 2.0
        val outputCenterX = (outputSize.width - 1) / 2.0
        val outputCenterY = (outputSize.height - 1) / 2.0
        val horizontalFov = horizontalFovRadians.coerceIn(0.61086524f, 2.0943952f).toDouble()
        val focalPixels = source.width / (2.0 * tan(horizontalFov / 2.0))
        val verticalFov = 2.0 * atan(source.height / (2.0 * focalPixels))
        // drawBitmapMesh performs the cylindrical mapping in Android's native graphics pipeline.
        // Keep the mesh below the vertex/index sizes that have produced incomplete rasterization
        // on Samsung builds; the projection is smooth enough that 32x24 remains sub-pixel accurate
        // at the final panorama scale.
        val meshWidth = 32
        val meshHeight = 24
        val vertices = FloatArray((meshWidth + 1) * (meshHeight + 1) * 2)
        var vertexIndex = 0
        for (meshY in 0..meshHeight) {
            val sourceY = source.height * meshY.toDouble() / meshHeight
            for (meshX in 0..meshWidth) {
                val sourceX = source.width * meshX.toDouble() / meshWidth
                val destinationX: Double
                val destinationY: Double
                if (horizontal) {
                    val theta = atan((sourceX - sourceCenterX) / focalPixels)
                    destinationX = outputCenterX + theta * outputSize.width / horizontalFov
                    destinationY = outputCenterY + (sourceY - sourceCenterY) * cos(theta)
                } else {
                    val theta = atan((sourceY - sourceCenterY) / focalPixels)
                    destinationX = outputCenterX + (sourceX - sourceCenterX) * cos(theta)
                    destinationY = outputCenterY + theta * outputSize.height / verticalFov
                }
                vertices[vertexIndex++] = destinationX.toFloat()
                vertices[vertexIndex++] = destinationY.toFloat()
            }
        }
        val meshOutput = Bitmap.createBitmap(
            outputSize.width,
            outputSize.height,
            Bitmap.Config.ARGB_8888,
        ).also { output ->
            Canvas(output).apply {
                drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawBitmapMesh(
                source,
                meshWidth,
                meshHeight,
                vertices,
                0,
                null,
                0,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
                )
            }
        }
        if (projectionHasOpaqueCoverage(meshOutput)) {
            meshOutput.setHasAlpha(false)
            return meshOutput
        }

        // A partially rasterized mesh must never reach the compositor: its transparent cells are
        // flattened into the characteristic black strips when the final JPEG is encoded. The
        // bounded-memory inverse mapper is slower, but deterministic and is used only when the
        // platform mesh failed its coverage check.
        meshOutput.recycle()
        Log.w(
            TAG,
            "Incomplete Android bitmap mesh; using deterministic inverse cylindrical warp",
        )
        return inverseCylindricalWarp(
            source = source,
            horizontal = horizontal,
            horizontalFovRadians = horizontalFovRadians,
            outputSize = outputSize,
        )
    }

    /** Samples the inscribed projection; every interior point must be backed by an opaque pixel. */
    private fun projectionHasOpaqueCoverage(bitmap: Bitmap): Boolean {
        if (bitmap.width < 3 || bitmap.height < 3) return false
        val xStep = (bitmap.width / 48).coerceAtLeast(1)
        val yStep = (bitmap.height / 32).coerceAtLeast(1)
        var y = 1
        while (y < bitmap.height - 1) {
            var x = 1
            while (x < bitmap.width - 1) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 250) return false
                x += xStep
            }
            y += yStep
        }
        return true
    }

    /**
     * Reference-quality inverse projection used when Android's mesh renderer leaves holes. Rows
     * are written directly to the destination bitmap, avoiding a second panorama-sized output
     * array while retaining bilinear sampling.
     */
    private fun inverseCylindricalWarp(
        source: Bitmap,
        horizontal: Boolean,
        horizontalFovRadians: Float,
        outputSize: PanoramaProjectionSize,
    ): Bitmap {
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val output = Bitmap.createBitmap(
            outputSize.width,
            outputSize.height,
            Bitmap.Config.ARGB_8888,
        )
        val row = IntArray(outputSize.width)
        val sourceCenterX = (source.width - 1) / 2.0
        val sourceCenterY = (source.height - 1) / 2.0
        val outputCenterX = (outputSize.width - 1) / 2.0
        val outputCenterY = (outputSize.height - 1) / 2.0
        val horizontalFov = horizontalFovRadians.coerceIn(0.61086524f, 2.0943952f).toDouble()
        val focalPixels = source.width / (2.0 * tan(horizontalFov / 2.0))
        val verticalFov = 2.0 * atan(source.height / (2.0 * focalPixels))
        val mappedMain = DoubleArray(if (horizontal) outputSize.width else outputSize.height)
        val inverseCosine = DoubleArray(mappedMain.size)
        for (index in mappedMain.indices) {
            val centre = if (horizontal) outputCenterX else outputCenterY
            val extent = if (horizontal) outputSize.width else outputSize.height
            val fov = if (horizontal) horizontalFov else verticalFov
            val theta = (index - centre) * fov / extent
            inverseCosine[index] = 1.0 / cos(theta)
            mappedMain[index] = if (horizontal) {
                sourceCenterX + focalPixels * tan(theta)
            } else {
                sourceCenterY + focalPixels * tan(theta)
            }
        }

        for (y in 0 until outputSize.height) {
            for (x in 0 until outputSize.width) {
                val sampleX: Double
                val sampleY: Double
                if (horizontal) {
                    sampleX = mappedMain[x]
                    sampleY = sourceCenterY + (y - outputCenterY) * inverseCosine[x]
                } else {
                    sampleX = sourceCenterX + (x - outputCenterX) * inverseCosine[y]
                    sampleY = mappedMain[y]
                }
                row[x] = bilinearOpaquePixel(
                    pixels = sourcePixels,
                    width = source.width,
                    height = source.height,
                    x = sampleX,
                    y = sampleY,
                )
            }
            output.setPixels(row, 0, outputSize.width, 0, y, outputSize.width, 1)
        }
        output.setHasAlpha(false)
        return output
    }

    private fun bilinearOpaquePixel(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ): Int {
        val x0 = kotlin.math.floor(x).toInt().coerceIn(0, width - 1)
        val y0 = kotlin.math.floor(y).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - x0).coerceIn(0.0, 1.0)
        val fy = (y - y0).coerceIn(0.0, 1.0)
        val topLeft = pixels[y0 * width + x0]
        val topRight = pixels[y0 * width + x1]
        val bottomLeft = pixels[y1 * width + x0]
        val bottomRight = pixels[y1 * width + x1]

        fun channel(shift: Int): Int {
            val top = ((topLeft ushr shift) and 0xff) * (1.0 - fx) +
                ((topRight ushr shift) and 0xff) * fx
            val bottom = ((bottomLeft ushr shift) and 0xff) * (1.0 - fx) +
                ((bottomRight ushr shift) and 0xff) * fx
            return (top * (1.0 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }
        return Color.argb(255, channel(16), channel(8), channel(0))
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
        incomingSharpness: Double = 1.0,
        existingSharpness: Double = 1.0,
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
        val exposureGamma = estimateOverlapExposureGamma(
            output = output,
            bitmap = bitmap,
            placement = placement,
            occupiedLeft = occupiedLeft,
            occupiedTop = occupiedTop,
            occupiedRight = occupiedRight,
            occupiedBottom = occupiedBottom,
        )
        applyGammaInPlace(bitmap, exposureGamma)
        val sharpnessBias = panoramaSharpnessRetentionBias(
            incomingSharpness = incomingSharpness,
            existingSharpness = existingSharpness,
        )
        val preferredSeam = when (direction) {
            "Right", "Down" -> -sharpnessBias
            else -> sharpnessBias
        }
        val edge = findLowestDifferenceSeam(
            output = output,
            bitmap = bitmap,
            placement = placement,
            horizontal = horizontal,
            occupiedLeft = occupiedLeft,
            occupiedTop = occupiedTop,
            occupiedRight = occupiedRight,
            occupiedBottom = occupiedBottom,
            preferredSeamBias = preferredSeam,
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

    /** Robustly matches the middle tones of two overlaps while ignoring clipped pixels. */
    private fun estimateOverlapExposureGamma(
        output: Bitmap,
        bitmap: Bitmap,
        placement: FramePlacement,
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
        if (overlapRight - overlapLeft < 12 || overlapBottom - overlapTop < 12) return 1f

        val existingHistogram = IntArray(256)
        val incomingHistogram = IntArray(256)
        val step = (minOf(overlapRight - overlapLeft, overlapBottom - overlapTop) / 96)
            .coerceAtLeast(2)
        var samples = 0
        var y = overlapTop
        while (y < overlapBottom) {
            var x = overlapLeft
            while (x < overlapRight) {
                val existing = output.getPixel(x, y)
                val incoming = bitmap.getPixel(x - placement.x, y - placement.y)
                val existingLuma = pixelLuma(existing)
                val incomingLuma = pixelLuma(incoming)
                // Black canvas and either end of the transfer curve carry no exposure signal.
                if (existingLuma in 9..246 && incomingLuma in 9..246) {
                    existingHistogram[existingLuma]++
                    incomingHistogram[incomingLuma]++
                    samples++
                }
                x += step
            }
            y += step
        }
        if (samples < 48) return 1f
        return panoramaExposureMatchGamma(
            incomingLuma = histogramMedian(incomingHistogram, samples),
            referenceLuma = histogramMedian(existingHistogram, samples),
        )
    }

    private fun histogramMedian(histogram: IntArray, samples: Int): Int {
        val target = (samples + 1) / 2
        var cumulative = 0
        histogram.forEachIndexed { value, count ->
            cumulative += count
            if (cumulative >= target) return value
        }
        return 128
    }

    /** Applies a white-point-preserving exposure curve in bounded strips. */
    private fun applyGammaInPlace(bitmap: Bitmap, gamma: Float) {
        if (abs(gamma - 1f) < 0.025f) return
        val lut = IntArray(256) { value ->
            if (value == 0) 0 else (
                255.0 * (value / 255.0).pow(gamma.toDouble())
                ).roundToInt().coerceIn(0, 255)
        }
        val stripRows = 48.coerceAtMost(bitmap.height)
        val pixels = IntArray(bitmap.width * stripRows)
        var top = 0
        while (top < bitmap.height) {
            val rows = minOf(stripRows, bitmap.height - top)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
            val count = bitmap.width * rows
            for (index in 0 until count) {
                val pixel = pixels[index]
                pixels[index] = Color.argb(
                    Color.alpha(pixel),
                    lut[Color.red(pixel)],
                    lut[Color.green(pixel)],
                    lut[Color.blue(pixel)],
                )
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
            top += rows
        }
    }

    /**
     * Samsung finishes its panorama with spatially varying tone and multi-frame detail. The app
     * cannot call that private algorithm, so this pass performs the equivalent public operation:
     * restrained CLAHE-style local tone mapping followed by an edge-limited unsharp fusion. It is
     * deliberately run after the final downscale, where one output pixel already represents the
     * overlapping source frames and the cost stays bounded.
     */
    private fun enhanceLocalToneAndDetail(bitmap: Bitmap) {
        if (bitmap.width < 3 || bitmap.height < 3) return
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val tilesX = (width / 768).coerceIn(4, 12)
            val tilesY = (height / 512).coerceIn(2, 5)
            val histograms = Array(tilesX * tilesY) { IntArray(256) }
            val sampleCounts = IntArray(histograms.size)
            var y = 0
            while (y < height) {
                val tileY = (y * tilesY / height).coerceAtMost(tilesY - 1)
                var x = 0
                while (x < width) {
                    val tileX = (x * tilesX / width).coerceAtMost(tilesX - 1)
                    val tile = tileY * tilesX + tileX
                    histograms[tile][pixelLuma(pixels[y * width + x])]++
                    sampleCounts[tile]++
                    x += 2
                }
                y += 2
            }

            val toneLuts = Array(histograms.size) { tile ->
                val histogram = histograms[tile]
                val samples = sampleCounts[tile].coerceAtLeast(1)
                val clipLimit = maxOf(6, (samples / 256) * 3)
                var excess = 0
                for (value in histogram.indices) {
                    if (histogram[value] > clipLimit) {
                        excess += histogram[value] - clipLimit
                        histogram[value] = clipLimit
                    }
                }
                val share = excess / 256
                val remainder = excess % 256
                for (value in histogram.indices) {
                    histogram[value] += share + if (value < remainder) 1 else 0
                }
                val lut = IntArray(256)
                var cumulative = 0
                var first = -1
                for (value in histogram.indices) {
                    cumulative += histogram[value]
                    if (first < 0 && cumulative > 0) first = cumulative
                    val denominator = (samples - first).coerceAtLeast(1)
                    lut[value] = ((cumulative - first).coerceAtLeast(0) * 255 / denominator)
                        .coerceIn(0, 255)
                }
                lut
            }

            val x0 = IntArray(width)
            val x1 = IntArray(width)
            val xWeight = IntArray(width)
            for (x in 0 until width) {
                val grid = (x + 0.5) * tilesX / width - 0.5
                val low = kotlin.math.floor(grid).toInt()
                x0[x] = low.coerceIn(0, tilesX - 1)
                x1[x] = (low + 1).coerceIn(0, tilesX - 1)
                xWeight[x] = ((grid - low).coerceIn(0.0, 1.0) * 256).roundToInt()
            }

            for (row in 0 until height) {
                val gridY = (row + 0.5) * tilesY / height - 0.5
                val lowY = kotlin.math.floor(gridY).toInt()
                val tileY0 = lowY.coerceIn(0, tilesY - 1)
                val tileY1 = (lowY + 1).coerceIn(0, tilesY - 1)
                val yWeight = ((gridY - lowY).coerceIn(0.0, 1.0) * 256).roundToInt()
                val rowStart = row * width
                for (column in 0 until width) {
                    val index = rowStart + column
                    val pixel = pixels[index]
                    val luma = pixelLuma(pixel)
                    val wx = xWeight[column]
                    val top = (
                        toneLuts[tileY0 * tilesX + x0[column]][luma] * (256 - wx) +
                            toneLuts[tileY0 * tilesX + x1[column]][luma] * wx + 128
                        ) shr 8
                    val bottom = (
                        toneLuts[tileY1 * tilesX + x0[column]][luma] * (256 - wx) +
                            toneLuts[tileY1 * tilesX + x1[column]][luma] * wx + 128
                        ) shr 8
                    val local = (top * (256 - yWeight) + bottom * yWeight + 128) shr 8
                    // Protect deep black and specular white from CLAHE noise/greying.
                    val strength = when {
                        luma < 10 -> 20
                        luma > 247 -> 18
                        else -> 72
                    }
                    val target = (luma * (256 - strength) + local * strength + 128) shr 8
                    val numerator = target + 8
                    val denominator = luma + 8
                    pixels[index] = Color.argb(
                        Color.alpha(pixel),
                        (Color.red(pixel) * numerator / denominator).coerceIn(0, 255),
                        (Color.green(pixel) * numerator / denominator).coerceIn(0, 255),
                        (Color.blue(pixel) * numerator / denominator).coerceIn(0, 255),
                    )
                }
            }

            // Edge-limited cross-kernel sharpening. Row rings preserve the pre-sharpened source,
            // avoiding a second panorama-sized allocation and preventing directional feedback.
            var up = IntArray(width)
            var centre = IntArray(width)
            var down = IntArray(width)
            val out = IntArray(width)
            System.arraycopy(pixels, 0, centre, 0, width)
            System.arraycopy(centre, 0, up, 0, width)
            System.arraycopy(pixels, minOf(1, height - 1) * width, down, 0, width)
            for (row in 0 until height) {
                for (column in 0 until width) {
                    val pixel = centre[column]
                    val leftLuma = pixelLuma(centre[if (column == 0) 0 else column - 1])
                    val rightLuma = pixelLuma(centre[if (column == width - 1) column else column + 1])
                    val laplacian = 4 * pixelLuma(pixel) - leftLuma - rightLuma -
                        pixelLuma(up[column]) - pixelLuma(down[column])
                    val detail = if (abs(laplacian) < 5) 0 else {
                        (laplacian * 3 / 16).coerceIn(-16, 16)
                    }
                    out[column] = Color.argb(
                        Color.alpha(pixel),
                        (Color.red(pixel) + detail).coerceIn(0, 255),
                        (Color.green(pixel) + detail).coerceIn(0, 255),
                        (Color.blue(pixel) + detail).coerceIn(0, 255),
                    )
                }
                System.arraycopy(out, 0, pixels, row * width, width)
                val reusable = up
                up = centre
                centre = down
                down = reusable
                val nextRow = (row + 2).coerceAtMost(height - 1)
                System.arraycopy(pixels, nextRow * width, down, 0, width)
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (_: OutOfMemoryError) {
            // Geometry and save remain valid on low-memory devices; only the finishing pass skips.
        }
    }

    private fun pixelLuma(pixel: Int): Int = (
        77 * Color.red(pixel) + 150 * Color.green(pixel) + 29 * Color.blue(pixel)
        ) shr 8

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
        preferredSeamBias: Float = 0f,
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
        val preferred = centre + preferredSeamBias.coerceIn(-0.18f, 0.18f) * overlapLength
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
                // The content term still owns the seam, but among similarly invisible joins the
                // motion-fusion preference retains more pixels from the sharper keyframe.
                val centrePenalty = abs(candidate - preferred) / overlapLength * 14.0
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
