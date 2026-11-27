package com.mobiledivecontrol.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import androidx.camera.core.SurfaceOutput
import java.util.concurrent.atomic.AtomicReference

/** Upload only subject coverage. Both the foreground and the background move at the preview frame rate. */
internal class PortraitPhotoPreview : SurfaceFrameShader {
    data class Frame(val layer: Bitmap, val sensorToAnalysis: Matrix, val width: Int, val height: Int, val time: Long)
    @Volatile var look = PortraitPhotoLook("Blur",
        com.mobiledivecontrol.core.CameraCatalog.defaultSettingValues.getValue("portrait.effect_strength").toInt())
    private var outputToSensor: Matrix? = null
    private var outputWidth = 1
    private var outputHeight = 1
    private val pending = AtomicReference<Frame?>()
    private var shown: Frame? = null
    private var visibleSince = 0L
    private val coordinates = FloatArray(9)
    private var texture = 0
    private var layerUniform = -1
    private var geometryUniform = -1
    private var gradeUniform = -1
    private var enabledUniform = -1
    private var styleUniform = -1
    private var blurRadiusUniform = -1
    private var outputTexelUniform = -1
    private var textureWidth = 0
    private var textureHeight = 0
    private val backgroundBlur = PortraitBackgroundBlur()
    private var backgroundTexture = 0
    private var backgroundUniform = -1
    private var frameLook = look
    private var opacity = 0f
    private var radius = 0f

    override val source = FocusPeakingSurfaceProcessor.FS.replace("precision mediump float;", "precision highp float;")
        .replace("void main() {", """
        uniform sampler2D uLayer;
        uniform mat3 uGeometry;
        uniform vec3 uGrade;
        uniform float uPortraitOn;
        uniform vec2 uStyle;
        uniform vec2 uBlurRadius;
        uniform vec2 uOutputTexel;
        uniform sampler2D uBackground;
        ${PortraitBackgroundBlur.GAUSSIAN}
        vec2 maskAt(vec2 p) { return (uGeometry * vec3(p.x, 1.0-p.y, 1.0)).xy; }
        vec4 applyPortrait(vec4 original) {
            if (uPortraitOn <= 0.0) return original;
            vec2 outputPoint = gl_FragCoord.xy * uOutputTexel;
            vec2 uv = maskAt(outputPoint);
            if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return original;
            float coverage = texture2D(uLayer, uv).a;
            float luma = dot(original.rgb, vec3(0.213,0.715,0.072));
            vec3 foreground = clamp(mix(original.rgb, vec3(luma), uGrade.x)*uGrade.y+uGrade.z, 0.0, 1.0);
            if (coverage < 0.004) return vec4(mix(original.rgb, foreground, uPortraitOn), original.a);
            vec3 background;
            if (uStyle.x < 0.5) {
                vec4 blurred = gaussian(uBackground,outputPoint,uBlurRadius);
                background = blurred.a > 0.0039 ? blurred.rgb / blurred.a : original.rgb;
            } else {
                float value = luma;
                if (uStyle.x < 1.5) value = 196.0/255.0 + 0.23*luma;
                else if (uStyle.x < 2.5) value = 0.20*luma;
                else if (uStyle.x < 3.5) value = (142.0-64.0*uv.y)/255.0;
                background = mix(original.rgb, vec3(value), uStyle.y);
            }
            return vec4(mix(original.rgb, mix(foreground, background, coverage), uPortraitOn), original.a);
        }
        void main() {
    """.trimIndent()).replace("vec4 display = c;", "vec4 display = applyPortrait(c);")

    fun onOutput(output: SurfaceOutput) {
        outputToSensor = Matrix().takeIf { output.sensorToBufferTransform.invert(it) }
        outputWidth = output.size.width
        outputHeight = output.size.height
        backgroundBlur.prepare(outputWidth, outputHeight)
        shown = null
        pending.getAndSet(null)?.layer?.recycle()
    }

    fun publish(frame: Frame) { pending.getAndSet(frame)?.layer?.recycle() }

    override fun onProgram(program: Int) {
        layerUniform = GLES20.glGetUniformLocation(program, "uLayer")
        geometryUniform = GLES20.glGetUniformLocation(program, "uGeometry")
        gradeUniform = GLES20.glGetUniformLocation(program, "uGrade")
        enabledUniform = GLES20.glGetUniformLocation(program, "uPortraitOn")
        backgroundUniform = GLES20.glGetUniformLocation(program, "uBackground")
        styleUniform = GLES20.glGetUniformLocation(program, "uStyle")
        blurRadiusUniform = GLES20.glGetUniformLocation(program, "uBlurRadius")
        outputTexelUniform = GLES20.glGetUniformLocation(program, "uOutputTexel")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        textureWidth = 0
        textureHeight = 0
    }

    fun prepare(sourceTexture: Int, sourceTransform: FloatArray) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        val fresh = pending.getAndSet(null)
        if (fresh != null) {
            if (shown == null || fresh.time - (shown?.time ?: 0L) > 450) visibleSince = SystemClock.uptimeMillis()
            shown = null
            val layer = fresh.layer
            try {
                val inverse = outputToSensor
                if (inverse != null) {
                    val transform = Matrix().apply {
                        setScale(outputWidth.toFloat(), outputHeight.toFloat())
                        postConcat(inverse)
                        postConcat(fresh.sensorToAnalysis)
                        postScale(1f / fresh.width, 1f / fresh.height)
                    }
                    val row = FloatArray(9)
                    transform.getValues(row)
                    for (i in 0..8) coordinates[i] = row[(i % 3) * 3 + i / 3]
                    if (textureWidth == layer.width && textureHeight == layer.height) {
                        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, layer)
                    } else {
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, layer, 0)
                        textureWidth = layer.width
                        textureHeight = layer.height
                    }
                    shown = fresh
                }
            } finally { layer.recycle() }
        }
        frameLook = look
        val now = SystemClock.uptimeMillis()
        opacity = if (frameLook.level == 0) 0f else shown?.let {
            minOf((now-visibleSince)/90f, (500L-(now-it.time))/240f).coerceIn(0f,1f)
        } ?: 0f
        if (opacity > 0f && (frameLook.name == "Blur" || frameLook.name == "Studio")) {
            radius = minOf(outputWidth, outputHeight) * 0.014f * frameLook.strength
            backgroundTexture = backgroundBlur.render(sourceTexture, texture, sourceTransform, coordinates, outputWidth, radius)
        }
    }

    override fun bind(width: Int, height: Int, timestampNs: Long) {
        val current = frameLook
        GLES20.glUniform1f(enabledUniform, opacity)
        GLES20.glUniformMatrix3fv(geometryUniform, 1, false, coordinates, 0)
        GLES20.glUniform3f(gradeUniform, current.mono, current.gain, current.lift)
        val style = when (current.name) {
            "High-key mono" -> 1f
            "Low-key mono" -> 2f
            "Backdrop" -> 3f
            "Color point" -> 4f
            else -> 0f
        }
        GLES20.glUniform2f(styleUniform, style, current.strength)
        GLES20.glUniform2f(blurRadiusUniform, 0f, radius / outputHeight)
        GLES20.glUniform2f(outputTexelUniform, 1f / outputWidth, 1f / outputHeight)
        GLES20.glUniform1i(layerUniform, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTexture)
        GLES20.glUniform1i(backgroundUniform, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    override fun release() {
        pending.getAndSet(null)?.layer?.recycle()
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        backgroundBlur.release()
        texture = 0
        shown = null
    }
}
