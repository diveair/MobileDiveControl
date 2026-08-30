package com.mobiledivecontrol.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class ExpertRawBlendMode {
    Add,
    Average,
    Bright,
    Dark,
}

internal data class AstroCapturePlan(
    val frameCount: Int,
    val intervalMillis: Long,
)

/** Shared, read-only-to-Compose progress for long Expert RAW operations. */
internal object ExpertRawCaptureState {
    val active = mutableStateOf(false)
    val progress = mutableStateOf(0f)
    val message = mutableStateOf("")
    val skyAzimuthDegrees = mutableStateOf(0f)
    val skyAltitudeDegrees = mutableStateOf(35f)
    val observerLatitudeDegrees = mutableStateOf<Double?>(null)
    val observerLongitudeDegrees = mutableStateOf<Double?>(null)

    fun begin(label: String) {
        active.value = true
        progress.value = 0f
        message.value = label
    }

    fun update(label: String, fraction: Double) {
        message.value = label
        progress.value = fraction.coerceIn(0.0, 1.0).toFloat()
    }

    fun finish(label: String) {
        active.value = false
        progress.value = 1f
        message.value = label
    }

    fun reset() {
        active.value = false
        progress.value = 0f
        message.value = ""
    }
}

internal fun virtualApertureFNumber(value: String?): Double = value
    ?.removePrefix("F")
    ?.toDoubleOrNull()
    ?.coerceIn(1.4, 16.0)
    ?: 16.0

internal fun virtualApertureStrength(value: String?): Double =
    ((16.0 - virtualApertureFNumber(value)) / 14.6).coerceIn(0.0, 1.0)

internal fun ndCaptureFrameCount(value: String?): Int = when (value) {
    "2 stops" -> 4
    "4 stops" -> 8
    "6 stops" -> 12
    "8 stops" -> 16
    "10 stops" -> 24
    else -> 1
}

internal fun astroCapturePlan(value: String?): AstroCapturePlan? {
    val minutes = value?.removeSuffix(" min")?.toIntOrNull()?.takeIf { it in setOf(4, 7, 10) }
        ?: return null
    // Sample throughout the full selected duration. Longer modes collect more independent
    // noise samples without retaining hundreds of full-resolution frames in storage.
    val frames = when (minutes) {
        4 -> 16
        7 -> 21
        else -> 30
    }
    return AstroCapturePlan(
        frameCount = frames,
        // The first frame is captured at t=0, so there are frameCount - 1 intervals.
        intervalMillis = minutes * 60_000L / (frames - 1),
    )
}

internal fun multiExposureBlendMode(value: String?): ExpertRawBlendMode = when (value) {
    "Add" -> ExpertRawBlendMode.Add
    "Bright" -> ExpertRawBlendMode.Bright
    "Dark" -> ExpertRawBlendMode.Dark
    else -> ExpertRawBlendMode.Average
}

/**
 * App-owned computational still processing used by Expert RAW Labs controls.
 *
 * CameraX supplies real sensor DNG files separately. These operations deliberately touch only
 * the rendered JPEG companion: a DNG must remain unmodified sensor data. All algorithms operate
 * at the native 12 MP Labs resolution and reuse row buffers so a multi-frame capture cannot
 * accumulate a bitmap per exposure in the app heap.
 */
