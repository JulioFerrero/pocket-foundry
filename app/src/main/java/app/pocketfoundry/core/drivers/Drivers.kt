package app.pocketfoundry.core.drivers

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.X11Prefs
import org.json.JSONObject
import java.io.File

/** The bundled graphics drivers (Zink on Turnip, Freedreno, the phone's own, the CPU) and the imported ones. */
object Drivers {
    /** [detail] sums it up in a line, [about] explains it and [worksOn] names its GPUs (imported ones leave those empty). */
    class Driver(val id: String, val name: String, val detail: String, val about: String = "", val worksOn: String = "") {
        val imported get() = id !in BUNDLED
    }

    /** How the last game with a driver ended. */
    enum class Outcome { RAN, STOPPED, DIDNT_START }

    private const val ZINK = "zink"
    private const val FREEDRENO = "freedreno"
    private const val PHONE = "phone"
    private const val CPU = "cpu"
    private val BUNDLED = setOf(ZINK, FREEDRENO, PHONE, CPU)
    private val ADRENO_ONLY = setOf(ZINK, FREEDRENO)
    private const val ADRENO = "Adreno 6xx and newer"

    fun all(context: Context): List<Driver> {
        val paths = Paths(context)
        val mesa = paths.lib.list()?.firstOrNull { it.startsWith("libgallium-") }
            ?.removePrefix("libgallium-")?.removeSuffix(".so")?.let { "Mesa $it" } ?: "Mesa"
        val bundled = listOf(
            Driver(ZINK, "Zink + Turnip", "Bundled · $mesa",
                   "Zink turns the game's OpenGL into Vulkan, and Mesa's Turnip drives the GPU. The fastest on Adreno.", ADRENO),
            Driver(FREEDRENO, "Freedreno", "Bundled · OpenGL, slower",
                   "Mesa's OpenGL driver for Adreno. Slower than Turnip: try it if Turnip shows glitches.", ADRENO),
            Driver(PHONE, "Phone's driver", "Experimental · Android's own, through libhybris",
                   "Zink on the Vulkan driver your phone already has, loaded through libhybris. The way in for Mali, " +
                   "PowerVR and Xclipse.", "GPUs with Vulkan 1.2"),
            Driver(CPU, "CPU", "Experimental · slow, uses a lot of battery",
                   "Mesa draws on the processor, without the GPU. Slow and hard on the battery.", "Any phone"),
        )
        return bundled + paths.drivers.listFiles().orEmpty().sortedByDescending { it.lastModified() }.mapNotNull(::load)
    }

    fun find(context: Context, id: String?) = all(context).firstOrNull { it.id == id }

    /** The driver list's sections: this GPU's drivers, those for any GPU, the imported ones, and those it can't use. */
    fun groups(drivers: List<Driver>): List<Pair<String, List<Driver>>> = listOf(
        "For your GPU" to drivers.filter { if (Gpu.supported) it.id in ADRENO_ONLY else it.id == PHONE },
        "Any GPU" to drivers.filter { it.id == CPU || (Gpu.supported && it.id == PHONE) },
        "Imported" to drivers.filter { it.imported },
        "Not for your GPU" to drivers.filter { !fits(it.id) },
    ).filter { it.second.isNotEmpty() }

    /** Turnip and Freedreno need an Adreno; the others run on any GPU, or none. */
    fun fits(id: String) = id !in ADRENO_ONLY || Gpu.supported

    /** An imported driver, or null if the folder holds none. */
    private fun load(folder: File): Driver? = runCatching {
        val info = JSONObject(File(folder, "driver.json").readText())
        Driver(folder.name, info.getString("name"), info.getString("detail"))
    }.getOrNull()

    /** The player's choice while it's still there, else the recommended driver. */
    fun chosen(context: Context): String =
        Settings(context).driver?.takeIf { it in BUNDLED || load(File(Paths(context).drivers, it)) != null }
            ?: recommended(context)

