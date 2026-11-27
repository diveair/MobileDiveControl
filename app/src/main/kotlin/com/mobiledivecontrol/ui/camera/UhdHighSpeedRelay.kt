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
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Camera -> GPU texture -> encoder (every frame, sensor timestamp preserved) and viewfinder
 * (about 30 fps). Used when qualifying a single texture at the advertised high-speed size.
 * No pixels are generated or interpolated; each encoded frame is one camera frame.
 */
internal class UhdHighSpeedRelay(
    val width: Int,
    val height: Int,
    encoderInput: Surface,
    viewfinder: Surface?,
    private val viewfinderIntervalNs: Long = 30_000_000L,
    /**
     * Camera service rebases timestamps to CLOCK_MONOTONIC only for encoder consumers. A texture
     * consumer receives the sensor's CLOCK_BOOTTIME stamps, which MediaRecorder would misalign
     * with its monotonic audio clock by the device's total sleep time.
     */
    private val boottimeSource: Boolean = false,
) {
    private val thread = HandlerThread("UhdHighSpeedRelay", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var viewfinderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var viewfinderWidth = 0
    private var viewfinderHeight = 0
    private lateinit var texture: SurfaceTexture
    lateinit var cameraSurface: Surface
        private set
    private var program = 0
    private var textureId = 0
    private var positionHandle = 0
    private var texMatrixHandle = 0
    private val matrix = FloatArray(16)
    private val quad = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, s, t
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }
    @Volatile private var drawing = true
    /** Encoder frames are drawn only while true; the viewfinder runs regardless. */
    @Volatile var recording = true
    @Volatile var relayed = 0
        private set
    @Volatile var displayed = 0
        private set
    @Volatile var lastTimestampNs = 0L
        private set
    @Volatile var failure: Throwable? = null
        private set
    private var lastViewfinderNs = 0L

    init {
        val ready = CompletableFuture<Unit>()
        handler.post {
            try { setup(encoderInput, viewfinder); ready.complete(Unit) } catch (error: Throwable) { ready.completeExceptionally(error) }
        }
        try { ready.get(5, TimeUnit.SECONDS) } catch (error: Throwable) { release(); throw error }
    }

    fun stop() { drawing = false }

    fun release() {
        drawing = false
        val done = CompletableFuture<Unit>()
        if (!handler.post {
                runCatching { if (::texture.isInitialized) texture.release() }
                runCatching { if (::cameraSurface.isInitialized) cameraSurface.release() }
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (encoderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderEgl)
                    if (viewfinderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, viewfinderEgl)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
                done.complete(Unit)
            }) done.complete(Unit)
        runCatching { done.get(2, TimeUnit.SECONDS) }
        thread.quitSafely()
    }

    private fun setup(encoderInput: Surface, viewfinder: Surface?) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL config" }
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        val none = intArrayOf(EGL14.EGL_NONE)
        encoderEgl = EGL14.eglCreateWindowSurface(display, config, encoderInput, none, 0)
        check(encoderEgl != EGL14.EGL_NO_SURFACE) { "Encoder EGL surface failed" }
        if (viewfinder != null) {
            viewfinderEgl = EGL14.eglCreateWindowSurface(display, config, viewfinder, none, 0)
            val size = IntArray(1)
            EGL14.eglQuerySurface(display, viewfinderEgl, EGL14.EGL_WIDTH, size, 0); viewfinderWidth = size[0]
            EGL14.eglQuerySurface(display, viewfinderEgl, EGL14.EGL_HEIGHT, size, 0); viewfinderHeight = size[0]
        }
        check(EGL14.eglMakeCurrent(display, encoderEgl, encoderEgl, context)) { "eglMakeCurrent failed" }
        // Never let a slow viewfinder block the encoder path.
        EGL14.eglSwapInterval(display, 0)
        textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = buildProgram()
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        texture = SurfaceTexture(textureId).apply { setDefaultBufferSize(width, height) }
        texture.setOnFrameAvailableListener({ onFrame() }, handler)
        cameraSurface = Surface(texture)
    }

    private fun onFrame() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        try {
            EGL14.eglMakeCurrent(display, encoderEgl, encoderEgl, context)
            texture.updateTexImage()
            if (!drawing) return
            texture.getTransformMatrix(matrix)
            val timestamp = texture.timestamp
            if (timestamp <= lastTimestampNs) return
            lastTimestampNs = timestamp
            if (recording) {
                // Encoders ignore the display transform the camera attaches to its buffers; keep the
                // sensor-oriented picture exactly as a direct camera -> encoder stream would.
                draw(width, height, ENCODER_MATRIX)
                val encoderTime = if (boottimeSource)
                    timestamp - (android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime()) else timestamp
                EGLExt.eglPresentationTimeANDROID(display, encoderEgl, encoderTime)
                check(EGL14.eglSwapBuffers(display, encoderEgl)) { "Encoder swap failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
                relayed++
            }
            if (viewfinderEgl != EGL14.EGL_NO_SURFACE && timestamp - lastViewfinderNs >= viewfinderIntervalNs) {
                lastViewfinderNs = timestamp
                EGL14.eglMakeCurrent(display, viewfinderEgl, viewfinderEgl, context)
                draw(viewfinderWidth, viewfinderHeight, matrix)
                if (EGL14.eglSwapBuffers(display, viewfinderEgl)) displayed++
            }
        } catch (error: Throwable) {
            failure = error
            drawing = false
        }
    }

    private fun draw(w: Int, h: Int, texMatrix: FloatArray) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: ${GLES20.glGetShaderInfoLog(it)}" }
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPosition;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vTex = (uTexMatrix * vec4(aPosition.zw, 0.0, 1.0)).xy;
            }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vTex); }
        """.trimIndent())
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it)
            val ok = IntArray(1); GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Program: ${GLES20.glGetProgramInfoLog(it)}" }
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        /** Column-major vertical flip: buffer row 0 at the top of the encoded picture. */
        val ENCODER_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    }
}