internal object ExpertRawImageProcessor {
    fun combineJpegs(
        files: List<File>,
        mode: ExpertRawBlendMode,
        onProgress: (Double) -> Unit = {},
    ): Result<Bitmap> = runCatching {
        require(files.isNotEmpty()) { "No JPEG frames were captured" }
        val output = decodeMutable(files.first())
        if (files.size == 1) {
            onProgress(1.0)
            return@runCatching output
        }
        files.drop(1).forEachIndexed { index, file ->
            val decoded = decodeMutable(file)
            val frame = if (decoded.width == output.width && decoded.height == output.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, output.width, output.height, true).also {
                    decoded.recycle()
                }
            }
            blendInto(
                output = output,
                incoming = frame,
                mode = mode,
                completedBeforeIncoming = index + 1,
            )
            frame.recycle()
            onProgress((index + 2).toDouble() / files.size)
        }
        output
    }

    fun applyVirtualAperture(source: Bitmap, value: String?): Bitmap {
        val strength = virtualApertureStrength(value)
        if (strength <= 0.001) return source

        // A bilinear down/up pyramid is a bounded-memory, deterministic Gaussian approximation.
        // The F-number continuously controls its footprint; F16 is a byte-for-byte pass-through.
        val divisor = (2.0 + strength * 18.0).roundToInt().coerceIn(2, 20)
        val small = Bitmap.createScaledBitmap(
            source,
            max(1, source.width / divisor),
            max(1, source.height / divisor),
            true,
        )
        val blurred = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()

        val chunkRows = 32
        val sharpPixels = IntArray(source.width * chunkRows)
        val blurPixels = IntArray(source.width * chunkRows)
        val cx = source.width * 0.5
        val cy = source.height * 0.5
        val minDimension = min(source.width, source.height).toDouble()
        val inner = minDimension * (0.20 + (1.0 - strength) * 0.12)
        val outer = minDimension * 0.58
        var top = 0
        while (top < source.height) {
            val rows = min(chunkRows, source.height - top)
            source.getPixels(sharpPixels, 0, source.width, 0, top, source.width, rows)
            blurred.getPixels(blurPixels, 0, source.width, 0, top, source.width, rows)
            for (row in 0 until rows) {
                val y = top + row
                for (x in 0 until source.width) {
                    val dx = x - cx
                    val dy = (y - cy) * 1.28 // portrait subjects occupy a taller focus island
                    val distance = sqrt(dx * dx + dy * dy)
                    val mask = smoothStep(inner, outer, distance) * strength
                    val i = row * source.width + x
                    sharpPixels[i] = mixArgb(sharpPixels[i], blurPixels[i], mask)
                }
            }
            source.setPixels(sharpPixels, 0, source.width, 0, top, source.width, rows)
            top += rows
        }
        blurred.recycle()
        return source
    }

    fun writeJpeg(bitmap: Bitmap, file: File): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, output)) {
                "JPEG encoder rejected the processed frame"
            }
            output.fd.sync()
        }
    }

    private fun decodeMutable(file: File): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) {
            "Could not decode ${file.name}"
        }
    }

    private fun blendInto(
        output: Bitmap,
        incoming: Bitmap,
        mode: ExpertRawBlendMode,
        completedBeforeIncoming: Int,
    ) {
        val chunkRows = 32
        val a = IntArray(output.width * chunkRows)
        val b = IntArray(output.width * chunkRows)
        var top = 0
        while (top < output.height) {
            val rows = min(chunkRows, output.height - top)
            val count = output.width * rows
            output.getPixels(a, 0, output.width, 0, top, output.width, rows)
            incoming.getPixels(b, 0, output.width, 0, top, output.width, rows)
            for (i in 0 until count) {
                a[i] = blendPixel(a[i], b[i], mode, completedBeforeIncoming)
            }
            output.setPixels(a, 0, output.width, 0, top, output.width, rows)
            top += rows
        }
    }

    private fun blendPixel(
        first: Int,
        second: Int,
        mode: ExpertRawBlendMode,
        completedBeforeIncoming: Int,
    ): Int {
        val ar = first shr 16 and 0xff
        val ag = first shr 8 and 0xff
        val ab = first and 0xff
        val br = second shr 16 and 0xff
        val bg = second shr 8 and 0xff
        val bb = second and 0xff
        fun luma(r: Int, g: Int, b: Int) = r * 54 + g * 183 + b * 19
        val (r, g, b) = when (mode) {
            ExpertRawBlendMode.Add -> Triple(
                min(255, ar + br),
                min(255, ag + bg),
                min(255, ab + bb),
            )
            ExpertRawBlendMode.Average -> {
                val total = completedBeforeIncoming + 1
                Triple(
                    (ar * completedBeforeIncoming + br) / total,
                    (ag * completedBeforeIncoming + bg) / total,
                    (ab * completedBeforeIncoming + bb) / total,
                )
            }
            ExpertRawBlendMode.Bright -> if (luma(br, bg, bb) > luma(ar, ag, ab)) {
                Triple(br, bg, bb)
            } else {
                Triple(ar, ag, ab)
            }
            ExpertRawBlendMode.Dark -> if (luma(br, bg, bb) < luma(ar, ag, ab)) {
                Triple(br, bg, bb)
            } else {
                Triple(ar, ag, ab)
            }
        }
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mixArgb(sharp: Int, blurred: Int, amount: Double): Int {
        val t = amount.coerceIn(0.0, 1.0)
        fun channel(shift: Int): Int {
            val a = sharp shr shift and 0xff
            val b = blurred shr shift and 0xff
            return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
