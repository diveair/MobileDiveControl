package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.ImageReader
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.CameraInternal
import androidx.core.util.Consumer
import java.lang.reflect.Proxy
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Drives the same OES input, mask tracker, and EGL output as the live preview with repeatable motion. */
internal fun verifyMovingPortrait(context: Context, source: Bitmap, report: Bundle) {
    val size = Size(source.width, source.height)
    val shader = PortraitPhotoPreview().apply { look = PortraitPhotoLook("Blur", 4) }
    val processor = FocusPeakingSurfaceProcessor(Executor { it.run() }, shader)
    val binding = processor.newBinding(1)
    val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 3)
    val camera = Proxy.newProxyInstance(CameraInternal::class.java.classLoader, arrayOf(CameraInternal::class.java)) {
        _, method, _ -> when (method.name) {
            "toString" -> "Portrait verification camera"
            "hashCode" -> 1
            else -> null
        }
    } as CameraInternal
    val request = SurfaceRequest(size, camera) {}
    val geometry = Matrix()
    try {
        binding.onOutputSurface(object : SurfaceOutput {
            override fun getSurface(executor: Executor, listener: Consumer<SurfaceOutput.Event>): Surface = reader.surface
            override fun getTargets() = CameraEffect.PREVIEW
            override fun getSize() = size
            override fun getSensorToBufferTransform() = geometry
            override fun updateTransformMatrix(updated: FloatArray, original: FloatArray) { original.copyInto(updated) }
            override fun close() {}
        })
        binding.onInputSurface(request)
        val input = request.deferrableSurface.surface.get(5, TimeUnit.SECONDS)
        fun draw(offset: Int, readPixels: Boolean = true): IntArray {
            val canvas = input.lockCanvas(null)
            try {
                canvas.drawColor(Color.rgb(24,46,80))
                canvas.drawBitmap(source,null,Rect(offset,0,size.width+offset,size.height),null)
            } finally { input.unlockCanvasAndPost(canvas) }
            val deadline = SystemClock.elapsedRealtime() + 4000
            var image = reader.acquireLatestImage()
            while (image == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(4)
                image = reader.acquireLatestImage()
            }
            return checkNotNull(image) { "Portrait GL output stalled" }.use { frame ->
                if (!readPixels) return@use IntArray(0)
                val plane = frame.planes[0]
                check(plane.pixelStride == 4)
                // RGBA and native-order ARGB store green in the same byte on Android ARM.
                val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
                IntArray(size.width * size.height).also { pixels ->
                    for (row in 0 until size.height) {
                        buffer.position(row * plane.rowStride / 4)
                        buffer.get(pixels, row*size.width, size.width)
                    }
                }
            }
        }
        val original = draw(0)
        val history = PortraitMaskHistory()
        var maxFaceDifference = 0.0
        var maxDetailRatio = 0.0
        val details = mutableListOf<String>()
        var frames = 0
        PortraitPhotoRenderer(context).use { model ->
            // Exercise first model initialization separately so a cold accelerator is not timed as a camera exposure.
            checkNotNull(model.estimate(source,0))
            for (step in 0..11) {
                val offset = step*3
                val moving = Bitmap.createBitmap(size.width,size.height,Bitmap.Config.ARGB_8888)
                try {
                    android.graphics.Canvas(moving).drawBitmap(source,null,Rect(offset,0,size.width+offset,size.height),null)
                    val time = SystemClock.uptimeMillis()
                    val face = RectF((150f+offset)/size.width,0.20f,(375f+offset)/size.width,0.61f)
                    val person = if (step == 6) null else model.estimate(moving,0)
                    val mask = history.update(person,face,time)
                    if (mask != null) shader.publish(PortraitPhotoPreview.Frame(mask,geometry,size.width,size.height,time))
                    if (step == 0) { draw(offset); SystemClock.sleep(100) }
                    val output = draw(offset)
                    var faceDifference = 0.0
                    var count = 0
                    for (y in 180..260 step 2) for (x in 195..310 step 2) {
                        val pixel = y*size.width+x
                        faceDifference += abs(Color.green(original[pixel])-Color.green(output[pixel+offset]))
                        count++
                    }
                    maxFaceDifference = maxOf(maxFaceDifference,faceDifference/count)
                    var inputDetail = 0.0
                    var outputDetail = 0.0
                    for (y in 35..180) for (x in 30..95) {
                        val pixel = y*size.width+x
                        inputDetail += abs(Color.green(original[pixel])-Color.green(original[pixel+1]))
                        outputDetail += abs(Color.green(output[pixel+offset])-Color.green(output[pixel+offset+1]))
                    }
                    maxDetailRatio = maxOf(maxDetailRatio,outputDetail/inputDetail)
                    details += "%.3f/%dms".format(java.util.Locale.US,outputDetail/inputDetail,SystemClock.uptimeMillis()-time)
                    frames++
                } finally { moving.recycle() }
            }
            // Run at camera cadence with independent mask updates, as in the actual preview.
            val mask = checkNotNull(PortraitMaskHistory().update(model.estimate(source,0),null,SystemClock.uptimeMillis()))
            try {
                val intervals = mutableListOf<Long>()
                var previous = SystemClock.elapsedRealtimeNanos()
                val start = previous
                for (i in 0 until 48) {
                    val due = start + i * 33_333_333L
                    val remaining = (due-SystemClock.elapsedRealtimeNanos())/1_000_000
                    if (remaining > 0) SystemClock.sleep(remaining)
                    if (i % 3 == 0) shader.publish(PortraitPhotoPreview.Frame(
                        mask.copy(Bitmap.Config.ALPHA_8,false),geometry,size.width,size.height,SystemClock.uptimeMillis()))
                    draw(0,readPixels=false)
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (i > 0) intervals += now-previous
                    previous = now
                }
                val fps = 1_000_000_000.0 / intervals.average()
                val p95 = intervals.sorted()[(intervals.size*0.95).toInt()]/1_000_000.0
                report.putDouble("activeBlurFramesPerSecond",fps)
                report.putDouble("activeBlurP95ms",p95)
                check(fps > 23 && p95 < 80) { "Uneven preview output: $fps fps, p95 $p95 ms" }
            } finally { mask.recycle() }
        }
        report.putDouble("previewWorstFaceChange",maxFaceDifference)
        report.putDouble("previewWorstBackgroundDetailRatio",maxDetailRatio)
        report.putInt("movingPreviewFrames",frames)
        report.putString("previewDetailByFrame",details.joinToString())
        check(maxFaceDifference < 5.0 && maxDetailRatio < 0.65 && maxDetailRatio > 0.01) {
            "Moving preview lost the face or dropped blur: face=$maxFaceDifference background=$maxDetailRatio"
        }
    } finally {
        request.deferrableSurface.close()
        processor.release()
        reader.close()
    }
}
