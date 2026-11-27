package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.camera.core.ImageProcessor
import androidx.camera.core.imagecapture.RgbaImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class PortraitPhotoLook(val name: String, val level: Int) {
    val strength = level.coerceIn(0, 7) / 7f
    val mono = if (name == "High-key mono" || name == "Low-key mono") strength else 0f
    val gain = when (name) {
        "Studio", "High-key mono" -> 1f + 0.12f * strength
        "Low-key mono" -> 1f - 0.22f * strength
        else -> 1f
    }
    val lift = if (name == "Studio" || name == "High-key mono") 0.045f * strength else 0f
}

/** RGB is the processed background. Alpha is its coverage. Clear pixels keep original subject detail. */
@Suppress("DEPRECATION")
internal class PortraitPhotoRenderer(context: Context) : AutoCloseable {
    private val engine = ImageSegmenter.createFromOptions(context, ImageSegmenter.ImageSegmenterOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_segmenter_landscape.tflite").build())
        .setOutputConfidenceMasks(true).setOutputCategoryMask(false).build())
    private val rsContext = lazy { RenderScript.create(context.applicationContext) }
    private val rs by rsContext
    private val blurScript = lazy { ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)) }
    private val blur by blurScript

    fun layer(source: Bitmap, rotation: Int, look: PortraitPhotoLook, longEdge: Int): Bitmap? {
        if (look.level == 0) return null
        val scale = minOf(1f, longEdge.toFloat() / maxOf(source.width, source.height))
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val work = Bitmap.createScaledBitmap(source, w, h, true)
        try {
            val matte = estimate(work, rotation) ?: return null
            return renderBackground(work, matte, look)
        } finally { if (work !== source) work.recycle() }
    }

    fun estimate(work: Bitmap, rotation: Int): Matte? {
        val clockwise = Math.floorMod(rotation, 360)
        val upright = if (clockwise == 0) work else Bitmap.createBitmap(work, 0, 0, work.width, work.height,
            Matrix().apply { postRotate(clockwise.toFloat()) }, true)
        // MPImage owns and recycles its input. An already model-sized exposure still belongs to the caller.
        val scaled = Bitmap.createScaledBitmap(upright, 256, 144, true)
        val input = if (scaled === work) work.copy(Bitmap.Config.ARGB_8888, false) else scaled
        return try {
            BitmapImageBuilder(input).build().use { image ->
                val masks = engine.segment(image).confidenceMasks().orElse(emptyList())
                try {
                    val mask = masks.lastOrNull() ?: return null
                    val buffer = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    val values = FloatArray(mask.width * mask.height).also(buffer::get)
                    if (values.count { it > 0.75f } < values.size / 180) return null
                    if (clockwise == 0) return Matte(mask.width, mask.height, values)
                    val width = if (clockwise == 180) mask.width else mask.height
                    val height = values.size / width
                    val raw = FloatArray(values.size) { i ->
                        val x = i % width
                        val y = i / width
                        val uprightIndex = when (clockwise) {
                            90 -> x * mask.width + height - 1 - y
                            180 -> values.lastIndex - i
                            else -> (width - 1 - x) * mask.width + y
                        }
                        values[uprightIndex]
                    }
                    Matte(width, height, raw)
                } finally { masks.forEach { it.close() } }
            }
        } finally {
            if (!input.isRecycled) input.recycle()
            if (upright !== work) upright.recycle()
        }
    }

    class Matte(val width: Int, val height: Int, val confidence: FloatArray)

    private fun renderBackground(work: Bitmap, matte: Matte, look: PortraitPhotoLook): Bitmap {
        val w = work.width
        val h = work.height
        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)
        val guide = Bitmap.createScaledBitmap(work, matte.width, matte.height, true)
        val guideRgb = IntArray(matte.confidence.size)
        guide.getPixels(guideRgb, 0, matte.width, 0, 0, matte.width, matte.height)
        if (guide !== work) guide.recycle()
        val alpha = ByteArray(pixels.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val sx = ((x + 0.5f) * matte.width / w - 0.5f).coerceIn(0f, matte.width - 1f)
            val sy = ((y + 0.5f) * matte.height / h - 0.5f).coerceIn(0f, matte.height - 1f)
            val left = floor(sx).toInt()
            val top = floor(sy).toInt()
            var sum = 0f
            var total = 0f
            val color = pixels[i]
            for (dy in 0..1) for (dx in 0..1) {
                val j = (top + dy).coerceAtMost(matte.height - 1) * matte.width + (left + dx).coerceAtMost(matte.width - 1)
                val ref = guideRgb[j]
                val difference = abs(Color.red(ref) - Color.red(color)) + abs(Color.green(ref) - Color.green(color)) +
                    abs(Color.blue(ref) - Color.blue(color))
                val weight = (1f - abs(sx - left - dx)) * (1f - abs(sy - top - dy)) / (16f + difference)
                total += weight
                sum += weight * matte.confidence[j]
            }
            val t = ((sum / total - 0.12f) / 0.76f).coerceIn(0f, 1f)
            val coverage = (255f * (1f - t*t*(3f-2f*t))).roundToInt()
            alpha[i] = coverage.toByte()
            pixels[i] = (coverage shl 24) or (color and 0xffffff)
        }
        val overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        overlay.setPixels(pixels, 0, w, 0, 0, w, h)
        if (look.name == "Blur" || look.name == "Studio") {
            // Blur premultiplied colour and background coverage together. Unpremultiplication on
            // readback keeps bright hair/skin from bleeding onto the background at the silhouette.
            val input = Allocation.createFromBitmap(rs, overlay)
            try {
                val output = Allocation.createTyped(rs, input.type)
                try {
                    blur.setRadius((minOf(w,h) * 0.045f * look.strength).coerceIn(0.1f, 25f))
                    blur.setInput(input)
                    blur.forEach(output)
                    output.copyTo(overlay)
                } finally { output.destroy() }
            } finally { input.destroy() }
            overlay.getPixels(pixels, 0, w, 0, 0, w, h)
        }
        for (i in pixels.indices) {
            val coverage = alpha[i].toInt() and 255
            val c = pixels[i]
            val red = Color.red(c)
            val green = Color.green(c)
            val blue = Color.blue(c)
            val luma = 0.213f * red + 0.715f * green + 0.072f * blue
            val target = when (look.name) {
                "Color point" -> luma
                "High-key mono" -> 196f + luma * 0.23f
                "Low-key mono" -> luma * 0.20f
                "Backdrop" -> 142f - 64f * (i / w).toFloat() / h
                else -> -1f
            }
            pixels[i] = if (target < 0f) (coverage shl 24) or (c and 0xffffff) else {
                fun channel(v: Int) = (v + (target - v) * look.strength).roundToInt()
                Color.argb(coverage, channel(red), channel(green), channel(blue))
            }
        }
        overlay.setPixels(pixels, 0, w, 0, 0, w, h)
        return overlay
    }

    override fun close() {
        engine.close()
        if (blurScript.isInitialized()) blur.destroy()
        if (rsContext.isInitialized()) rs.destroy()
    }
}

