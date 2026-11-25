package app.pocketfoundry.core.logs

import android.content.Context
import android.os.Build
import android.util.Log
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.drivers.Gpu
import app.pocketfoundry.core.game.FrameCounter
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.X11Prefs
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * How a game ran, sent after it to Pocket Foundry's report server, only once the player said yes to it
 * (Settings.shareResults): the phone, its GPU and driver, the game's settings, its frames a second while it drew and
 * how it ended, and the game's logs when it didn't end well. No saves and nothing personal; before that yes, nothing
 * is measured or sent. Acrarium groups them by driver, outcome and GPU. What's kept and for how long:
 * Reports.PRIVACY.
 */
object GameResults {
    /** Acrarium's reporter for its "Pocket Foundry results" app: it can send and nothing else. */
    private const val REPORTER = "vcrMPQzmdNUKoUDP:tPDxgYHxxSPRT0TI"
    private const val EVERY_MS = 5_000L
    private const val LOG_LINES = 300

    /** How a game ended, for Acrarium to group by: the words stay as they are, or its groups split. */
    enum class Outcome(val words: String) {
        PLAYED("played"), STOPPED("stopped"), DIDNT_START("didn't start"), FAILED("failed"),
        /** The player quit after a long wait for the game to be ready (GameEnding.quitLoading). */
        QUIT_LOADING("quit while loading"),
        /** Android closed the app, and the game went with it: sent at the app's next start (LastRun). */
        APP_CLOSED("app closed");

        /** Anything but a game played comes with the logs that say why. */
        val withLogs get() = this != PLAYED

        companion object {
            fun of(outcome: Drivers.Outcome) = when (outcome) {
                Drivers.Outcome.RAN -> PLAYED
                Drivers.Outcome.STOPPED -> STOPPED
                Drivers.Outcome.DIDNT_START -> DIDNT_START
                Drivers.Outcome.FAILED -> FAILED
            }
        }
    }

    private var sampler: Thread? = null
    private val rates = mutableListOf<Float>()

