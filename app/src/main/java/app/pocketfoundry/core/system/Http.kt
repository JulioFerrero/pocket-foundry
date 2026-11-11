package app.pocketfoundry.core.system

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GitHub, for the app's updates and its drivers. Off the main thread. */
object Http {
    /** [timeoutMs]: to connect; a read may wait twice that. */
    fun get(url: String, accept: String? = null, timeoutMs: Int = 15_000): ByteArray =
        connect(url, accept, timeoutMs) { connection -> connection.inputStream.use { it.readBytes() } }

    /** [onPercent] gets how much of [url] is in [file] so far. */
    fun download(url: String, file: File, onPercent: (Int) -> Unit) = connect(url) { connection ->
        ProgressInputStream(connection.inputStream, connection.contentLengthLong, onPercent).use { input ->
            file.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
    }

    private fun <T> connect(url: String, accept: String? = null, timeoutMs: Int = 15_000,
                            read: (HttpURLConnection) -> T): T {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs * 2
            setRequestProperty("User-Agent", "PocketFoundry")
            if (accept != null) setRequestProperty("Accept", accept)
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("GitHub answered ${connection.responseCode}")
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }
}
