package app.pocketfoundry.core.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

const val TAG = "PocketFoundry"
const val MIB = 1 shl 20

// Lazy: unit tests load this file, and they have no main looper.
val mainHandler by lazy { Handler(Looper.getMainLooper()) }

fun <T> background(name: String, work: () -> T, onDone: (Result<T>) -> Unit) {
    thread(name = "pocketfoundry-$name") {
        val result = runCatching(work)
        mainHandler.post { onDone(result) }
    }
}

val Context.isDebuggable get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

const val REPOSITORY = "JulioFerrero/pocket-foundry"
const val GITHUB = "https://github.com/$REPOSITORY"

private const val STOCK = 0xa5013371.toInt()

val Context.isStock: Boolean get() = isDebuggable || signers().any { it.hashCode() == STOCK }

private fun Context.signers(): Array<Signature> {
    @Suppress("DEPRECATION")
    val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyArray()
    return if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
}

fun Context.appVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEachBlock(1 shl 16) { chunk, n -> digest.update(chunk, 0, n) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
fun JSONArray.strings() = List(length()) { getString(it) }
fun JSONArray.objects() = List(length()) { getJSONObject(it) }

object Uris {
    fun displayName(context: Context, uri: Uri): String? = query(context, uri, OpenableColumns.DISPLAY_NAME, Cursor::getString)

    /** Only document providers have it: others leave the column out, and some refuse a column they don't know. */
    fun lastModified(context: Context, uri: Uri): Long? = try {
        query(context, uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED, Cursor::getLong)?.takeIf { it > 0 }
    } catch (e: IllegalArgumentException) {
        null
    }

    fun size(context: Context, uri: Uri): Long = query(context, uri, OpenableColumns.SIZE, Cursor::getLong) ?: 0L

    private fun <T> query(context: Context, uri: Uri, column: String, read: (Cursor, Int) -> T): T? =
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val i = cursor.getColumnIndex(column)
            if (i >= 0 && cursor.moveToFirst() && !cursor.isNull(i)) read(cursor, i) else null
        }
}
