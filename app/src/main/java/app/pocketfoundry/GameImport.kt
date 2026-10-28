package app.pocketfoundry

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The user's own Factorio, from the factorio.com Linux arm64 .tar.xz: never ship it. */
object GameImport {
    fun installedVersion(context: Context): String? {
        val paths = Paths(context)
        val info = File(paths.game, "data/base/info.json")
        if (!paths.gameBinary.exists() || !info.exists()) return null
        return runCatching { JSONObject(info.readText()).getString("version") }.getOrNull()
    }

    /** [onProgress] gets 0..1 of the compressed file read. */
    fun import(context: Context, uri: Uri, onProgress: (Float) -> Unit) {
        val paths = Paths(context)
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        val started = System.nanoTime()
        val staging = File(paths.files, "game.importing")
        staging.deleteRecursively()

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Can't open the selected file")
        var last = -1
        CountingInputStream(input) { read ->
            val percent = if (size > 0) (read * 100 / size).toInt() else 0
            if (percent != last) { last = percent; onProgress(percent / 100f) }
        }.use { untarXz(it, staging, strip = 1) }   // the archive's top folder is factorio/

        if (!File(staging, "bin/arm64/factorio").exists()) {
            staging.deleteRecursively()
            throw IOException("That isn't the Linux arm64 build of Factorio (bin/arm64/factorio is missing)")
        }
        paths.game.deleteRecursively()
        if (!staging.renameTo(paths.game)) throw IOException("Couldn't move the game into place")
        Log.i("PocketFoundry", "Imported the game in ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
