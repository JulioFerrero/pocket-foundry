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
import java.io.File
import java.io.IOException

/** Installs the player's own Factorio from factorio.com's Linux arm64 .tar.xz. The app never bundles the game. */
object GameImport {
    /** The installed game's version, or null when there's none the app can play ([refusal]). */
    fun installedVersion(context: Context): String? {
        val paths = Paths(context)
        if (refusal(paths, paths.game) != null || !paths.gameInfo.exists()) return null
        return try {
            JSONObject(paths.gameInfo.readText()).getString("version")
        } catch (e: Exception) {
            Log.w(TAG, "The game's info.json is unreadable: import it again", e)
            null
        }
    }

    /** Why the installed game can't be played, for Welcome: null when it can, or when there's none. */
    fun installedRefusal(context: Context): String? {
        val paths = Paths(context)
        return if (paths.game.exists()) refusal(paths, paths.game) else null
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
        refusal(paths, staging)?.let {
            staging.deleteRecursively()
            throw IOException(it)
        }
        paths.game.deleteRecursively()
        if (!staging.renameTo(paths.game)) throw IOException("Couldn't move the game into place")
        Log.i(TAG, "Imported the game in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /**
     * Why the unpacked game in [dir] can't be played, or null. Factorio's headless server has the same binary but
     * none of the graphics, and only says so once started: its entity sprites are most of the full game's 1.4 GB, and
     * the server has the folder with only their .lua files in it.
     */
    fun refusal(paths: Paths, dir: File): String? {
        val binary = paths.gameBinary.relativeTo(paths.game)
        val sprites = File(paths.baseGraphics, "entity").relativeTo(paths.game)
        return when {
            !dir.resolve(binary).exists() -> "That isn't the Linux arm64 build of Factorio ($binary is missing)"
            !hasSprites(dir.resolve(sprites)) -> HEADLESS
            else -> null
        }
    }

    const val HEADLESS = "That's Factorio's headless server, which has no graphics: import the full game."

    /** The full game's entity sprites are PNGs. The headless server keeps only their .lua files there. */
    private fun hasSprites(dir: File) = dir.walk().any { it.isFile && it.extension == "png" }
}
