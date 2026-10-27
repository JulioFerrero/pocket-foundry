package app.pocketfoundry

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/** The Linux side shipped in assets/runtime.tar.xz: glibc, Mesa, X11 libraries, pocketfoundry-wm. */
object Runtime {
    private fun assetVersion(context: Context) =
        context.assets.open("runtime.version").bufferedReader().readText().trim()

    private fun stamp(paths: Paths) = File(paths.prefix, ".version")

    fun isInstalled(context: Context): Boolean {
        val stamp = stamp(Paths(context))
        return stamp.exists() && stamp.readText().trim() == assetVersion(context)
    }

    /** Unpacks the runtime if this APK carries a different one than what's on disk. */
    fun install(context: Context) {
        if (isInstalled(context)) return
        val paths = Paths(context)
        paths.prefix.deleteRecursively()
        context.assets.open("runtime.tar.xz").use { untarXz(it, paths.files) }
        paths.tmp.mkdirs()
        stamp(paths).writeText(assetVersion(context))
    }

    /** glibc reads files/usr/etc/resolv.conf; Android has no such file, so write the current DNS servers. */
    fun writeResolvConf(context: Context) {
        val servers = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty().mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList()).ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        File(Paths(context).prefix, "etc/resolv.conf")
            .writeText(servers.joinToString("") { "nameserver $it\n" })
    }
}
