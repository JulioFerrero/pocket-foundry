package app.pocketfoundry.core.drivers

import android.content.Context
import android.util.Log
import app.pocketfoundry.core.runtime.DebugEnv
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.X11Prefs
import java.io.File
import java.io.IOException

/**
 * The app has two drivers of its own, the phone's and the CPU. The GPU drivers come from the catalog (Catalog), which
 * the app downloads before a game; players can import others.
 */
object Drivers {
    enum class Origin { APP, CATALOG, IMPORTED }

    /** [entry]: a catalog driver's, from the catalog, or from its install when the catalog no longer lists it. */
    class Driver(val id: String, val name: String, val origin: Origin, val note: String, val about: String = "",
                 val forGpus: String = "", val entry: Catalog.Entry? = null)

    enum class Outcome { RAN, STOPPED, DIDNT_START }

    const val PHONE = "phone"
    const val CPU = "cpu"

    /** Read by our libhybris patches 0003 and 0004 (runtime/hybris/patches). */
    internal const val HYBRIS_PROPERTIES = "HYBRIS_PROPERTY_FILE"
    internal const val HYBRIS_VNDK = "HYBRIS_VNDK_VERSION"
    /** The app's Turnip until 0.8.18: its records and choice moved to the catalog's. */
    private const val OLD_TURNIP = "zink"

    fun all(context: Context): List<Driver> {
        val paths = Paths(context)
        val listed = Catalog.get(context).drivers
        val gone = DriverImport.downloaded(paths).filter { installed -> listed.none { it.id == installed.id } }
        return app() + (listed + gone).map(::fromCatalog) + DriverImport.all(paths)
    }

    private fun app() = listOf(
        Driver(PHONE, "Phone's driver", Origin.APP, "Android's own, through libhybris",
               "Zink on the Vulkan driver your phone already has, loaded through libhybris. The way in for Mali, " +
               "PowerVR and Xclipse.", "Any GPU"),
        Driver(CPU, "CPU", Origin.APP, "slow, uses a lot of battery",
               "Mesa draws on the processor, without the GPU. Slow and hard on the battery.", "Any GPU"),
    )

    private fun fromCatalog(entry: Catalog.Entry) =
        Driver(entry.id, entry.name, Origin.CATALOG, entry.version, entry.about, entry.forGpus, entry)

    fun find(context: Context, id: String?) = all(context).firstOrNull { it.id == id }

    /** Listed For your GPU. */
    fun forThisGpu(driver: Driver) = driver.entry?.let { Catalog.fits(it, Gpu.name, Catalog.android) } == true

    /** Listed To try: made for another GPU of the same family. */
    fun toTry(driver: Driver) = driver.entry?.let { Catalog.mightFit(it, Gpu.name, Catalog.android) } == true

    /** A catalog driver for neither this GPU nor its family can't be used. */
    fun fits(driver: Driver) = driver.origin != Origin.CATALOG || forThisGpu(driver) || toTry(driver)

    /** A choice that can't be used any more (a phone moved to another GPU, a driver removed) isn't one. */
    fun chosen(context: Context): String =
        DriverRecords.choice(context)?.takeIf { id -> find(context, id)?.let(::fits) == true } ?: recommended(context)

    /** A driver that didn't start is passed over until a new runtime brings new drivers. */
    fun recommended(context: Context): String {
        val fitting = Catalog.get(context).drivers.filter { Catalog.fits(it, Gpu.name, Catalog.android) }
            .sortedBy { it.priority }.map { it.id }
        return recommend(fitting, Gpu.hasVulkan(context)) { DriverRecords.outcome(context, it) == Outcome.DIDNT_START }
    }

    /** [fitting]: the catalog's drivers for this GPU, best first. */
    internal fun recommend(fitting: List<String>, vulkan: Boolean, didntStart: (String) -> Boolean): String =
        (fitting + listOfNotNull(PHONE.takeIf { vulkan })).firstOrNull { !didntStart(it) } ?: CPU

    fun record(context: Context, id: String, outcome: Outcome) =
        switching(context) { DriverRecords.setOutcome(context, id, outcome) }

    fun cpu(context: Context) = chosen(context) == CPU

    /** MaliBridge's fixes come with the phone's own driver. */
    fun isPhone(id: String) = id == PHONE

    /** Whether the game reaches [id] through Zink: all but the CPU and the catalog's OpenGL drivers. */
    fun usesZink(paths: Paths, id: String) =
        id != CPU && DriverImport.downloaded(paths, id)?.kind != Catalog.Kind.OPENGL

