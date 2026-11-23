package com.mobiledivecontrol.ui.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Night Hyperlapse GPU stage: camera -> [this] -> (preview SurfaceView, MediaRecorder input surface).
 *
 * Every camera frame is merged into a floating-point accumulation target (RGBA16F when the GPU can
 * render/blend it, otherwise a dithered RGBA8 ping-pong). The normalised accumulation is drawn to
 * the preview every frame (what the diver sees is exactly what will be recorded), and once per
 * output window (see [NightOutputCadence]) it is drawn to the encoder surface with
 * eglPresentationTimeANDROID = the closing camera frame's sensor timestamp, after which the window
 * restarts. One output frame therefore integrates the light of a whole capture interval: at 45x
 * (capture interval 1.5 s) that is a ~1.5 s "long exposure" per video frame -> sqrt(N) less noise
 * in [NightAccumulationMode.AVERAGE], continuous light streaks in [NightAccumulationMode.TRAILS].
 *
 * ## MediaRecorder capture-rate contract
 * Keep `MediaRecorder.setCaptureRate(captureFps)` (captureFps = 30 / speed) and
 * `setVideoFrameRate(30)` exactly as for the direct path, and call
 * `setOutputIntervalNs(HyperlapseNightMath.outputIntervalNs(captureFps))`. MediaRecorder's
 * GraphicBufferSource still applies time-lapse selection by buffer timestamp: it drops a frame that
 * arrives < 0.45 capture intervals after its last snapped capture point and otherwise advances
 * round(elapsed / interval) output slots. Emitting on a fixed grid at exactly the capture interval
 * keeps every emitted frame and maps each to the next 1/30 s slot. Do NOT raise the capture rate to
 * the sensor rate (it would disable the time-lapse retiming and play back in real time).
 * Count mode ([setFramesPerOutput] only) is exact only when the sensor runs at a fixed frame rate;
 * with a variable AE range interval mode also absorbs frame-rate changes. Interval mode needs at
 * least ~3 camera frames per interval (true for every hyperlapse speed >= 5x at >= 18 fps).
 *
 * ## Recommended night exposure (PreparedCamera2Recorder, night && highSpeedFps == null)
 * Light collected per output frame is ~ the capture interval whatever the per-frame exposure, so
 * the choice is about read noise, motion rendering and flicker:
 * - Default (robust): CONTROL_AE_MODE_ON with the lowest advertised CONTROL_AE_TARGET_FPS_RANGE
 *   lower bound whose upper <= 30 (S24: typically [8..15, 30]); do NOT also set
 *   CONTROL_SCENE_MODE_NIGHT (it can pin very long frame times that make the preview stutter).
 *   Fewer, longer frames carry less read noise; N = sensorFps / captureFps falls accordingly,
 *   which interval mode handles automatically. Use NOISE_REDUCTION_MODE_FAST (not HIGH_QUALITY:
 *   temporal averaging beats spatial NR, which only smears detail and adds latency) and lock AE and
 *   AWB (CONTROL_AE_LOCK / CONTROL_AWB_LOCK = true) for the duration of a take, otherwise AE
 *   hunting appears as exposure flicker between output frames.
 * - Manual (best quality, tripod/static scene, requires REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR):
 *   CONTROL_AE_MODE_OFF, SENSOR_EXPOSURE_TIME 1/15 s (66 ms) .. 1/8 s (125 ms) clamped to
 *   SENSOR_INFO_EXPOSURE_TIME_RANGE, SENSOR_FRAME_DURATION = exposure (<= SENSOR_INFO_MAX_FRAME_DURATION),
 *   SENSOR_SENSITIVITY 800..3200 within SENSOR_INFO_SENSITIVITY_RANGE, then brighten with
 *   [setExposureGain] instead of more ISO. No AE flicker at all, but no adaptation to changing
 *   light, no antibanding (mains-lit scenes may beat), and the preview runs at 8-15 fps.
 *   N = round(fps / captureFps), e.g. 1/10 s at 45x -> 10 fps * 1.5 s = 15 frames per output frame.
 * Trade-offs: averaging blurs anything moving (handheld/underwater drift smears in AVERAGE; use
 * OIS on, EIS off), and 1/8 s per frame motion-blurs fish/divers within each frame.
 *
 * Threading: all public methods are thread-safe. [start] callbacks run on the main thread. Surface
 * setters and [release] block (bounded) until the GL thread has applied them, so a SurfaceHolder
 * `surfaceDestroyed` or a MediaRecorder stop can follow them safely.
 */
