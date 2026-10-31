package app.pocketfoundry

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log

/** The phone's GPU, as Android's own OpenGL ES driver names it. */
object Gpu {
    /** Null if Android's driver couldn't be asked. */
    val name: String? by lazy {
        runCatching { renderer() }.onFailure { Log.w("PocketFoundry", "Couldn't ask the GPU's name", it) }.getOrNull()
    }

    /**
     * Turnip starts at Adreno 6xx (older ones also lack the kernel's dma-buf heaps freedreno needs). A GPU that
     * couldn't be named, or an Adreno named without three digits (the laptops' X1-85), gets the benefit of the doubt.
     */
    val supported: Boolean get() {
        val name = name ?: return true
        if (!name.contains("Adreno") && !name.contains("Mali")) return false
        val number = ADRENO.find(name)?.groupValues?.get(1)?.toInt() ?: return true
        return number >= 600
    }

    private val ADRENO = Regex("""Adreno \(TM\) (\d{3})""")

    /** No eglTerminate: the display is shared with the rest of the app, which draws with it. */
    private fun renderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, null, 0, null, 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val wanted = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                                             intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            return GLES20.glGetString(GLES20.GL_RENDERER)?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}
