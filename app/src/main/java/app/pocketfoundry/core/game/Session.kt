package app.pocketfoundry.core.game

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import app.pocketfoundry.core.controller.InputBridge
import app.pocketfoundry.core.controller.gamepadConnected
import app.pocketfoundry.core.controller.Input
import app.pocketfoundry.core.controller.keyboardConnected
import app.pocketfoundry.core.drivers.DriverCheck
import app.pocketfoundry.core.drivers.DriverRecords
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.drivers.Route
import app.pocketfoundry.core.logs.DetailedLogs
import app.pocketfoundry.core.logs.GameResults
import app.pocketfoundry.core.logs.MemoryWatch
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.runtime.DebugEnv
import app.pocketfoundry.core.runtime.Program
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.runtime.Sdl
import app.pocketfoundry.core.saves.Saves
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.X11Prefs
import app.pocketfoundry.core.system.mainHandler
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.json.JSONObject

/** Runs a game: Termux:X11's X server, our window manager and Factorio, each a process of its own. */
object Session {
    enum class State { STOPPED, RUNNING, PAUSED, QUITTING }

    /**
     * [exit] is Factorio's when it failed, null after a normal quit. [noWindow] says it never opened its window: the
     * graphics driver didn't start. [failure]: the driver failed it later, at [stage]. [closedBy]: the app went with
     * it, said in a sentence (LastRun). [gameError]: Factorio stopped by itself, for a mod or the headless build, in
     * its words (GameEnding.gameError).
     */
    class Ending(val driver: String, val exit: Program.Exit?, val noWindow: Boolean,
                 val failure: GameEnding.Failure? = null, val stage: GameEnding.Stage? = null,
                 val closedBy: String? = null, private val closedForMemory: Boolean = false,
                 val gameError: String? = null) {
        /** Android, or its low-memory killer, ended the game for memory. */
        val outOfMemory get() = closedForMemory || failure == GameEnding.Failure.OUT_OF_MEMORY
    }

    /** A files/usr/bin program that DebugPad or debug.env's test.program may run: no paths. */
    private val TOOL_NAME = Regex("[a-z0-9-]+")

    /** A Galaxy A03s's ART, without its boot image, needs 6 s to reach the X server's main. */
    private const val X_START_TIMEOUT_MS = 30_000L
    private const val X_POLL_MS = 50L
    private const val KILL_AFTER_MS = 10_000L
    private const val WINDOW_TIMEOUT_MS = 60_000L
    private const val LOG_POLL_MS = 250L
    private const val WINDOW_OPENED = "Initialised OpenGL"
    private const val DEVICE_LOST = "zink: DEVICE LOST!"
    private const val CRASHED = "Factorio crashed"
    /** The line Factorio writes as it opens its crash dialog, after the stack trace. */
    private const val CRASH_DIALOG = "Unexpected error occurred"
    private const val CRASH_TRACE_MS = 20_000L
    /** Factorio's log as it quits: "Quitting: user-quit." from its menu, "Quitting: signal." for the app's quit. */
    private const val QUITTING = "Quitting: "
    private const val QUIT_HANG_MS = 10_000L
    /** How long to wait for Factorio to write its crash trace (CrashFrames.TRACE_DONE). */
    private const val CRASH_TRACE_WAIT_MS = 5_000L
    private val MARKER_LENGTH = maxOf(DEVICE_LOST.length, CRASHED.length, CRASH_DIALOG.length, QUITTING.length)
    private const val DEVICE_POLL_MS = 1_000L
    /**
     * glibc gives blocks of 1 MB and more back to Android as soon as the game frees them, instead of keeping them for
     * later. Loading the sprites peaked 18% lower on a vivo X200 Ultra (1.69 GB -> 1.39), and the game held 23% less
     * once loaded: room a phone with little memory needs to get through a first load. Options can change it.
     */
    private val MEMORY = mapOf("GLIBC_TUNABLES" to
                                   "glibc.malloc.mmap_threshold=1048576:glibc.malloc.trim_threshold=1048576")
    private val NO_WINDOW = listOf("Failed to create an application window", "Failed to create OpenGL context",
                                   "Factorio crashed")

    /** The driver check got no OpenGL from [driverName]: the game wasn't started on it, and it's passed over. */
    class NoOpenGL(driverName: String) :
        IOException("$driverName gives the game no OpenGL on this phone: choose another in Settings -> Driver.")

