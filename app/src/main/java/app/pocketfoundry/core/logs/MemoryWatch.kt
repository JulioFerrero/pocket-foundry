package app.pocketfoundry.core.logs

import app.pocketfoundry.core.runtime.Program
import app.pocketfoundry.core.system.Paths
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The game's memory and the phone's, read every 2 s while it runs. Short of memory, Android's low-memory killer closes
 * the app, the only process of ours it knows, and the game goes with it before either can log a word: so each reading
 * goes over Paths.lastMemory, which the next start adds to the game's screen.log (LastRun), and big moves go into
 * screen.log as they happen. The GPU's memory is the same RAM on phones: it's in what the phone has free.
 */
class MemoryWatch private constructor() {
    @Volatile var last: String? = null
        private set

    companion object {
        private const val POLL_MS = 2000L
        private const val STEP_KB = 128 * 1024L

        fun start(paths: Paths, game: Program): MemoryWatch {
            val watch = MemoryWatch()
            thread(name = "pocketfoundry-game-memory", isDaemon = true) {
                var logged: Pair<Long, Long>? = null
                while (game.isAlive) {
                    val status = read(File("/proc/${game.pid}/status"))
                    val phone = read(File("/proc/meminfo"))
                    val used = status["VmRSS"]
                    val free = phone["MemAvailable"]
                    if (used != null && free != null) {
                        val reading = describe(used, status["VmSwap"], free, phone["SwapFree"])
                        watch.last = reading
                        runCatching { paths.lastMemory.writeText(reading) }
                        if (logged == null || abs(used - logged.first) >= STEP_KB ||
                            abs(free - logged.second) >= STEP_KB) {
                            ScreenLog.add("memory: $reading")
                            logged = used to free
                        }
                    }
                    Thread.sleep(POLL_MS)
                }
            }
            return watch
        }

        /** "game 3215 MB (+120 MB swapped), phone free 412 MB, swap free 980 MB", from kB. */
        internal fun describe(gameKb: Long, gameSwapKb: Long?, freeKb: Long, swapFreeKb: Long?) =
            "game ${gameKb / 1024} MB" +
                (gameSwapKb?.takeIf { it >= 1024 }?.let { " (+${it / 1024} MB swapped)" } ?: "") +
                ", phone free ${freeKb / 1024} MB" +
                (swapFreeKb?.let { ", swap free ${it / 1024} MB" } ?: "")

        /** A /proc file's "Key:   123 kB" lines, in kB. */
        internal fun read(file: File): Map<String, Long> = runCatching {
            file.readLines().mapNotNull { line ->
                val key = line.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
