package app.pocketfoundry.core.logs

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.TAG
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.concurrent.thread

/**
 * How a game or a driver check goes, from the app's side, in logs/screen.log: the X server, the game screen joining
 * it, memory warnings, and the frame lines of Detailed logs. Termux:X11 logs the screen's side only to logcat, and
 * reading logcat from the app can pop up a system dialog.
 */
object ScreenLog {
    /**
     * The lines our Termux:X11 patches write for it (third_party/patches/termux-x11): the screen's buffers (0010) on
     * the app's stderr, and with Detailed logs what it draws (0011, the app's stderr) and the frames it's sent (0011,
     * the X server's output).
     */
    internal val SCREEN_LINES = listOf("buffer ", "screen: ", "frames: ")

    private var file: File? = null
    private var start = 0L
    @Volatile var driver: String? = null
        private set
    @Volatile var announced = false
        private set

    fun isScreenLine(line: String) = SCREEN_LINES.any(line::startsWith)

    /** At the app's start: the screen's lines on its stderr come here from then on. */
    fun install() {
        try {
            val (read, write) = Os.pipe()
            Os.dup2(write, OsConstants.STDERR_FILENO)
            Os.close(write)
            thread(isDaemon = true, name = "screen stderr") {
                FileInputStream(read).bufferedReader().forEachLine { if (isScreenLine(it)) add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "screen.log won't have the screen's lines", e)
        }
    }

    @Synchronized
    fun begin(context: Context, driver: String) {
        file = Paths(context).screenLog.apply { writeText("") }
        start = SystemClock.uptimeMillis()
        this.driver = driver
        announced = false
    }

    @Synchronized
    fun add(line: String) {
        val file = file ?: return
        try {
            file.appendText("%6.1f s  %s\n".format(Locale.ROOT, (SystemClock.uptimeMillis() - start) / 1000.0, line))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't write screen.log: $line", e)
        }
    }

    /** Before a game, the driver it gets, when the driver check moved on to another. */
    fun playing(driver: String) {
        this.driver = driver
    }

    /** The X server repeats it every second until the screen connects: only the first is written. */
    fun announcement() {
        if (announced) return
        announced = true
        add("the X server's announcement reached the app")
    }
}
