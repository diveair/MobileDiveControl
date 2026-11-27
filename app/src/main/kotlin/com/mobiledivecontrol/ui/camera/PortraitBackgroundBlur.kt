package com.mobiledivecontrol.ui.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20 as GL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Reduce and blur current-frame premultiplied background colour. Alpha holds its sample weight. */
internal class PortraitBackgroundBlur {
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
        position(0)
    }
    private val textures = IntArray(2)
    private val targets = IntArray(2)
    private var width = 0
    private var height = 0
    private var reduce = 0
    private var horizontal = 0
    private var transform = -1
    private var geometry = -1
    private var sampling = -1
    private var distance = -1

    companion object {
        const val GAUSSIAN = """
            vec4 gaussian(sampler2D image, vec2 p, vec2 stepSize) {
                vec4 value = texture2D(image, p)*0.227027;
                value += (texture2D(image,p+1.384615*stepSize)+texture2D(image,p-1.384615*stepSize))*0.316216;
                value += (texture2D(image,p+3.230769*stepSize)+texture2D(image,p-3.230769*stepSize))*0.070270;
                return value;
            }
        """
        private const val VERTEX = """
            attribute vec2 aPosition;
            varying vec2 vPoint;
            void main() { gl_Position=vec4(aPosition,0.0,1.0); vPoint=aPosition*0.5+0.5; }
        """
        private const val REDUCE = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vPoint;
            uniform samplerExternalOES uCamera;
            uniform sampler2D uMask;
            uniform mat4 uTransform;
            uniform mat3 uGeometry;
            uniform vec2 uQuarterPixel;
            vec4 background(vec2 p) {
                vec2 maskPoint = (uGeometry*vec3(p.x,1.0-p.y,1.0)).xy;
                float a=texture2D(uMask,maskPoint).a;
                vec3 color=texture2D(uCamera,(uTransform*vec4(p,0.0,1.0)).xy).rgb;
                return vec4(color*a,a);
            }
            void main() {
                vec2 d=uQuarterPixel;
                gl_FragColor=(background(vPoint+d)+background(vPoint-d)
                    +background(vPoint+vec2(d.x,-d.y))+background(vPoint+vec2(-d.x,d.y)))*0.25;
            }
        """
    }

    fun prepare(outputWidth: Int, outputHeight: Int) {
        if (reduce == 0) {
            reduce = program(REDUCE.trimIndent())
            horizontal = program("precision mediump float; varying vec2 vPoint; uniform sampler2D uImage;" +
                "uniform vec2 uDistance;" + GAUSSIAN + "void main(){gl_FragColor=gaussian(uImage,vPoint,uDistance);}")
            GL.glUseProgram(reduce)
            transform = GL.glGetUniformLocation(reduce, "uTransform")
            geometry = GL.glGetUniformLocation(reduce, "uGeometry")
            sampling = GL.glGetUniformLocation(reduce, "uQuarterPixel")
            GL.glUniform1i(GL.glGetUniformLocation(reduce, "uCamera"), 0)
            GL.glUniform1i(GL.glGetUniformLocation(reduce, "uMask"), 1)
            GL.glUseProgram(horizontal)
            distance = GL.glGetUniformLocation(horizontal, "uDistance")
            GL.glUniform1i(GL.glGetUniformLocation(horizontal,"uImage"), 2)
            GL.glGenTextures(2, textures, 0)
            GL.glGenFramebuffers(2, targets, 0)
        }
        val scale = minOf(1f, 384f / maxOf(outputWidth, outputHeight))
        val w = (outputWidth*scale).roundToInt().coerceAtLeast(1)
        val h = (outputHeight*scale).roundToInt().coerceAtLeast(1)
        GL.glActiveTexture(GL.GL_TEXTURE2)
        if (width != w || height != h) {
            width = w
            height = h
            for (i in targets.indices) {
                GL.glBindTexture(GL.GL_TEXTURE_2D, textures[i])
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,null)
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, targets[i])
                GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,textures[i],0)
                check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)==GL.GL_FRAMEBUFFER_COMPLETE)
            }
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    fun render(source: Int, mask: Int, sourceTransform: FloatArray, maskTransform: FloatArray,
               outputWidth: Int, radius: Float): Int {
        GL.glViewport(0,0,width,height)
        GL.glVertexAttribPointer(0,2,GL.GL_FLOAT,false,0,quad)
        GL.glEnableVertexAttribArray(0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,targets[0])
        GL.glUseProgram(reduce)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,source)
        GL.glActiveTexture(GL.GL_TEXTURE1)
        GL.glBindTexture(GL.GL_TEXTURE_2D,mask)
        GL.glUniformMatrix4fv(transform,1,false,sourceTransform,0)
        GL.glUniformMatrix3fv(geometry,1,false,maskTransform,0)
        GL.glUniform2f(sampling,0.25f/width,0.25f/height)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP,0,4)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,targets[1])
        GL.glUseProgram(horizontal)
        GL.glActiveTexture(GL.GL_TEXTURE2)
        GL.glBindTexture(GL.GL_TEXTURE_2D,textures[0])
        GL.glUniform2f(distance,radius/outputWidth,0f)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP,0,4)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        return textures[1]
    }

    private fun program(fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val shader = GL.glCreateShader(type)
            GL.glShaderSource(shader,source)
            GL.glCompileShader(shader)
            val status = IntArray(1)
            GL.glGetShaderiv(shader,GL.GL_COMPILE_STATUS,status,0)
            check(status[0] != 0) { GL.glGetShaderInfoLog(shader) }
            return shader
        }
        val p = GL.glCreateProgram()
        val vs = shader(GL.GL_VERTEX_SHADER,VERTEX)
        val fs = shader(GL.GL_FRAGMENT_SHADER,fragment)
        GL.glAttachShader(p,vs)
        GL.glAttachShader(p,fs)
        GL.glBindAttribLocation(p,0,"aPosition")
        GL.glLinkProgram(p)
        GL.glDeleteShader(vs)
        GL.glDeleteShader(fs)
        val status = IntArray(1)
        GL.glGetProgramiv(p,GL.GL_LINK_STATUS,status,0)
        check(status[0] != 0) { GL.glGetProgramInfoLog(p) }
        return p
    }

    fun release() {
        GL.glDeleteTextures(2,textures,0)
        GL.glDeleteFramebuffers(2,targets,0)
        GL.glDeleteProgram(reduce)
        GL.glDeleteProgram(horizontal)
    }
}
