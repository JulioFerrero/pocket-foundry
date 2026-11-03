package app.pocketfoundry.core.game

import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Settings' Detailed logs. The game and the X server get [environment]: our Mesa and Termux:X11 patches then log how
 * each frame gets to the screen, the X server hands the switch to the app's screen renderer, and Mesa prints every GL
 * error. The app watches the game's memory.
 */
object DetailedLogs {
    const val ENV = "POCKETFOUNDRY_DETAILED_LOGS"
    val environment = mapOf(ENV to "1", "MESA_DEBUG" to "1")
    private const val POLL_MS = 2000L
    private const val STEP_KB = 256 * 1024L

    @Volatile private var lastMemory: String? = null

    /** The game's and the phone's memory at the last look, once: for the line the game's exit writes. */
    fun takeLastMemory() = lastMemory.also { lastMemory = null }

    /** Logs the game's resident memory and the phone's available memory, from /proc, whenever either moves 256 MB. */
    fun watchMemory(process: Process, pid: Int) {
        lastMemory = null
        thread(name = "pocketfoundry-game-memory", isDaemon = true) {
            var logged: Pair<Long, Long>? = null
            while (process.isAlive) {
                val game = kb(File("/proc/$pid/status"), "VmRSS:")
                val free = kb(File("/proc/meminfo"), "MemAvailable:")
                if (game != null && free != null) {
                    lastMemory = "game ${game / 1024} MB, phone free ${free / 1024} MB"
                    if (logged == null || abs(game - logged.first) >= STEP_KB || abs(free - logged.second) >= STEP_KB) {
                        ScreenLog.add("memory: $lastMemory")
                        logged = game to free
                    }
                }
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun kb(file: File, key: String) = runCatching {
        file.useLines { lines -> lines.firstOrNull { it.startsWith(key) } }
            ?.substringAfter(key)?.trim()?.substringBefore(' ')?.toLong()
    }.getOrNull()
}
