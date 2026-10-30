package app.pocketfoundry

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object Updater {
    private const val API_URL = "https://api.github.com/repos/JulioFerrero/pocket-foundry/releases/latest"

    @Volatile var checkedThisSession = false

    data class ReleaseInfo(
        val version: String,
        val htmlUrl: String,
    )

    fun check(context: Context, onResult: (ReleaseInfo?) -> Unit) {
        if (checkedThisSession || !Settings.checkForUpdates(context)) return
        checkedThisSession = true

        thread(name = "pocketfoundry-updater") {
            val release = runCatching {
                val conn = URL(API_URL).openConnection().apply {
                    setRequestProperty("User-Agent", "PocketFoundry")
                    connectTimeout = 4000
                }
                val json = JSONObject(conn.getInputStream().reader().use { it.readText() })
                val latest = json.getString("tag_name").removePrefix("v").trim()
                val url = json.getString("html_url")
                val current = context.packageManager.getPackageInfo(context.packageName, 0)
                    .versionName.orEmpty().removePrefix("v").trim()

                if (isNewer(latest, current)) {
                    ReleaseInfo(latest, url)
                } else null
            }.getOrNull()

            onResult(release)
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val diff = (l.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }
}