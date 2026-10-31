package app.pocketfoundry

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Pocket Foundry's own releases on GitHub: whether a newer one is out, and its APK handed to Android's installer. */
object Updates {
    /** [changes]: what each version since the installed one brought, newest first. */
    class Release(val version: String, val apk: String, val checksum: String, val changes: List<Pair<String, List<String>>>)

    private const val RELEASES = "https://api.github.com/repos/JulioFerrero/pocket-foundry/releases?per_page=50"
    private const val INSTALLED = "app.pocketfoundry.UPDATE_INSTALLED"

    /** The newer release [check] found, if any. */
    @Volatile var available: Release? = null
        private set
    @Volatile var checked = false
        private set

    /** Not in debug builds: they're for testing on the phone, and a release would replace them. */
    fun enabled(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    /** Off the main thread. Throws when GitHub can't be reached. */
    fun check(context: Context): Release? {
        val installed = installedVersion(context)
        val newer = JSONArray(get(RELEASES).decodeToString()).let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .map { it.getString("tag_name").removePrefix("v") to it }
            .filter { (version, _) -> newer(version, installed) }
            .sortedWith { a, b -> if (newer(a.first, b.first)) -1 else if (newer(b.first, a.first)) 1 else 0 }
        val (version, latest) = newer.firstOrNull() ?: return null.also { available = null; checked = true }
        val assets = latest.getJSONArray("assets").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
        val apk = assets.firstOrNull { it.getString("name").endsWith(".apk") }
        val sum = apk?.let { assets.firstOrNull { a -> a.getString("name") == it.getString("name") + ".sha256" } }
        val release = if (apk != null && sum != null) Release(version, apk.getString("browser_download_url"),
            sum.getString("browser_download_url"), newer.map { (v, json) -> v to changes(json.optString("body")) }) else null
        available = release
        checked = true
        return release
    }

    /**
     * The "Since vX:" list in tools/release.sh's notes, as plain text: no links, bold, code or issue numbers, which
     * mean something on GitHub only.
     */
    private fun changes(notes: String): List<String> = notes.lines()
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

    /**
     * Off the main thread. [onProgress] gets 0..1. The file matches the SHA-256 published with the release; one
     * downloaded before that still does is kept.
     */
    fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        val expected = get(release.checksum).decodeToString().trim().substringBefore(' ').lowercase()
        val dir = File(context.cacheDir, "update")
        val file = File(dir, "pocket-foundry-${release.version}.apk")
        if (file.exists() && sha256(file) == expected) return file
        dir.deleteRecursively()
        dir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(release.apk)
        try {
            val size = connection.contentLengthLong
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val chunk = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        output.write(chunk, 0, n)
                        digest.update(chunk, 0, n)
                        read += n
                        if (size > 0) onProgress(read.toFloat() / size)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != expected) {
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
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(receiver: Context, intent: Intent) {
                    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        // Android's own confirmation, and its prompt to allow installing apps if that's still off.
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                ?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        PackageInstaller.STATUS_SUCCESS -> app.unregisterReceiver(this)
                        PackageInstaller.STATUS_FAILURE_ABORTED -> { app.unregisterReceiver(this); onFailure("Update cancelled.") }
                        else -> {
                            app.unregisterReceiver(this)
                            onFailure("Android didn't install the update: " +
                                      (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no reason given"))
                        }
                    }
                }
            }, IntentFilter(INSTALLED), ContextCompat.RECEIVER_NOT_EXPORTED)
            val status = PendingIntent.getBroadcast(app, id, Intent(INSTALLED).setPackage(app.packageName),
                                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(status.intentSender)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                digest.update(chunk, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installedVersion(context: Context) =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** 0.6.10 after 0.6.9: compared number by number. */
    private fun newer(version: String, than: String): Boolean {
        val a = version.split('.').map { it.toIntOrNull() ?: 0 }
        val b = than.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "PocketFoundry")
        if (url == RELEASES) setRequestProperty("Accept", "application/vnd.github+json")
        if (responseCode !in 200..299) throw IOException("GitHub answered $responseCode")
    }

    private fun get(url: String): ByteArray {
        val connection = open(url)
        try {
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
