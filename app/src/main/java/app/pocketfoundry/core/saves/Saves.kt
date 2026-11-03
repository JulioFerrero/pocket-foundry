package app.pocketfoundry.core.saves

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Uris
import app.pocketfoundry.core.system.sha256
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

object Saves {
    fun all(paths: Paths): List<File> =
        paths.saves.listFiles { f -> f.name.endsWith(".zip") && !f.name.endsWith(".tmp.zip") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun newest(paths: Paths): File? = all(paths).firstOrNull()

    /** Autosaves cut short by a kill: Factorio offers them as Continue and can't open them. */
    fun deleteInterrupted(paths: Paths) {
        paths.saves.listFiles { f -> f.name.endsWith(".tmp.zip") }?.forEach { it.delete() }
    }

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

    /** Copies the picked saves in, skipping non-saves and exact copies. Returns how many were added. */
    fun import(context: Context, uris: List<Uri>): Int {
        val dir = Paths(context).saves.apply { mkdirs() }
        var added = 0
        for (uri in uris) {
            val incoming = File(dir, ".importing")
            context.contentResolver.openInputStream(uri)?.use { input -> incoming.outputStream().use { input.copyTo(it) } } ?: continue
            val target = if (isSave(incoming)) freeName(dir, Uris.displayName(context, uri) ?: "imported", incoming) else null
            if (target == null) { incoming.delete(); continue }
            if (!incoming.renameTo(target)) throw IOException("Couldn't add ${target.name}")
            // Keep the save's date, so an old one doesn't become Continue.
            Uris.lastModified(context, uri)?.let { target.setLastModified(it) }
            added++
        }
        return added
    }

    /** name.zip, else "name (2).zip" and on; null when one of them already holds [incoming]. */
    private fun freeName(dir: File, name: String, incoming: File): File? {
        val base = name.removeSuffix(".zip")
        var target = File(dir, "$base.zip")
        var n = 2
        while (target.exists()) {
            // Hashed a block at a time: a save can be too big to read whole.
            if (target.length() == incoming.length() && target.sha256() == incoming.sha256()) return null
            target = File(dir, "$base (${n++}).zip")
        }
        return target
    }

    internal fun isSave(file: File) = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { e -> e.name.substringAfterLast('/').let { it == "level.dat0" || it == "level-init.dat" } }
        }
    }.getOrDefault(false)

    /** The save as of its last change: a preview made under another key is out of date. */
    fun previewKey(save: File) = "${save.path}@${save.lastModified()}"

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