internal class HyperlapseNightAccumulator(private val width: Int, private val height: Int) {
    init {
        require(width > 0 && height > 0) { "Invalid accumulator size ${width}x$height" }
    }

    private val thread = HandlerThread("GL-NightHyperlapse").apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    // ── Settings written by any thread, read once per frame on the GL thread ──
    @Volatile private var released = false
    @Volatile private var requestedMode = NightAccumulationMode.AVERAGE
    @Volatile private var requestedFramesPerOutput = 1
    @Volatile private var requestedIntervalNs = 0L
    @Volatile private var requestedGain = 1f
    @Volatile private var displayRotationIndex = 0
    @Volatile private var previewScale = NightPreviewScale.FILL
    @Volatile private var previewMinIntervalNs = 0L

    // ── EGL (GL thread) ──
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: Surface? = null
    private var previewViewWidth = 0
    private var previewViewHeight = 0
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: Surface? = null
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var pendingPreview: Surface? = null
    private var pendingEncoder: Surface? = null

    // ── Input ──
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var cameraTexture = 0

    // ── Accumulation targets ──
    private var floatAccumulation = false
    private val meanTexture = IntArray(2)
    private val meanFramebuffer = IntArray(2)
    private var meanRead = 0
    private val maxTexture = IntArray(1)
    private val maxFramebuffer = IntArray(1)
    private var maxUnavailable = false
    private var trailsInWindow = false

    // ── Programs ──
    private var copyProgram = 0
    private var meanProgram = 0
    private var outputProgram = 0
    private var meanInvN = -1
    private var meanNoise = -1
    private var outputTexMatrix = -1
    private var outputTrails = -1
    private var outputToneK = -1
    private var outputToneNorm = -1
    private var outputNoise = -1
    private var vertexBuffer = 0

    // ── Per-frame scratch (preallocated; nothing is allocated per frame) ──
    private val stMatrix = FloatArray(16)
    private val rotatedMatrix = FloatArray(16)
    private val previewMatrix = FloatArray(16)
    private val viewport = IntArray(4)
    private val querySize = IntArray(1)
    private val cadence = NightOutputCadence()
    private var appliedMode = NightAccumulationMode.AVERAGE
    private var appliedGain = Float.NaN
    private var toneK = 0f
    private var toneNorm = 1f
    private var frameCounter = 0L
    private var lastPreviewTimestampNs = Long.MIN_VALUE
    private var lastLogAtMs = 0L
    private val frameListener = SurfaceTexture.OnFrameAvailableListener { onFrame() }

    /**
     * Creates the EGL context, accumulation targets and the camera input surface (sized
     * width x height via setDefaultBufferSize) on the GL thread, then hands the input surface to
     * [onInputSurfaceReady] on the main thread. The camera session must target ONLY this surface.
     */
    fun start(onInputSurfaceReady: (Surface) -> Unit, onError: ((Throwable) -> Unit)? = null) {
        check(!released) { "Night accumulator is released" }
        check(started.compareAndSet(false, true)) { "Night accumulator already started" }
        val posted = gl.post {
            if (released) return@post
            try {
                setupEgl()
                setupGl()
                setupInput()
                val surface = checkNotNull(inputSurface)
                pendingPreview?.let { attachPreview(it, previewViewWidth, previewViewHeight) }
                pendingEncoder?.let { attachEncoder(it) }
                Log.i(TAG, "Night accumulator ${width}x$height float=$floatAccumulation")
                main.post { if (!released) onInputSurfaceReady(surface) }
            } catch (error: Throwable) {
                Log.e(TAG, "Night accumulator setup failed", error)
                runCatching { teardown() }
                main.post { onError?.invoke(error) }
            }
        }
        if (!posted) main.post { onError?.invoke(IllegalStateException("Night accumulator thread stopped")) }
    }