    @Volatile var state = State.STOPPED
        private set
    val inGame get() = state != State.STOPPED
    val live get() = state == State.RUNNING || state == State.PAUSED
    /** Factorio can't change its input mode until it restarts. */
    @Volatile var input = Input.PAD
        private set
    /** Factorio is in its keyboard-and-mouse mode. */
    val keyboard get() = input != Input.PAD
    @Volatile var lastGame: Ending? = null
        private set

    /**
     * At the app's start: a game the app went down with, in screen.log, as [lastGame] (LastRun) and in the results,
     * unless a new game has started by the time Android has said why, and written over its logs.
     */
    fun checkLastRun(context: Context) {
        val app = context.applicationContext
        thread(name = "pocketfoundry-last-run", isDaemon = true) {
            GameResults.sendKept(app)
            val ending = LastRun.check(app) ?: return@thread
            synchronized(this) {
                if (inGame) return@thread
                lastGame = ending
                GameResults.finish(app, Paths(app), ending.driver, GameResults.Outcome.APP_CLOSED, ending.closedBy)
            }
            mainHandler.post { listeners.forEach { it() } }
        }
    }

    /**
     * A running game, with what its watchers find out. [testing]: debug.env's test.program runs in the game's place,
     * so its ending says nothing about the driver.
     */
    private class Game(val program: Program, val driver: String, val testing: Boolean, val memory: MemoryWatch) {
        @Volatile var windowOpened = false
        @Volatile var noWindow = false
        @Volatile var deviceLost = false
        /** Quit from Factorio's own menu, then hung instead of exiting: stopped, and a quit all the same. */
        @Volatile var quitHung = false
        /** Its crash's frames are in screen.log already (logCrashFrames). */
        @Volatile var crashLogged = false
        val startedAt = SystemClock.elapsedRealtime()
    }

    private var xServer: Process? = null
    private var wm: Program? = null
    private var game: Game? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Listeners run on the main thread. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /**
     * Throws if the game didn't start. [onStep]: the driver, should it still need downloading or checking (the app's
     * start sets it up, setUpDriver), and how far along, for the start screen.
     */
    @Synchronized
    fun start(context: Context, gameArgs: List<String>, onStep: (String, Float) -> Unit = { _, _ -> }) {
        if (inGame) return
        val paths = Paths(context)
        Runtime.prepare(context)
        ScreenLog.begin(context, Drivers.chosen(context))
        LastRun.opened(context, paths, Drivers.chosen(context))
        startBridges(context, paths)
        try {
            startXServer(context, paths)
            val driver = setUpDriver(context, paths, onStep)
            ScreenLog.playing(driver)
            wm = Program.start(paths, listOf(paths.windowManager.path), paths.wmLog)
            launchGame(context, paths, driver, gameArgs)
        } catch (e: Exception) {
            // A game already launched goes too, unrecorded: its ending says nothing about the driver.
            game?.let {
                game = null
                it.program.kill()
                setState(State.STOPPED)
            }
            stopServers()
            LastRun.closed(context)
            throw e
        }
    }

    /**
     * The driver for this phone, ready before any game: at the app's start, which covers a first install, an update
     * and a phone update, and when the driver list or the player's downloads change (LauncherActivity). The chosen
     * driver is downloaded and checked where this version of it hasn't been; one that can't play here stops being
     * recommended (Drivers.recommended), and the next in line gets the same, until one can or the player's own choice
     * comes up. [onStep]: what it's doing and how far along, for the start screen. Returns the driver.
     */
    @Synchronized
    fun setUpDriver(context: Context, onStep: (String, Float) -> Unit = { _, _ -> }): String {
        check(!inGame) { "Quit the game first." }
        val paths = Paths(context)
        try {
            return setUpDriver(context, paths, onStep)
        } finally {
            stopServers()
        }
    }

    private fun setUpDriver(context: Context, paths: Paths, onStep: (String, Float) -> Unit): String {
        while (true) {
            Drivers.prepare(context) { name, percent -> onStep("Downloading $name", percent / 100f) }
            val driver = Drivers.chosen(context)
            val environment = gameEnvironment(context, paths, driver)
            if (DriverCheck.needed(context, paths, driver, environment)) {
                val found = checkNow(context, paths, driver, environment, onStep)
                val next = Drivers.chosen(context)
                if (next != driver) {
                    // Passed, yet another comes first now: driver downloads turned on while it was checked.
                    val name = Drivers.find(context, driver)?.name ?: driver
                    val nextName = Drivers.find(context, next)?.name ?: next
                    Log.i(TAG, if (DriverCheck.passed(found)) "$name passed, but $nextName comes first now: " +
                                   DriverCheck.summary(found)
                               else "$name can't play here: ${DriverCheck.summary(found)}")
                    continue
                }
            }
            // The other of the two every phone has, timed, so the faster plays: once, at the first start.
            val other = Drivers.toTime(context) ?: return driver
            checkNow(context, paths, other, gameEnvironment(context, paths, other), onStep)
            val faster = Drivers.chosen(context)
            if (faster == driver) return driver
            Log.i(TAG, "${Drivers.find(context, faster)?.name ?: faster} drew the speed test faster: chosen")
        }
    }

