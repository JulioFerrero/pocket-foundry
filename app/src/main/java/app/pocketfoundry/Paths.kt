package app.pocketfoundry

import android.content.Context
import java.io.File

/**
 * Where everything lives. glibc and Mesa were built for files/usr, so the
 * runtime has to be unpacked exactly there.
 */
class Paths(context: Context) {
    val files: File = context.filesDir
    val prefix = File(files, "usr")
    val lib = File(prefix, "lib")
    val tmp = File(prefix, "tmp")
    val xSocket = File(tmp, ".X11-unix/X0")
    /** Where the game's SDL sends its sound and picks up the gamepad (Bridges.kt). */
    val audioSocket = File(tmp, "pocketfoundry-audio")
    val inputSocket = File(tmp, "pocketfoundry-input")
    /** The frames per second the player chose, read by the game's SDL once a second. */
    val frameRate = File(tmp, "pocketfoundry-frame-rate")

    /** The user's own Factorio, unpacked from the archive they imported. */
    val game = File(files, "game")
    val gameBinary = File(game, "bin/arm64/factorio")

    /** Factorio's write-data: saves, mods, config, its own log. */
    val gameData = File(files, "factorio")
    val config = File(gameData, "config/config.ini")

    val home = File(files, "home")
    val logs = File(files, "logs")

    private val nativeLibs = File(context.applicationInfo.nativeLibraryDir)
    val loader = File(nativeLibs, "libld-linux.so")
    val xlorie = File(nativeLibs, "libXlorie.so")
}
