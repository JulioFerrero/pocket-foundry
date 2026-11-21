package app.pocketfoundry.core.drivers

import android.content.Context
import android.util.Log
import app.pocketfoundry.core.runtime.DebugEnv
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.X11Prefs
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

    /** [FAILED]: it lost the GPU, crashed or ran out of memory in a game (GameEnding). */
    enum class Outcome { RAN, STOPPED, DIDNT_START, FAILED }

    const val PHONE = "phone"
    const val CPU = "cpu"

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
        Driver(PHONE, "Phone's Vulkan", Origin.APP, "Android's own, through libhybris",
               "Zink on the Vulkan driver your phone already has, loaded through libhybris. The way in for Mali, " +
               "PowerVR and Xclipse.", "Any GPU"),
    )

    private fun fromCatalog(entry: Catalog.Entry) =
        Driver(entry.id, entry.name, Origin.CATALOG, entry.version, entry.about, entry.forGpus, entry)

    fun find(context: Context, id: String?) = all(context).firstOrNull { it.id == id }

    /**
     * One way to say which build a driver is, wherever the app names it: "1.2.9 · 10 Nov" for one with its own
     * version, "Mesa 27 · 10 Nov" for one built from Mesa's development code (where the date is what tells builds
     * apart), the maker's version for the phone's. A downloaded driver's is the build it has, not the catalog's. The
     * drivers named after the GPU's own driver, or the CPU, say first what draws through it: "Zink · Arm r38p1",
     * "MobileGlues 2.3.0 · 10 Nov", "llvmpipe · Mesa 26 · 10 Nov".
     */
    fun version(context: Context, driver: Driver): String {
        val build = when (driver.origin) {
            Origin.APP -> Gpu.driverVersion?.let(::makersVersion) ?: "Android's own"
            Origin.IMPORTED -> driver.note
            Origin.CATALOG -> (DriverImport.downloaded(Paths(context), driver.id) ?: driver.entry)
                ?.let { buildVersion(it.version, it.mesa, it.updated, LocalDate.now()) }.orEmpty()
        }
        return withEngine(Route.of(driver).engine, build)
    }

    /** "MobileGlues 2.3.0", its own version after it, but "Zink · Arm r38p1". */
    internal fun withEngine(engine: String?, build: String) = when {
        engine == null -> build
        build.isEmpty() -> engine
        build.first().isDigit() -> "$engine $build"
        else -> "$engine · $build"
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

    /** A catalog driver for neither this GPU nor its family can't be used. */
    fun fits(driver: Driver) =
        driver.origin != Origin.CATALOG || Route.of(driver).anyGpu || forThisGpu(driver) || toTry(driver)

    /** A choice that can't be used any more (a phone moved to another GPU, a driver removed) isn't one. */
    fun chosen(context: Context): String =
        DriverRecords.choice(context)?.takeIf { id -> find(context, id)?.let(::fits) == true } ?: recommended(context)

    /** The player let the app download the driver list and drivers (Settings.driverDownloads). */
    fun downloads(context: Context) = Settings(context).driverDownloads == Settings.ON

    /**
     * A driver that didn't start, or that this version's check found draws wrong or can't draw (DriverCheck), is
     * passed over for the next in line, and one that failed in a game for MobileGlues. With downloads off, only the
     * drivers already here count.
     */
    fun recommended(context: Context): String {
        val online = downloads(context)
        val paths = Paths(context)
        val listed = Catalog.get(context).drivers
            .filter { !it.testing && (online || DriverImport.downloaded(paths, it.id) != null) }
            .sortedBy { it.priority }
        val fitting = listed.filter { Catalog.fits(it, Gpu.name, Catalog.android) }.map { it.id }
        val gles = listed.filter { it.kind == Catalog.Kind.GLES }.map { it.id }
        return recommend(fitting, Gpu.hasVulkan(context), gles,
                         failedInGame = { DriverRecords.outcome(context, it) == Outcome.FAILED }) {
            DriverRecords.outcome(context, it) == Outcome.DIDNT_START || DriverCheck.passed(context, it) == false
        }
    }

    /**
     * [fitting]: the catalog's drivers for this GPU, best first. [gles]: its ones on the phone's own OpenGL ES, for
     * any GPU, which play where the CPU would crawl. One of the others that failed in a game sends the choice
     * straight to those: the rest go through the same Mesa and Vulkan and would likely fail the same way (#76: PanVK
     * lost the GPU, the phone's driver drew black textures, MobileGlues played).
     */
    internal fun recommend(fitting: List<String>, vulkan: Boolean, gles: List<String>,
                           failedInGame: (String) -> Boolean = { false }, passedOver: (String) -> Boolean): String {
        val line = fitting + listOfNotNull(PHONE.takeIf { vulkan }) + gles
        val usable = if (line.any { it !in gles && failedInGame(it) }) gles else line
        return usable.firstOrNull { !passedOver(it) && !failedInGame(it) } ?: CPU
    }

    fun record(context: Context, id: String, outcome: Outcome) =
        switching(context) { DriverRecords.setOutcome(context, id, outcome) }

    fun cpu(context: Context) = chosen(context) == CPU

    /** MaliBridge's fixes come with the phone's own driver. */
    fun isPhone(id: String) = id == PHONE

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
        val online = downloads(context)
        if (find(context, id) == null) throw IOException(
            if (online) "No driver list yet. Check the connection and try again."
            else "Driver downloads are off: turn them on in Settings for a driver this phone can play with.")
        val entry = pending(context, id) ?: return
        val installed = DriverImport.downloaded(Paths(context), id) != null
        // Off, a newer build waits; one that isn't here can't be had.
        if (!online) {
            if (installed) return
            throw IOException("${entry.name} isn't downloaded, and driver downloads are off: turn them on in Settings.")
        }
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
        Route.of(paths, id) == Route.MOBILEGLUES -> when {
            environment["MG_BC_DECODE"] == "1" -> Compression.NONE
            "BC" in Gpu.textureFormats && environment["MG_BC_DECODE"] != "astc" -> Compression.GPU
            else -> Compression.CPU
        }
        gpuCompression == false -> Compression.CPU
        else -> Compression.GPU
    }

    /** What keeps the textures the way DriverCheck found, by its name. */
    fun textureWay(paths: Paths, id: String, environment: Map<String, String>, name: String) =
        Route.of(paths, id).textureWays(paths, environment).firstOrNull { it.saved == name }?.environment.orEmpty()

    /**
     * Off the main thread: the newest catalog, which can change the recommended driver (a first start without it
     * has only the phone's). Offline, the saved one stays.
     */
    fun refreshCatalog(context: Context) = switching(context) {
        if (!downloads(context)) return@switching
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
        settings.setGraphicsDefaults(defaultSprites(context, driver, cpu), cpu || fit?.light == true, onlyUnset)
        val x11 = X11Prefs(context)
        if (!onlyUnset || !x11.hasResolutionScale) x11.resolutionScale = fit?.scale ?: x11.startingResolutionScale(cpu)
    }

    private fun defaultSprites(context: Context, driver: String, cpu: Boolean): String {
        val gles = Route.of(Paths(context), driver, Catalog.get(context).find(driver)) == Route.MOBILEGLUES
        val room = highSpritesFit(DriverCheck.freeMemory(context, driver), DriverCheck.textureCost(context, driver))
        return if (cpu || gles || !room) Settings.MEDIUM else Settings.HIGH
    }

    /**
     * The sprites the next game loads: the player's, or before its driver's first game what useSpeedTest sets then,
     * which the memory warning (MemoryNeed) has to count. A driver not checked yet may well get High: counted as High.
     */
    fun nextSprites(context: Context): String {
        val settings = Settings(context)
        val driver = chosen(context)
        if (settings.graphicsChosen || settings.graphicsDriver == driver) return settings.spriteQuality
        if (driver != CPU && DriverCheck.brief(context, driver) == null) return Settings.HIGH
        return defaultSprites(context, driver, driver == CPU)
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
        return base + Route.of(id, installed).environment(paths, id, vndk) + installed?.env.orEmpty() + fixes
    }

    private const val X11_PREFIX = "TERMUX_X11_"

    /** Factorio's estimate of its textures at High, compressed, with Space Age: 1053 MB on a vivo X200 Ultra. */
    private const val HIGH_TEXTURES_MB = 1053
    /** What else the game had in GPU memory: a Tecno's PanVK held 1628 MB with those textures, then lost the GPU. */
    private const val REST_MB = 600
    /** Room to spare above that, for mods and maps. */
    private const val HEADROOM = 1.5

    /**
     * Whether High sprites fit in [freeMb] of GPU memory, the textures costing [cost] times their compressed size
     * (DriverCheck.textureCost): about 2.5 GB at 1. Unknown: they don't.
     */
    internal fun highSpritesFit(freeMb: Int?, cost: Double) =
        freeMb != null && freeMb >= HEADROOM * (HIGH_TEXTURES_MB * cost + REST_MB)

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

    /** The X server's share of [id]'s overrides: Termux:X11 reads the TERMUX_X11_* ones (Catalog.APP_SETTINGS). */
    fun xServerEnvironment(context: Context, id: String) = overrides(context, id).filterKeys { it.startsWith(X11_PREFIX) }

    internal fun parseOptions(text: String): Map<String, String> = text.split(Regex("\\s+"))
        .mapNotNull { OPTION.matchEntire(it)?.destructured }.associate { (name, value) -> name to value }

    private val OPTION = Regex("""([A-Za-z_][A-Za-z0-9_]*)=(\S*)""")
}