    /** Drawing needs the X server: started for the first check that needs it, stopped with the rest. */
    private fun checkNow(context: Context, paths: Paths, driver: String, environment: Map<String, String>,
                         onStep: (String, Float) -> Unit): JSONObject {
        if (!Runtime.isInstalled(context)) onStep("Getting ready", 0f)
        Runtime.prepare(context)
        if (xServer == null) startXServer(context, paths)
        val name = Drivers.find(context, driver)?.name ?: driver
        return DriverCheck.check(context, paths, driver, environment) { step, done ->
            onStep("Checking $name: $step", done)
        }
    }

    /** Settings -> Driver: [driver] checked now, before it's chosen or again. The next game gets what it finds. */
    @Synchronized
    fun checkDriver(context: Context, driver: String, onStep: (String, Float) -> Unit = { _, _ -> }): JSONObject {
        check(!inGame) { "Quit the game first." }
        val paths = Paths(context)
        try {
            return checkNow(context, paths, driver, gameEnvironment(context, paths, driver), onStep)
        } finally {
            stopServers()
        }
    }

    @Synchronized
    fun pause() {
        if (state != State.RUNNING) return
        game?.program?.signal(OsConstants.SIGSTOP)
        ScreenLog.add("game paused: the app is in the background")
        setState(State.PAUSED)
    }

    @Synchronized
    fun resume() {
        if (state != State.PAUSED) return
        game?.program?.signal(OsConstants.SIGCONT)
        ScreenLog.add("game resumed")
        setState(State.RUNNING)
    }

    /** Factorio quits cleanly on SIGTERM, in a few seconds, but doesn't save: the last autosave is kept. */
    @Synchronized
    fun quit() {
        val game = game?.program ?: return
        if (state == State.QUITTING || !game.isAlive) return
        game.signal(OsConstants.SIGCONT)
        game.signal(OsConstants.SIGTERM)
        setState(State.QUITTING)
        // A game hung in the GPU driver ignores SIGTERM.
        mainHandler.postDelayed({ if (game.isAlive) game.kill() }, KILL_AFTER_MS)
    }

    /**
     * Swiped away in Recents: the player's quit. Some phones' cleaners (OPPO's, realme's, Xiaomi's) kill the app a
     * second or two later, before the game has ended: so the quit is written first (LastRun), and so is the game's
     * result, for the next start to send.
     */
    @Synchronized
    fun quitBySwipe(context: Context) {
        val game = game ?: return
        LastRun.quit(context)
        if (game.windowOpened && !game.testing) GameResults.keep(context, game.driver)
        quit()
    }

    fun runTool(context: Context, name: String): Program {
        require(name.matches(TOOL_NAME)) { "Tool names are plain words" }
        val paths = Paths(context)
        Runtime.prepare(context)
        startBridges(context, paths)
        return Program.start(paths, listOf(paths.program(name).path), paths.toolLog(name), Sdl.environment(paths))
    }

    private fun startBridges(context: Context, paths: Paths) {
        AudioBridge.start(paths)
        InputBridge.start(context.applicationContext)
    }

