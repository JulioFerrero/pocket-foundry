package app.pocketfoundry.core.engine

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.Locale

/**
 * How the game screen joins the X server, in logs/screen.log: Termux:X11 tells that only to Android's log, and
 * reading it from the app can bring up a system dialog.
 */
object ScreenLog {
    private var file: File? = null
    private var start = 0L
    @Volatile var announced = false
        private set

    @Synchronized
    fun begin(context: Context) {
        file = File(Paths(context).logs, "screen.log").apply { writeText("") }
        start = SystemClock.uptimeMillis()
        announced = false
    }

    @Synchronized
    fun add(line: String) {
        val file = file ?: return
        runCatching { file.appendText("%6.1f s  %s\n".format(Locale.ROOT, (SystemClock.uptimeMillis() - start) / 1000.0, line)) }
    }

    /** The X server repeats it every second until the screen connects: only the first is written. */
    fun announcement() {
        if (announced) return
        announced = true
        add("the X server's announcement reached the app")
    }
}
