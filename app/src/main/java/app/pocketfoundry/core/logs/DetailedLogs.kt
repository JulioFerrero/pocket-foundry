package app.pocketfoundry.core.logs

import android.content.Context
import app.pocketfoundry.core.runtime.Program
import app.pocketfoundry.core.system.Settings
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Settings' Detailed logs. The game and the X server get [environment]: our Mesa and Termux:X11 patches then log how
 * each frame gets to the screen (POCKETFOUNDRY_DETAILED_LOGS: runtime/mesa/patches 0004 and 0005,
 * third_party/patches/termux-x11 0011), the X server hands the switch to the app's screen renderer, and Mesa prints
 * every GL error. The app watches the game's memory.
 */
object DetailedLogs {
    internal const val SWITCH = "POCKETFOUNDRY_DETAILED_LOGS"
    private const val POLL_MS = 2000L
    private const val STEP_KB = 256 * 1024L

    fun on(context: Context) = Settings(context).detailedLogs

    fun environment(context: Context) =
        if (on(context)) mapOf(SWITCH to "1", "MESA_DEBUG" to "1") else emptyMap()

    class MemoryWatch {
        @Volatile var last: String? = null
            internal set
    }

    fun watchMemory(game: Program): MemoryWatch {
        val watch = MemoryWatch()
        thread(name = "pocketfoundry-game-memory", isDaemon = true) {
            var logged: Pair<Long, Long>? = null
            while (game.isAlive) {
                val used = kb(File("/proc/${game.pid}/status"), "VmRSS:")
                val free = kb(File("/proc/meminfo"), "MemAvailable:")
                if (used != null && free != null) {
                    watch.last = "game ${used / 1024} MB, phone free ${free / 1024} MB"
                    if (logged == null || abs(used - logged.first) >= STEP_KB || abs(free - logged.second) >= STEP_KB) {
                        ScreenLog.add("memory: ${watch.last}")
                        logged = used to free
                    }
                }
                Thread.sleep(POLL_MS)
            }
        }
        return watch
    }

    private fun kb(file: File, key: String) = runCatching {
        file.useLines { lines -> lines.firstOrNull { it.startsWith(key) } }
            ?.substringAfter(key)?.trim()?.substringBefore(' ')?.toLong()
    }.getOrNull()
}