    private fun launchGame(context: Context, paths: Paths, driver: String, gameArgs: List<String>) {
        Saves.deleteInterrupted(paths)
        val controller = gamepadConnected(context)
        input = when {
            keyboardConnected(context) && !controller -> Input.KEYBOARD
            // debug.env's app.keyboard=1: Mouse & keys whatever Settings says.
            !controller && (Settings(context).controls == Settings.KEYS ||
                            DebugEnv.read(context, "app")["keyboard"] == "1") -> Input.KEYS
            else -> Input.PAD
        }
        val testProgram = DebugEnv.read(context, "test")["program"]?.takeIf { it.matches(TOOL_NAME) }
        val testing = testProgram != null
        val command = if (testProgram != null) listOf(paths.program(testProgram).path)
                      else listOf(paths.gameBinary.path, "--config", paths.config.path) + gameArgs
        val environment = gameEnvironment(context, paths, driver)
        val applied = if (testing) DriverCheck.Applied(environment)
                      else DriverCheck.apply(context, paths, driver, environment)
        if (!applied.opens) {
            Drivers.record(context, driver, Drivers.Outcome.DIDNT_START)
            // A driver that can't run here isn't a choice any more: before 0.8.17 Mesa fell back to the CPU on its
            // own, so a player may have picked one that "worked".
            if (DriverRecords.choice(context) == driver) Drivers.choose(context, null)
            ScreenLog.add("not started: the driver gives no OpenGL, the next game skips it")
            throw NoOpenGL(Drivers.find(context, driver)?.name ?: driver)
        }
        // Before the game's config and its UI scale, which follow the graphics and resolution.
        if (!testing) Drivers.useSpeedTest(context, driver)
        val atlasCache = Settings(context).fastStart && applied.keepsCache
        AtlasCache.prepare(context, paths, DriverCheck.cacheMaker(context, driver), atlasCache)
        FactorioConfig.write(context, paths, keyboard,
                             Drivers.compression(paths, driver, applied.gpuCompression, applied.environment),
                             atlasCache, Route.of(paths, driver) == Route.MOBILEGLUES)
        val uiScale = "%.4f".format(Locale.ROOT, X11Prefs(context).uiScale)
        val started = Program.start(paths, command, paths.gameLog,
                                    Sdl.environment(paths) + applied.environment +
                                        ("SDL_VIDEO_X11_SCALING_FACTOR" to uiScale),
                                    Route.of(paths, driver).libraryPath(paths, driver))
        LastRun.launched(context, driver)
        val game = Game(started, driver, testing, MemoryWatch.start(paths, started))
        this.game = game
        lastGame = null
        ScreenLog.add("game started")
        if (!testing) GameResults.start(context, paths)
        setState(State.RUNNING)
        val app = context.applicationContext
        thread(name = "pocketfoundry-game-exit", isDaemon = true) { onGameExit(app, game, started.waitFor()) }
        watchForNoWindow(game, paths.gameLog)
        watchForFailure(game, paths.gameLog)
        context.startForegroundService(Intent(context, GameService::class.java))
    }

    private fun gameEnvironment(context: Context, paths: Paths, driver: String): Map<String, String> {
        return Drivers.environment(context, driver) + PerformanceHints.environment(context, paths) + MEMORY +
               DetailedLogs.environment(context) + Drivers.overrides(context, driver)
    }

    /**
     * Termux:X11's X server, which the game screen (Termux:X11's activity) connects to on [Paths.screenSocket]. A
     * native program: Termux:X11 runs it in a Java VM of its own (app_process), 40 MB more for a server that only
     * needs Java to announce itself to the screen.
     */
    private fun startXServer(context: Context, paths: Paths) {
        // Leftovers from a server that died with the app.
        paths.xSocket.delete()
        paths.xLock.delete()
        paths.screenSocket.delete()
        val pb = ProcessBuilder(paths.xServer.path, ":${Paths.DISPLAY}", "-ac")
        pb.environment().apply {
            remove("LD_PRELOAD")
            put("TMPDIR", paths.tmp.path)
            put("XKB_CONFIG_ROOT", paths.xkb.path)
            put("XLORIE_LIB", paths.xlorie.path)
            put("LORIE_SOCKET", paths.screenSocket.path)
            // The chosen driver's checkboxes and Options meant for Termux:X11 (Show frames directly).
            putAll(Drivers.xServerEnvironment(context, Drivers.chosen(context)))
            putAll(DetailedLogs.environment(context))
            putAll(DebugEnv.read(context, "x11"))
        }
        val server = pb.redirectErrorStream(true).start()
        xServer = server
        keepXServerLog(server, paths.x11Log)
        val deadline = System.currentTimeMillis() + X_START_TIMEOUT_MS
        while (!paths.xSocket.exists()) {
            // How it ended tells a crash (a signal) from a refusal (a status) when it dies before writing its log.
            if (!server.isAlive) throw IOException("The X server exited (${Program.Exit(server.exitValue())}); " +
                                                   "see ${paths.x11Log.name}")
            if (System.currentTimeMillis() > deadline) {
                // On a Galaxy A03s one still starting outlived destroy()'s SIGTERM and slowed the next start.
                server.destroyForcibly()
                throw IOException("The X server didn't start in ${X_START_TIMEOUT_MS / 1000} s")
            }
            Thread.sleep(X_POLL_MS)
        }
        ScreenLog.add("X server started")
    }

