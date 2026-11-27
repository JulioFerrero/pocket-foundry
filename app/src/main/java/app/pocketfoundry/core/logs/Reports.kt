package app.pocketfoundry.core.logs

import android.content.Context
import android.os.Build
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.WEBSITE
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * A report to Pocket Foundry's Acrarium (reports.pocketfoundry.dev): what the player wrote and the Logs as a file, in
 * ACRA's format, so Acrarium takes it as any ACRA app's. Sent only when the player taps Send: nothing is collected or
 * sent otherwise, and there's no crash handler. Acrarium groups reports by their first line, here the driver and the
 * GPU, and keeps the Logs as the report's attachment. What's kept and for how long: [PRIVACY].
 */
object Reports {
    /** pocketfoundry-site's privacy page: what reports and results hold, where they go, how long they stay. */
    const val PRIVACY = "$WEBSITE/privacy/"
    private const val URL = "https://reports.pocketfoundry.dev/report"
    /** Acrarium's reporter for this app: it can send reports and nothing else, as every ACRA app ships its own. */
    private const val REPORTER = "QWzL85yVnZXGkfHa:gwJdaa28uZskYImi"
    private const val TIMEOUT_MS = 20_000

    /**
     * Off the main thread. [driver] and [gpu] name the report's group; [ending]: how the last game ended, if it
     * didn't end well. Returns the report's id, which the player can quote in an issue. Throws when it can't send.
     */
    fun send(context: Context, driver: String, gpu: String, ending: String?, comment: String, contact: String,
             logs: String): String = post(context, REPORTER, installation(context, ID), "$driver: $gpu",
                                          ending.orEmpty(), comment, contact,
                                          JSONObject().put("driver", driver).put("gpu", gpu)
                                              .put("ending", ending.orEmpty()), logs)

    /**
     * An ACRA report to Acrarium as [reporter] ("login:password"), from the install [installation] names: [group], its
     * first line, is what Acrarium groups by; [details] follow it. [logs], when there are, go as a file, without the
     * names of the player's saves. Returns its id; throws when it can't send.
     */
    internal fun post(context: Context, reporter: String, installation: String, group: String, details: String,
                      comment: String, contact: String, custom: JSONObject, logs: String?): String {
        val id = UUID.randomUUID().toString()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = JSONObject()
            .put("REPORT_ID", id)
            .put("STACK_TRACE", listOf(group, details).filter { it.isNotEmpty() }.joinToString("\n"))
            .put("USER_CRASH_DATE", now())
            .put("INSTALLATION_ID", installation)
            .put("APP_VERSION_CODE", info.longVersionCode)
            .put("APP_VERSION_NAME", info.versionName)
            .put("ANDROID_VERSION", Build.VERSION.RELEASE)
            .put("PHONE_MODEL", Build.MODEL)
            .put("BRAND", Build.BRAND)
            .put("BUILD", JSONObject().put("DEVICE", Build.DEVICE).put("MANUFACTURER", Build.MANUFACTURER)
                .put("MARKETING_NAME", Phone.name))
            .put("USER_COMMENT", comment)
            .put("USER_EMAIL", contact)
            .put("IS_SILENT", false)
            .put("CUSTOM_DATA", custom)
        upload(reporter, report.toString(), logs?.let(::withoutSaveNames))
        return id
    }

    /** A save's name can say who plays it: "/saves/Ana's base.zip" goes as "/saves/(save).zip". */
    internal fun withoutSaveNames(logs: String) = SAVE.replace(logs, "\$1(save).zip")

    private val SAVE = Regex("""(/saves/)[^"\n/]*?\.zip""")

    /**
     * Acrarium's two ways in: the report alone as JSON, or a multipart form with the report as ACRA_REPORT and the
     * Logs as an ACRA_ATTACHMENT file.
     */
    private fun upload(reporter: String, report: String, logs: String?) {
        val boundary = "PocketFoundry" + UUID.randomUUID().toString().replace("-", "")
        val (type, body) = if (logs == null) "application/json" to report.toByteArray() else
            "multipart/form-data; boundary=$boundary" to buildString {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"ACRA_REPORT\"\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n\r\n").append(report).append("\r\n")
                append("--$boundary\r\nContent-Disposition: form-data; name=\"ACRA_ATTACHMENT\"; ")
                append("filename=\"pocket-foundry-logs.txt\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n")
                append(logs).append("\r\n--$boundary--\r\n")
            }.toByteArray()
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("User-Agent", "PocketFoundry")
            setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(reporter.toByteArray()))
            setRequestProperty("Content-Type", type)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("the report server answered $code")
        } finally {
            connection.disconnect()
        }
    }

    /** ACRA's date format, which Acrarium reads. */
    private fun now() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /**
     * A random id for this install, made the first time [key]'s sender needs it: Acrarium counts an app's reports per
     * install with it. Reports and results each have their own, so an email left in a report doesn't name the results.
     */
    internal fun installation(context: Context, key: String): String {
        val prefs = Settings.prefs(context)
        return prefs.getString(key, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(key, it).apply() }
    }

    private const val ID = "reportsId"
}