/** Each shutter owns its look even if a later exposure or mode switch happens during processing. */
internal class PortraitPhotoCapture(private val context: Context) : ImageProcessor {
    class Shot(val look: PortraitPhotoLook, val rotationOffset: Int)
    private val shots = ConcurrentLinkedQueue<Shot>()
    fun enqueue(look: PortraitPhotoLook, rotationOffset: Int) = Shot(look, rotationOffset).also(shots::add)
    fun finish(shot: Shot) { shots.remove(shot) }

    override fun process(request: ImageProcessor.Request): ImageProcessor.Response {
        val image = request.inputImage
        val shot = shots.poll() ?: return ImageProcessor.Response { image }
        val look = shot.look
        if (look.level == 0) return ImageProcessor.Response { image }
        val bitmap = (image as RgbaImageProxy).createBitmap()
        try {
            val rotation = (image.imageInfo.rotationDegrees + shot.rotationOffset) % 360
            val layer = PortraitPhotoRenderer(context).use { it.layer(bitmap, rotation, look, 768) }
                ?: return ImageProcessor.Response { image }
            try {
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                if (look.mono != 0f || look.gain != 1f || look.lift != 0f) {
                    val matrix = ColorMatrix().apply { setSaturation(1f - look.mono) }
                    val gain = look.gain
                    val bias = look.lift * 255f
                    matrix.postConcat(ColorMatrix(floatArrayOf(gain,0f,0f,0f,bias, 0f,gain,0f,0f,bias,
                        0f,0f,gain,0f,bias, 0f,0f,0f,1f,0f)))
                    // Only a strip of temporary storage, even for a full-resolution JPEG.
                    val strip = Bitmap.createBitmap(bitmap.width, minOf(128, bitmap.height), Bitmap.Config.ARGB_8888)
                    try {
                        paint.colorFilter = ColorMatrixColorFilter(matrix)
                        val stripCanvas = Canvas(strip)
                        val sourceRect = Rect()
                        val localRect = Rect()
                        for (y in 0 until bitmap.height step strip.height) {
                            val rows = minOf(strip.height, bitmap.height - y)
                            sourceRect.set(0, y, bitmap.width, y + rows)
                            localRect.set(0, 0, bitmap.width, rows)
                            stripCanvas.drawBitmap(bitmap, sourceRect, localRect, null)
                            canvas.drawBitmap(strip, localRect, sourceRect, paint)
                        }
                    } finally { strip.recycle() }
                    paint.colorFilter = null
                }
                canvas.drawBitmap(layer, null, Rect(0, 0, bitmap.width, bitmap.height), paint)
                val output = RgbaImageProxy(bitmap, image.cropRect, image.imageInfo.rotationDegrees,
                    image.imageInfo.sensorToBufferTransformMatrix, image.imageInfo.timestamp)
                image.close()
                return ImageProcessor.Response { output }
            } finally { layer.recycle() }
        } finally { bitmap.recycle() }
    }
}
