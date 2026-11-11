package app.pocketfoundry.core.game

import android.content.Context
import android.content.Intent
import android.system.OsConstants
import app.pocketfoundry.core.controller.InputBridge
import app.pocketfoundry.core.controller.gamepadConnected
import app.pocketfoundry.core.controller.keyboardConnected
import app.pocketfoundry.core.drivers.DriverCheck
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.logs.DetailedLogs
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.runtime.DebugEnv
import app.pocketfoundry.core.runtime.Program
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.runtime.Sdl
import app.pocketfoundry.core.saves.Saves
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.X11Prefs
import app.pocketfoundry.core.system.mainHandler
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Runs a game: Termux:X11's X server, our window manager and Factorio, each a process of its own. */
object Session {
    enum class State { STOPPED, RUNNING, PAUSED, QUITTING }

    /**
     * [exit] is Factorio's when it failed, null after a normal quit. [noWindow] says it never opened its window: the
     * graphics driver didn't start.
     */
    class Ending(val driver: String, val exit: Program.Exit?, val noWindow: Boolean)

    /** A files/usr/bin program that DebugPad or debug.env's test.program may run: no paths. */
    private val TOOL_NAME = Regex("[a-z0-9-]+")

    private const val X_START_TIMEOUT_MS = 10_000L
    private const val X_POLL_MS = 50L
    private const val KILL_AFTER_MS = 10_000L
    private const val WINDOW_TIMEOUT_MS = 60_000L
    private const val LOG_POLL_MS = 250L
    private const val WINDOW_OPENED = "Initialised OpenGL"
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
    @Volatile var keyboard = false
        private set
    @Volatile var lastGame: Ending? = null
        private set

    /**
     * A running game, with what its watchers find out. [testing]: debug.env's test.program runs in the game's place,
     * so its ending says nothing about the driver.
     */
    private class Game(val program: Program, val driver: String, val testing: Boolean,
                       val memory: DetailedLogs.MemoryWatch?) {
        @Volatile var windowOpened = false
        @Volatile var noWindow = false
    }

    private var xServer: Process? = null
    private var wm: Program? = null
    private var game: Game? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Listeners run on the main thread. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Throws if the game didn't start. */
    @Synchronized
    fun start(context: Context, gameArgs: List<String>) {
        if (inGame) return
        val paths = Paths(context)
        Runtime.prepare(context)
        val driver = Drivers.chosen(context)
        ScreenLog.begin(context, driver)
        startBridges(context, paths)
        try {
            startXServer(context, paths)
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
            throw e
        }
    }

