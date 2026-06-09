package com.example.optik.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.example.optik.camera.gl.EglCore
import com.example.optik.camera.gl.GlLutFilter
import com.example.optik.camera.gl.WindowSurface

class GlVideoProcessor(private val context: Context) {

    private var eglCore: EglCore? = null
    private var displaySurface: WindowSurface? = null
    private var recordSurface: WindowSurface? = null
    private var lutFilter: GlLutFilter? = null

    private var cameraTextureId = -1
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val transformMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Listener để báo cho CameraManagerHelper biết Surface đã sẵn sàng
    var onInputSurfaceReady: ((Surface) -> Unit)? = null
        set(value) {
            field = value
            inputSurface?.let { value?.invoke(it) }
        }

    fun start() {
        handlerThread = HandlerThread("GlVideoProcessor").apply { start() }
        handler = Handler(handlerThread!!.looper)

        handler?.post {
            initGL()
        }
    }

    fun stop() {
        handler?.post {
            releaseGL()
            handlerThread?.quitSafely()
        }
    }

    fun setDisplaySurface(surface: Surface) {
        handler?.post {
            displaySurface?.release()
            eglCore?.let {
                displaySurface = WindowSurface(it, surface, false)
                // Phải make current 1 lần để đảm bảo context
                displaySurface?.makeCurrent()
            }
        }
    }

    fun setRecordSurface(surface: Surface?) {
        handler?.post {
            recordSurface?.release()
            recordSurface = null
            if (surface != null) {
                eglCore?.let {
                    recordSurface = WindowSurface(it, surface, false)
                }
            }
        }
    }

    fun setDefaultBufferSize(width: Int, height: Int) {
        handler?.post {
            cameraSurfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    fun setLut(assetFileName: String?) {
        handler?.post {
            displaySurface?.makeCurrent()
            lutFilter?.loadLut(assetFileName)
        }
    }

    private fun initGL() {
        eglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
        
        // Cần 1 pbuffer surface tạm thời để có thể gọi hàm GL trước khi có display surface
        val tempSurface = eglCore!!.createWindowSurface(SurfaceTexture(10))
        eglCore!!.makeCurrent(tempSurface)

        lutFilter = GlLutFilter(context)

        // Tạo OES texture cho camera
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(0x8D65, cameraTextureId) // GL_TEXTURE_EXTERNAL_OES
        GLES20.glTexParameterf(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        cameraSurfaceTexture = SurfaceTexture(cameraTextureId)
        cameraSurfaceTexture?.setOnFrameAvailableListener({
            handler?.post { drawFrame() }
        }, handler)

        inputSurface = Surface(cameraSurfaceTexture)
        
        // Báo về cho main thread
        onInputSurfaceReady?.invoke(inputSurface!!)

        Matrix.setIdentityM(mvpMatrix, 0)
        // Camera sensor thường bị xoay hoặc lật, nhưng TransformMatrix từ SurfaceTexture đã lo việc xoay
        // Tuy nhiên, có thể cần lật dọc (flip Y) do trục Y của OpenGL bị ngược với Android Canvas
        Matrix.scaleM(mvpMatrix, 0, 1f, -1f, 1f)
        
        eglCore!!.releaseSurface(tempSurface)
    }

    private fun drawFrame() {
        val st = cameraSurfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(transformMatrix)
        
        val timestamp = st.timestamp

        // Render ra Display
        displaySurface?.let {
            it.makeCurrent()
            GLES20.glViewport(0, 0, it.getWidth(), it.getHeight())
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            lutFilter?.draw(cameraTextureId, mvpMatrix, transformMatrix)
            it.swapBuffers()
        }

        // Render ra Record
        recordSurface?.let {
            it.makeCurrent()
            GLES20.glViewport(0, 0, it.getWidth(), it.getHeight())
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            lutFilter?.draw(cameraTextureId, mvpMatrix, transformMatrix)
            
            it.setPresentationTime(timestamp)
            it.swapBuffers()
        }
    }

    private fun releaseGL() {
        displaySurface?.release()
        displaySurface = null
        recordSurface?.release()
        recordSurface = null
        
        lutFilter?.release()
        lutFilter = null

        inputSurface?.release()
        inputSurface = null
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null

        if (cameraTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            cameraTextureId = -1
        }

        eglCore?.release()
        eglCore = null
    }
}
