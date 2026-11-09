package app.pocketfoundry.core.drivers

import android.content.Context
import android.os.Build
import app.pocketfoundry.core.runtime.DebugEnv
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.X11Prefs
import java.io.File

object Drivers {
    /** Imported drivers leave [about] and [worksOn] empty. */
    class Driver(val id: String, val name: String, val category: String, val note: String, val about: String = "",
                 val worksOn: String = "") {
        val imported get() = bundled(id) == null
        val detail get() = "$category · $note"
    }

    enum class Outcome { RAN, STOPPED, DIDNT_START }

    /** Ours: [adrenoOnly] needs an Adreno that runs Turnip, [zink] draws the game through Zink (imported ones do). */
    private enum class Bundled(val id: String, val adrenoOnly: Boolean, val zink: Boolean) {
        ZINK("zink", adrenoOnly = true, zink = true),
        FREEDRENO("freedreno", adrenoOnly = true, zink = false),
        PHONE("phone", adrenoOnly = false, zink = true),
        CPU("cpu", adrenoOnly = false, zink = false),
    }

    private fun bundled(id: String) = Bundled.entries.find { it.id == id }

    private const val ADRENO = "Adreno 6xx and newer"
    /** Read by our libhybris patches 0003 and 0004 (runtime/hybris/patches). */
    internal const val HYBRIS_PROPERTIES = "HYBRIS_PROPERTY_FILE"
    internal const val HYBRIS_VNDK = "HYBRIS_VNDK_VERSION"

    fun all(context: Context): List<Driver> {
        val paths = Paths(context)
        val mesa = paths.lib.list()?.firstOrNull { it.startsWith("libgallium-") }
            ?.removePrefix("libgallium-")?.removeSuffix(".so")?.let { "Mesa $it" } ?: "Mesa"
        val bundled = Bundled.entries.map {
            when (it) {
                Bundled.ZINK -> Driver(it.id, "Zink + Turnip", "Bundled", mesa,
                    "Zink turns the game's OpenGL into Vulkan, and Mesa's Turnip drives the GPU. The fastest on Adreno.",
                    ADRENO)
                Bundled.FREEDRENO -> Driver(it.id, "Freedreno", "Bundled", "OpenGL, slower",
                    "Mesa's OpenGL driver for Adreno. Slower than Turnip: try it if Turnip shows glitches.",
                    ADRENO)
                Bundled.PHONE -> Driver(it.id, "Phone's driver", "Experimental",
                    "Android's own, through libhybris",
                    "Zink on the Vulkan driver your phone already has, loaded through libhybris. The way in for Mali, " +
                    "PowerVR and Xclipse.", "GPUs with Vulkan 1.1")
                Bundled.CPU -> Driver(it.id, "CPU", "Experimental", "slow, uses a lot of battery",
                    "Mesa draws on the processor, without the GPU. Slow and hard on the battery.", "Any phone")
            }
        }
        return bundled + DriverImport.all(paths)
    }

    fun find(context: Context, id: String?) = all(context).firstOrNull { it.id == id }

    /** The Adreno-only drivers on an Adreno, else the phone's own: the list's first section. */
    fun forThisGpu(driver: Driver): Boolean {
        val ours = bundled(driver.id) ?: return false
        return if (Gpu.supported) ours.adrenoOnly else ours == Bundled.PHONE
    }

    fun forAnyGpu(driver: Driver): Boolean {
        val ours = bundled(driver.id) ?: return false
        return !ours.adrenoOnly && !forThisGpu(driver)
    }

    fun fits(id: String) = bundled(id)?.adrenoOnly != true || Gpu.supported

    /** A choice the GPU can't run (a phone moved to another GPU) isn't one. */
    fun chosen(context: Context): String =
        DriverRecords.choice(context)
            ?.takeIf { if (bundled(it) != null) fits(it) else DriverImport.has(Paths(context), it) }
            ?: recommended(context)

