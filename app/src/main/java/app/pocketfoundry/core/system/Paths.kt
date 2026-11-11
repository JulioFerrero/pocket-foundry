package app.pocketfoundry.core.system

import android.content.Context
import java.io.File

/**
 * Every file the app uses. glibc and Mesa were built for files/usr: the runtime has to be unpacked exactly there, and
 * what's under [prefix] is laid out by runtime/assemble/build-assemble.sh (RuntimeContractTest checks the two agree).
 */
class Paths(val files: File, nativeLibs: File) {
    constructor(context: Context) : this(context.filesDir, File(context.applicationInfo.nativeLibraryDir))

    val prefix = File(files, "usr")
    val lib = File(prefix, "lib")
    val tmp = File(prefix, "tmp")
    val runtimeVersion = File(prefix, ".version")
    /** Android's properties for libhybris, which can't read them itself (Runtime.writeProperties). */
    val androidProperties = File(prefix, "etc/android.prop")
    /** glibc reads files/usr/etc/resolv.conf, which Android doesn't have. */
    val resolvConf = File(prefix, "etc/resolv.conf")
    val xkb = File(prefix, "share/X11/xkb")
    val xLocales = File(prefix, "share/X11/locale")
    val gconv = File(lib, "gconv")
    /** Our SDL, which the game's built-in one hands everything to (runtime/sdl). */
    val sdl = File(lib, "libSDL3.so.0")
    val eglVendors = File(prefix, "share/glvnd/egl_vendor.d")
    /** libhybris and its Vulkan layers (runtime/hybris). */
    val hybris = File(lib, "hybris")
    val hybrisIcd = File(hybris, "hybris_icd.json")

    fun program(name: String) = File(prefix, "bin/$name")
    val windowManager = program("pocketfoundry-wm")
    val driverCheck = program("pocketfoundry-drivercheck")
    val speedTest = program("pocketfoundry-speedtest")

    /** What the app reads from the runtime, beside the libraries it loads: RuntimeContractTest finds each in it. */
    val shipped get() = listOf(xkb, xLocales, gconv, sdl, eglVendors, hybris, hybrisIcd, windowManager, driverCheck,
                                    speedTest)

    /** No /tmp on Android: the X server's socket and lock live in [tmp], and DISPLAY names the socket itself. */
    val xSocket = File(tmp, ".X11-unix/X$DISPLAY")
    val xLock = File(tmp, ".X$DISPLAY-lock")
    val audioSocket = File(tmp, "pocketfoundry-audio")
    val inputSocket = File(tmp, "pocketfoundry-input")
    /** Read by the game's SDL once a second. */
    val frameRate = File(tmp, "pocketfoundry-frame-rate")

    val game = File(files, "game")
    val gameBinary = File(game, "bin/arm64/factorio")
    val gameReadData = File(game, "data")
    val gameInfo = File(gameReadData, "base/info.json")
    val baseGraphics = File(gameReadData, "base/graphics")
    val coreGraphics = File(gameReadData, "core/graphics")
    val gameStaging = File(files, "game.importing")

    val gameData = File(files, "factorio")
    val config = File(gameData, "config/config.ini")
    /** Factorio's sprite atlases, kept when Settings.fastStart is on. */
    val atlasCache = File(gameData, "atlas-cache.dat")
    val saves = File(gameData, "saves")

    val drivers = File(files, "drivers")
    /** The last signed catalog of drivers downloaded (Catalog). */
    val catalog = File(files, "catalog.json")
    /** Outside [drivers] so the driver list never sees a half-imported one. */
    val driverStaging = File(files, "driver.importing")
    val debugEnv = File(files, "debug.env")
    val home = File(files, "home")

    val logs = File(files, "logs")
    val gameLog = File(logs, "game.log")
    val wmLog = File(logs, "wm.log")
    val screenLog = File(logs, "screen.log")
    val x11Log = File(logs, "x11.log")
    val driverCheckLog = File(logs, "drivercheck.log")
    fun toolLog(name: String) = File(logs, "$name.log")

    val loader = File(nativeLibs, "libld-linux.so")
    val xlorie = File(nativeLibs, "libXlorie.so")

    companion object {
        /** The X display the game draws on, Termux:X11's :0. */
        const val DISPLAY = 0
    }
}
