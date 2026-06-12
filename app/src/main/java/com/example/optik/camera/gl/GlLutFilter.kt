package com.example.optik.camera.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Filter áp dụng 3D LUT (từ ảnh Hald CLUT 2D 512x512) lên video/camera stream.
 */
class GlLutFilter(private val context: Context) {

    private var programId = 0
    private var positionLoc = 0
    private var textureCoordLoc = 0
    private var mvpMatrixLoc = 0
    private var texMatrixLoc = 0
    private var cameraTexLoc = 0
    private var lutTexLoc = 0

    private var lutTextureId = -1
    private var dummyTextureId = -1

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        // Shader xử lý 3D LUT (Kích thước Hald CLUT 512x512, grid 8x8x8 = 512, mỗi cell 64x64)
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sCameraTex;
            uniform sampler2D sLutTex;
            uniform int uHasLut;

            vec2 getTexPos(float g_int, float b_int, float r_float) {
                float y_local = floor(g_int / 8.0);
                float x_block = g_int - y_local * 8.0;
                float x = x_block * 64.0 + r_float + 0.5;
                float y = b_int * 8.0 + y_local + 0.5;
                return vec2(x / 512.0, y / 512.0);
            }

            void main() {
                vec4 textureColor = texture2D(sCameraTex, vTextureCoord);
                
                if (uHasLut == 0) {
                    gl_FragColor = textureColor;
                    return;
                }
                
                // Cực kỳ quan trọng: YUV -> RGB từ Camera đôi khi cho ra giá trị ngoài khoảng [0, 1] ở các vùng tối hoặc quá sáng (bề mặt bóng).
                // Nếu không clamp, R, G, B sẽ vượt quá giới hạn khối LUT, dẫn đến loang màu sai lệch (artifacts).
                textureColor = clamp(textureColor, 0.0, 1.0);
                
                float R = textureColor.r * 63.0;
                float G = textureColor.g * 63.0;
                float B = textureColor.b * 63.0;
                
                float g0 = floor(G);
                float g1 = min(g0 + 1.0, 63.0);
                float b0 = floor(B);
                float b1 = min(b0 + 1.0, 63.0);
                
                float g_frac = fract(G);
                float b_frac = fract(B);
                
                vec2 pos00 = getTexPos(g0, b0, R);
                vec2 pos10 = getTexPos(g1, b0, R);
                vec2 pos01 = getTexPos(g0, b1, R);
                vec2 pos11 = getTexPos(g1, b1, R);
                
                vec4 c00 = texture2D(sLutTex, pos00);
                vec4 c10 = texture2D(sLutTex, pos10);
                vec4 c01 = texture2D(sLutTex, pos01);
                vec4 c11 = texture2D(sLutTex, pos11);
                
                vec4 c0 = mix(c00, c10, g_frac);
                vec4 c1 = mix(c01, c11, g_frac);
                
                vec4 newColor = mix(c0, c1, b_frac);
                gl_FragColor = vec4(newColor.rgb, textureColor.a);
            }
        """

        private val FULL_RECTANGLE_COORDS = floatArrayOf(
            -1.0f, -1.0f,   // bottom left
             1.0f, -1.0f,   // bottom right
            -1.0f,  1.0f,   // top left
             1.0f,  1.0f    // top right
        )

        private val FULL_RECTANGLE_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,     // bottom left
            1.0f, 0.0f,     // bottom right
            0.0f, 1.0f,     // top left
            1.0f, 1.0f      // top right
        )
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(FULL_RECTANGLE_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(FULL_RECTANGLE_COORDS)
        .apply { position(0) }

    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(FULL_RECTANGLE_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(FULL_RECTANGLE_TEX_COORDS)
        .apply { position(0) }

    init {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        positionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        textureCoordLoc = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        mvpMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        texMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        cameraTexLoc = GLES20.glGetUniformLocation(programId, "sCameraTex")
        lutTexLoc = GLES20.glGetUniformLocation(programId, "sLutTex")
        
        // Cần lưu vị trí của biến uHasLut
        GLES20.glUseProgram(programId)
        val hasLutLoc = GLES20.glGetUniformLocation(programId, "uHasLut")
        GLES20.glUniform1i(hasLutLoc, 0)
        GLES20.glUseProgram(0)

        // Khởi tạo dummy texture để tránh lỗi đen màn hình (texture completeness) trên GPU Adreno khi không có LUT
        val dummyTex = IntArray(1)
        GLES20.glGenTextures(1, dummyTex, 0)
        dummyTextureId = dummyTex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyTextureId)
        val dummyBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        dummyBuffer.put(byteArrayOf(0, 0, 0, 0))
        dummyBuffer.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, dummyBuffer)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun loadLut(assetFileName: String?) {
        if (lutTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = -1
        }
        
        if (assetFileName == null) {
            return
        }

        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            context.assets.open(assetFileName).use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (bitmap != null) {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            lutTextureId = textures[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }
    }

    fun draw(cameraTexId: Int, mvpMatrix: FloatArray, texMatrix: FloatArray) {
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(0x8D65, cameraTexId) // GL_TEXTURE_EXTERNAL_OES
        GLES20.glUniform1i(cameraTexLoc, 0)

        val hasLutLoc = GLES20.glGetUniformLocation(programId, "uHasLut")

        if (lutTextureId != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLES20.glUniform1i(lutTexLoc, 1)
            GLES20.glUniform1i(hasLutLoc, 1)
        } else {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyTextureId)
            GLES20.glUniform1i(lutTexLoc, 1)
            GLES20.glUniform1i(hasLutLoc, 0)
        }

        GLES20.glUniformMatrix4fv(mvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        GLES20.glEnableVertexAttribArray(textureCoordLoc)
        GLES20.glVertexAttribPointer(textureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisableVertexAttribArray(textureCoordLoc)
        GLES20.glBindTexture(0x8D65, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        if (lutTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = -1
        }
        if (dummyTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(dummyTextureId), 0)
            dummyTextureId = -1
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $error")
        }
        return shader
    }
}
