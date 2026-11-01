package app.pocketfoundry.drivers

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import app.pocketfoundry.Paths
import app.pocketfoundry.Settings
import app.pocketfoundry.TAG
import app.pocketfoundry.X11Prefs
import org.json.JSONObject
import java.io.File

/** The bundled graphics drivers (Zink on Turnip, Freedreno, the CPU) and the ones the player imported. */
object Drivers {
    class Driver(val id: String, val name: String, val detail: String)

    private const val ZINK = "zink"
    private const val FREEDRENO = "freedreno"
    private const val CPU = "cpu"
    private val BUNDLED = setOf(ZINK, FREEDRENO, CPU)

    fun all(context: Context): List<Driver> {
        val paths = Paths(context)
        val mesa = paths.lib.list()?.firstOrNull { it.startsWith("libgallium-") }
            ?.removePrefix("libgallium-")?.removeSuffix(".so")?.let { "Mesa $it" } ?: "Mesa"
        val bundled = listOf(
            Driver(ZINK, "Zink + Turnip", "Bundled · $mesa"),
            Driver(FREEDRENO, "Freedreno", "Bundled · OpenGL, slower"),
            Driver(CPU, "CPU", "Experimental · slow, uses a lot of battery"),
        )
        return bundled + paths.drivers.listFiles().orEmpty().sortedByDescending { it.lastModified() }.mapNotNull(::load)
    }

    /** An imported driver, or null if the folder holds none. */
    private fun load(folder: File): Driver? = runCatching {
        val info = JSONObject(File(folder, "driver.json").readText())
        Driver(folder.name, info.getString("name"), info.getString("detail"))
    }.getOrNull()

    /** The player's choice while it's still there. Without one: Zink, or the CPU on a GPU we have no driver for. */
    fun chosen(context: Context): String {
        val id = Settings(context).driver ?: return if (Gpu.supported) ZINK else CPU
        return if (id in BUNDLED || load(File(Paths(context).drivers, id)) != null) id else ZINK
    }

    fun imported(context: Context) = chosen(context) !in BUNDLED

    fun cpu(context: Context) = chosen(context) == CPU

    fun isPanvk(id: String) = id.startsWith("panvk-")

    /** The phone's GPU has no bundled driver, and the player hasn't picked the CPU or an imported one. */
    fun missingForGpu(context: Context) = !Gpu.supported && chosen(context).let { it == ZINK || it == FREEDRENO }

    /** A new driver, with the sprites, effects and resolution that suit it when it moves to or from the CPU. */
    fun choose(context: Context, id: String?) {
        val wasCpu = cpu(context)
        Settings(context).driver = id
        val cpu = cpu(context)
        if (cpu == wasCpu) return
        Settings(context).setDriverDefaults(cpu)
        val x11 = X11Prefs(context)
        x11.resolutionScale = x11.startingResolutionScale
    }

    /** Sprites and effects the player never set start as what suits the driver. */
    fun fillDefaults(context: Context) = Settings(context).setDriverDefaults(cpu(context), onlyUnset = true)

    /** The game's graphics environment: Zink on our Turnip, unless the driver says otherwise. */
    fun environment(paths: Paths, id: String): Map<String, String> = mapOf(
        "VK_DRIVER_FILES" to File(paths.prefix, "share/vulkan/icd.d/freedreno_icd.aarch64.json").path,
        "__EGL_VENDOR_LIBRARY_DIRS" to File(paths.prefix, "share/glvnd/egl_vendor.d").path,
        "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
    ) + when (id) {
        ZINK -> emptyMap()
        FREEDRENO -> mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "kgsl")
        // llvmpipe: Mesa on the CPU. Software GLX still follows MESA_LOADER_DRIVER_OVERRIDE, so it must name llvmpipe too.
        // 256-bit code (two NEON registers at a time) and bilinear filtering were the fastest that still looked right.
        CPU -> mapOf("LIBGL_ALWAYS_SOFTWARE" to "1", "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
                     "GALLIUM_DRIVER" to "llvmpipe", "LP_NATIVE_VECTOR_WIDTH" to "256", "LP_PERF" to "no_mip_linear")
        // Imported drivers lack our wait-before-present patch; the software path waits for the GPU instead.
        else -> mapOf("VK_DRIVER_FILES" to File(paths.drivers, "$id/icd.json").path, "MESA_VK_WSI_DEBUG" to "sw")
    }

    /** The libraries an imported driver brought, searched after the runtime's. */
    fun libraries(paths: Paths, id: String): File? = if (id in BUNDLED) null else File(paths.drivers, id)

    /** Settings -> Driver -> Options: `NAME=value` pairs for the game's environment, to test drivers with. */
    fun options(context: Context): Map<String, String> = parseOptions(Settings(context).driverOptions)

    internal fun parseOptions(text: String): Map<String, String> = text.split(Regex("\\s+"))
        .mapNotNull { OPTION.matchEntire(it)?.destructured }.associate { (name, value) -> name to value }

    private val OPTION = Regex("""([A-Za-z_][A-Za-z0-9_]*)=(\S*)""")

    fun remove(context: Context, id: String) = File(Paths(context).drivers, id).deleteRecursively()
}

/** The phone's GPU, as Android's own OpenGL ES driver names it. */
object Gpu {
    /** Null if Android's driver couldn't be asked. */
    val name: String? by lazy {
        runCatching { renderer() }.onFailure { Log.w(TAG, "Couldn't ask the GPU's name", it) }.getOrNull()
    }

    /**
     * Turnip starts at Adreno 6xx (older ones also lack the kernel's dma-buf heaps freedreno needs). A GPU that
     * couldn't be named, or an Adreno named without three digits (the laptops' X1-85), gets the benefit of the doubt.
     */
    val supported: Boolean get() = isSupported(name)

    internal fun isSupported(renderer: String?): Boolean {
        val name = renderer ?: return true
        if (!name.contains("Adreno")) return false
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