    /** Settings -> Driver -> Check. What it finds applies to the next game, and Logs shows it. */
    @Synchronized
    fun checkDriver(context: Context, driver: String) {
        check(!inGame) { "Quit the game first." }
        val paths = Paths(context)
        Runtime.prepare(context)
        ScreenLog.begin(context, driver)
        try {
            startXServer(context, paths)
            DriverCheck.check(context, paths, driver, gameEnvironment(context, paths, driver))
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
        keyboard = keyboardConnected(context) && !gamepadConnected(context)
        val testProgram = DebugEnv.read(context, "test")["program"]?.takeIf { it.matches(TOOL_NAME) }
        val testing = testProgram != null
        val command = if (testProgram != null) listOf(paths.program(testProgram).path)
                      else listOf(paths.gameBinary.path, "--config", paths.config.path) + gameArgs
        val environment = gameEnvironment(context, paths, driver)
        val applied = if (testing) DriverCheck.Applied(environment, transcodes = false)
                      else DriverCheck.apply(context, paths, driver, environment)
        if (!applied.opens) {
            Drivers.record(context, driver, Drivers.Outcome.DIDNT_START)
            ScreenLog.add("not started: the driver gives no OpenGL, the next game skips it")
            throw NoOpenGL(Drivers.find(context, driver)?.name ?: driver)
        }
        // Before the game's config and its UI scale, which follow the graphics and resolution.
        if (!testing) Drivers.useSpeedTest(context, driver)
        FactorioConfig.write(context, paths, keyboard, Drivers.compression(paths, driver, applied.transcodes))
        val uiScale = "%.4f".format(Locale.ROOT, X11Prefs(context).uiScale)
        val started = Program.start(paths, command, paths.gameLog,
                                    Sdl.environment(paths) + applied.environment +
                                        ("SDL_VIDEO_X11_SCALING_FACTOR" to uiScale),
                                    Drivers.libraryPath(paths, driver))
        val memory = if (DetailedLogs.on(context)) DetailedLogs.watchMemory(started) else null
        val game = Game(started, driver, testing, memory)
        this.game = game
        lastGame = null
        ScreenLog.add("game started")
        setState(State.RUNNING)
        val app = context.applicationContext
        thread(name = "pocketfoundry-game-exit", isDaemon = true) { onGameExit(app, game, started.waitFor()) }
        watchForNoWindow(game, paths.gameLog)
        context.startForegroundService(Intent(context, GameService::class.java))
    }

    private fun gameEnvironment(context: Context, paths: Paths, driver: String): Map<String, String> {
        // Mesa's own counter, drawn into the game's frames, whichever driver draws them.
        val fpsCounter = if (Settings(context).fpsCounter) mapOf("GALLIUM_HUD" to "simple,fps") else emptyMap()
        return Drivers.environment(context, driver) + fpsCounter + MEMORY +
               DetailedLogs.environment(context) + Drivers.overrides(context, driver)
    }

    /** Termux:X11's X server, which the game screen (Termux:X11's activity) connects to. */
    private fun startXServer(context: Context, paths: Paths) {
        // Leftovers from a server that died with the app.
        paths.xSocket.delete()
        paths.xLock.delete()
        val pb = ProcessBuilder("/system/bin/app_process", "-Xnoimage-dex2oat", "/",
                                "com.termux.x11.CmdEntryPoint", ":${Paths.DISPLAY}", "-ac")
        pb.environment().apply {
            remove("LD_PRELOAD")
            put("CLASSPATH", context.applicationInfo.sourceDir)
            put("TMPDIR", paths.tmp.path)
            put("XKB_CONFIG_ROOT", paths.xkb.path)
            put("XLORIE_LIB", paths.xlorie.path)
            putAll(DetailedLogs.environment(context))
            putAll(DebugEnv.read(context, "x11"))
        }
        val server = pb.redirectErrorStream(true).start()
        xServer = server
        keepXServerLog(server, paths.x11Log)
        val deadline = System.currentTimeMillis() + X_START_TIMEOUT_MS
        while (!paths.xSocket.exists()) {
            if (!server.isAlive) throw IOException("The X server exited; see ${paths.x11Log.name}")
            if (System.currentTimeMillis() > deadline) throw IOException("The X server didn't start in ${X_START_TIMEOUT_MS / 1000} s")
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
        val unstarted = if (game.noWindow && exit.signal != null) " as it opened no window" else ""
        val memory = game.memory?.last?.takeIf { exit.status != 0 }?.let { "; last memory: $it" } ?: ""
        ScreenLog.add("game exited ($exit$unstarted)$memory")
        val failed = state != State.QUITTING && exit.status != 0
        stopServers()
        this.game = null
        // Killed by a signal before its window opened: the driver crashed starting, like one that opens no window.
        val noWindow = game.noWindow || failed && !game.windowOpened && exit.signal != null
        lastGame = Ending(game.driver, exit.takeIf { failed }, noWindow)
        val outcome = when {
            game.testing -> null
            noWindow -> Drivers.Outcome.DIDNT_START
            failed -> Drivers.Outcome.STOPPED
            game.windowOpened -> Drivers.Outcome.RAN
            else -> null   // quit while loading
        }
        outcome?.let { Drivers.record(context, game.driver, it) }
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
                game.program.kill()
                return@thread
            }
            Thread.sleep(LOG_POLL_MS)
        }
    }

    private fun setState(new: State) {
        state = new
        mainHandler.post { listeners.forEach { it() } }
    }
}
