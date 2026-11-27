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

    /** The GPU's name, as players and the driver list know it (shownName). */
    val name: String? get() = info?.renderer?.let(::shownName)

    /** As Android's OpenGL ES gives it, ANGLE's wrapping and all: for the logs. */
    val renderer: String? get() = info?.renderer

    /**
     * The GPU's own name, where Android's OpenGL ES is ANGLE and puts it inside its own: "ANGLE ((Samsung Xclipse 940)
     * on Vulkan 1.3.279)" is "Samsung Xclipse 940", and "ANGLE (Imagination Technologies, Vulkan 1.4.317 (PowerVR
     * C-Series CXTP-48-1536 MC1 (0x70061042)), PowerVR C-Series Vulkan Driver-1.662.3024)" a Pixel 11's "PowerVR
     * C-Series CXTP-48-1536 MC1". Home and the welcome screen said the whole of it.
     */
    internal fun shownName(renderer: String): String {
        if (!renderer.startsWith("ANGLE (")) return renderer
        SAMSUNG_ANGLE.find(renderer)?.let { return it.groupValues[1] }
        ANGLE_DEVICE.find(renderer)?.let { return it.groupValues[1] }
        return renderer
    }

    private val SAMSUNG_ANGLE = Regex("""^ANGLE \(\((.+?)\) on Vulkan""")
    private val ANGLE_DEVICE = Regex("""Vulkan [\d.]+ \((.+?) \(0x[0-9a-fA-F]+\)\)""")

    /** As Android's OpenGL ES driver gives it ("OpenGL ES 3.2 v1.r38p1-…"): a phone update can change it. */
    val driverVersion: String? get() = info?.version

    /** BC is the game's format. ETC2 and ASTC are Android's own. */
    val textureFormats: List<String> get() = info?.let {
        listOfNotNull("BC".takeIf { _ -> "GL_EXT_texture_compression_s3tc" in it.extensions },
                      "ETC2".takeIf { _ -> it.version?.startsWith("OpenGL ES 3") == true },
                      "ASTC".takeIf { _ -> "GL_KHR_texture_compression_astc_ldr" in it.extensions })
    }.orEmpty()

    /**
     * Android's Vulkan is 1.1 or newer: worth trying the phone's driver. One without the timeline semaphores Zink
     * needs, which came with 1.2, gets them from Khronos's layer (DriverCheck).
     */
    fun hasVulkan(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)

    private const val VULKAN_1_1 = 0x401000

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
