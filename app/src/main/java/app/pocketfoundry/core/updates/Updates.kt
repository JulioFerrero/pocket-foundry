package app.pocketfoundry.core.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import app.pocketfoundry.core.system.Http
import app.pocketfoundry.core.system.REPOSITORY
import app.pocketfoundry.core.system.appVersion
import app.pocketfoundry.core.system.objects
import app.pocketfoundry.core.system.sha256
import org.json.JSONArray
import java.io.File
import java.io.IOException

object Updates {
    /** [changes]: what each version since the installed one brought, newest first. */
    class Release(val version: String, val apk: String, val checksum: String, val changes: List<Pair<String, List<String>>>)

    private const val RELEASES = "https://api.github.com/repos/$REPOSITORY/releases?per_page=50"
    private const val INSTALLED = "app.pocketfoundry.UPDATE_INSTALLED"

    @Volatile var available: Release? = null
        private set
    @Volatile var checked = false
        private set

    /** Off the main thread. Throws when GitHub can't be reached. */
    fun check(context: Context): Release? {
        val list = JSONArray(Http.get(RELEASES, accept = "application/vnd.github+json").decodeToString())
        val release = newest(list, context.appVersion())
        available = release
        checked = true
        return release
    }

    internal fun newest(list: JSONArray, installed: String): Release? {
        val releases = list.objects()
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .map { it.getString("tag_name").removePrefix("v") to it }
            .filter { (version, _) -> compareVersions(version, installed) > 0 }
            .sortedWith { a, b -> compareVersions(b.first, a.first) }
        val (version, latest) = releases.firstOrNull() ?: return null
        val assets = latest.getJSONArray("assets").objects()
        val apk = assets.firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        val sum = assets.firstOrNull { it.getString("name") == apk.getString("name") + ".sha256" } ?: return null
        return Release(version, apk.getString("browser_download_url"), sum.getString("browser_download_url"),
                       releases.map { (v, json) -> v to changes(json.optString("body")) })
    }

    /**
     * 0.6.10 after 0.6.9, compared number by number, and a version after its betas: 0.8.17 after 0.8.17-beta.2, which
     * comes after 0.8.17-beta.1. A beta installed from a pre-release is then offered its release, never an older one.
     */
    internal fun compareVersions(a: String, b: String): Int {
        val release = compareNumbers(a.substringBefore('-'), b.substringBefore('-'))
        if (release != 0) return release
        val x = a.substringAfter('-', "")
        val y = b.substringAfter('-', "")
        return when {
            x == y -> 0
            x.isEmpty() -> 1
            y.isEmpty() -> -1
            else -> compareNumbers(x.filter { it.isDigit() || it == '.' }, y.filter { it.isDigit() || it == '.' })
        }
    }

    private fun compareNumbers(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * The "Since vX:" list in tools/release.sh's notes, as plain text: no links, bold, code or issue numbers, which
     * mean something on GitHub only. Older notes stay on GitHub as they were written, so this reads them all.
     */
    internal fun changes(notes: String): List<String> = notes.lines()
        .dropWhile { !it.startsWith("**Since") }.drop(1)
        .takeWhile { it.isNotBlank() }
        .filter { it.startsWith("- ") }
        .map { line ->
            line.removePrefix("- ")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                .replace("**", "").replace("`", "")
                .replace(Regex("""\s*\(#\d+(, #\d+)*\)"""), "")
                .trim()
        }

    /** Off the main thread. */
    fun download(context: Context, release: Release, onPercent: (Int) -> Unit): File {
        val expected = Http.get(release.checksum).decodeToString().trim().substringBefore(' ').lowercase()
        val dir = File(context.cacheDir, "update")
        val file = File(dir, "pocket-foundry-${release.version}.apk")
        if (file.exists() && file.sha256() == expected) return file
        dir.deleteRecursively()
        dir.mkdirs()
        Http.download(release.apk, file, onPercent)
        if (file.sha256() != expected) {
            file.delete()
            throw IOException("The download doesn't match the release's checksum")
        }
        return file
    }

    /**
     * Hands [file] to Android's installer, which asks the user and then replaces this app, closing it. [onFailure]
     * gets the reason when it doesn't: cancelled, or not allowed to install apps.
     */
    fun install(context: Context, file: File, onFailure: (String) -> Unit) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("pocket-foundry.apk", 0, file.length()).use { output ->
                file.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            ContextCompat.registerReceiver(app, InstallResult(app, onFailure), IntentFilter(INSTALLED), ContextCompat.RECEIVER_NOT_EXPORTED)
            val status = PendingIntent.getBroadcast(app, id, Intent(INSTALLED).setPackage(app.packageName),
                                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(status.intentSender)
        }
    }

    private class InstallResult(private val app: Context, private val onFailure: (String) -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            // Android's own confirmation, and its prompt to allow installing apps if that's still off.
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return
            }
            app.unregisterReceiver(this)
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {}
                PackageInstaller.STATUS_FAILURE_ABORTED -> onFailure("Update cancelled.")
                else -> onFailure("Android didn't install the update: " +
                                  (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no reason given"))
            }
        }
    }
}
