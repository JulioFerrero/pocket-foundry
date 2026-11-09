package app.pocketfoundry.core.game

import android.content.Context
import android.net.Uri
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.ProgressInputStream
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.Uris
import app.pocketfoundry.core.system.untarXz
import org.json.JSONObject
import java.io.IOException

/** Installs the player's own Factorio from factorio.com's Linux arm64 .tar.xz. The app never bundles the game. */
object GameImport {
    fun installedVersion(context: Context): String? {
        val paths = Paths(context)
        if (!paths.gameBinary.exists() || !paths.gameInfo.exists()) return null
        return try {
            JSONObject(paths.gameInfo.readText()).getString("version")
        } catch (e: Exception) {
            Log.w(TAG, "The game's info.json is unreadable: import it again", e)
            null
        }
    }

    /** [onPercent] gets how much of the compressed file has been read. */
    fun import(context: Context, uri: Uri, onPercent: (Int) -> Unit) {
        val paths = Paths(context)
        val started = System.nanoTime()
        val staging = paths.gameStaging
        staging.deleteRecursively()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the selected file")
        // The archive's top folder is factorio/.
        ProgressInputStream(input, Uris.size(context, uri), onPercent).use { untarXz(it, staging, strip = 1) }
        val binary = paths.gameBinary.relativeTo(paths.game)
        if (!staging.resolve(binary).exists()) {
            staging.deleteRecursively()
            throw IOException("That isn't the Linux arm64 build of Factorio ($binary is missing)")
        }
        paths.game.deleteRecursively()
        if (!staging.renameTo(paths.game)) throw IOException("Couldn't move the game into place")
        Log.i(TAG, "Imported the game in ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