    private fun keepXServerLog(server: Process, log: File) = thread(name = "pocketfoundry-x11-log", isDaemon = true) {
        log.printWriter().use { out ->
            try {
                server.inputStream.bufferedReader().forEachLine {
                    if (ScreenLog.isScreenLine(it)) ScreenLog.add(it)
                    else {
                        out.println(it)
                        out.flush()
                    }
                }
            } catch (e: IOException) {
                // The game exited: destroy() closed the X server's output while this read it.
            }
        }
    }

    private fun stopServers() {
        wm?.signal(OsConstants.SIGTERM)
        xServer?.destroy()
        wm = null
        xServer = null
    }

    @Synchronized
    private fun onGameExit(context: Context, game: Game, exit: Program.Exit) {
        if (game !== this.game) return
        LastRun.closed(context)
        val unstarted = if (game.noWindow && exit.signal != null) " as it opened no window" else ""
        val memory = game.memory.last?.takeIf { exit.status != 0 }?.let { "; last memory: $it" } ?: ""
        ScreenLog.add("game exited ($exit$unstarted)$memory")
        val quit = state == State.QUITTING || game.quitHung
        val failed = !quit && exit.status != 0
        stopServers()
        this.game = null
        // Killed by a signal before its window opened: the driver crashed starting, like one that opens no window.
        val noWindow = game.noWindow || failed && !game.windowOpened && exit.signal != null
        val log = if (failed && !noWindow) runCatching { Paths(context).gameLog.readText() }.getOrDefault("") else ""
        val failure = if (failed && !noWindow) GameEnding.failure(game.deviceLost, exit.signal, log) else null
        val stage = failure?.let { GameEnding.stage(log) }
        if (failure != null) ScreenLog.add("the driver ${failure.words}, ${stage?.words}: the next game passes it over")
        // Stopped by itself with an error of its own: a mod or the headless build, which no driver would change.
        val gameError = if (failed && !noWindow && failure == null) GameEnding.gameError(log) else null
        lastGame = Ending(game.driver, exit.takeIf { failed }, noWindow, failure, stage, gameError = gameError)
        val outcome = when {
            game.testing -> null
            noWindow -> Drivers.Outcome.DIDNT_START
            failure != null -> Drivers.Outcome.FAILED
            failed -> Drivers.Outcome.STOPPED
            game.windowOpened -> Drivers.Outcome.RAN
            else -> null   // quit while loading
        }
        outcome?.let { Drivers.record(context, game.driver, it) }
        if (outcome == Drivers.Outcome.RAN) Settings(context).played = true
        // Quit before Factorio was ready: after a long wait, a load that may have been stuck (#93).
        val quitLoading = if (!quit || noWindow || game.testing) null
            else GameEnding.quitLoading(GameEnding.stage(Paths(context).gameLog),
                                        (SystemClock.elapsedRealtime() - game.startedAt) / 1000)
        val result = if (quitLoading != null) GameResults.Outcome.QUIT_LOADING else outcome?.let(GameResults.Outcome::of)
        if (result != null) {
            val words = quitLoading ?: failure?.let {
                GameEnding.sentence(Drivers.find(context, game.driver)?.name ?: game.driver, it, stage)
            } ?: gameError
            GameResults.finish(context, Paths(context), game.driver, result, words)
        }
        setState(State.STOPPED)
    }

    /**
     * Without a window or GL context Factorio doesn't exit: it spins with nothing on screen, deaf to SIGTERM.
     * Crashing before it has one (a driver failing as it starts), it symbolizes its stack trace for a minute or more,
     * in the dark.
     */
    private fun watchForNoWindow(game: Game, log: File) = thread(name = "pocketfoundry-game-window", isDaemon = true) {
        val deadline = System.currentTimeMillis() + WINDOW_TIMEOUT_MS
        while (game.program.isAlive && System.currentTimeMillis() < deadline) {
            val text = runCatching { log.readText() }.getOrDefault("")
            if (WINDOW_OPENED in text) {
                game.windowOpened = true
                return@thread
            }
            if (NO_WINDOW.any { it in text }) {
                game.noWindow = true
                if (CRASHED in text) logCrashFrames(game, log)
                game.program.kill()
                return@thread
            }
            Thread.sleep(LOG_POLL_MS)
        }
    }

