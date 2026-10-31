package app.pocketfoundry.core.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.pocketfoundry.core.engine.Paths
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

object Saves {
    fun all(paths: Paths): List<File> =
        File(paths.gameData, "saves").listFiles { f -> f.name.endsWith(".zip") && !f.name.endsWith(".tmp.zip") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun newest(paths: Paths): File? = all(paths).firstOrNull()

    fun export(context: Context): String {
        val folder = "${Environment.DIRECTORY_DOWNLOADS}/Pocket Foundry/Saves " +
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss").format(LocalDateTime.now())
        val resolver = context.contentResolver
        for (save in all(Paths(context))) {
            val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, save.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IOException("Couldn't write ${save.name} to Downloads")
            resolver.openOutputStream(item)!!.use { out -> save.inputStream().use { it.copyTo(out) } }
            resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return folder
    }

    /** Keeps the saves' dates, so an old one doesn't become Continue. */
    fun import(context: Context, uris: List<Uri>): Int {
        val dir = File(Paths(context).gameData, "saves").apply { mkdirs() }
        var added = 0
        for (uri in uris) {
            val incoming = File(dir, ".importing")
            context.contentResolver.openInputStream(uri)?.use { input -> incoming.outputStream().use { input.copyTo(it) } } ?: continue
            if (!isSave(incoming)) { incoming.delete(); continue }
            val base = (displayName(context, uri) ?: "imported").removeSuffix(".zip")
            var target = File(dir, "$base.zip")
            var n = 2
            while (target.exists()) {
                if (target.length() == incoming.length() && target.readBytes().contentEquals(incoming.readBytes())) break
                target = File(dir, "$base ($n).zip")
                n++
            }
            if (target.exists()) { incoming.delete(); continue }
            if (!incoming.renameTo(target)) throw IOException("Couldn't add ${target.name}")
            lastModified(context, uri)?.let { target.setLastModified(it) }
            added++
        }
        return added
    }

    private fun isSave(file: File) = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { e -> e.name.substringAfterLast('/').let { it == "level.dat0" || it == "level-init.dat" } }
        }
    }.getOrDefault(false)

    fun preview(save: File, sample: Int): Bitmap? = runCatching {
        ZipFile(save).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name.substringAfterLast('/') == "preview.jpg" } ?: return null
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }?.let(::trimBlack)
    }.getOrNull()

    /** Factorio's picture is 16:9; taken from a wider screen it carries black bands. */
    private fun trimBlack(bitmap: Bitmap): Bitmap {
        fun black(x: Int, y: Int) = bitmap.getPixel(x, y).let { Color.red(it) + Color.green(it) + Color.blue(it) < 40 }
        fun blackColumn(x: Int) = (0 until bitmap.height step 4).all { black(x, it) }
        fun blackRow(y: Int) = (0 until bitmap.width step 4).all { black(it, y) }
        var left = 0; var right = bitmap.width; var top = 0; var bottom = bitmap.height
        while (left < bitmap.width / 2 && blackColumn(left)) left++
        while (right > bitmap.width / 2 && blackColumn(right - 1)) right--
        while (top < bitmap.height / 2 && blackRow(top)) top++
        while (bottom > bitmap.height / 2 && blackRow(bottom - 1)) bottom--
        // All dark, like a night shot: keep it whole.
        if (right - left < bitmap.width / 2 || bottom - top < bitmap.height / 2) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }
}

private fun lastModified(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
        ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
}.getOrNull()?.takeIf { it > 0 }

fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
