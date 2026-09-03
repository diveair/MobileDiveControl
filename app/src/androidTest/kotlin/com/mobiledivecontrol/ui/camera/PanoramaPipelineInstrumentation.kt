package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import dalvik.system.PathClassLoader
import java.io.File
import java.nio.ByteBuffer
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Device-only regression: real Android bitmap projection, blending, coverage and JPEG timing. */
class PanoramaPipelineInstrumentation : Instrumentation() {
    private var fullResolution = false
    private var probeSamsungEngine = false
    private var initializeSamsungNode = false
    private var exerciseSamsungInterface = false
    private var exerciseSamsungPipeline = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fullResolution = arguments?.getString("fullResolution") == "true"
        probeSamsungEngine = arguments?.getString("probeSamsungEngine") == "true"
        initializeSamsungNode = arguments?.getString("initializeSamsungNode") == "true"
        exerciseSamsungInterface = arguments?.getString("exerciseSamsungInterface") == "true"
        exerciseSamsungPipeline = arguments?.getString("exerciseSamsungPipeline") == "true"
        start()
    }

    override fun onStart() {
        val results = Bundle()
        if (exerciseSamsungPipeline) {
            try {
                for (vertical in listOf(false, true)) exerciseSamsungCapture(vertical, results)
                finish(0, results)
            } catch (error: Throwable) {
                results.putString("failure", android.util.Log.getStackTraceString(error))
                finish(1, results)
            }
            return
        }
        if (probeSamsungEngine || initializeSamsungNode || exerciseSamsungInterface) {
            try {
                val apk = "/system/priv-app/SamsungCamera/SamsungCamera.apk"
                val loader = PathClassLoader(
                    apk,
                    "$apk!/lib/arm64-v8a",
                    targetContext.classLoader,
                )
                val native = Class.forName(
                    "com.samsung.android.panorama.InterfaceNative",
                    true,
                    loader,
                )
                val wrapper = Class.forName(
                    "com.samsung.android.panorama.Interface",
                    true,
                    loader,
                )
                val node = Class.forName(
                    "com.samsung.android.camera.core2.node.panorama.PanoramaNode",
                    true,
                    loader,
                )
                val initParam = Class.forName(
                    "com.samsung.android.camera.core2.node.panorama.PanoramaNodeBase\$PanoramaInitParam",
                    true,
                    loader,
                )
                val callback = Class.forName(
                    "com.samsung.android.camera.core2.node.panorama.PanoramaNodeBase\$PanoramaNodeCallback",
                    true,
                    loader,
                )
                if (exerciseSamsungInterface) {
                    SystemClock.sleep(1_000L)
                    val callbackClass = Class.forName(
                        "com.samsung.android.panorama.PanoCallbackInterface",
                        true,
                        loader,
                    )
                    val interfaceClass = Class.forName(
                        "com.samsung.android.panorama.Interface",
                        true,
                        loader,
                    )
                    val initClass = Class.forName(
                        "com.samsung.android.panorama.InitParam",
                        true,
                        loader,
                    )
                    val captureClass = Class.forName(
                        "com.samsung.android.panorama.CaptureParam",
                        true,
                        loader,
                    )
                    val selectClass = Class.forName(
                        "com.samsung.android.panorama.SelectFrames",
                        true,
                        loader,
                    )
                    val events = mutableListOf<String>()
                    val callbackInstance = Proxy.newProxyInstance(loader, arrayOf(callbackClass)) {
                            _, method, args ->
                        events += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "()")
                        null
                    }
                    val instance = interfaceClass
                        .getConstructor(callbackClass, android.content.Context::class.java)
                        .newInstance(callbackInstance, targetContext)
                    val init = initClass.getConstructor(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                    ).newInstance(5_000, 5, 6, 4000, 3000, 90, 12, 12, 87.335556f, 103f)
                    val initResult = interfaceClass.getMethod("init", initClass).invoke(instance, init)
                    val capture = captureClass.getConstructor(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).newInstance(90, 0, false, 0)
                    val captureResult = interfaceClass.getMethod("capture", captureClass)
                        .invoke(instance, capture)
                    val stride = 4032
                    val elevation = 3008
                    val frame = ByteBuffer.allocateDirect(stride * elevation * 3 / 2)
                    while (frame.hasRemaining()) frame.put(128.toByte())
                    frame.rewind()
                    val select = selectClass.getConstructor(
                        ByteBuffer::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).newInstance(frame, stride, elevation)
                    val selectResult = interfaceClass.getMethod("selectFrames", selectClass)
                        .invoke(instance, select)
                    val selection = listOf("select", "direction", "point_x", "point_y", "estimateProgress")
                        .associateWith { selectClass.getField(it).get(select) }
                    val cancelResult = interfaceClass.getMethod("cancel").invoke(instance)
                    val deinitResult = interfaceClass.getMethod("deinit").invoke(instance)
                    results.putString(
                        "samsungInterface",
                        "init=$initResult capture=$captureResult selectResult=$selectResult " +
                            "selection=$selection cancel=$cancelResult deinit=$deinitResult events=$events",
                    )
                    finish(0, results)
                    return
                }
                if (initializeSamsungNode) {
                    // Samsung Camera normally loads this dependency from NativeNode's static
                    // initializer before any core2 DirectBuffer is allocated.
                    Class.forName(
                        "com.samsung.android.camera.core2.node.NativeNode",
                        true,
                        loader,
                    )
                    Class.forName(
                        "com.samsung.android.camera.core2.util.DirectBuffer",
                        true,
                        loader,
                    )
                    Class.forName(
                        "com.samsung.android.camera.core2.util.ImageUtils",
                        true,
                        loader,
                    )
                    Class.forName(
                        "com.samsung.android.camera.core2.util.QuramResizer",
                        true,
                        loader,
                    )
                    SystemClock.sleep(1_000L)
                    val unsafeClass = Class.forName("sun.misc.Unsafe")
                    val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply {
                        isAccessible = true
                    }
                    val unsafe = unsafeField.get(null)
                    val init = unsafeClass.getMethod("allocateInstance", Class::class.java)
                        .invoke(unsafe, initParam)
                    fun field(name: String, value: Any) {
                        initParam.getField(name).set(init, value)
                    }
                    field("a", 5)
                    field("b", 6)
                    field("c", 12)
                    field("d", 90)
                    field("e", 1)
                    field("f", 5_000)
                    field("g", 87.335556f)
                    field("h", 103.0f)
                    field("i", android.util.Size(4000, 3000))
                    field("j", android.util.Size(512, 384))
                    field("k", targetContext.filesDir)
                    field("l", android.graphics.ImageFormat.YUV_420_888)
                    val events = mutableListOf<String>()
                    val callbackInstance = Proxy.newProxyInstance(
                        loader,
                        arrayOf(callback),
                    ) { _, method, args ->
                        synchronized(events) {
                            events += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "()")
                        }
                        null
                    }
                    val instance = node.getConstructor(initParam, callback, android.content.Context::class.java)
                        .newInstance(init, callbackInstance, targetContext)
                    node.getMethod("onInitialized", Map::class.java)
                        .invoke(instance, Collections.emptyMap<Any, Any>())
                    val state = node.getMethod("getPanoramaState").invoke(instance)
                    node.getMethod("onDeinitialized").invoke(instance)
                    node.getMethod("release").invoke(instance)
                    results.putString(
                        "samsungNode",
                        "initialized state=${state.javaClass.name} init=$init events=$events",
                    )
                    finish(0, results)
                    return
                }
                results.putString(
                    "samsungEngine",
                    "loaded native=${native.name} wrapper=${wrapper.name} " +
                        "methods=${wrapper.declaredMethods.map { it.name }.sorted().distinct()} " +
                        "nodeCtors=${node.declaredConstructors.contentToString()} " +
                        "initCtors=${initParam.declaredConstructors.contentToString()} " +
                        "callbackMethods=${callback.declaredMethods.contentToString()}",
                )
                finish(0, results)
            } catch (error: Throwable) {
                results.putString("samsungEngineFailure", android.util.Log.getStackTraceString(error))
                finish(1, results)
            }
            return
        }
        val directory = File(targetContext.noBackupFilesDir, "panorama-pipeline-test-${System.nanoTime()}")
        directory.mkdirs()
        try {
            for (direction in listOf("Right", "Left", "Up", "Down")) {
                val width = if (fullResolution) 4000 else 1920
                val height = if (fullResolution) 3000 else 1440
                val horizontal = direction == "Right" || direction == "Left"
                val count = if (direction == "Right" && !fullResolution) 35 else 6
                val stitcher = PanoramaBitmapStitcher.Incremental(direction, 1.5f,
                    if (horizontal) 1808 else 2411, PanoramaDynamicRangeProfile.Hdr)
                try {
                    var appendMs = 0L
                    for (index in 0 until count) {
                        val pixels = IntArray(width * height) { pixel ->
                            val sign = if (direction == "Left" || direction == "Up") -1 else 1
                            val x = pixel % width + if (horizontal) index * 180 * sign else 0
                            val y = pixel / width + if (horizontal) 0 else index * 160 * sign
                            val value = 50 + ((x * 17 xor y * 31 xor (x / 43) * 719 xor (y / 61) * 133) and 127)
                            -0x1000000 or (value shl 16) or (value shl 8) or value
                        }
                        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                        val buffer = ByteBuffer.allocate(width * height * 4)
                        bitmap.copyPixelsToBuffer(buffer)
                        bitmap.recycle()
                        val frameFile = File(directory, "frame.rgba")
                        frameFile.writeBytes(buffer.array())
                        val before = SystemClock.elapsedRealtime()
                        stitcher.append(StoredPanoramaFrame(frameFile, index * PANORAMA_FRAME_STEP_RADIANS,
                            rawWidth = width, rawHeight = height, physicalDisplayRotation = 1))
                        appendMs += SystemClock.elapsedRealtime() - before
                    }
                    val stop = SystemClock.elapsedRealtime()
                    val output = stitcher.finish()
                    try {
                        check(if (horizontal) output.width > output.height else output.height > output.width)
                        check(if (horizontal) output.height == 1808 else output.width == 2411)
                        val file = File(directory, "$direction.jpg")
                        file.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
                        check(file.length() > 0)
                        results.putString(direction, "frames=$count appendMeanMs=${appendMs / count} finishJpegMs=${SystemClock.elapsedRealtime() - stop} size=${output.width}x${output.height}")
                    } finally { output.recycle() }
                } finally { stitcher.close() }
            }
            directory.deleteRecursively()
            finish(0, results)
        } catch (error: Throwable) {
            results.putString("failure", android.util.Log.getStackTraceString(error))
            directory.deleteRecursively()
            finish(1, results)
        } finally { directory.deleteRecursively() }
    }

    /** Exercises real Image planes, the production adapter and the native stop/JPEG callback. */
    private fun exerciseSamsungCapture(vertical: Boolean, results: Bundle) {
        val width = 4000
        val height = 3000
        val worldWidth = if (vertical) width else 6400
        val worldHeight = if (vertical) 5400 else height
        val world = ByteArray(worldWidth * worldHeight) { index ->
            val x = index % worldWidth
            val y = index / worldWidth
            val cell = (x / 19) * 73856093 xor (y / 23) * 19349663
            (40 + ((cell xor (cell ushr 13)) and 159)).toByte()
        }
        val resultReady = CountDownLatch(1)
        var result: SamsungPanoramaEngine.Result? = null
        var errorCode: Int? = null
        var accepted = 0
        var uiImages = 0
        var stopRequested = false
        val engine = SamsungPanoramaEngine.create(targetContext, 103.68555f,
            object : SamsungPanoramaEngine.Listener {
                override fun onUiImage(bitmap: Bitmap, direction: Int) { uiImages++; bitmap.recycle() }
                override fun onDirectionChanged(direction: Int) = Unit
                override fun onRectChanged(point: android.graphics.Point) = Unit
                override fun onFrameAccepted() { accepted++ }
                override fun onStopRequested() { stopRequested = true }
                override fun onWarning(code: Int) = Unit
                override fun onError(code: Int) { errorCode = code; resultReady.countDown() }
                override fun onResult(value: SamsungPanoramaEngine.Result) {
                    result = value
                    resultReady.countDown()
                }
            })
        val reader = android.media.ImageReader.newInstance(width, height,
            android.graphics.ImageFormat.YUV_420_888, 3)
        val writer = android.media.ImageWriter.newInstance(reader.surface, 3)
        try {
            engine.start()
            check(!engine.stop()) { "An empty sweep should cancel without requesting a JPEG" }
            engine.start()
            for (index in 0 until 100) {
                val input = writer.dequeueInputImage()
                val yPlane = input.planes[0]
                val yBuffer = yPlane.buffer
                val shift = index * 20
                for (row in 0 until height) {
                    yBuffer.position(row * yPlane.rowStride)
                    yBuffer.put(world, (row + if (vertical) shift else 0) * worldWidth +
                        if (vertical) 0 else shift, width)
                }
                for (plane in input.planes.drop(1)) {
                    val buffer = plane.buffer
                    val neutral = ByteArray(buffer.remaining()) { 128.toByte() }
                    buffer.put(neutral)
                }
                writer.queueInputImage(input)
                val deadline = SystemClock.elapsedRealtime() + 1_000L
                var image = reader.acquireLatestImage()
                while (image == null && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(1L)
                    image = reader.acquireLatestImage()
                }
                checkNotNull(image).use { check(engine.process(it)) }
                check(errorCode == null) { "Native frame error $errorCode" }
                if (stopRequested) break
                SystemClock.sleep(30L)
            }
            check(accepted >= 2 && uiImages > 0) { "No stitched frames: accepted=$accepted ui=$uiImages" }
            val stopped = SystemClock.elapsedRealtime()
            engine.stop()
            check(resultReady.await(5L, TimeUnit.SECONDS)) { "Native result callback timed out" }
            check(errorCode == null) { "Native result error $errorCode" }
            val completed = checkNotNull(result)
            check(completed.jpeg.size > 2 && completed.jpeg[0] == 0xff.toByte() &&
                completed.jpeg[1] == 0xd8.toByte()) { "Native output is not JPEG" }
            val decoded = checkNotNull(android.graphics.BitmapFactory.decodeByteArray(
                completed.jpeg, 0, completed.jpeg.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }))
            decoded.recycle()
            results.putString(if (vertical) "SamsungVertical" else "SamsungHorizontal",
                "accepted=$accepted ui=$uiImages jpeg=${completed.jpeg.size} " +
                    "size=${completed.imageSize} stopAndDecodeMs=${SystemClock.elapsedRealtime() - stopped}")
        } finally {
            engine.release()
            writer.close()
            reader.close()
        }
    }
}
