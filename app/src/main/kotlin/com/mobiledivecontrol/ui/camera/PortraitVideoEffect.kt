package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.opengl.GLES20
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/** Optional recorded image transform. The default preview/focus shader is unchanged. */
internal interface SurfaceFrameShader {
    val source: String
    fun onProgram(program: Int)
    fun bind(width: Int, height: Int, timestampNs: Long)
    fun release()
}

internal class PortraitFrameShader : SurfaceFrameShader {
    data class Mask(val width: Int, val height: Int, val bytes: ByteBuffer, val atMs: Long)
    val pending = AtomicReference<Mask?>()
    @Volatile var effect = 1
    @Volatile var strength = 4f / 7f
    private var texture = 0
    private var maskAt = 0L
    private var maskSize = 0L
    private var maskUniform = -1
    private var effectUniform = -1
    private var strengthUniform = -1
    private var timeUniform = -1
    private val portraitFunction = """
        uniform sampler2D uMask;
        uniform float uEffect, uStrength, uTime;
        vec4 portrait(vec4 c) {
            if (uEffect < 0.5 || uStrength < 0.001) return c;
            float person = smoothstep(0.25, 0.75, texture2D(uMask, vec2(vTex.x, 1.0-vTex.y)).r);
            vec3 background = c.rgb;
            if (uEffect < 2.5) {
                vec3 sum = vec3(0.0); float weight = 0.0;
                // Fixed sixteen-tap disk: cost is bounded independently of the strength dial.
                for (int i = 0; i < 16; i++) {
                    float a = float(i) * 2.39996323;
                    float r = sqrt((float(i)+0.5)/16.0) * (3.0+uStrength*24.0);
                    vec3 p = texture2D(uSamp, vTex + vec2(cos(a),sin(a))*r*uStep*3.0).rgb;
                    float w = uEffect > 1.5 ? 1.0 + 5.0*dot(p,p) : 1.0;
                    sum += p*w; weight += w;
                }
                background = sum / weight;
            } else if (uEffect < 3.5) {
                background = vec3(dot(c.rgb,vec3(0.2126,0.7152,0.0722)));
            } else {
                float shift = sin(floor(vTex.y*30.0)+floor(uTime*8.0))*0.02*uStrength;
                background = vec3(texture2D(uSamp,vTex+vec2(shift,0.0)).r,
                    c.g, texture2D(uSamp,vTex-vec2(shift,0.0)).b);
            }
            return vec4(mix(c.rgb,background,(1.0-person)*uStrength),c.a);
        }
    """.trimIndent()

    // Reuse the existing focus/exposure shader verbatim, changing only the underlying image.
    override val source = FocusPeakingSurfaceProcessor.FS
        .replace("void main() {", portraitFunction + "\nvoid main() {")
        .replace("vec4 display = c;", "vec4 display = portrait(c);")

    override fun onProgram(program: Int) {
        maskUniform = GLES20.glGetUniformLocation(program, "uMask")
        effectUniform = GLES20.glGetUniformLocation(program, "uEffect")
        strengthUniform = GLES20.glGetUniformLocation(program, "uStrength")
        timeUniform = GLES20.glGetUniformLocation(program, "uTime")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
    }

    override fun bind(width: Int, height: Int, timestampNs: Long) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        pending.getAndSet(null)?.let { mask ->
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            val size = (mask.width.toLong() shl 32) or mask.height.toLong()
            if (size != maskSize) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, mask.width, mask.height,
                    0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, mask.bytes)
                maskSize = size
            } else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, mask.width, mask.height,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, mask.bytes)
            maskAt = mask.atMs
        }
        GLES20.glUniform1i(maskUniform, 1)
        GLES20.glUniform1f(effectUniform, if (SystemClock.uptimeMillis()-maskAt < 500) effect.toFloat() else 0f)
        GLES20.glUniform1f(strengthUniform, strength)
        GLES20.glUniform1f(timeUniform, (timestampNs % 10_000_000_000L) / 1e9f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    override fun release() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        pending.set(null)
    }
}

/** Runs synchronously on the existing analysis executor, at most 12.5 Hz, with no frame queue. */
internal class PortraitSegmenter(context: Context) : AutoCloseable {
    private val engine = ImageSegmenter.createFromOptions(context,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_segmenter_landscape.tflite").build())
            .setRunningMode(RunningMode.VIDEO).setOutputConfidenceMasks(true).setOutputCategoryMask(false).build())
    private val rgba = ByteBuffer.allocateDirect(256 * 144 * 4)
    private var lastAt = 0L

    fun analyze(image: ImageProxy, output: PortraitFrameShader) {
        val now = SystemClock.uptimeMillis()
        if (now-lastAt < 80) return
        lastAt = now
        val plane = image.planes.firstOrNull() ?: return
        if (plane.pixelStride != 4) return
        rgba.clear()
        val crop = image.cropRect
        // Preserve sensor coordinates; the SurfaceTexture transform handles display rotation/mirroring.
        for (y in 0 until 144) for (x in 0 until 256) {
            val offset = (crop.top+y*crop.height()/144)*plane.rowStride + (crop.left+x*crop.width()/256)*4
            for (c in 0..3) rgba.put(plane.buffer.get(offset+c))
        }
        rgba.flip()
        ByteBufferImageBuilder(rgba, 256, 144, MPImage.IMAGE_FORMAT_RGBA).build().use { input ->
            val masks = engine.segmentForVideo(input, now).confidenceMasks().orElse(emptyList())
            try {
                val mask = masks.lastOrNull() ?: return
                val floats = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val bytes = ByteBuffer.allocateDirect(mask.width * mask.height)
                for (i in 0 until mask.width*mask.height) bytes.put((floats.get(i).coerceIn(0f,1f)*255f).toInt().toByte())
                bytes.flip()
                output.pending.set(PortraitFrameShader.Mask(mask.width, mask.height, bytes, now))
            } finally { masks.forEach { it.close() } }
        }
    }

    override fun close() = engine.close()
}