    /** Not downloaded yet, or the catalog has a newer build of it: the next game downloads [id] first. */
    fun pending(context: Context, id: String): Catalog.Entry? {
        val entry = Catalog.get(context).find(id) ?: return null
        return entry.takeIf { DriverImport.downloaded(Paths(context), id)?.sha256 != it.sha256 }
    }

    /**
     * Off the main thread, before a game: the chosen driver's package if it's [pending]. A newer build that can't be
     * downloaded waits for the next game; a driver that isn't there at all can't play.
     */
    @Synchronized
    fun prepare(context: Context, onPercent: (name: String, percent: Int) -> Unit) {
        val id = chosen(context)
        val entry = pending(context, id) ?: return
        val installed = DriverImport.downloaded(Paths(context), id) != null
        try {
            download(context, entry) { onPercent(entry.name, it) }
        } catch (e: IOException) {
            if (installed) {
                Log.w(TAG, "Keeping the installed ${entry.name}: the newer one didn't download", e)
                return
            }
            throw IOException("${entry.name} didn't download: ${e.message}. Check the connection and try again.", e)
        }
    }

    /** Off the main thread: [entry]'s package, checked against the catalog. */
    fun download(context: Context, entry: Catalog.Entry, onPercent: (Int) -> Unit) {
        DriverImport.download(context, entry, onPercent)
        // A new build starts with nothing known about how it played.
        DriverRecords.forgetGames(context, entry.id)
    }

    enum class Compression { GPU, CPU, NONE }

    /**
     * Factorio's GPU compressor fails its self-test on PanVK, whose BC emulation skips the compressor's copies (a G610
     * faulted right after), and through the compat layer's transcoder: the CPU compresses instead. Malis have no BC
     * formats: on their own driver the transcoder keeps the textures compressed where the driver check found it draws
     * them right ([transcoded]), else Mesa would only unpack what the CPU packed, so they load uncompressed.
     */
    fun compression(paths: Paths, id: String, transcoded: Boolean) = when {
        DriverImport.downloaded(paths, id)?.cpuCompression == true || DriverImport.isPanVK(id) -> Compression.CPU
        transcodable(id) -> if (transcoded) Compression.CPU else Compression.NONE
        else -> Compression.GPU
    }

    /** Whether the game's BC textures on [id] go through the compat layer's transcoder, if the driver check allows. */
    fun transcodable(id: String) = isPhone(id) && Gpu.mali

    /**
     * Off the main thread: the newest catalog, which can change the recommended driver (a first start without it
     * has only the phone's and the CPU). Offline, the saved one stays.
     */
    fun refreshCatalog(context: Context) = switching(context) {
        runCatching { Catalog.refresh(context) }.onFailure { Log.w(TAG, "No driver list: ${it.message}") }
    }

