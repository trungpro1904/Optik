package com.example.optik.camera.gl

import android.opengl.EGLSurface
import android.view.Surface

/**
 * Recordable EGL window surface.
 *
 * It's good practice to explicitly release() the surface, preferably from a "finally" block.
 */
class WindowSurface(private val eglCore: EglCore, private val surface: Surface, private val releaseSurface: Boolean) {

    private var eglSurface: EGLSurface = eglCore.createWindowSurface(surface)

    /**
     * Releases any resources associated with the EGL surface (and, if configured to do so,
     * with the Surface as well).
     *
     * Does not require that the surface's EGL context be current.
     */
    fun release() {
        eglCore.releaseSurface(eglSurface)
        if (releaseSurface) {
            surface.release()
        }
    }

    /**
     * Makes our EGL context and surface current.
     */
    fun makeCurrent() {
        eglCore.makeCurrent(eglSurface)
    }

    /**
     * Calls eglSwapBuffers. Use this to "publish" the current frame.
     *
     * @return false on failure
     */
    fun swapBuffers(): Boolean {
        return eglCore.swapBuffers(eglSurface)
    }

    /**
     * Sends the presentation time stamp to EGL. Time is expressed in nanoseconds.
     */
    fun setPresentationTime(nsecs: Long) {
        eglCore.setPresentationTime(eglSurface, nsecs)
    }
}