    /**
     * The driver for this phone: our Turnip on Adreno 6xx and newer, else Android's own Vulkan driver, else the CPU.
     * One that didn't start is passed over until a new runtime brings new drivers.
     */
    fun recommended(context: Context) =
        recommend(Gpu.supported, Gpu.hasVulkan(context)) { lastGame(context, it) == Outcome.DIDNT_START }

    internal fun recommend(adreno: Boolean, vulkan: Boolean, didntStart: (String) -> Boolean): String =
        listOfNotNull(ZINK.takeIf { adreno }, PHONE.takeIf { vulkan }).firstOrNull { !didntStart(it) } ?: CPU

    fun lastGame(context: Context, id: String): Outcome? =
        runCatching { Outcome.valueOf(json(Settings(context).driverGames).getString(id)) }.getOrNull()

    /** Remembers how the game with [id] ended. One that didn't start moves the automatic choice on. */
    fun record(context: Context, id: String, outcome: Outcome) = switching(context) {
        val settings = Settings(context)
        settings.driverGames = json(settings.driverGames).put(id, outcome.name).toString()
    }

    /** A new runtime brings new drivers: how the old ones did no longer counts. */
    fun forgetGames(context: Context) = switching(context) { Settings(context).driverGames = "{}" }

    fun cpu(context: Context) = chosen(context) == CPU

    /** Whether [id] is the phone's own driver, which MaliBridge's credit goes with. */
    fun isPhone(id: String) = id == PHONE

    /** Whether the game draws through Zink on [id]: all but Freedreno and the CPU. */
    fun usesZink(id: String) = id != FREEDRENO && id != CPU

    /** How Factorio compresses its textures: on the GPU, on the CPU, or not at all. */
    enum class Compression { GPU, CPU, NONE }

    /**
     * Factorio's GPU compressor fails its self-test on PanVK, whose BC emulation skips the compressor's copies (a G610
     * faulted right after), and through the compat layer's transcoder: the CPU compresses instead. Malis have no BC
     * formats: on their own driver the transcoder keeps the textures compressed where the driver check found it draws
     * them right ([transcoded]), else Mesa would only unpack what the CPU packed, so they load uncompressed.
     */
    fun compression(id: String, transcoded: Boolean) = when {
        id.startsWith("panvk-") -> Compression.CPU
        transcodable(id) -> if (transcoded) Compression.CPU else Compression.NONE
        else -> Compression.GPU
    }

    /** Whether the game's BC textures on [id] go through the compat layer's transcoder, if the driver check allows. */
    fun transcodable(id: String) = id == PHONE && Gpu.mali

    /** The player picked Turnip or Freedreno for a GPU that isn't an Adreno they run on. */
    fun missingForGpu(context: Context) = !fits(chosen(context))

    /** Choosing the recommended driver, or none, leaves the choice to the app, so it follows later releases. */
    fun choose(context: Context, id: String?) = switching(context) {
        Settings(context).driver = id?.takeIf { it != recommended(context) }
    }

    /** Runs [change], then sets the sprites, effects and resolution that suit the driver if it moved to or from the CPU. */
    private fun switching(context: Context, change: () -> Unit) {
        val wasCpu = cpu(context)
        change()
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
        // Zink on Android's own Vulkan driver, which libhybris loads (runtime/hybris). Its compat layer adds what Zink
        // needs and the driver may lack, like null descriptors on Mali.
        PHONE -> mapOf("VK_DRIVER_FILES" to File(paths.lib, "hybris/hybris_icd.json").path,
                       "VK_LAYER_PATH" to File(paths.lib, "hybris").path,
                       "VK_INSTANCE_LAYERS" to "VK_LAYER_HYBRIS_compat") + if (Gpu.mali) MALI else emptyMap()
        // llvmpipe: Mesa on the CPU. Software GLX still follows MESA_LOADER_DRIVER_OVERRIDE, so it must name llvmpipe too.
        // 256-bit code (two NEON registers at a time) and bilinear filtering were the fastest that still looked right.
        CPU -> mapOf("LIBGL_ALWAYS_SOFTWARE" to "1", "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
                     "GALLIUM_DRIVER" to "llvmpipe", "LP_NATIVE_VECTOR_WIDTH" to "256", "LP_PERF" to "no_mip_linear")
        // Imported drivers lack our wait-before-present patch; the software path waits for the GPU instead.
        else -> mapOf("VK_DRIVER_FILES" to File(paths.drivers, "$id/icd.json").path, "MESA_VK_WSI_DEBUG" to "sw")
    }