    /** A driver that didn't start is passed over until a new runtime brings new drivers. */
    fun recommended(context: Context) =
        recommend(Gpu.supported, Gpu.hasVulkan(context)) { DriverRecords.outcome(context, it) == Outcome.DIDNT_START }

    internal fun recommend(adreno: Boolean, vulkan: Boolean, didntStart: (String) -> Boolean): String =
        listOfNotNull(Bundled.ZINK.takeIf { adreno }, Bundled.PHONE.takeIf { vulkan })
            .firstOrNull { !didntStart(it.id) }?.id ?: Bundled.CPU.id

    fun record(context: Context, id: String, outcome: Outcome) =
        switching(context) { DriverRecords.setOutcome(context, id, outcome) }

    fun cpu(context: Context) = chosen(context) == Bundled.CPU.id

    /** MaliBridge's fixes come with the phone's own driver. */
    fun isPhone(id: String) = id == Bundled.PHONE.id

    fun usesZink(id: String) = bundled(id)?.zink ?: true

    enum class Compression { GPU, CPU, NONE }

    /**
     * Factorio's GPU compressor fails its self-test on PanVK, whose BC emulation skips the compressor's copies (a G610
     * faulted right after), and through the compat layer's transcoder: the CPU compresses instead. Malis have no BC
     * formats: on their own driver the transcoder keeps the textures compressed where the driver check found it draws
     * them right ([transcoded]), else Mesa would only unpack what the CPU packed, so they load uncompressed.
     */
    fun compression(id: String, transcoded: Boolean) = when {
        DriverImport.isPanVK(id) -> Compression.CPU
        transcodable(id) -> if (transcoded) Compression.CPU else Compression.NONE
        else -> Compression.GPU
    }

    /** Whether the game's BC textures on [id] go through the compat layer's transcoder, if the driver check allows. */
    fun transcodable(id: String) = isPhone(id) && Gpu.mali

    /** Choosing the recommended driver, or none, leaves the choice to the app, so it follows later releases. */
    fun choose(context: Context, id: String?) = switching(context) {
        DriverRecords.setChoice(context, id?.takeIf { it != recommended(context) })
    }

    fun remove(context: Context, id: String) {
        if (id == chosen(context)) choose(context, null)
        DriverImport.remove(Paths(context), id)
        DriverRecords.remove(context, id)
    }

    private fun switching(context: Context, change: () -> Unit) {
        val wasCpu = cpu(context)
        change()
        val cpu = cpu(context)
        if (cpu == wasCpu) return
        setGraphicsDefaults(context, cpu, onlyUnset = false)
    }

    /** At the app's start; Termux:X11's prefs are set up by then. */
    fun setUp(context: Context) {
        setGraphicsDefaults(context, cpu(context), onlyUnset = true)
        DriverRecords.moveOldOptions(context, chosen(context))
    }

    /**
     * The CPU driver gets Medium sprites, Light graphics and the coarser resolution it was made for. Malis get Medium
     * sprites too: High crashed PanVK while loading, and their own driver loads them unpacked. [onlyUnset] keeps what
     * the player chose.
     */
    private fun setGraphicsDefaults(context: Context, cpu: Boolean, onlyUnset: Boolean) {
        Settings(context).setGraphicsDefaults(if (cpu || Gpu.mali) Settings.MEDIUM else Settings.HIGH, cpu, onlyUnset)
        val x11 = X11Prefs(context)
        if (!onlyUnset || !x11.hasResolutionScale) x11.resolutionScale = x11.startingResolutionScale(cpu)
    }

