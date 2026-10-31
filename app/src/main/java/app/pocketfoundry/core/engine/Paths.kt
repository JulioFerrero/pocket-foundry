package app.pocketfoundry.core.engine

import android.content.Context
import java.io.File

/** glibc and Mesa were built for files/usr: the runtime has to be unpacked exactly there. */
class Paths(context: Context) {
    val files: File = context.filesDir
    val prefix = File(files, "usr")
    val lib = File(prefix, "lib")
    val tmp = File(prefix, "tmp")
    val xSocket = File(tmp, ".X11-unix/X0")
    val audioSocket = File(tmp, "pocketfoundry-audio")
    val inputSocket = File(tmp, "pocketfoundry-input")
    /** Read by the game's SDL once a second. */
    val frameRate = File(tmp, "pocketfoundry-frame-rate")

    val game = File(files, "game")
    val gameBinary = File(game, "bin/arm64/factorio")

    val gameData = File(files, "factorio")
    val config = File(gameData, "config/config.ini")

    val home = File(files, "home")
    val logs = File(files, "logs")

    private val nativeLibs = File(context.applicationInfo.nativeLibraryDir)
    val loader = File(nativeLibs, "libld-linux.so")
    val xlorie = File(nativeLibs, "libXlorie.so")
}