    fun setMode(mode: NightAccumulationMode) {
        requestedMode = mode
    }

    /** Count-mode window length (used while no output interval is set). */
    fun setFramesPerOutput(n: Int) {
        requestedFramesPerOutput = n.coerceAtLeast(1)
    }

    /** Interval mode (recommended): one output frame per [intervalNs] of sensor time; 0 = count mode. */
    fun setOutputIntervalNs(intervalNs: Long) {
        requestedIntervalNs = intervalNs.coerceAtLeast(0L)
    }

    /** Digital shadow gain (1..16) applied through the highlight-preserving soft-knee curve. */
    fun setExposureGain(gain: Float) {
        requestedGain = if (gain.isNaN()) 1f else gain.coerceIn(1f, HyperlapseNightMath.MAX_EXPOSURE_GAIN)
    }

    /**
     * Display rotation in degrees (Surface.ROTATION_90 -> 90). The camera SurfaceTexture transform
     * is relative to the device's natural orientation and, unlike a camera-fed SurfaceView, an
     * EGL-fed preview gets no compositor compensation, so this activity's (landscape) rotation must
     * be supplied. It affects only the preview; the encoder receives the sensor buffer orientation
     * exactly as the direct camera -> MediaRecorder path did.
     */
    fun setDisplayRotation(degrees: Int) {
        displayRotationIndex = (((degrees % 360) + 360) % 360 + 45) / 90 % 4
    }

    fun setPreviewScale(scale: NightPreviewScale) {
        previewScale = scale
    }

    /** Caps preview redraws (0 = every camera frame). Accumulation and encoding are unaffected. */
    fun setPreviewMaxFps(fps: Int) {
        previewMinIntervalNs = if (fps > 0) 1_000_000_000L / fps * 9 / 10 else 0L
    }

    /**
     * Attaches (or with null detaches) the preview window surface, e.g. a SurfaceView holder
     * surface. [viewWidth]/[viewHeight] are the on-screen view size; the EGL buffer size is queried,
     * so a SurfaceHolder.setFixedSize buffer is letterboxed correctly. Blocks until applied (<= 1 s);
     * call with null from surfaceDestroyed before returning.
     */
    fun setPreviewSurface(surface: Surface?, viewWidth: Int, viewHeight: Int) {
        runOnGlSync { attachPreview(surface, viewWidth, viewHeight) }
    }

    /**
     * Attaches (or with null detaches) the encoder input surface (MediaRecorder persistent input
     * surface). Attach after MediaRecorder.start(); detach before MediaRecorder.stop(). Blocks until
     * applied (<= 1 s) so no frame is queued into a stopped encoder.
     */
    fun setEncoderSurface(surface: Surface?) {
        runOnGlSync { attachEncoder(surface) }
    }

    /** Releases EGL, GL objects and the input surface. Close the camera session first. */
    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        val posted = gl.post {
            try { teardown() } catch (error: Throwable) { Log.w(TAG, "Night accumulator teardown", error) }
            finally { done.countDown() }
        }
        if (posted && Looper.myLooper() != thread.looper) {
            if (!done.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "Teardown timed out")
        }
        thread.quitSafely()
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // GL thread
    // ──────────────────────────────────────────────────────────────────────────────

