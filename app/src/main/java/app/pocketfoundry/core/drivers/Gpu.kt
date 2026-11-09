package app.pocketfoundry.core.drivers

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import app.pocketfoundry.core.system.TAG

object Gpu {
    private class Info(val renderer: String?, val version: String?, val extensions: Set<String>)

    private val info: Info? by lazy {
        runCatching { query() }.onFailure { Log.w(TAG, "Couldn't ask the GPU's name", it) }.getOrNull()
    }

    val name: String? get() = info?.renderer

    /** As Android's OpenGL ES driver gives it ("OpenGL ES 3.2 v1.r38p1-…"): a phone update can change it. */
    val driverVersion: String? get() = info?.version

    /** BC is the game's format. ETC2 and ASTC are Android's own. */
    val textureFormats: List<String> get() = info?.let {
        listOfNotNull("BC".takeIf { _ -> "GL_EXT_texture_compression_s3tc" in it.extensions },
                      "ETC2".takeIf { _ -> it.version?.startsWith("OpenGL ES 3") == true },
                      "ASTC".takeIf { _ -> "GL_KHR_texture_compression_astc_ldr" in it.extensions })
    }.orEmpty()

    /**
     * Turnip starts at Adreno 6xx (older ones also lack the kernel's dma-buf heaps freedreno needs). A GPU that
     * couldn't be named, or an Adreno named without three digits (the laptops' X1-85), gets the benefit of the doubt.
     */
    val supported: Boolean get() = isSupported(name)

    /** Arm's Mali, which the phone's driver sets up for OpenGL 3.3 and uncompressed textures. */
    val mali: Boolean get() = name?.contains("Mali") == true

    /**
     * Android's Vulkan is 1.1 or newer: worth trying the phone's driver. One without the timeline semaphores Zink
     * needs, which came with 1.2, gets them from Khronos's layer (DriverCheck).
     */
    fun hasVulkan(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)

    private const val VULKAN_1_1 = 0x401000

    internal fun isSupported(renderer: String?): Boolean {
        val name = renderer ?: return true
        if (!name.contains("Adreno")) return false
        val number = adrenoNumber(name) ?: return true
        return number >= 600
    }

    /** "Adreno (TM) 740" -> 740. */
    val adreno: Int? get() = adrenoNumber(name)

    internal fun adrenoNumber(renderer: String?) = renderer?.let { ADRENO.find(it) }?.groupValues?.get(1)?.toInt()

    private val ADRENO = Regex("""Adreno \(TM\) (\d{3})""")

    /** No eglTerminate: the display is shared with the rest of the app, which draws with it. */
    private fun query(): Info {
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
            fun string(name: Int) = GLES20.glGetString(name)?.trim()?.takeIf { it.isNotEmpty() }
            return Info(string(GLES20.GL_RENDERER), string(GLES20.GL_VERSION),
                        string(GLES20.GL_EXTENSIONS)?.split(' ')?.toSet().orEmpty())
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}
