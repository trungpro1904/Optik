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

            void main() {
                vec4 textureColor = texture2D(sCameraTex, vTextureCoord);
                
                if (uHasLut == 0) {
                    gl_FragColor = textureColor;
                    return;
                }
                
                // Tránh lỗi lấy mẫu tại viền
                float blueColor = textureColor.b * 63.0;
                
                vec2 quad1;
                quad1.y = floor(floor(blueColor) / 8.0);
                quad1.x = floor(blueColor) - (quad1.y * 8.0);
                
                vec2 quad2;
                quad2.y = floor(ceil(blueColor) / 8.0);
                quad2.x = ceil(blueColor) - (quad2.y * 8.0);
                
                vec2 texPos1;
                texPos1.x = (quad1.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.r);
                texPos1.y = (quad1.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.g);
                texPos1.y = 1.0 - texPos1.y;
                
                vec2 texPos2;
                texPos2.x = (quad2.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.r);
                texPos2.y = (quad2.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.g);
                texPos2.y = 1.0 - texPos2.y;
                
                vec4 newColor1 = texture2D(sLutTex, texPos1);
                vec4 newColor2 = texture2D(sLutTex, texPos2);
                
                vec4 newColor = mix(newColor1, newColor2, fract(blueColor));
                gl_FragColor = mix(textureColor, vec4(newColor.rgb, textureColor.a), 1.0);
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
    }

    fun loadLut(assetFileName: String?) {
        if (lutTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = -1
        }
        
        if (assetFileName == null) {
            return
        }

        val bitmap = try {
            val inputStream = context.assets.open(assetFileName)
            BitmapFactory.decodeStream(inputStream)
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