    /**
     * Factorio needs OpenGL 3.3, which Zink offers only on Vulkan drivers with depth clamp and vertex attribute
     * divisors; some Malis' lack one (G52, G610). Asked for anyway, a G610 got its context: without the extension, Zink
     * steps each instanced attribute once per instance. The game also runs on 3.3 where the driver offers more.
     * The compat layer's stand-in for the BC textures Malis lack failed on every one Factorio made (a G925, and the
     * vivo with it forced): Mesa unpacks them itself instead.
     */
    private val MALI = mapOf("MESA_GL_VERSION_OVERRIDE" to "3.3",
                             "MESA_EXTENSION_OVERRIDE" to "+GL_ARB_instanced_arrays",
                             "HYBRIS_BC_TEXTURES" to "0")

    /** The libraries an imported driver brought, searched after the runtime's. */
    fun libraries(paths: Paths, id: String): File? = if (id in BUNDLED) null else File(paths.drivers, id)

    /** Settings -> Driver -> Options for [id]: `NAME=value` pairs for the game's environment, to test the driver with. */
    fun options(context: Context, id: String): String = json(Settings(context).driverOptionsById).optString(id)

    fun setOptions(context: Context, id: String, text: String) {
        val settings = Settings(context)
        val all = json(settings.driverOptionsById)
        if (text.isEmpty()) all.remove(id) else all.put(id, text)
        settings.driverOptionsById = all.toString()
    }

    /** The options of the driver the game runs on. */
    fun options(context: Context): Map<String, String> = parseOptions(options(context, chosen(context)))

    /** The old Options field, one for every driver, becomes the options of the driver in use. */
    fun moveOldOptions(context: Context) {
        val settings = Settings(context)
        if (settings.driverOptions.isEmpty()) return
        setOptions(context, chosen(context), settings.driverOptions)
        settings.driverOptions = ""
    }

    private fun json(text: String) = runCatching { JSONObject(text) }.getOrElse { JSONObject() }

    internal fun parseOptions(text: String): Map<String, String> = text.split(Regex("\\s+"))
        .mapNotNull { OPTION.matchEntire(it)?.destructured }.associate { (name, value) -> name to value }

    private val OPTION = Regex("""([A-Za-z_][A-Za-z0-9_]*)=(\S*)""")

    fun remove(context: Context, id: String) = File(Paths(context).drivers, id).deleteRecursively()
}

/** The phone's GPU, as Android's own OpenGL ES driver names it. */
object Gpu {
    /** What Android's own OpenGL ES driver says about itself. */
    private class Info(val renderer: String?, val version: String?, val extensions: Set<String>)

    /** Null if Android's driver couldn't be asked. */
    private val info: Info? by lazy {
        runCatching { query() }.onFailure { Log.w(TAG, "Couldn't ask the GPU's name", it) }.getOrNull()
    }

    val name: String? get() = info?.renderer

    /** As Android's OpenGL ES driver gives it ("OpenGL ES 3.2 v1.r38p1-…"): a phone update can change it. */
    val driverVersion: String? get() = info?.version

    /** The compressed texture formats the GPU has, by family: the game's BC, and Android's own ETC2 and ASTC. */
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

    /** Android's Vulkan is 1.2 or newer, with the timeline semaphores Zink needs: worth trying the phone's driver. */
    fun hasVulkan(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_2)

    private const val VULKAN_1_2 = 0x402000

    internal fun isSupported(renderer: String?): Boolean {
        val name = renderer ?: return true
        if (!name.contains("Adreno")) return false
        val number = ADRENO.find(name)?.groupValues?.get(1)?.toInt() ?: return true
        return number >= 600
    }

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
