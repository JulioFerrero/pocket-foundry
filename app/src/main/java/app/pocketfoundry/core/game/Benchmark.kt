package app.pocketfoundry.core.game

import android.content.Context
import app.pocketfoundry.core.runtime.Program
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.CpuClocks
import app.pocketfoundry.core.system.Paths
import java.io.File
import java.io.IOException

/**
 * Factorio's own benchmark of a save, as Factorio Bench runs it (factoriobench.com/scripts/benchmark.sh): the
 * simulation alone, without graphics or mods, [RUNS] times [TICKS] updates, the best run as updates a second, so a
 * phone's number compares with a desktop's on the same map. Phones also keep their cores below their top, which that
 * number alone doesn't show: the clocks while it ran go with it (CpuClocks).
 */
object Benchmark {
    const val TICKS = 1000
    const val RUNS = 5
    private const val POLL_MS = 500L
    /** "Performed 1000 updates in 14188.013 ms": one line a run. */
    private val RUN = Regex("""Performed (\d+) updates in ([0-9.]+) ms""")

    class Result(val save: String, val runsMs: List<Double>, val clocks: List<CpuClocks.Group>) {
        val bestMs get() = runsMs.min()
        val ups get() = TICKS * 1000.0 / bestMs
    }

    /**
     * Runs it, blocking, till Factorio is done or [stopped] says so. [onRun]: the runs done so far. Throws with
     * Factorio's own words when it gives no timings: a save that needs mods, or one this version can't load.
     */
    fun run(context: Context, save: File, stopped: () -> Boolean, onRun: (Int) -> Unit): Result {
        Runtime.prepare(context)
        val paths = Paths(context)
        // Factorio Bench's: no mods, whatever the player has on.
        val noMods = File(paths.tmp, "benchmark-mods").apply { deleteRecursively(); mkdirs() }
        val command = listOf(paths.gameBinary.path, "--config", paths.config.path, "--mod-directory", noMods.path,
                             "--benchmark", save.path, "--benchmark-ticks", "$TICKS", "--benchmark-runs", "$RUNS")
        val clocks = CpuClocks()
        val program = Program.start(paths, command, paths.benchmarkLog)
        var reported = 0
        while (program.waitFor(0) == null) {
            if (stopped()) {
                program.kill()
                program.waitFor()
                throw IOException("Stopped.")
            }
            clocks.sample()
            val done = runs(paths.benchmarkLog).size
            if (done != reported) onRun(done).also { reported = done }
            Thread.sleep(POLL_MS)
        }
        val runs = runs(paths.benchmarkLog)
        if (runs.isEmpty()) throw IOException(why(paths.benchmarkLog))
        return Result(save.nameWithoutExtension, runs, clocks.groups())
    }

    internal fun runs(log: File): List<Double> = runCatching { log.readText() }.getOrDefault("")
        .let { text -> RUN.findAll(text).map { it.groupValues[2].toDouble() }.toList() }

    /** Factorio's last error line, for a run that gave no timings. */
    private fun why(log: File): String {
        val lines = runCatching { log.readLines() }.getOrDefault(emptyList())
        return lines.lastOrNull { "Error" in it || "error" in it }?.substringAfter("Error")?.trim(' ', ':')
            ?.let { "Factorio couldn't run it: $it" } ?: "Factorio gave no timings. See Logs."
    }
}