    /**
     * Once Zink has lost the GPU, Factorio goes on with nothing to draw with: a black or frozen screen, and memory
     * growing by gigabytes (a Mali-G52 on PanVK-kbase went from 2 to 5.6 GB in 20 s). Once Factorio has crashed, it
     * writes its stack trace, then waits on a small desktop dialog over a black screen ("Unexpected error occurred
     * ... open the folder containing the log file?") that players can't make sense of: it goes once the trace is
     * written, or after [CRASH_TRACE_MS]. Quit from its own menu, Factorio can hang instead of exiting (a Redmi Note 12
     * Pro on Turnip, still there 48 s after "Quitting: user-quit."): it's stopped after [QUIT_HANG_MS], as a quit, and
     * where its threads waited goes in screen.log.
     */
    private fun watchForFailure(game: Game, log: File) = thread(name = "pocketfoundry-game-failure", isDaemon = true) {
        var read = 0L
        var tail = ""
        var crashedAt = 0L
        var quittingAt = 0L
        while (game.program.isAlive) {
            if (quittingAt > 0 && state != State.QUITTING && System.currentTimeMillis() - quittingAt > QUIT_HANG_MS) {
                ScreenLog.add("Factorio quit but hadn't exited ${QUIT_HANG_MS / 1000} s later" +
                              waits(game.program.pid).let { if (it.isEmpty()) "" else " (its threads waiting in $it)" } +
                              ": game stopped")
                game.quitHung = true
                game.program.kill()
                return@thread
            }
            if (crashedAt > 0 && System.currentTimeMillis() - crashedAt > CRASH_TRACE_MS) {
                logCrashFrames(game, log)
                ScreenLog.add("Factorio crashed: game stopped")
                game.program.kill()
                return@thread
            }
            val new = runCatching {
                RandomAccessFile(log, "r").use { file ->
                    if (file.length() < read) read = 0
                    ByteArray((file.length() - read).toInt()).also { file.seek(read); file.readFully(it) }
                }
            }.getOrNull()
            if (new != null) {
                read += new.size
                // A line read across two polls is found too.
                val text = tail + String(new)
                if (DEVICE_LOST in text) {
                    game.deviceLost = true
                    ScreenLog.add("the graphics driver lost the GPU: game stopped")
                    game.program.kill()
                    return@thread
                }
                if (crashedAt == 0L && CRASHED in text) crashedAt = System.currentTimeMillis()
                if (quittingAt == 0L && QUITTING in text) quittingAt = System.currentTimeMillis()
                if (crashedAt > 0 && CRASH_DIALOG in text) {
                    logCrashFrames(game, log)
                    ScreenLog.add("Factorio crashed: game stopped")
                    game.program.kill()
                    return@thread
                }
                tail = text.takeLast(MARKER_LENGTH)
            }
            Thread.sleep(DEVICE_POLL_MS)
        }
    }

    /**
     * The crash's trace in libraries and offsets (CrashFrames), into screen.log and so into a report: read while
     * Factorio still lives, before either watcher stops it, once its trace is written ([CRASH_TRACE_WAIT_MS] at most).
     */
    private fun logCrashFrames(game: Game, log: File) {
        synchronized(game) {
            if (game.crashLogged) return
            game.crashLogged = true
        }
        val deadline = System.currentTimeMillis() + CRASH_TRACE_WAIT_MS
        var text = runCatching { log.readText() }.getOrDefault("")
        while (CrashFrames.TRACE_DONE !in text && game.program.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(LOG_POLL_MS)
            text = runCatching { log.readText() }.getOrDefault("")
        }
        val maps = runCatching { File("/proc/${game.program.pid}/maps").readText() }.getOrDefault("")
        val frames = CrashFrames.frames(text, maps)
        if (frames.isNotEmpty()) ScreenLog.add("Factorio crashed in: " + frames.joinToString(" · "))
    }

    /** Where a program's threads wait, the most first: "futex_wait_queue 14, do_epoll_wait 2". Empty if hidden. */
    private fun waits(pid: Int): String = runCatching {
        File("/proc/$pid/task").listFiles().orEmpty()
            .mapNotNull { task -> runCatching { File(task, "wchan").readText().trim() }.getOrNull() }
            .filter { it.isNotEmpty() && it != "0" }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} ${it.value}" }
    }.getOrDefault("")

    private fun setState(new: State) {
        state = new
        mainHandler.post { listeners.forEach { it() } }
    }
}