    private fun runOnGlSync(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) {
            block()
            return
        }
        val done = CountDownLatch(1)
        val posted = gl.post {
            try { block() } catch (error: RuntimeException) { Log.w(TAG, "Night surface change failed", error) }
            finally { done.countDown() }
        }
        if (posted && !done.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "GL thread did not apply a surface change within ${SYNC_TIMEOUT_MS}ms")
        }
    }

    private fun attachPreview(surface: Surface?, viewWidth: Int, viewHeight: Int) {
        previewViewWidth = viewWidth
        previewViewHeight = viewHeight
        if (released) return
        if (display == EGL14.EGL_NO_DISPLAY) {
            pendingPreview = surface
            return
        }
        pendingPreview = null
        if (surface != null && surface === previewSurface && previewEgl != EGL14.EGL_NO_SURFACE) return
        destroyWindow(previewEgl)
        previewEgl = EGL14.EGL_NO_SURFACE
        previewSurface = surface
        lastPreviewTimestampNs = Long.MIN_VALUE
        if (surface == null) return
        previewEgl = createWindow(surface, "preview")
        if (previewEgl != EGL14.EGL_NO_SURFACE && makeCurrent(previewEgl)) {
            // Async (dropping) queue: the preview must never stall accumulation or encoding.
            EGL14.eglSwapInterval(display, 0)
            makeOffscreenCurrent()
        }
    }

    private fun attachEncoder(surface: Surface?) {
        if (released) return
        if (display == EGL14.EGL_NO_DISPLAY) {
            pendingEncoder = surface
            return
        }
        pendingEncoder = null
        if (surface != null && surface === encoderSurface && encoderEgl != EGL14.EGL_NO_SURFACE) return
        destroyWindow(encoderEgl)
        encoderEgl = EGL14.EGL_NO_SURFACE
        encoderSurface = surface
        if (surface == null) return
        encoderEgl = createWindow(surface, "encoder")
        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglQuerySurface(display, encoderEgl, EGL14.EGL_WIDTH, querySize, 0)
            encoderWidth = querySize[0].takeIf { it > 0 } ?: width
            EGL14.eglQuerySurface(display, encoderEgl, EGL14.EGL_HEIGHT, querySize, 0)
            encoderHeight = querySize[0].takeIf { it > 0 } ?: height
            // Swap interval stays 1: encoder frames must block rather than be dropped.
        }
    }

    private fun onFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            val previewCurrent = previewEgl != EGL14.EGL_NO_SURFACE && makeCurrent(previewEgl)
            if (!previewCurrent && !makeOffscreenCurrent()) return
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            val timestampNs = st.timestamp
            applySettings()
            val n = cadence.beginFrame()
            accumulate(n)
            val emit = cadence.endFrame(timestampNs)
            if (previewCurrent) {
                val minInterval = previewMinIntervalNs
                if (emit || minInterval <= 0L || lastPreviewTimestampNs == Long.MIN_VALUE ||
                    timestampNs - lastPreviewTimestampNs >= minInterval
                ) {
                    renderPreview()
                    lastPreviewTimestampNs = timestampNs
                }
            }
            if (emit && encoderEgl != EGL14.EGL_NO_SURFACE) renderEncoder(timestampNs)
            frameCounter++
        } catch (error: RuntimeException) {
            logThrottled("Night accumulator frame failed", error)
        }
    }

    private fun applySettings() {
        val mode = requestedMode
        if (mode != appliedMode) {
            appliedMode = mode
            cadence.restartWindow()
        }
        cadence.framesPerOutput = requestedFramesPerOutput
        cadence.outputIntervalNs = requestedIntervalNs
        val gain = requestedGain
        if (gain != appliedGain) {
            appliedGain = gain
            toneK = HyperlapseNightMath.toneCurveK(gain)
            toneNorm = HyperlapseNightMath.toneCurveNorm(toneK)
        }
    }

    /** One full-screen pass (two in TRAILS) at stream resolution; no copies, no readbacks. */
    private fun accumulate(n: Int) {
        if (n <= 1) trailsInWindow = appliedMode == NightAccumulationMode.TRAILS && ensureMaxTarget()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        if (floatAccumulation) {
            // Running mean by blending: dst = src/n + dst(1 - 1/n). n = 1 overwrites (= reset).
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meanFramebuffer[0])
            GLES20.glUseProgram(copyProgram)
            if (n <= 1) {
                GLES20.glDisable(GLES20.GL_BLEND)
            } else {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
                GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA)
                GLES20.glBlendColor(0f, 0f, 0f, 1f / n)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } else {
            // RGBA8 fallback: same running mean computed in highp with stochastic rounding
            // (+-0.5 LSB dither) so small 1/n increments are not lost to 8-bit quantisation.
            val write = 1 - meanRead
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meanFramebuffer[write])
            GLES20.glUseProgram(meanProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, meanTexture[meanRead])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1f(meanInvN, 1f / n)
            GLES20.glUniform2f(meanNoise, noiseX(), noiseY())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            meanRead = write
        }
        if (trailsInWindow) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maxFramebuffer[0])
            GLES20.glUseProgram(copyProgram)
            if (n <= 1) {
                GLES20.glDisable(GLES20.GL_BLEND)
            } else {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendEquation(GLES30.GL_MAX)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
    }

    private fun renderPreview() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        EGL14.eglQuerySurface(display, previewEgl, EGL14.EGL_WIDTH, querySize, 0)
        val bufferWidth = querySize[0]
        EGL14.eglQuerySurface(display, previewEgl, EGL14.EGL_HEIGHT, querySize, 0)
        val bufferHeight = querySize[0]
        if (bufferWidth <= 0 || bufferHeight <= 0) return
        // window -> natural orientation -> camera buffer (SurfaceTexture) -> accumulation texture.
        Matrix.multiplyMM(rotatedMatrix, 0, stMatrix, 0, ROTATIONS[displayRotationIndex], 0)
        Matrix.multiplyMM(previewMatrix, 0, FLIP_Y, 0, rotatedMatrix, 0)
        val quarterTurn = abs(rotatedMatrix[0]) < abs(rotatedMatrix[4])
        val contentAspect = if (quarterTurn) height.toFloat() / width else width.toFloat() / height
        HyperlapseNightMath.previewViewport(
            viewport,
            if (previewViewWidth > 0) previewViewWidth else bufferWidth,
            if (previewViewHeight > 0) previewViewHeight else bufferHeight,
            bufferWidth,
            bufferHeight,
            contentAspect,
            previewScale,
        )
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        drawOutput(previewMatrix)
        if (!EGL14.eglSwapBuffers(display, previewEgl)) {
            logThrottled("Preview swap failed egl=0x${Integer.toHexString(EGL14.eglGetError())}", null)
        }
    }

    private fun renderEncoder(timestampNs: Long) {
        if (!makeCurrent(encoderEgl)) {
            logThrottled("Encoder surface not current egl=0x${Integer.toHexString(EGL14.eglGetError())}", null)
            return
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
        // Identity: the accumulation already holds the sensor buffer orientation the encoder got
        // from the direct camera path (no display rotation, no SurfaceTexture transform).
        drawOutput(IDENTITY)
        EGLExt.eglPresentationTimeANDROID(display, encoderEgl, timestampNs)
        if (!EGL14.eglSwapBuffers(display, encoderEgl)) {
            logThrottled("Encoder swap failed egl=0x${Integer.toHexString(EGL14.eglGetError())}", null)
        }
    }

    private fun drawOutput(texMatrix: FloatArray) {
        GLES20.glUseProgram(outputProgram)
        GLES20.glUniformMatrix4fv(outputTexMatrix, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (floatAccumulation) meanTexture[0] else meanTexture[meanRead])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (trailsInWindow) maxTexture[0] else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(outputTrails, if (trailsInWindow) 1f else 0f)
        GLES20.glUniform1f(outputToneK, toneK)
        GLES20.glUniform1f(outputToneNorm, toneNorm)
        GLES20.glUniform2f(outputNoise, noiseX(), noiseY())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun noiseX(): Float = (frameCounter and 63L).toFloat() * 5.588238f
    private fun noiseY(): Float = (frameCounter and 63L).toFloat() * 3.137f

    private fun ensureMaxTarget(): Boolean {
        if (maxTexture[0] != 0) return true
        if (maxUnavailable) return false
        // GL_MAX blending on UNORM is exact, so the peak needs no float storage.
        if (!createTarget(GLES30.GL_RGBA8, maxTexture, maxFramebuffer, 0)) {
            maxUnavailable = true
            Log.w(TAG, "Trails target unavailable; using Average")
            return false
        }
        return true
    }

    // ── Setup ──

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            display = EGL14.EGL_NO_DISPLAY
            error("eglInitialize failed")
        }
        val chosen = chooseConfig(withPbuffer = true) ?: chooseConfig(withPbuffer = false)
        config = checkNotNull(chosen) { "No recordable GLES3 RGBA8888 EGL config" }
        context = EGL14.eglCreateContext(
            display, chosen, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) {
            "GLES3 context failed egl=0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        // A 1x1 pbuffer keeps the context current when no window is attached; without pbuffer
        // support EGL_KHR_surfaceless_context (universal on Android 8+) is used instead.
        offscreen = runCatching {
            EGL14.eglCreatePbufferSurface(
                display, chosen, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
        }.getOrNull() ?: EGL14.EGL_NO_SURFACE
        check(makeOffscreenCurrent()) { "Cannot make night GL context current" }
    }

    private fun chooseConfig(withPbuffer: Boolean): EGLConfig? {
        val surfaceType = if (withPbuffer) EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun setupGl() {
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        copyProgram = buildProgram(COPY_FRAGMENT_SHADER)
        meanProgram = buildProgram(MEAN_FRAGMENT_SHADER)
        outputProgram = buildProgram(OUTPUT_FRAGMENT_SHADER)

        GLES20.glUseProgram(copyProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram, "uTexMatrix"), 1, false, FLIP_Y, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "uCamera"), 0)

        GLES20.glUseProgram(meanProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meanProgram, "uTexMatrix"), 1, false, FLIP_Y, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meanProgram, "uCamera"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meanProgram, "uPrevious"), 1)
        meanInvN = GLES20.glGetUniformLocation(meanProgram, "uInvN")
        meanNoise = GLES20.glGetUniformLocation(meanProgram, "uNoise")

        GLES20.glUseProgram(outputProgram)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(outputProgram, "uMean"), 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(outputProgram, "uMax"), 2)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(outputProgram, "uTrailsGate"),
            HyperlapseNightMath.TRAILS_GATE_LOW, HyperlapseNightMath.TRAILS_GATE_HIGH,
        )
        outputTexMatrix = GLES20.glGetUniformLocation(outputProgram, "uTexMatrix")
        outputTrails = GLES20.glGetUniformLocation(outputProgram, "uTrails")
        outputToneK = GLES20.glGetUniformLocation(outputProgram, "uToneK")
        outputToneNorm = GLES20.glGetUniformLocation(outputProgram, "uToneNorm")
        outputNoise = GLES20.glGetUniformLocation(outputProgram, "uNoise")
        GLES20.glUseProgram(0)

        // One interleaved quad (x, y, s, t) bound once; attribute state lives in the context.
        val quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(QUAD).position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vertexBuffer = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, QUAD.size * 4, quad, GLES20.GL_STATIC_DRAW)
        GLES20.glEnableVertexAttribArray(ATTR_POSITION)
        GLES20.glVertexAttribPointer(ATTR_POSITION, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(ATTR_TEXCOORD)
        GLES20.glVertexAttribPointer(ATTR_TEXCOORD, 2, GLES20.GL_FLOAT, false, 16, 8)

        GLES20.glGenTextures(1, ids, 0)
        cameraTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DITHER)

        val floatRenderable = extensions.contains("GL_EXT_color_buffer_half_float") ||
            extensions.contains("GL_EXT_color_buffer_float") || version.contains("OpenGL ES 3.2")
        floatAccumulation = floatRenderable && createTarget(GLES30.GL_RGBA16F, meanTexture, meanFramebuffer, 0)
        if (!floatAccumulation) {
            check(
                createTarget(GLES30.GL_RGBA8, meanTexture, meanFramebuffer, 0) &&
                    createTarget(GLES30.GL_RGBA8, meanTexture, meanFramebuffer, 1),
            ) { "Cannot allocate night accumulation targets ${width}x$height" }
        }
        meanRead = 0
        cadence.reset()
    }

    private fun setupInput() {
        val st = SurfaceTexture(cameraTexture)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener(frameListener, gl)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun createTarget(internalFormat: Int, textures: IntArray, framebuffers: IntArray, index: Int): Boolean {
        drainGlErrors()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internalFormat, width, height)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        val ok = status == GLES20.GL_FRAMEBUFFER_COMPLETE && GLES20.glGetError() == GLES20.GL_NO_ERROR
        if (ok) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!ok) {
            Log.i(TAG, "Accumulation format 0x${Integer.toHexString(internalFormat)} unavailable status=0x${Integer.toHexString(status)}")
            ids[0] = framebuffer
            GLES20.glDeleteFramebuffers(1, ids, 0)
            ids[0] = texture
            GLES20.glDeleteTextures(1, ids, 0)
            drainGlErrors()
            return false
        }
        textures[index] = texture
        framebuffers[index] = framebuffer
        return true
    }

    private fun buildProgram(fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glBindAttribLocation(program, ATTR_POSITION, "aPos")
        GLES20.glBindAttribLocation(program, ATTR_TEXCOORD, "aTex")
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Night program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Night shader compile failed: $log")
        }
        return shader
    }

    // ── EGL helpers ──

    private fun createWindow(surface: Surface, label: String): EGLSurface {
        if (!surface.isValid) {
            Log.w(TAG, "Night $label surface is not valid")
            return EGL14.EGL_NO_SURFACE
        }
        val window = try {
            EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Night $label window rejected", error)
            null
        }
        if (window == null || window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "Night $label window failed egl=0x${Integer.toHexString(EGL14.eglGetError())}")
            return EGL14.EGL_NO_SURFACE
        }
        return window
    }

    /** Never destroy a window while it is current: park the context on the offscreen target first. */
    private fun destroyWindow(window: EGLSurface) {
        if (window == EGL14.EGL_NO_SURFACE || display == EGL14.EGL_NO_DISPLAY) return
        makeOffscreenCurrent()
        EGL14.eglDestroySurface(display, window)
    }

    private fun makeCurrent(surface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    private fun makeOffscreenCurrent(): Boolean =
        display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT &&
            EGL14.eglMakeCurrent(display, offscreen, offscreen, context)

    private fun teardown() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        val current = makeOffscreenCurrent()
        destroyWindow(previewEgl)
        destroyWindow(encoderEgl)
        previewEgl = EGL14.EGL_NO_SURFACE
        encoderEgl = EGL14.EGL_NO_SURFACE
        previewSurface = null
        encoderSurface = null
        pendingPreview = null
        pendingEncoder = null
        surfaceTexture?.release()
        inputSurface?.release()
        surfaceTexture = null
        inputSurface = null
        if (current) {
            val ids = IntArray(1)
            for (texture in intArrayOf(cameraTexture, meanTexture[0], meanTexture[1], maxTexture[0])) {
                if (texture != 0) { ids[0] = texture; GLES20.glDeleteTextures(1, ids, 0) }
            }
            for (framebuffer in intArrayOf(meanFramebuffer[0], meanFramebuffer[1], maxFramebuffer[0])) {
                if (framebuffer != 0) { ids[0] = framebuffer; GLES20.glDeleteFramebuffers(1, ids, 0) }
            }
            if (vertexBuffer != 0) { ids[0] = vertexBuffer; GLES20.glDeleteBuffers(1, ids, 0) }
            for (program in intArrayOf(copyProgram, meanProgram, outputProgram)) {
                if (program != 0) GLES20.glDeleteProgram(program)
            }
        }
        cameraTexture = 0
        meanTexture.fill(0)
        meanFramebuffer.fill(0)
        maxTexture.fill(0)
        maxFramebuffer.fill(0)
        vertexBuffer = 0
        copyProgram = 0
        meanProgram = 0
        outputProgram = 0
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (offscreen != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, offscreen)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            // Android's eglTerminate is reference counted per eglInitialize; other GL users survive.
            EGL14.eglTerminate(display)
        }
        offscreen = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        config = null
    }

    private fun drainGlErrors() {
        var guard = 0
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR && guard++ < 16) { }
    }

    private fun logThrottled(message: String, error: Throwable?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAtMs < 1_000L) return
        lastLogAtMs = now
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "DiveNightHyperlapse"
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val SYNC_TIMEOUT_MS = 1_000L
        const val ATTR_POSITION = 0
        const val ATTR_TEXCOORD = 1

        /** Triangle strip, interleaved clip-space x, y and texture s, t. */
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        /**
         * (s, t) -> (s, 1 - t). Sampling the camera with it stores the buffer rows in memory order
         * (the orientation the encoder received from the direct camera path); composed with the
         * SurfaceTexture transform it maps upright coordinates into the accumulation texture.
         */
        val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

        /**
         * Window -> natural-orientation coordinates for display rotation 0/90/180/270
         * (column-major). E.g. ROTATION_90 (natural top now on the left): u = wv, v = 1 - wu.
         */
        val ROTATIONS = arrayOf(
            IDENTITY,
            floatArrayOf(0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f),
        )

        const val VERTEX_SHADER = """
attribute vec4 aPos;
attribute vec4 aTex;
uniform mat4 uTexMatrix;
varying vec2 vTex;
varying vec2 vFbo;
void main() {
    gl_Position = aPos;
    vTex = (uTexMatrix * aTex).xy;
    vFbo = aTex.xy;
}
"""

        const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

        /** Interleaved gradient noise in [0, 1); per-frame offsets decorrelate it over time. */
        const val NOISE = """
float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}
"""

        const val COPY_FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" + PRECISION + """