    /** As a game starts, if the player shares results: its frames a second, every few seconds while it draws. */
    @Synchronized
    fun start(context: Context, paths: Paths) {
        stop()
        synchronized(rates) { rates.clear() }
        if (Settings(context).shareResults != Settings.ON) return
        sampler = thread(name = "pocketfoundry-game-results", isDaemon = true) {
            val counter = FrameCounter(paths.frames)
            var last: FrameCounter.Sample? = null
            var playing = false
            try {
                while (true) {
                    Thread.sleep(EVERY_MS)
                    // From the map on: the loading screen's few frames a second aren't the game's.
                    if (!playing) {
                        val log = runCatching { paths.gameLog.readText() }.getOrDefault("")
                        playing = MAP_LOADED.any { it in log }
                        continue
                    }
                    val now = counter.sample() ?: continue
                    val before = last
                    last = now
                    // Paused, in the background or loading: no frames, no rate.
                    if (before == null || now.frames <= before.frames || now.swappedNs <= before.swappedNs) continue
                    val rate = (now.frames - before.frames) * 1e9f / (now.swappedNs - before.swappedNs)
                    synchronized(rates) { rates += rate }
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    @Synchronized
    private fun stop() {
        sampler?.interrupt()
        sampler = null
    }

    /**
     * As the game exits, or at the app's next start for one Android closed the app in: [ending] how it ended in words
     * when it didn't end well. Sent in the background; a phone without a connection just doesn't send it.
     */
    fun finish(context: Context, paths: Paths, driverId: String, outcome: Outcome, ending: String?) {
        stop()
        if (Settings(context).shareResults != Settings.ON) return
        val drawn = synchronized(rates) { rates.sorted().also { rates.clear() } }
        // Read now: a next game, started straight away, writes over them.
        val log = runCatching { paths.gameLog.readText() }.getOrDefault("")
        val logs = if (outcome.withLogs) logs(paths) else null
        val app = context.applicationContext
        thread(name = "pocketfoundry-game-results-send", isDaemon = true) {
            runCatching { send(app, driverId, outcome, ending, drawn, log, logs) }
                .onFailure { Log.w(TAG, "Game results not sent", it) }
        }
    }

    private fun send(context: Context, driverId: String, outcome: Outcome, ending: String?, drawn: List<Float>,
                     log: String, logs: String?) {
        val settings = Settings(context)
        val driver = Drivers.find(context, driverId)
        val name = driver?.name ?: driverId
        val gpu = Gpu.name ?: "GPU unknown"
        val (width, height) = X11Prefs(context).let { it.gameSize(it.resolutionScale) }
        val custom = JSONObject()
            .put("outcome", outcome.words)
            .put("driver", name)
            .put("driverVersion", driver?.let { Drivers.version(context, it) }.orEmpty())
            .put("gpu", gpu)
            .put("factorio", FACTORIO.find(log)?.groupValues?.get(1).orEmpty())
            .put("resolution", "${width}x$height")
            .put("frameRate", settings.frameRate)
            .put("sprites", settings.spriteQuality)
            .put("graphics", if (settings.lightGraphics) "light" else "full")
            .put("fastCpu", settings.fastCpu)
            .put("fps", percentile(drawn, 0.5))
            .put("fpsLow", percentile(drawn, 0.1))
            .put("minutesDrawn", (drawn.size * EVERY_MS / 60_000.0 * 10).roundToInt() / 10.0)
            .put("android", Build.VERSION.RELEASE)
        val details = listOfNotNull(
            ending,
            ("FPS ${custom.optString("fps")} (lowest tenth ${custom.optString("fpsLow")}) over " +
                "${custom.optString("minutesDrawn")} minutes drawn").takeIf { drawn.isNotEmpty() },
        )
        Reports.post(context, REPORTER, id(context), "$name (${outcome.words}): $gpu", details.joinToString("\n"),
                     "", "", custom, logs)
    }

    /**
     * This install's random results id, which Settings shows so a player can ask for theirs to be deleted. 0.9.4-beta.1
     * sent reports and results under one; results keep it, so the ones sent already stay findable by it.
     */
    private fun id(context: Context): String {
        val prefs = Settings.prefs(context)
        prefs.getString(SHARED_ID, null)?.takeIf { !prefs.contains(ID) }?.let {
            prefs.edit().putString(ID, it).remove(SHARED_ID).apply()
        }
        return Reports.installation(context, ID)
    }

    /** As Settings shows it: the id's start, enough to find its results; none before the first one is sent. */
    fun shownId(context: Context): String? {
        val prefs = Settings.prefs(context)
        return (prefs.getString(ID, null) ?: prefs.getString(SHARED_ID, null))?.take(8)
    }

    /** The [share] lowest of the rates: 0.5 the middle, 0.1 the slowest tenth. 0 without any. */
    internal fun percentile(sorted: List<Float>, share: Double): Int =
        if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * share).roundToInt()].roundToInt()

    /** What a game that didn't end well needs: the ends of the screen's, the game's and the driver check's logs. */
    private fun logs(paths: Paths) = listOf(paths.screenLog, paths.gameLog, paths.driverCheckLog).joinToString("\n") {
        "── ${it.name} ──\n" + tail(it)
    }

    private fun tail(file: File) = runCatching { file.readLines().takeLast(LOG_LINES).joinToString("\n") }
        .getOrDefault("(none)")

    private const val ID = "resultsId"
    private const val SHARED_ID = "reportInstallation"

    private val FACTORIO = Regex("""Factorio (\d+\.\d+\.\d+ \(build \d+, [^)]*\))""")
    /** What Factorio's log says as a save's map is in: a new map's script, or a save's. */
    private val MAP_LOADED = listOf("Loading script.dat", "Checksum for script __level__")
}
