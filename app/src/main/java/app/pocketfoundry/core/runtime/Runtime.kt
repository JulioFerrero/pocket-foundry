package app.pocketfoundry.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.untarXz
import java.io.File

/**
 * assets/runtime.tar.xz: glibc, Mesa, libhybris, SDL, X11 libraries and data, and our programs (runtime/). Its version
 * comes from the runtime's sources, so what's known about a driver holds until they change (DriverRecords).
 */
object Runtime {
    fun version(context: Context) =
        context.assets.open("runtime.version").bufferedReader().use { it.readText() }.trim()

    /**
     * Where the runtime was built to be unpacked, when that isn't where it is: its glibc and drivers' paths point there
     * (a second Android user, a work profile, or another app id). Null when they agree.
     */
    fun misplaced(context: Context): String? {
        val built = context.assets.open("runtime.prefix").bufferedReader().use { it.readText() }.trim()
        val here = Paths(context).prefix
        return if (File(built).canonicalPath == here.canonicalPath) null else "built for $built, unpacked at $here"
    }

    fun isInstalled(context: Context): Boolean {
        val stamp = Paths(context).runtimeVersion
        return stamp.exists() && stamp.readText().trim() == version(context)
    }

    /** Before anything runs on the runtime: unpacked, and Android's facts it can't read itself written down. */
    @Synchronized
    fun prepare(context: Context) {
        val paths = Paths(context)
        if (!isInstalled(context)) install(context, paths)
        paths.home.mkdirs()
        paths.logs.mkdirs()
        writeProperties(paths)
        writeResolvConf(context, paths)
        writeFrameRate(context)
    }

    private fun install(context: Context, paths: Paths) {
        paths.prefix.deleteRecursively()
        // The archive's top folder is usr/.
        context.assets.open("runtime.tar.xz").use { untarXz(it, paths.files) }
        paths.tmp.mkdirs()
        paths.runtimeVersion.writeText(version(context))
    }

    /** Read once: the ones that matter here (ro.) don't change. */
    val properties: Map<String, String> by lazy {
        val property = Regex("""\[([^\]]*)]: \[(.*)]""")
        runCatching {
            ProcessBuilder("/system/bin/getprop").start().inputStream.bufferedReader().useLines { output ->
                output.mapNotNull { property.matchEntire(it)?.destructured }
                    .associate { (name, value) -> name to value }
            }
        }.onFailure { Log.w(TAG, "Couldn't read Android's properties", it) }.getOrDefault(emptyMap())
    }

    /**
     * Android's properties as name=value lines, which libhybris reads (HYBRIS_PROPERTY_FILE): on its own it sees only
     * /system/build.prop's, and Android's Vulkan loader, which it runs, finds the phone's driver by properties that can
     * be elsewhere (ro.board.platform on a Huawei P40).
     */
    private fun writeProperties(paths: Paths) {
        paths.androidProperties.writeText(properties.entries.joinToString("") { "${it.key}=${it.value}\n" })
    }

    private fun writeResolvConf(context: Context, paths: Paths) {
        val servers = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty().mapNotNull { it.hostAddress }
        }.onFailure { Log.w(TAG, "Couldn't read the DNS servers", it) }.getOrDefault(emptyList())
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        paths.resolvConf.writeText(servers.joinToString("") { "nameserver $it\n" })
    }

    /** The player's choice, stored and told to a running game. */
    fun setFrameRate(context: Context, fps: Int) {
        Settings(context).frameRate = fps
        writeFrameRate(context)
    }

    /**
     * The game's SDL rereads it every second (runtime/sdl), so a change applies in game. Our 60 is the uncapped one.
     */
    private fun writeFrameRate(context: Context) {
        val file = Paths(context).frameRate
        if (file.parentFile?.isDirectory == true) file.writeText("${Settings(context).frameRate}\n")
    }
}