uniform samplerExternalOES uCamera;
varying vec2 vTex;
void main() {
    gl_FragColor = vec4(texture2D(uCamera, vTex).rgb, 1.0);
}
"""

        const val MEAN_FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" + PRECISION + NOISE + """
uniform samplerExternalOES uCamera;
uniform sampler2D uPrevious;
uniform float uInvN;
uniform vec2 uNoise;
varying vec2 vTex;
varying vec2 vFbo;
void main() {
    vec3 current = texture2D(uCamera, vTex).rgb;
    vec3 previous = texture2D(uPrevious, vFbo).rgb;
    vec3 mean = previous + (current - previous) * uInvN;
    gl_FragColor = vec4(mean + (ign(gl_FragCoord.xy + uNoise) - 0.5) / 255.0, 1.0);
}
"""

        const val OUTPUT_FRAGMENT_SHADER = PRECISION + NOISE + """
uniform sampler2D uMean;
uniform sampler2D uMax;
uniform float uTrails;
uniform vec2 uTrailsGate;
uniform float uToneK;
uniform float uToneNorm;
uniform vec2 uNoise;
varying vec2 vTex;
void main() {
    vec3 c = texture2D(uMean, vTex).rgb;
    if (uTrails > 0.5) {
        vec3 excursion = max(texture2D(uMax, vTex).rgb - c, 0.0);
        float peak = max(excursion.r, max(excursion.g, excursion.b));
        c += excursion * smoothstep(uTrailsGate.x, uTrailsGate.y, peak);
    }
    if (uToneK > 0.0) {
        float m = max(max(c.r, c.g), max(c.b, 1.0e-4));
        c *= (1.0 - exp(-uToneK * m)) * uToneNorm / m;
    }
    c += (ign(gl_FragCoord.xy + uNoise) - 0.5) / 255.0;
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
