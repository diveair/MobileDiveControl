package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Point
import android.media.Image
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.samsung.android.panorama.PanoCallbackInterface
import com.samsung.android.panorama.ResultParam
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import kotlin.math.atan
import kotlin.math.tan

/**
 * Uses the panorama implementation in the installed Samsung Camera APK, including its native
 * frame selector, gyro integration, stitcher, scan mosaic and JPEG encoder. No Samsung binaries
 * are bundled. The public Interface accepts direct NV21 buffers; PanoramaNode's Image wrapper
 * requires private ImageReader fields that Android denies to third-party applications.
 */
internal class SamsungPanoramaEngine private constructor(
    private val api: Any,
    private val apiClass: Class<*>,
    private val listener: Listener,
    val sourceApk: String,
) {
    interface Listener {
        fun onUiImage(bitmap: Bitmap, direction: Int)
        fun onDirectionChanged(direction: Int)
        fun onRectChanged(point: Point)
        fun onFrameAccepted()
        fun onStopRequested()
        fun onWarning(code: Int)
        fun onError(code: Int)
        fun onResult(result: Result)
    }

    data class Result(
        val jpeg: ByteArray,
        val imageSize: Size,
        val croppedSize: Size,
        val fullPanoramaWidth: Int,
        val orientation: Int,
        val vertical: Boolean,
    )

    private fun parameter(name: String) =
        Class.forName("com.samsung.android.panorama.$name", true, apiClass.classLoader)

    private val captureClass = parameter("CaptureParam")
    private val selectClass = parameter("SelectFrames")
    private val addClass = parameter("AddImage")
    private val uiClass = parameter("UpdateUIImage")
    private val selectConstructor = selectClass.getConstructor(
        ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    )
    private val selectMethod = apiClass.getMethod("selectFrames", selectClass)
    private val selectedField = selectClass.getField("select")
    private val directionField = selectClass.getField("direction")
    private val pointXField = selectClass.getField("point_x")
    private val pointYField = selectClass.getField("point_y")
    private val addConstructor = addClass.getConstructor()
    private val addMethod = apiClass.getMethod("addImage", addClass)
    private val stitchProgressField = addClass.getField("stitchProgress")
    private val uiConstructor = uiClass.getConstructor(ByteBuffer::class.java)
    private val uiMethod = apiClass.getMethod("updateUIImage", uiClass)
    private val uiWidthField = uiClass.getField("UIWidth")
    private val uiHeightField = uiClass.getField("UIHeight")
    private val processLock = Any()
    private var frameBuffer: ByteBuffer? = null
    private val yuvScratch = arrayOf(
        ByteBuffer.allocateDirect(INPUT_WIDTH * INPUT_HEIGHT),
        ByteBuffer.allocateDirect(INPUT_WIDTH * INPUT_HEIGHT / 4),
        ByteBuffer.allocateDirect(INPUT_WIDTH * INPUT_HEIGHT / 4),
    )
    // CameraX already bundles libyuv. Its converter handles the actual plane layout in native
    // code, including planar YUV, NV12 and NV21, without a Java loop over millions of samples.
    private val repackYuv = Class.forName("androidx.camera.core.ImageProcessingUtil")
        .declaredMethods.single { it.name == "nativeRotateYUV" && it.parameterTypes.size == 22 }
        .apply { isAccessible = true }
    private val uiBuffer = ByteBuffer.allocateDirect(2_000_000)
    @Volatile private var capturing = false
    private var released = false
    private var direction = 0
    private var selectedFrames = 0
    private var submittedFrames = 0
    private var startedAtMs = 0L
    private var stopAtMs = 0L

    fun start() = synchronized(processLock) {
        check(!released && !capturing)
        val capture = captureClass.getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        // This is the OUTPUT format, not the YUV input format: Samsung's JPEG mode is 0.
        // Mode 1 requests rawImageOutput instead of native JPEG encoding.
        ).newInstance(90, 0, false, 0)
        direction = 0
        selectedFrames = 0
        submittedFrames = 0
        stopAtMs = 0L
        startedAtMs = SystemClock.elapsedRealtime()
        check(apiClass.getMethod("capture", captureClass).invoke(api, capture) == 0) {
            "Samsung panorama capture initialization failed"
        }
        capturing = true
    }

    /** Selection and stitching consume the buffer before this returns, then CameraX closes Image. */
    fun process(image: Image): Boolean {
        if (!capturing) return false
        return synchronized(processLock) {
            if (!capturing) return@synchronized false
            try {
                require(image.format == ImageFormat.YUV_420_888 &&
                    image.width == INPUT_WIDTH && image.height == INPUT_HEIGHT) {
                    "Samsung panorama requires 4000x3000 YUV input, got " +
                        "${image.width}x${image.height} format=${image.format}"
                }
                val stride = image.planes[0].rowStride
                val elevation = (image.height + 15) and -16
                val packed = packNv21(image, stride, elevation)
                val selection = selectConstructor.newInstance(packed, stride, elevation)
                val status = selectMethod.invoke(api, selection) as Int
                submittedFrames++
                if (submittedFrames == 1 || submittedFrames % 60 == 0) {
                    Log.i(TAG, "selectFrames=$submittedFrames accepted=$selectedFrames " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                        "stride=$stride elevation=$elevation status=$status")
                }
                listener.onWarning(if (status and 0x8080 == 0x8080) 2 else 0)
                // Samsung's selector can reject a frame (including reverse/unstable movement)
                // without ending the capture. Only selected frames enter the stitcher.
                if ((selectedField.get(selection) as Number).toLong() == 0L) return@synchronized true
                direction = when (directionField.getInt(selection)) {
                    1 -> DIRECTION_LEFT_TO_RIGHT
                    2 -> DIRECTION_RIGHT_TO_LEFT
                    3 -> DIRECTION_TOP_TO_BOTTOM
                    4 -> DIRECTION_BOTTOM_TO_TOP
                    else -> direction
                }
                listener.onRectChanged(Point(pointXField.getInt(selection), pointYField.getInt(selection)))
                val addition = addConstructor.newInstance()
                val addStatus = addMethod.invoke(api, addition) as Int
                check(addStatus == 0) { "Samsung panorama addImage returned $addStatus" }
                selectedFrames++
                listener.onFrameAccepted()
                if (selectedFrames == 2) listener.onDirectionChanged(direction)
                if ((stitchProgressField.get(addition) as Number).toInt() >= 95) {
                    capturing = false
                    listener.onStopRequested()
                } else if (selectedFrames >= 2) {
                    uiBuffer.clear()
                    val ui = uiConstructor.newInstance(uiBuffer)
                    val uiStatus = uiMethod.invoke(api, ui) as Int
                    if (uiStatus == 0) {
                        decodeNv21(uiBuffer, uiWidthField.getInt(ui), uiHeightField.getInt(ui))
                            ?.let { listener.onUiImage(it, direction) }
                    }
                }
                true
            } catch (error: Throwable) {
                capturing = false
                Log.e(TAG, "Samsung panorama frame submission failed", unwrap(error))
                listener.onError(-1)
                // The Samsung path owns this capture even when a submission fails.
                true
            }
        }
    }

    fun stop(): Boolean = synchronized(processLock) {
        capturing = false
        if (selectedFrames == 0) {
            cancel()
            return@synchronized false
        }
        stopAtMs = SystemClock.elapsedRealtime()
        val status = apiClass.getMethod("stop").invoke(api)
        check(status == 0) { "Samsung panorama stop returned $status" }
        true
    }

    fun cancel() = synchronized(processLock) {
        if (released) return@synchronized
        capturing = false
        runCatching { apiClass.getMethod("cancel").invoke(api) }
            .onFailure { Log.w(TAG, "Samsung panorama cancel failed", unwrap(it)) }
        Unit
    }

    fun release() = synchronized(processLock) {
        if (released) return@synchronized
        cancel()
        runCatching { apiClass.getMethod("deinit").invoke(api) }
            .onFailure { Log.w(TAG, "Samsung panorama deinit failed", unwrap(it)) }
        released = true
        frameBuffer = null
    }

    private fun callback(methodName: String, args: Array<out Any?>?): Any? {
        if (methodName == "onResult") {
            val result = args?.getOrNull(0) ?: return null
            val type = result.javaClass
            fun number(name: String) = (type.getField(name).get(result) as Number).toInt()
            val size = number("size")
            val source = type.getField("resultBuffer").get(result) as? ByteBuffer
            if (number("format") != 0 || size <= 0 || source == null || size > source.capacity()) {
                listener.onError(-2)
                return null
            }
            val bytes = ByteArray(size)
            source.duplicate().apply { clear(); limit(size) }.get(bytes)
            Log.i(TAG, "Samsung JPEG ready bytes=$size selected=$selectedFrames " +
                "submitted=$submittedFrames stopToResultMs=${SystemClock.elapsedRealtime() - stopAtMs}")
            listener.onResult(Result(
                jpeg = bytes,
                imageSize = Size(number("width"), number("height")),
                croppedSize = Size(number("croppedWidth"), number("croppedHeight")),
                fullPanoramaWidth = number("fullPanoWidth"),
                orientation = number("orientation"),
                vertical = direction == DIRECTION_BOTTOM_TO_TOP || direction == DIRECTION_TOP_TO_BOTTOM,
            ))
        }
        // onProgress is deliberately not a saving UI; stop returns the native completed JPEG.
        return null
    }

    private fun packNv21(image: Image, stride: Int, elevation: Int): ByteBuffer {
        val required = stride * elevation * 3 / 2
        val target = frameBuffer?.takeIf { it.capacity() == required }
            ?: ByteBuffer.allocateDirect(required).also { frameBuffer = it }
        target.clear()
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val chromaOffset = stride * elevation
        val outputY = target.duplicate().slice()
        val outputU = target.duplicate().apply { position(chromaOffset + 1) }.slice()
        val outputV = target.duplicate().apply { position(chromaOffset) }.slice()
        val status = repackYuv.invoke(null,
            y.buffer.slice(), y.rowStride,
            u.buffer.slice(), u.rowStride,
            v.buffer.slice(), v.rowStride, u.pixelStride,
            outputY, stride, 1,
            outputU, stride, 2,
            outputV, stride, 2,
            yuvScratch[0], yuvScratch[1], yuvScratch[2],
            image.width, image.height, 0,
        ) as Int
        check(status == 0) { "CameraX NV21 packing failed: $status" }
        return target
    }

    private fun decodeNv21(source: ByteBuffer, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0 ||
            width.toLong() * height * 3 / 2 > source.capacity()) return null
        val pixels = IntArray(width * height)
        val chromaStart = width * height
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val chroma = chromaStart + row / 2 * width + (column and -2)
                val y = ((source.get(index).toInt() and 255) - 16).coerceAtLeast(0)
                val v = (source.get(chroma).toInt() and 255) - 128
                val u = (source.get(chroma + 1).toInt() and 255) - 128
                val r = ((298 * y + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * y - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * y + 516 * u + 128) shr 8).coerceIn(0, 255)
                pixels[index] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val TAG = "SamsungPanoEngine"
        private const val INPUT_WIDTH = 4000
        private const val INPUT_HEIGHT = 3000
        private const val SAMSUNG_CAMERA_PACKAGE = "com.sec.android.app.camera"
        private const val SYSTEM_CAMERA_APK = "/system/priv-app/SamsungCamera/SamsungCamera.apk"
        @Volatile private var samsungClassLoader: ClassLoader? = null
        const val DIRECTION_LEFT_TO_RIGHT = 1
        const val DIRECTION_RIGHT_TO_LEFT = 2
        const val DIRECTION_BOTTOM_TO_TOP = 4
        const val DIRECTION_TOP_TO_BOTTOM = 8

        fun create(context: Context, horizontalViewAngle: Float, listener: Listener): SamsungPanoramaEngine {
            val apk = resolveSamsungCameraApk(context)
            val loader = samsungClassLoader ?: synchronized(this) {
                samsungClassLoader ?: PathClassLoader(apk, "$apk!/lib/arm64-v8a", context.classLoader)
                    .also { samsungClassLoader = it }
            }
            val apiClass = Class.forName("com.samsung.android.panorama.Interface", true, loader)
            val initClass = Class.forName("com.samsung.android.panorama.InitParam", true, loader)
            val callbackClass = Class.forName("com.samsung.android.panorama.PanoCallbackInterface", true, loader)
            lateinit var engine: SamsungPanoramaEngine
            // JNI calls onProgress with a primitive int. ART CheckJNI treats a dynamic Proxy's
            // generic invocation signature as object arguments, aborting at progress 50 (0x32).
            // A concrete callback preserves the real (I)V ABI, as Samsung's own callback does.
            val callback = object : PanoCallbackInterface {
                override fun onProgress(progress: Int) = Unit
                override fun onResult(result: ResultParam) {
                    engine.callback("onResult", arrayOf(result))
                }
            }
            val api = apiClass.getConstructor(callbackClass, Context::class.java)
                .newInstance(callback, context)
            engine = SamsungPanoramaEngine(api, apiClass, listener, apk)
            val verticalViewAngle = Math.toDegrees(
                2.0 * atan(tan(Math.toRadians(horizontalViewAngle.toDouble()) / 2.0) * 0.75),
            ).toFloat()
            val init = initClass.getConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            ).newInstance(5_000, 5, 6, INPUT_WIDTH, INPUT_HEIGHT, 90, 12, 12,
                verticalViewAngle, horizontalViewAngle)
            try {
                check(apiClass.getMethod("init", initClass).invoke(api, init) == 0) {
                    "Samsung panorama engine initialization failed"
                }
            } catch (error: Throwable) {
                engine.release()
                throw unwrap(error)
            }
            Log.i(TAG, "Samsung direct panorama engine initialized from $apk " +
                "horizontalFov=$horizontalViewAngle verticalFov=$verticalViewAngle")
            return engine
        }

        @Suppress("DEPRECATION")
        private fun resolveSamsungCameraApk(context: Context): String {
            val packagePath = runCatching {
                context.packageManager.getApplicationInfo(SAMSUNG_CAMERA_PACKAGE, 0).sourceDir
            }.getOrNull()
            return listOfNotNull(packagePath, SYSTEM_CAMERA_APK).firstOrNull { File(it).isFile }
                ?: error("Samsung Camera system APK is unavailable")
        }

        private fun unwrap(error: Throwable): Throwable =
            (error as? InvocationTargetException)?.targetException ?: error
    }
}
