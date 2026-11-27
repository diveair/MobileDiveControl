package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** One inference in flight. Gesture recognition and camera display never wait for the portrait model. */
internal class PortraitLiveTracker(
    private val context: Context,
    private val preview: PortraitPhotoPreview,
    camera: CameraInfo?,
    displayRotation: Int,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var face: Face? = null
    private val renderer = worker.submit<PortraitPhotoRenderer> { PortraitPhotoRenderer(context) }
    private val history = PortraitMaskHistory()
    @Volatile var rotationOffset = 0
        private set
    // The housing UI is locked in landscape. The person in the scene follows gravity.
    private val orientation = camera?.let { info ->
        val displayDegrees = info.getSensorRotationDegrees(displayRotation)
        object : OrientationEventListener(context.applicationContext) {
            private var quadrant = -1
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                if (quadrant >= 0 && abs((degrees - quadrant*90 + 540) % 360 - 180) < 55) return
                quadrant = ((degrees+45)/90) % 4
                val rotation = when (quadrant) {
                    1 -> Surface.ROTATION_270
                    2 -> Surface.ROTATION_180
                    3 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                rotationOffset = (info.getSensorRotationDegrees(rotation) - displayDegrees + 360) % 360
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }
    private data class Face(val bounds: Rect, val timestampNs: Long)

    fun faces(bounds: Rect?, timestampNs: Long) {
        face = bounds?.let { Face(it, timestampNs) }
    }

    private var lastSubmissionMs = 0L
    private var snapshotPixels = IntArray(0)
    private var sourceWidth = 0
    private var byteOffsets = IntArray(0)

    @Synchronized
    fun submit(image: ImageProxy, reduceLoad: Boolean, onFailure: (Throwable) -> Unit) {
        val time = SystemClock.uptimeMillis()
        if (reduceLoad && time - lastSubmissionMs < 200L) return
        if (closed || !renderer.isDone || preview.look.level == 0 || !busy.compareAndSet(false, true)) return
        try {
            lastSubmissionMs = time
            val width = image.width
            val height = image.height
            val transform = Matrix(image.imageInfo.sensorToBufferTransformMatrix)
            val rotation = (image.imageInfo.rotationDegrees + rotationOffset) % 360
            val timestamp = image.imageInfo.timestamp
            val faceBounds = face?.takeIf { abs(it.timestampNs - timestamp) <= 120_000_000L }?.let {
                RectF(it.bounds).apply {
                    transform.mapRect(this)
                    set(left / width, top / height, right / width, bottom / height)
                }
            }
            val source = snapshot(image)
            worker.execute {
                try {
                    if (!closed) {
                        val matte = renderer.get().estimate(source, rotation)
                        val mask = history.update(matte, faceBounds, time)
                        if (mask != null) synchronized(this) {
                            if (closed) mask.recycle() else preview.publish(
                                PortraitPhotoPreview.Frame(mask, transform, width, height, time))
                        }
                    }
                } catch (error: Exception) {
                    if (!closed) onFailure(error)
                } finally {
                    source.recycle()
                    busy.set(false)
                }
            }
        } catch (error: Exception) {
            busy.set(false)
            onFailure(error)
        }
    }

    /** Read only model-scale RGB samples before CameraX releases the shared RGBA frame. */
    private fun snapshot(image: ImageProxy): Bitmap {
        val scale = minOf(1f, 320f / maxOf(image.width, image.height))
        val w = (image.width * scale).roundToInt().coerceAtLeast(1)
        val h = (image.height * scale).roundToInt().coerceAtLeast(1)
        val plane = image.planes.first()
        check(plane.pixelStride == 4)
        if (snapshotPixels.size != w * h) snapshotPixels = IntArray(w * h)
        if (sourceWidth != image.width || byteOffsets.size != w) {
            sourceWidth = image.width
            byteOffsets = IntArray(w) { (it * sourceWidth / w) * 4 }
        }
        val data = plane.buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        for (y in 0 until h) {
            val row = (y * image.height / h) * plane.rowStride + data.position()
            for (x in 0 until w) {
                snapshotPixels[y*w+x] = (data.getInt(row + byteOffsets[x]) ushr 8) or (255 shl 24)
            }
        }
        return Bitmap.createBitmap(snapshotPixels, w, h, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        orientation?.disable()
        worker.execute { runCatching { renderer.get().close() } }
        worker.shutdown()
    }
}

/** Stabilize uncertain boundaries in sensor-buffer coordinates. Confident changes follow motion promptly. */
internal class PortraitMaskHistory {
    private var previous: FloatArray? = null
    private var previousWidth = 0
    private var previousFace: RectF? = null
    private var previousTime = 0L

    fun update(matte: PortraitPhotoRenderer.Matte?, face: RectF?, time: Long): Bitmap? {
        if (matte == null) return null
        val w = matte.width
        val h = matte.height
        val now = matte.confidence
        val elapsed = time - previousTime
        val old = previous?.takeIf { previousWidth == w && it.size == now.size && elapsed in 1..240 }
        val from = previousFace
        val dx = if (from != null && face != null) ((face.centerX()-from.centerX())*w).roundToInt() else 0
        val dy = if (from != null && face != null) ((face.centerY()-from.centerY())*h).roundToInt() else 0
        val canAlign = old != null && abs(dx) < w/6 && abs(dy) < h/6
        val alpha = ByteArray(now.size)
        val blend = 1f - kotlin.math.exp(-elapsed.coerceIn(1, 240) / 80f)
        for (i in now.indices) {
            var p = now[i]
            val x = i % w - dx
            val y = i / w - dy
            if (canAlign && x in 0 until w && y in 0 until h) {
                val past = old!![y*w + x]
                val follow = if (abs(p-past) > 0.35f) maxOf(blend, 0.82f) else blend
                p = past + (p-past)*follow
            }
            now[i] = p
            val t = ((p-0.12f)/0.76f).coerceIn(0f,1f)
            alpha[i] = (255f*(1f-t*t*(3f-2f*t))).roundToInt().toByte()
        }
        previous = now
        previousWidth = w
        previousFace = face
        previousTime = time
        return Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
        }
    }
}
