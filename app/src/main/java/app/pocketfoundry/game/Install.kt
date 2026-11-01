package app.pocketfoundry.game

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import app.pocketfoundry.Paths
import app.pocketfoundry.ProgressInputStream
import app.pocketfoundry.TAG
import app.pocketfoundry.Uris
import app.pocketfoundry.untarXz
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** assets/runtime.tar.xz: glibc, Mesa, SDL, X11 libraries and xkb data, pocketfoundry-wm and the test tools. */
object Runtime {
    private fun assetVersion(context: Context) =
        context.assets.open("runtime.version").bufferedReader().use { it.readText() }.trim()

    private fun stamp(paths: Paths) = File(paths.prefix, ".version")

    fun isInstalled(context: Context): Boolean {
        val stamp = stamp(Paths(context))
        return stamp.exists() && stamp.readText().trim() == assetVersion(context)
    }

    fun install(context: Context) {
        if (isInstalled(context)) return
        val paths = Paths(context)
        paths.prefix.deleteRecursively()
        context.assets.open("runtime.tar.xz").use { untarXz(it, paths.files) }
        paths.tmp.mkdirs()
        stamp(paths).writeText(assetVersion(context))
    }

    /** glibc reads files/usr/etc/resolv.conf, which Android doesn't have. */
    fun writeResolvConf(context: Context) {
        val servers = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.activeNetwork?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty().mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList()).ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        File(Paths(context).prefix, "etc/resolv.conf").writeText(servers.joinToString("") { "nameserver $it\n" })
    }
}

/** Installs the player's own Factorio from factorio.com's Linux arm64 .tar.xz. The app never bundles the game. */
object GameImport {
    fun installedVersion(context: Context): String? {
        val paths = Paths(context)
        val info = File(paths.game, "data/base/info.json")
        if (!paths.gameBinary.exists() || !info.exists()) return null
        return runCatching { JSONObject(info.readText()).getString("version") }.getOrNull()
    }

    /** [onPercent] gets how much of the compressed file has been read. */
    fun import(context: Context, uri: Uri, onPercent: (Int) -> Unit) {
        val paths = Paths(context)
        val started = System.nanoTime()
        val staging = File(paths.files, "game.importing")
        staging.deleteRecursively()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the selected file")
        // The archive's top folder is factorio/.
        ProgressInputStream(input, Uris.size(context, uri), onPercent).use { untarXz(it, staging, strip = 1) }
        if (!File(staging, "bin/arm64/factorio").exists()) {
            staging.deleteRecursively()
            throw IOException("That isn't the Linux arm64 build of Factorio (bin/arm64/factorio is missing)")
        }
        paths.game.deleteRecursively()
        if (!staging.renameTo(paths.game)) throw IOException("Couldn't move the game into place")
        Log.i(TAG, "Imported the game in ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
