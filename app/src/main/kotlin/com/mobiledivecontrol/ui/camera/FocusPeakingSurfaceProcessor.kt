package com.mobiledivecontrol.ui.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * GPU-accelerated focus peaking via OpenGL ES 2.0 fragment shader.
 *
 * Sits in the CameraX preview pipeline as a [SurfaceProcessor] within a
 * [androidx.camera.core.CameraEffect]. The Sobel edge-detection shader runs
 * on the **same** frame the user sees, at full preview resolution, with zero
 * latency. This eliminates the drift / jitter inherent in CPU bitmap overlays.
 *
 * When [peakingEnabled] is `false` the shader is a trivial texture pass-through
 * (single texture fetch per pixel — negligible GPU cost).
 */
class FocusPeakingSurfaceProcessor(
    private val cbExecutor: Executor,
) : SurfaceProcessor {

    companion object {
        private const val TAG = "FocusPeaking"

        // ── Vertex shader ─────────────────────────────────────────────
        private const val VS = """
attribute vec4 aPos;
attribute vec2 aTex;
varying vec2 vTex;
uniform mat4 uSTM;
void main() {
    gl_Position = aPos;
    // uSTM is the CameraX-adjusted transform (updateTransformMatrix), which already
    // encodes every rotation/crop the OUTPUT surface expects — with or without
    // stream sharing. No hand-tuned rotation may live here: it would be correct for
    // exactly one pipeline shape and wrong the moment a use case is added.
    vTex = (uSTM * vec4(aTex, 0.0, 1.0)).xy;
}
"""

        // ── Fragment shader (Sobel edge peaking) ──────────────────────
        private const val FS = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES uSamp;
uniform vec2  uStep;
uniform float uOn;
uniform float uThr;

void main() {
    vec4 c = texture2D(uSamp, vTex);
    if (uOn < 0.5) { gl_FragColor = c; return; }

    vec3 w = vec3(0.299, 0.587, 0.114);
    float tl = dot(texture2D(uSamp, vTex + uStep*vec2(-1,-1)).rgb, w);
    float tc = dot(texture2D(uSamp, vTex + uStep*vec2( 0,-1)).rgb, w);
    float tr = dot(texture2D(uSamp, vTex + uStep*vec2( 1,-1)).rgb, w);
    float ml = dot(texture2D(uSamp, vTex + uStep*vec2(-1, 0)).rgb, w);
    float mr = dot(texture2D(uSamp, vTex + uStep*vec2( 1, 0)).rgb, w);
    float bl = dot(texture2D(uSamp, vTex + uStep*vec2(-1, 1)).rgb, w);
    float bc = dot(texture2D(uSamp, vTex + uStep*vec2( 0, 1)).rgb, w);
    float br = dot(texture2D(uSamp, vTex + uStep*vec2( 1, 1)).rgb, w);

    float gx = (tr + 2.0*mr + br) - (tl + 2.0*ml + bl);
    float gy = (bl + 2.0*bc + br) - (tl + 2.0*tc + tr);
    float edge = sqrt(gx*gx + gy*gy);

    // Contrast-normalized response: dividing by local mean luma makes the measure
    // relative, so in-focus texture peaks equally in shadow and in light instead of
    // only the brightest edges crossing a fixed threshold.
    float mean = (tl + tc + tr + ml + mr + bl + bc + br) * 0.125;
    float norm = edge / (mean + 0.05);

    // Dual-scale sharpness ratio — the true focus discriminator. A genuinely sharp
    // edge completes its transition within one pixel, so its fine-pitch gradient
    // matches its coarse-pitch gradient (ratio near 1). A defocused edge is a ramp:
    // the 1-px gradient sees only a fraction of the step the 2-px gradient sees
    // (ratio near 0.5). Vendor sharpening halos and plain contrast cannot fake this.
    float l2 = dot(texture2D(uSamp, vTex + uStep*vec2(-2.0, 0.0)).rgb, w);
    float r2 = dot(texture2D(uSamp, vTex + uStep*vec2( 2.0, 0.0)).rgb, w);
    float u2 = dot(texture2D(uSamp, vTex + uStep*vec2( 0.0,-2.0)).rgb, w);
    float d2 = dot(texture2D(uSamp, vTex + uStep*vec2( 0.0, 2.0)).rgb, w);
    float cgx = r2 - l2;
    float cgy = d2 - u2;
    float coarse = 4.0 * sqrt(cgx*cgx + cgy*cgy);
    float ratio = edge / (coarse + 0.02);

    // Blown highlights carry steep gradients whether or not they are in focus — a
    // lamp halo peaks at any lens position. Never peak on or beside saturation.
    float lc = dot(c.rgb, w);
    // 0.55, not the theoretical ~0.9: the vendor ISP's noise reduction spreads even a
    // perfectly focused edge across 2-3 pixels, which alone costs a third of the ratio.
    // Genuine defocus sits near 0.3-0.4, so the margin holds.
    if (norm > uThr && ratio > 0.55 && lc < 0.95 && mean < 0.95) {
        gl_FragColor = vec4(0.0, 0.9, 0.0, 1.0);
    } else {
        gl_FragColor = c;
    }
}
"""

        private val QUAD = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
        private val TCOORD = floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)
    }

    // ── Public knobs ──────────────────────────────────────────────────
    @Volatile var peakingEnabled = false

    /**
     * Timestamp of the last frame that actually reached the display surface. This is the
     * pipeline's true heartbeat: PreviewView's stream state only tracks the camera, so it
     * stays STREAMING while this GL stage silently drops every frame (e.g. the SurfaceView
     * surface was destroyed in background and eglSwapBuffers fails without throwing).
     */
    @Volatile var lastDrawSuccessAtMs = 0L
    @Volatile var peakingThreshold = 0.9f

    // ── GL thread ─────────────────────────────────────────────────────
    private val glThread = HandlerThread("GL-FocusPeak").apply { start() }
    private val glH = Handler(glThread.looper)

    // ── EGL ───────────────────────────────────────────────────────────
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var tmpSurf: EGLSurface = EGL14.EGL_NO_SURFACE

    // ── Input ─────────────────────────────────────────────────────────
    private var inST: SurfaceTexture? = null
    private var inSurf: Surface? = null
    private var inTex = 0

    // ── Output ────────────────────────────────────────────────────────
    private var outEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outSO: SurfaceOutput? = null
    private var outW = 0
    private var outH = 0
    private var inW = 0
    private var inH = 0

    // ── Program ───────────────────────────────────────────────────────
    private var prog = 0
    private var lPos = -1; private var lTex = -1
    private var lSTM = -1; private var lSamp = -1
    private var lStep = -1; private var lOn = -1; private var lThr = -1
    private var vb: FloatBuffer? = null
    private var tb: FloatBuffer? = null
    private var lastSwapFailLogAtMs = 0L
    private val stm = FloatArray(16)       // raw SurfaceTexture transform
    private val outStm = FloatArray(16)    // CameraX-adjusted transform for the output surface

    // ──────────────────────────────────────────────────────────────────
    override fun onInputSurface(request: SurfaceRequest) {
        val sz = request.resolution
        glH.post {
            try {
                eglInit()
                progInit()
                bufInit()
                texInit(sz.width, sz.height)
                // The release callback must free ONLY the surfaces of THIS request. On a
                // rebind (camera eviction recovery, lifecycle stop/start) the previous
                // request's callback fires AFTER the replacement input exists — a blind
                // releaseIn() here destroyed the NEW SurfaceTexture and left the preview
                // permanently black while camera frames kept flowing.
                val st = inST!!
                val surf = inSurf!!
                val tex = inTex
                request.provideSurface(surf, cbExecutor) {
                    glH.post {
                        st.release()
                        surf.release()
                        if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                        if (inST === st) {
                            inST = null
                            inSurf = null
                            inTex = 0
                        }
                    }
                }
                st.setOnFrameAvailableListener({ glH.post { draw() } })
            } catch (e: Exception) {
                Log.e(TAG, "onInputSurface failed", e)
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        glH.post {
            try {
                outSO?.close()
                if (outEgl != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(dpy, outEgl)
                    outEgl = EGL14.EGL_NO_SURFACE
                }
                outSO = output
                outW = output.size.width
                outH = output.size.height
                val surf = output.getSurface(cbExecutor) { }
                outEgl = EGL14.eglCreateWindowSurface(dpy, cfg, surf,
                    intArrayOf(EGL14.EGL_NONE), 0)
            } catch (e: Exception) {
                Log.e(TAG, "onOutputSurface failed", e)
            }
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────
    private fun draw() {
        val st = inST ?: return
        if (outEgl == EGL14.EGL_NO_SURFACE) return
        outSO ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(stm)
            // Ask CameraX what the output surface actually wants: it composes the input
            // transform with whatever rotation/crop this pipeline shape introduces.
            outSO?.updateTransformMatrix(outStm, stm)

            EGL14.eglMakeCurrent(dpy, outEgl, outEgl, ctx)
            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inTex)
            GLES20.glUniform1i(lSamp, 0)

            GLES20.glUniformMatrix4fv(lSTM, 1, false, outStm, 0)
            // One INPUT pixel per tap: a blurred (out-of-focus) edge spreads its
            // gradient across many sensor pixels, so at this pitch it reads soft and
            // stays unpeaked, while the display scale can no longer skew the measure.
            GLES20.glUniform2f(lStep, 1f / maxOf(inW, 1), 1f / maxOf(inH, 1))
            GLES20.glUniform1f(lOn, if (peakingEnabled) 1f else 0f)
            GLES20.glUniform1f(lThr, peakingThreshold)

            vb!!.position(0)
            GLES20.glVertexAttribPointer(lPos, 2, GLES20.GL_FLOAT, false, 0, vb)
            GLES20.glEnableVertexAttribArray(lPos)
            tb!!.position(0)
            GLES20.glVertexAttribPointer(lTex, 2, GLES20.GL_FLOAT, false, 0, tb)
            GLES20.glEnableVertexAttribArray(lTex)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (EGL14.eglSwapBuffers(dpy, outEgl)) {
                lastDrawSuccessAtMs = SystemClock.elapsedRealtime()
            } else {
                // Swap failure does NOT throw — without this check a dead output surface
                // (destroyed while backgrounded) looks like a healthy pipeline upstream.
                val now = SystemClock.elapsedRealtime()
                if (now - lastSwapFailLogAtMs > 1000L) {
                    lastSwapFailLogAtMs = now
                    Log.w(TAG, "eglSwapBuffers failed — output surface dead? err=0x" +
                        Integer.toHexString(EGL14.eglGetError()))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "draw error", e)
        }
    }

    // ── EGL init ──────────────────────────────────────────────────────
    private fun eglInit() {
        if (dpy != EGL14.EGL_NO_DISPLAY) return
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(dpy, v, 0, v, 1)
        val ca = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, ca, 0, cfgs, 0, 1, n, 0)
        cfg = cfgs[0]!!
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        tmpSurf = EGL14.eglCreatePbufferSurface(dpy, cfg,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, tmpSurf, tmpSurf, ctx)
    }

    private fun progInit() {
        if (prog != 0) return
        val vs = compile(GLES20.GL_VERTEX_SHADER, VS)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FS)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val s = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, s, 0)
        if (s[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog); prog = 0
            throw RuntimeException("Link: $log")
        }
        lPos  = GLES20.glGetAttribLocation(prog, "aPos")
        lTex  = GLES20.glGetAttribLocation(prog, "aTex")
        lSTM  = GLES20.glGetUniformLocation(prog, "uSTM")
        lSamp = GLES20.glGetUniformLocation(prog, "uSamp")
        lStep = GLES20.glGetUniformLocation(prog, "uStep")
        lOn   = GLES20.glGetUniformLocation(prog, "uOn")
        lThr  = GLES20.glGetUniformLocation(prog, "uThr")
    }

    private fun bufInit() {
        if (vb != null) return
        vb = fbuf(QUAD); tb = fbuf(TCOORD)
    }

    private fun texInit(w: Int, h: Int) {
        inST?.setOnFrameAvailableListener(null)
        inW = w
        inH = h
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        inTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(inTex)
        st.setDefaultBufferSize(w, h)
        inST = st
        inSurf = Surface(st)
    }

    // ── Cleanup ───────────────────────────────────────────────────────
    private fun releaseIn() {
        inST?.release(); inSurf?.release()
        inST = null; inSurf = null
        if (inTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(inTex), 0); inTex = 0 }
    }

    fun release() {
        glH.post {
            releaseIn()
            if (prog != 0) { GLES20.glDeleteProgram(prog); prog = 0 }
            outSO?.close(); outSO = null
            if (outEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, outEgl)
            if (tmpSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, tmpSurf)
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            if (dpy != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(dpy)
            dpy = EGL14.EGL_NO_DISPLAY; ctx = EGL14.EGL_NO_CONTEXT
        }
        glThread.quitSafely()
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private fun compile(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val s = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, s, 0)
        if (s[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Compile: $log")
        }
        return id
    }

    private fun fbuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(a); position(0) }
}
