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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app has one driver of its own, the phone's. The others come from the catalog (Catalog), which the app downloads
 * before a game: the GPU drivers, and MobileGlues and the CPU, which a game falls back on. Players can import others.
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
        val testing = Settings(context).testDrivers
        return app() + (listed + gone).filter { testing || !it.testing }.map(::fromCatalog) + DriverImport.all(paths)
    }

    private fun app() = listOf(
        Driver(PHONE, "Phone's driver", Origin.APP, "Android's own, through libhybris",
               "Zink on the Vulkan driver your phone already has, loaded through libhybris. The way in for Mali, " +
               "PowerVR and Xclipse.", "Any GPU"),
    )

    private fun fromCatalog(entry: Catalog.Entry) =
        Driver(entry.id, entry.name, Origin.CATALOG, entry.version, entry.about, entry.forGpus, entry)

    fun find(context: Context, id: String?) = all(context).firstOrNull { it.id == id }

    /**
     * One way to say which build a driver is, wherever the app names it: "1.2.9 · 10 Nov" for one with its own
     * version, "Mesa 27 · 10 Nov" for one built from Mesa's development code (where the date is what tells builds
     * apart), the maker's version for the phone's. A downloaded driver's is the build it has, not the catalog's.
     */
    fun version(context: Context, driver: Driver): String = when (driver.origin) {
        Origin.APP -> Gpu.driverVersion?.let(::makersVersion) ?: "Android's own"
        Origin.IMPORTED -> driver.note
        Origin.CATALOG -> (DriverImport.downloaded(Paths(context), driver.id) ?: driver.entry)
            ?.let { buildVersion(it.version, it.mesa, it.updated, LocalDate.now()) }.orEmpty()
    }

    internal fun buildVersion(version: String, mesa: String, built: String, today: LocalDate): String {
        val own = if (version.isEmpty() || version == mesa) "Mesa ${mesa.substringBefore('.')}" else version
        return listOf(own, shortDate(built, today)).filter { it.isNotEmpty() && it != "Mesa " }.joinToString(" · ")
    }

    /** Android's OpenGL ES version string, down to the driver's own: "Qualcomm 0800.73", "Arm r38p1". */
    internal fun makersVersion(gl: String): String {
        Regex("""V@(\d+\.\d+)""").find(gl)?.let { return "Qualcomm ${it.groupValues[1]}" }
        Regex("""\b(r\d+p\d+)""").find(gl)?.let { return "Arm ${it.groupValues[1]}" }
        return gl.removePrefix("OpenGL ES ").substringAfter(' ').trim().take(24).ifEmpty { gl }
    }

    /** "3 Nov" this year, "Nov 2025" before; "" for none. */
    internal fun shortDate(day: String, today: LocalDate): String = runCatching {
        val date = LocalDate.parse(day)
        date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "MMM yyyy", Locale.ENGLISH))
    }.getOrDefault(day)

    /** Listed For your GPU. */
    fun forThisGpu(driver: Driver) = driver.entry?.let { Catalog.fits(it, Gpu.name, Catalog.android) } == true

    /** Listed To try: made for another GPU of the same family. */
    fun toTry(driver: Driver) = driver.entry?.let { Catalog.mightFit(it, Gpu.name, Catalog.android) } == true

    /** The phone's driver, the CPU and MobileGlues, on the phone's own OpenGL ES. */
    fun forAnyGpu(driver: Driver) = driver.origin == Origin.APP ||
        driver.entry?.kind.let { it == Catalog.Kind.CPU || it == Catalog.Kind.GLES }

    /** A catalog driver for neither this GPU nor its family can't be used. */
    fun fits(driver: Driver) =
        driver.origin != Origin.CATALOG || forAnyGpu(driver) || forThisGpu(driver) || toTry(driver)

    /** A choice that can't be used any more (a phone moved to another GPU, a driver removed) isn't one. */
    fun chosen(context: Context): String =
        DriverRecords.choice(context)?.takeIf { id -> find(context, id)?.let(::fits) == true } ?: recommended(context)

    /**
     * A driver that didn't start, or that this version's check found draws wrong or can't draw (DriverCheck), is
     * passed over for the next in line.
     */
    fun recommended(context: Context): String {
        val listed = Catalog.get(context).drivers.filter { !it.testing }.sortedBy { it.priority }
        val fitting = listed.filter { Catalog.fits(it, Gpu.name, Catalog.android) }.map { it.id }
        val gles = listed.filter { it.kind == Catalog.Kind.GLES }.map { it.id }
        return recommend(fitting, Gpu.hasVulkan(context), gles) {
            DriverRecords.outcome(context, it) == Outcome.DIDNT_START || DriverCheck.passed(context, it) == false
        }
    }

    /**
     * [fitting]: the catalog's drivers for this GPU, best first. [gles]: its ones on the phone's own OpenGL ES, for
     * any GPU, which play where the CPU would crawl.
     */
    internal fun recommend(fitting: List<String>, vulkan: Boolean, gles: List<String>,
                           passedOver: (String) -> Boolean): String =
        (fitting + listOfNotNull(PHONE.takeIf { vulkan }) + gles).firstOrNull { !passedOver(it) } ?: CPU

    fun record(context: Context, id: String, outcome: Outcome) =
        switching(context) { DriverRecords.setOutcome(context, id, outcome) }

    fun cpu(context: Context) = chosen(context) == CPU

    /** MaliBridge's fixes come with the phone's own driver. */
    fun isPhone(id: String) = id == PHONE

    /**
     * Whether DriverCheck checks [id]: every driver the game reaches through Mesa, as the check program does. Not
     * MobileGlues, which goes through Android's OpenGL ES, nor the CPU, which a game falls back on anyway. [listed]:
     * the catalog's entry, for one not downloaded yet.
     */
    fun checked(paths: Paths, id: String, listed: Catalog.Entry? = null) =
        (DriverImport.downloaded(paths, id) ?: listed)?.kind.let { it != Catalog.Kind.GLES && it != Catalog.Kind.CPU }

    /** Not downloaded yet, or the catalog has a newer build of it: the next game downloads [id] first. */
    fun pending(context: Context, id: String): Catalog.Entry? {
        val entry = Catalog.get(context).find(id) ?: return null
        return entry.takeIf { DriverImport.downloaded(Paths(context), id)?.sha256 != it.sha256 }
    }

    /**
     * Off the main thread, before a game: the chosen driver's package if it's [pending]. A newer build that can't be
     * downloaded waits for the next game; a driver that isn't there at all can't play. Neither can the CPU before the
     * app has the catalog that lists it, on a first start without a connection.
     */
    @Synchronized
    fun prepare(context: Context, onPercent: (name: String, percent: Int) -> Unit) {
        val id = chosen(context)
        if (find(context, id) == null) throw IOException("No driver list yet. Check the connection and try again.")
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
     * Who compresses the game's textures. On a checked driver ([gpuCompression]: DriverCheck's finding) Factorio's GPU
     * compressor, where blocks copied in from integer textures read right, as it makes them; else the CPU (PanVK's BC
     * stand-in skipped the compressor's copies, and a G610 faulted right after).
     */
    fun compression(paths: Paths, id: String, gpuCompression: Boolean?,
                    environment: Map<String, String> = emptyMap()) = when {
        // On a GPU with BC (Adreno) the game's GPU compressor feeds it. Without (Mali, PowerVR) the CPU compresses:
        // the GPU compressor crashed a Redmi 12's Mali-G52 through MobileGlues. MobileGlues keeps that BC as ASTC (from
        // its 2.3.0), or unpacks it where it can't, as it did before. MG_BC_DECODE in Options acts as a GPU without BC:
        // with ASTC (astc), or unpacking all (1).
        DriverImport.downloaded(paths, id)?.kind == Catalog.Kind.GLES -> when {
            environment["MG_BC_DECODE"] == "1" -> Compression.NONE
            "BC" in Gpu.textureFormats && environment["MG_BC_DECODE"] != "astc" -> Compression.GPU
            else -> Compression.CPU
        }
        gpuCompression == false -> Compression.CPU
        else -> Compression.GPU
    }

    /** Through MobileGlues Factorio finds no GPU memory ("0 MB (failed to detect)"): FactorioConfig's noGpuMemory. */
    fun findsNoGpuMemory(paths: Paths, id: String) = DriverImport.downloaded(paths, id)?.kind == Catalog.Kind.GLES

    /**
     * A way the game's BC textures can be kept on a driver, over its [environment]: DriverCheck tries them in order
     * and measures what each does. [convertsWhatsMissing]: it can't leave them unpacked, where the driver doesn't say
     * its memory.
     */
    class TextureWay(val saved: String, val environment: Map<String, String>, val convertsWhatsMissing: Boolean) {
        companion object {
            /** As the driver keeps BC: its own formats, or its own stand-in for them. */
            const val DRIVER = "driver"
            /**
             * Through the compat layer's converter: BC where the driver has it, ASTC and EAC where it doesn't.
             * Unpacked, they take about five times the room: a Mali-G52 with 3.7 GB ran out of memory loading the
             * base game.
             */
            const val LAYER = "layer"
            /**
             * The same with the driver saying it's Vulkan 1.3 (Mesa's MESA_VK_VERSION_OVERRIDE): the layer turns
             * itself off on 1.4, whose descriptor commands it doesn't handle yet, and PanVK-kbase on a Mali-G615 is
             * 1.4. Mesa's drivers only; the phone's own driver doesn't read it.
             */
            const val LAYER_VULKAN_13 = "layer at Vulkan 1.3"
        }
    }

    /** The compat layer's converter (runtime/hybris): 1 converts what the driver lacks, 0 leaves it to Mesa. */
    internal const val CONVERTER = "HYBRIS_BC_TEXTURES"

    /**
     * The phone's driver has the compat layer already: its converter only. Other drivers first as they are, then with
     * the layer on top, their own stand-in off so the layer sees what they lack (PANVK_DEBUG; the others have none),
     * and without the layer's fixes for Arm's own shader compiler, which only the phone's driver has.
     */
    fun textureWays(paths: Paths, id: String, environment: Map<String, String>): List<TextureWay> {
        val layer = TextureWay(TextureWay.LAYER, mapOf(CONVERTER to "1") + if (isPhone(id)) emptyMap() else mapOf(
            "VK_LAYER_PATH" to listOfNotNull(environment["VK_LAYER_PATH"], paths.hybris.path).joinToString(":"),
            "VK_INSTANCE_LAYERS" to listOfNotNull(environment["VK_INSTANCE_LAYERS"], COMPAT_LAYER).joinToString(":"),
            "PANVK_DEBUG" to listOfNotNull(environment["PANVK_DEBUG"], "no_bc_emul").joinToString(","),
            "HYBRIS_MALI_SPEC_CONSTANTS" to "0"), convertsWhatsMissing = true)
        val layerAt13 = TextureWay(TextureWay.LAYER_VULKAN_13, layer.environment +
                                       ("MESA_VK_VERSION_OVERRIDE" to "1.3"), convertsWhatsMissing = true)
        return when {
            isPhone(id) -> listOf(layer)
            usesVulkan(paths, id) -> listOf(driverWay, layer, layerAt13)
            // Freedreno: no Vulkan for the layer to go on.
            else -> listOf(driverWay)
        }
    }

    private val driverWay = TextureWay(TextureWay.DRIVER, emptyMap(), convertsWhatsMissing = false)

    /**
     * For DriverCheck's test picture: the driver's stand-ins for the BC it lacks off, so Mesa unpacks the compressed
     * squares itself, exactly. The compat layer's converter on the phone's driver, PanVK's own on Vulkan drivers (the
     * others have none, and ignore it); the textures step judges each stand-in on its own.
     */
    fun withoutStandIns(paths: Paths, id: String, environment: Map<String, String>): Map<String, String> = when {
        isPhone(id) -> mapOf(CONVERTER to "0")
        usesVulkan(paths, id) -> mapOf("PANVK_DEBUG" to
            listOfNotNull(environment["PANVK_DEBUG"], "no_bc_emul").joinToString(","))
        else -> emptyMap()
    }

    /** What keeps the textures the way DriverCheck found, by its name. */
    fun textureWay(paths: Paths, id: String, environment: Map<String, String>, name: String) =
        textureWays(paths, id, environment).firstOrNull { it.saved == name }?.environment.orEmpty()

    /** The phone's driver, imported drivers and the catalog's Vulkan ones: Zink, where the compat layer can go. */
    private fun usesVulkan(paths: Paths, id: String) =
        DriverImport.downloaded(paths, id)?.kind.let { it == null || it == Catalog.Kind.VULKAN }

    /**
     * Off the main thread: the newest catalog, which can change the recommended driver (a first start without it
     * has only the phone's). Offline, the saved one stays.
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
     * The CPU driver gets Medium sprites, Light graphics and the coarser resolution it was made for. A GPU driver gets
     * High sprites where its check found room for them (highSpritesFit), the resolution its speed test found, and
     * Light graphics if even the coarsest was too slow. MobileGlues gets Medium: High ran a vivo X200 Ultra out of
     * memory, and the check can't measure it. [onlyUnset] keeps what the player chose.
     */
    private fun setGraphicsDefaults(context: Context, cpu: Boolean, onlyUnset: Boolean) {
        val settings = Settings(context)
        val driver = chosen(context)
        val fit = if (cpu) null else SpeedTest.fit(context, DriverCheck.speed(context, driver), settings.frameRate)
        val gles = (DriverImport.downloaded(Paths(context), driver) ?: Catalog.get(context).find(driver))?.kind ==
            Catalog.Kind.GLES
        val room = highSpritesFit(DriverCheck.freeMemory(context, driver), DriverCheck.textureCost(context, driver))
        settings.setGraphicsDefaults(if (cpu || gles || !room) Settings.MEDIUM else Settings.HIGH,
                                     cpu || fit?.light == true, onlyUnset)
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

    /**
     * [installed]: a catalog driver's entry as downloaded, null for the others. [fixes]: the catalog's for it here.
     * [vndk]: Android's VNDK version, for libhybris's linker.
     */
    internal fun environment(paths: Paths, id: String, installed: Catalog.Entry?, fixes: Map<String, String>,
                             vndk: () -> String = { Runtime.properties["ro.vndk.version"].orEmpty() }):
            Map<String, String> {
        // DRIRC_CONFIGDIR: Mesa looks for these settings under the path a driver was built for, which may be another
        // app id's (pocket-foundry-drivers built for app.pocketfoundry, before 0.9); here they're always found.
        val base = mapOf("__EGL_VENDOR_LIBRARY_DIRS" to paths.eglVendors.path, "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                         "DRIRC_CONFIGDIR" to paths.drirc.path)
        return base + when (id) {
            // Zink on Android's own Vulkan driver, which libhybris loads (runtime/hybris). Its compat layer adds what
            // Zink needs and the driver may lack, like null descriptors on Mali.
            PHONE -> mapOf("VK_DRIVER_FILES" to paths.hybrisIcd.path,
                           "VK_LAYER_PATH" to paths.hybris.path,
                           HYBRIS_PROPERTIES to paths.androidProperties.path,
                           // Android 10's linker config is named by it: libhybris's linker can't read properties.
                           HYBRIS_VNDK to vndk(),
                           "VK_INSTANCE_LAYERS" to COMPAT_LAYER)
            else -> if (installed?.kind == Catalog.Kind.GLES) {
                mobileGlues(paths, DriverImport.folder(paths, id), vndk()) + installed.env + fixes
            } else if (installed != null) {
                val icd = DriverImport.icd(paths, id).path
                val vulkan = if (installed.kind.ownMesa) emptyMap() else mapOf("VK_DRIVER_FILES" to icd)
                vulkan + installed.env + fixes
            } else {
                // Imported drivers lack our wait-before-present patch; the software path waits for the GPU instead.
                mapOf("VK_DRIVER_FILES" to DriverImport.icd(paths, id).path, "MESA_VK_WSI_DEBUG" to "sw")
            }
        }
    }

    /**
     * SDL makes the game's OpenGL context with [folder]'s EGL, a shim to MobileGlues' (libmgx11.so, from
     * pocket-foundry-drivers): MobileGlues turns OpenGL into the phone's OpenGL ES, which libhybris's EGL and GLES
     * load (runtime/hybris), and the shim gives SDL's X11 window a pbuffer the GPU copies into Android hardware buffers
     * the X server shows. No Zink, no Vulkan.
     */
    private fun mobileGlues(paths: Paths, folder: File, vndk: String) = mapOf(
        "SDL_VIDEO_FORCE_EGL" to "1",
        "SDL_EGL_LIBRARY" to File(folder, "libmgx11.so").path,
        "SDL_OPENGL_LIBRARY" to File(folder, "libmobileglues.so").path,
        "MG_LIBRARY" to File(folder, "libmobileglues.so").path,
        "MG_EGL_LIBRARY" to File(paths.hybris, "libEGL.so.1").path,
        "MG_GLES_LIBRARY" to File(paths.hybris, "libGLESv2.so.2").path,
        "MG_DIR_PATH" to File(folder, "mobileglues").path,
        // libhybris's EGL draws offscreen only: the shim shows the frames.
        "EGL_PLATFORM" to "null",
        HYBRIS_PROPERTIES to paths.androidProperties.path,
        HYBRIS_VNDK to vndk)

    private const val COMPAT_LAYER = "VK_LAYER_HYBRIS_compat"

    /** Factorio's estimate of its textures at High, compressed, with Space Age: 1053 MB on a vivo X200 Ultra. */
    private const val HIGH_TEXTURES_MB = 1053
    /** What else the game had in GPU memory: a Tecno's PanVK held 1628 MB with those textures, then lost the GPU. */
    private const val REST_MB = 600
    /** Room to spare above that, for mods and maps. */
    private const val HEADROOM = 1.5

    /**
     * Whether High sprites fit in [freeMb] of GPU memory, the textures costing [cost] times their compressed size
     * (DriverCheck.textureCost): about 2.5 GB at 1, 4 GB through the compat layer's converter. Unknown: they don't.
     */
    internal fun highSpritesFit(freeMb: Int?, cost: Double) =
        freeMb != null && freeMb >= HEADROOM * (HIGH_TEXTURES_MB * cost + REST_MB)

    /**
     * Where [id]'s process finds its libraries. A driver with its own Mesa needs it before the runtime's; a Vulkan
     * driver's folder only adds what the runtime lacks.
     */
    fun libraryPath(paths: Paths, id: String): List<File> {
        if (id == PHONE) return listOf(paths.lib)
        val folder = DriverImport.folder(paths, id)
        return if (DriverImport.downloaded(paths, id)?.kind?.ownMesa == true) listOf(folder, paths.lib)
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