    /** Choosing the recommended driver, or none, leaves the choice to the app, so it follows the catalog. */
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
        DriverRecords.rename(context, OLD_TURNIP, "turnip")
        setGraphicsDefaults(context, cpu(context), onlyUnset = true)
        DriverRecords.moveOldOptions(context, chosen(context))
    }

    /**
     * The CPU driver gets Medium sprites, Light graphics and the coarser resolution it was made for. Malis get Medium
     * sprites too: High crashed PanVK while loading, and their own driver loads them unpacked. A GPU driver gets the
     * resolution its speed test found, and Light graphics if even the coarsest was too slow. [onlyUnset] keeps what
     * the player chose.
     */
    private fun setGraphicsDefaults(context: Context, cpu: Boolean, onlyUnset: Boolean) {
        val settings = Settings(context)
        val fit = if (cpu) null
                  else SpeedTest.fit(context, DriverCheck.speed(context, chosen(context)), settings.frameRate)
        settings.setGraphicsDefaults(if (cpu || Gpu.mali) Settings.MEDIUM else Settings.HIGH, cpu || fit?.light == true,
                                     onlyUnset)
        val x11 = X11Prefs(context)
        if (!onlyUnset || !x11.hasResolutionScale) x11.resolutionScale = fit?.scale ?: x11.startingResolutionScale(cpu)
    }

    /**
     * Before a game, once its driver's check has run: on the first game, and the first after the driver changes, the
     * resolution and graphics its speed test found, unless the player chose their own. A new build of the same driver,
     * a new runtime or a Check measure again but change nothing.
     */
    fun useSpeedTest(context: Context, driver: String) {
        val settings = Settings(context)
        if (settings.graphicsDriver == driver) return
        if (!settings.graphicsChosen) setGraphicsDefaults(context, driver == CPU, onlyUnset = false)
        settings.graphicsDriver = driver
    }

    /** [id]'s Settings and Options and DriverCheck's findings go on top of this. */
    fun environment(context: Context, id: String): Map<String, String> {
        val paths = Paths(context)
        val installed = DriverImport.downloaded(paths, id)
        val fixes = installed?.let { Catalog.fixes(Catalog.get(context), id, Gpu.name, Catalog.android) }.orEmpty()
        return environment(paths, id, installed, fixes)
    }

    /** [installed]: a catalog driver's entry as downloaded, null for the others. [fixes]: the catalog's for it here. */
    internal fun environment(paths: Paths, id: String, installed: Catalog.Entry?, fixes: Map<String, String>):
            Map<String, String> {
        val base = mapOf("__EGL_VENDOR_LIBRARY_DIRS" to paths.eglVendors.path, "MESA_LOADER_DRIVER_OVERRIDE" to "zink")
        return base + when (id) {
            // Zink on Android's own Vulkan driver, which libhybris loads (runtime/hybris). Its compat layer adds what
            // Zink needs and the driver may lack, like null descriptors on Mali.
            PHONE -> mapOf("VK_DRIVER_FILES" to paths.hybrisIcd.path,
                           "VK_LAYER_PATH" to paths.hybris.path,
                           HYBRIS_PROPERTIES to paths.androidProperties.path,
                           // Android 10's linker config is named by it: libhybris's linker can't read properties.
                           HYBRIS_VNDK to Runtime.properties["ro.vndk.version"].orEmpty(),
                           "VK_INSTANCE_LAYERS" to "VK_LAYER_HYBRIS_compat") + if (Gpu.mali) MALI else emptyMap()
            // llvmpipe: Mesa on the CPU. Software GLX still follows MESA_LOADER_DRIVER_OVERRIDE, so it must name
            // llvmpipe too. 256-bit code (two NEON registers at a time) and bilinear filtering were the fastest that
            // still looked right.
            CPU -> mapOf("LIBGL_ALWAYS_SOFTWARE" to "1", "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
                         "GALLIUM_DRIVER" to "llvmpipe", "LP_NATIVE_VECTOR_WIDTH" to "256",
                         "LP_PERF" to "no_mip_linear")
            else -> if (installed != null) {
                val icd = DriverImport.icd(paths, id).path
                val vulkan = if (installed.kind == Catalog.Kind.VULKAN) mapOf("VK_DRIVER_FILES" to icd) else emptyMap()
                vulkan + installed.env + fixes
            } else {
                // Imported drivers lack our wait-before-present patch; the software path waits for the GPU instead.
                mapOf("VK_DRIVER_FILES" to DriverImport.icd(paths, id).path, "MESA_VK_WSI_DEBUG" to "sw")
            }
        }
    }

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

    /**
     * Where [id]'s process finds its libraries. An OpenGL driver brings its own Mesa, which must come before the
     * runtime's; a Vulkan driver's folder only adds what the runtime lacks.
     */
    fun libraryPath(paths: Paths, id: String): List<File> {
        if (id == PHONE || id == CPU) return listOf(paths.lib)
        val folder = DriverImport.folder(paths, id)
        return if (DriverImport.downloaded(paths, id)?.kind == Catalog.Kind.OPENGL) listOf(folder, paths.lib)
               else listOf(paths.lib, folder)
    }

    /**
     * What's laid over [id]'s environment, last: its Settings checkboxes, then Settings -> Driver -> Options for it
     * (DriverRecords), `NAME=value` pairs to test the driver with, then debug.env's game lines.
     */
    fun overrides(context: Context, id: String): Map<String, String> {
        val enabled = DriverRecords.settings(context, id)
        val settings = DriverImport.downloaded(Paths(context), id)?.settings.orEmpty().filter { it.id in enabled }
            .fold(emptyMap<String, String>()) { env, setting -> env + setting.env }
        return settings + parseOptions(DriverRecords.options(context, id)) + DebugEnv.read(context, "game")
    }

    internal fun parseOptions(text: String): Map<String, String> = text.split(Regex("\\s+"))
        .mapNotNull { OPTION.matchEntire(it)?.destructured }.associate { (name, value) -> name to value }

    private val OPTION = Regex("""([A-Za-z_][A-Za-z0-9_]*)=(\S*)""")
}
