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
    var videoOrientation: Int = 0

    // Listener để báo cho CameraManagerHelper biết Surface đã sẵn sàng
    private var _onInputSurfaceReady: ((Surface) -> Unit)? = null
    var onInputSurfaceReady: ((Surface) -> Unit)?
        get() = _onInputSurfaceReady
        set(value) {
            handler?.post {
                _onInputSurfaceReady = value
                inputSurface?.let { value?.invoke(it) }
            }
        }

    private var isGlInitialized = false

    fun start() {
        val thread = HandlerThread("GlVideoProcessor").apply { start() }
        handlerThread = thread
        handler = Handler(thread.looper)
    }

    fun stop() {
        handler?.post {
            releaseGL()
            handlerThread?.quitSafely()
        }
    }

    fun setDisplaySurface(surface: Surface) {
        handler?.post {
            if (!isGlInitialized) {
                initGL(surface)
                isGlInitialized = true
            } else {
                displaySurface?.release()
                eglCore?.let {
                    displaySurface = WindowSurface(it, surface, false)
                    displaySurface?.makeCurrent()
                }
            }
        }
    }

    fun recreateDisplaySurface() {
        handler?.post {
            val currentSurface = displaySurface?.surface ?: return@post
            displaySurface?.release()
            eglCore?.let {
                displaySurface = WindowSurface(it, currentSurface, false)
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
            if (displaySurface != null) {
                displaySurface!!.makeCurrent()
            } else if (recordSurface != null) {
                recordSurface!!.makeCurrent()
            } else {
                // If neither surface is ready, ignore for now.
                // It will be applied again when setDisplaySurface is called.
                return@post
            }
            lutFilter?.loadLut(assetFileName)
        }
    }

    private fun initGL(surface: Surface) {
        eglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
        
        displaySurface = WindowSurface(eglCore!!, surface, false)
        displaySurface?.makeCurrent()

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

        // Camera sensor thường bị xoay hoặc lật, nhưng TransformMatrix từ SurfaceTexture đã lo việc xoay
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    private fun drawFrame() {
        val st = cameraSurfaceTexture ?: return
        
        if (displaySurface == null && recordSurface == null) return
        
        if (displaySurface != null) {
            displaySurface!!.makeCurrent()
        } else if (recordSurface != null) {
            recordSurface!!.makeCurrent()
        }
        
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

            val recordMvpMatrix = FloatArray(16)
            android.opengl.Matrix.setIdentityM(recordMvpMatrix, 0)
            android.opengl.Matrix.rotateM(recordMvpMatrix, 0, videoOrientation.toFloat(), 0f, 0f, 1f)

            lutFilter?.draw(cameraTextureId, recordMvpMatrix, transformMatrix)
            
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
        isGlInitialized = false
    }
}
