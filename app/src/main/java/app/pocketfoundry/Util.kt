package app.pocketfoundry

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.concurrent.thread

const val TAG = "PocketFoundry"
const val MIB = 1 shl 20

// Lazy: unit tests load this file, and they have no main looper.
val mainHandler by lazy { Handler(Looper.getMainLooper()) }

val Context.isDebuggable get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

const val GITHUB = "https://github.com/JulioFerrero/pocket-foundry"

// SHA-256 of the certificate releases are signed with (tools/release.sh checks the same key). A fork changes it.
private const val RELEASE_CERT = "0e301617d64bc294bcc2d1dbee71a8c83f8fcc7b7c3616edc26e9b2e89c7e35f"

/** Signed with Pocket Foundry's release key. Debug builds count: they're signed with the developer's own key. */
val Context.isGenuine: Boolean get() = isDebuggable || RELEASE_CERT in signerDigests()

private fun Context.signerDigests(): List<String> {
    @Suppress("DEPRECATION")
    val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
    val certs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
    return certs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }
}

fun Context.appVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEachBlock(1 shl 16) { chunk, n -> digest.update(chunk, 0, n) }
    return digest.digest().hex()
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
fun JSONArray.strings() = List(length()) { getString(it) }
fun JSONArray.objects() = List(length()) { getJSONObject(it) }

/** What the app a file was picked from tells about it. */
object Uris {
    fun displayName(context: Context, uri: Uri): String? = query(context, uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }

    fun lastModified(context: Context, uri: Uri): Long? = runCatching {
        query(context, uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED) { if (it.isNull(0)) null else it.getLong(0) }
    }.getOrNull()?.takeIf { it > 0 }

    /** 0 when unknown. */
    fun size(context: Context, uri: Uri): Long = query(context, uri, OpenableColumns.SIZE) { it.getLong(0) } ?: 0L

    private fun <T> query(context: Context, uri: Uri, column: String, read: (Cursor) -> T?): T? =
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) read(it) else null }
}

private val serving = HashSet<File>()

/**
 * The game can't load Android's libraries, so its sound and gamepad reach the app through these sockets, from our
 * SDL (runtime/sdl/pocketfoundry/SDL_pocketfoundry.c). Starts once per socket. One client at a time; listens again
 * when it goes. In the app's private files rather than the abstract namespace, which other apps share.
 */
internal fun serve(file: File, handle: (LocalSocket) -> Unit) {
    synchronized(serving) { if (!serving.add(file)) return }
    thread(name = file.name, isDaemon = true) {
        while (true) {
            try {
                file.delete()
                // Keep the bound socket referenced: its cleanup on GC would close the server's descriptor.
                LocalSocket(LocalSocket.SOCKET_STREAM).use { bound ->
                    bound.bind(LocalSocketAddress(file.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    val server = LocalServerSocket(bound.fileDescriptor)
                    while (true) server.accept().use(handle)
                }
            } catch (e: IOException) {
                Log.e(TAG, "${file.name}: listening again after $e")
                Thread.sleep(1000)
            }
        }
    }
}