    /** [id]'s Options and DriverCheck's findings go on top of this. */
    fun environment(paths: Paths, id: String): Map<String, String> = mapOf(
        "VK_DRIVER_FILES" to paths.turnipIcd.path,
        "__EGL_VENDOR_LIBRARY_DIRS" to paths.eglVendors.path,
        "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
    ) + when (bundled(id)) {
        Bundled.ZINK -> emptyMap()
        Bundled.FREEDRENO -> mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "kgsl")
        // Zink on Android's own Vulkan driver, which libhybris loads (runtime/hybris). Its compat layer adds what Zink
        // needs and the driver may lack, like null descriptors on Mali.
        Bundled.PHONE -> mapOf("VK_DRIVER_FILES" to paths.hybrisIcd.path,
                               "VK_LAYER_PATH" to paths.hybris.path,
                               HYBRIS_PROPERTIES to paths.androidProperties.path,
                               // Android 10's linker config is named by it: libhybris's linker can't read properties.
                               HYBRIS_VNDK to Runtime.properties["ro.vndk.version"].orEmpty(),
                               "VK_INSTANCE_LAYERS" to "VK_LAYER_HYBRIS_compat") + if (Gpu.mali) MALI else emptyMap()
        // llvmpipe: Mesa on the CPU. Software GLX still follows MESA_LOADER_DRIVER_OVERRIDE, so it must name llvmpipe
        // too. 256-bit code (two NEON registers at a time) and bilinear filtering were the fastest that still looked
        // right.
        Bundled.CPU -> mapOf("LIBGL_ALWAYS_SOFTWARE" to "1", "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
                             "GALLIUM_DRIVER" to "llvmpipe", "LP_NATIVE_VECTOR_WIDTH" to "256",
                             "LP_PERF" to "no_mip_linear")
        // Imported drivers lack our wait-before-present patch; the software path waits for the GPU instead.
        null -> mapOf("VK_DRIVER_FILES" to DriverImport.icd(paths, id).path, "MESA_VK_WSI_DEBUG" to "sw")
    } + if (noImageCompression(Build.VERSION.SDK_INT) { Gpu.supported }) NO_IMAGE_COMPRESSION else emptyMap()

    /**
     * On Android 16 an Adreno 740 (Galaxy S23s, Tab S9 Ultra, POCO F6 Pro) flashes black, and leaves black lines in
     * the terrain, while Turnip or Freedreno compress images (UBWC); the same tablet on Android 15 doesn't. Of
     * everything Turnip can switch, only turning compression off helped, and an S23 Ultra kept 60 FPS without it.
     */
    internal fun noImageCompression(android: Int, adreno: () -> Boolean) = android >= ANDROID_16 && adreno()

    private const val ANDROID_16 = 36
    private val NO_IMAGE_COMPRESSION = mapOf("TU_DEBUG" to "noubwc", "FD_MESA_DEBUG" to "noubwc")

    /**
     * Factorio needs OpenGL 3.3, which Zink offers only on Vulkan drivers with depth clamp and vertex attribute
     * divisors; some Malis' lack one (G52, G610). Asked for anyway, a G610 got its context: without the extension, Zink
     * steps each instanced attribute once per instance. The game also runs on 3.3 where the driver offers more.
     * The compat layer's stand-in for the BC textures Malis lack failed on every one Factorio made (a G925, and the
     * vivo with it forced): Mesa unpacks them itself instead, unless DriverCheck found its transcoder draws them right.
     */
    private val MALI = mapOf("MESA_GL_VERSION_OVERRIDE" to "3.3",
                             "MESA_EXTENSION_OVERRIDE" to "+GL_ARB_instanced_arrays",
                             DriverCheck.TRANSCODER to "0")

    fun libraries(paths: Paths, id: String): File? = if (bundled(id) != null) null else DriverImport.folder(paths, id)

    /**
     * What's laid over [id]'s environment, last: Settings -> Driver -> Options for it (DriverRecords), `NAME=value`
     * pairs to test the driver with, then debug.env's game lines.
     */
    fun overrides(context: Context, id: String): Map<String, String> =
        parseOptions(DriverRecords.options(context, id)) + DebugEnv.read(context, "game")

    internal fun parseOptions(text: String): Map<String, String> = text.split(Regex("\\s+"))
        .mapNotNull { OPTION.matchEntire(it)?.destructured }.associate { (name, value) -> name to value }

    private val OPTION = Regex("""([A-Za-z_][A-Za-z0-9_]*)=(\S*)""")
}
