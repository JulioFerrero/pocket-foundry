package app.pocketfoundry.core.game

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Vibrator
import android.system.Os
import android.system.OsConstants
import app.pocketfoundry.core.controller.*
import app.pocketfoundry.core.controller.gamepadConnected
import app.pocketfoundry.core.controller.gamepadVibrator
import app.pocketfoundry.core.controller.InputBridge
import app.pocketfoundry.core.controller.keyboardConnected
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.saves.Saves
import app.pocketfoundry.core.system.*
import app.pocketfoundry.core.system.mainHandler
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.X11Prefs
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/** Runs a game: Termux:X11's X server, our window manager and Factorio, each a process of its own. */
object Session {
    enum class State { STOPPED, RUNNING, PAUSED, QUITTING }

    /** A files/usr/bin program that DebugPad or debug.env's test.program may run. */
    val TOOL_NAME = Regex("[a-z0-9-]+")

    private const val X_START_TIMEOUT_MS = 10_000L
    private const val X_POLL_MS = 50L
    private const val KILL_AFTER_MS = 10_000L
    private const val WINDOW_TIMEOUT_MS = 60_000L
    private const val LOG_POLL_MS = 250L
    private const val WINDOW_OPENED = "Initialised OpenGL"
    private val NO_WINDOW = listOf("Failed to create an application window", "Failed to create OpenGL context",
                                   "Factorio crashed")
    /** Exit statuses above this are 128 plus the signal that killed the process. */
    private const val SIGNALLED = 128

    @Volatile var state = State.STOPPED
        private set
    val inGame get() = state != State.STOPPED
    /** Running or paused: a game that can be played on. */
    val live get() = state == State.RUNNING || state == State.PAUSED
    /** Whether the running game started in keyboard-and-mouse mode: its input mode can't change until it restarts. */
    @Volatile var keyboard = false
        private set
    /** Factorio's exit status, or null after a normal quit. */
    @Volatile var lastFailure: Int? = null
        private set
    /** Whether that failure was Factorio not opening its window: the graphics driver didn't start. */
    @Volatile var noWindow = false
        private set
    /** The graphics driver of the running or last game. */
    @Volatile var driver: String? = null
        private set
    @Volatile private var windowOpened = false

    private var xServer: Process? = null
    private var wm: Process? = null
    private var game: Process? = null
    private var gamePid = 0
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Listeners run on the main thread. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    @Synchronized
    fun start(context: Context, gameArgs: List<String>) {
        val paths = Paths(context)
        val newGame = game?.isAlive != true
        paths.logs.mkdirs()
        if (newGame) ScreenLog.begin(context)
        paths.home.mkdirs()
        Runtime.writeResolvConf(context)
        Settings(context).writeFrameRate()
        startBridges(context, paths)
        if (xServer?.isAlive != true) startXServer(context, paths)
        if (wm?.isAlive != true) wm = Glibc.start(paths, listOf(File(paths.prefix, "bin/pocketfoundry-wm").path), paths.wmLog)
        if (newGame) launchGame(context, paths, gameArgs)
    }

    @Synchronized
    fun pause() {
        if (state != State.RUNNING) return
        Os.kill(gamePid, OsConstants.SIGSTOP)
        setState(State.PAUSED)
    }

    @Synchronized
    fun resume() {
        if (state != State.PAUSED) return
        Os.kill(gamePid, OsConstants.SIGCONT)
        setState(State.RUNNING)
    }

    /** Factorio quits cleanly on SIGTERM, in a few seconds, but doesn't save: the last autosave is kept. */
    @Synchronized
    fun quit() {
        val process = game ?: return
        if (state == State.QUITTING || !process.isAlive) return
        val pid = gamePid
        Os.kill(pid, OsConstants.SIGCONT)
        Os.kill(pid, OsConstants.SIGTERM)
        setState(State.QUITTING)
        // A game hung in the GPU driver ignores SIGTERM. Not destroyForcibly(): on Android it sends SIGTERM again.
        mainHandler.postDelayed({ if (process.isAlive) Os.kill(pid, OsConstants.SIGKILL) }, KILL_AFTER_MS)
    }

    fun runTool(context: Context, name: String): Process {
        val paths = Paths(context)
        paths.logs.mkdirs()
        startBridges(context, paths)
        return Glibc.start(paths, listOf(File(paths.prefix, "bin/$name").path), File(paths.logs, "$name.log"))
    }

    private fun startBridges(context: Context, paths: Paths) {
        val app = context.applicationContext
        val phoneVibrator = app.getSystemService(Vibrator::class.java)
        val settings = Settings(app)
        AudioBridge.start(paths)
        InputBridge.start(paths, rumbleTarget = { gamepadVibrator(app) ?: phoneVibrator }, enabled = { settings.vibration })
    }

    private fun launchGame(context: Context, paths: Paths, gameArgs: List<String>) {
        Saves.deleteInterrupted(paths)
        keyboard = keyboardConnected(context) && !gamepadConnected(context)
        val driver = Drivers.chosen(context).also { this.driver = it }
        FactorioConfig.write(context, paths, keyboard, Drivers.gpuCompression(driver))
        val testProgram = DebugEnv.read(context, "test")["program"]?.takeIf { it.matches(TOOL_NAME) }
        val command = if (testProgram != null) listOf(File(paths.prefix, "bin/$testProgram").path)
                      else listOf(paths.gameBinary.path, "--config", paths.config.path) + gameArgs
        val started = Glibc.start(paths, command, paths.gameLog, gameEnvironment(context, paths, driver),
                                  Drivers.libraries(paths, driver))
        game = started
        gamePid = Glibc.childPid(command.first())
        ScreenLog.add("game started")
        lastFailure = null
        noWindow = false
        windowOpened = false
        setState(State.RUNNING)
        val app = context.applicationContext
        thread(name = "pocketfoundry-game-exit", isDaemon = true) { onGameExit(app, driver, started, started.waitFor()) }
        watchForNoWindow(started, gamePid, paths.gameLog)
        context.startForegroundService(Intent(context, GameService::class.java))
    }

    private fun gameEnvironment(context: Context, paths: Paths, driver: String): Map<String, String> {
        val uiScale = "%.4f".format(Locale.ROOT, X11Prefs(context).uiScale)
        // Mesa's own counter, drawn into the game's frames, whichever driver draws them.
        val fpsCounter = if (Settings(context).fpsCounter) mapOf("GALLIUM_HUD" to "simple,fps") else emptyMap()
        return Drivers.environment(paths, driver) + ("SDL_VIDEO_X11_SCALING_FACTOR" to uiScale) + fpsCounter +
               Drivers.options(context) + DebugEnv.read(context, "game")
    }

    /** Termux:X11's X server, which the game screen (Termux:X11's activity) connects to. Returns once it listens. */
    private fun startXServer(context: Context, paths: Paths) {
        // Leftovers from a server that died with the app.
        paths.xSocket.delete()
        File(paths.tmp, ".X0-lock").delete()
        val pb = ProcessBuilder("/system/bin/app_process", "-Xnoimage-dex2oat", "/",
                                "com.termux.x11.CmdEntryPoint", ":0", "-ac")
        pb.environment().apply {
            remove("LD_PRELOAD")
            put("CLASSPATH", context.applicationInfo.sourceDir)
            put("TMPDIR", paths.tmp.path)
            put("XKB_CONFIG_ROOT", File(paths.prefix, "share/X11/xkb").path)
            put("XLORIE_LIB", paths.xlorie.path)
            putAll(DebugEnv.read(context, "x11"))
        }
        val server = pb.redirectErrorStream(true).redirectOutput(paths.x11Log).start()
        xServer = server
        val deadline = System.currentTimeMillis() + X_START_TIMEOUT_MS
        while (!paths.xSocket.exists()) {
            if (!server.isAlive) throw IOException("The X server exited; see logs/x11.log")
            if (System.currentTimeMillis() > deadline) throw IOException("The X server didn't start in ${X_START_TIMEOUT_MS / 1000} s")
            Thread.sleep(X_POLL_MS)
        }
        ScreenLog.add("X server started")
    }

    @Synchronized
    private fun onGameExit(context: Context, driver: String, process: Process, status: Int) {
        if (process !== game) return
        ScreenLog.add("game exited ($status)")
        val quitting = state == State.QUITTING
        wm?.destroy()
        xServer?.destroy()
        game = null; wm = null; xServer = null
        lastFailure = if (quitting || status == 0) null else status
        // Killed by a signal before its window opened: the driver crashed starting, like one that opens no window.
        if (lastFailure != null && !windowOpened && status > SIGNALLED) noWindow = true
        val outcome = when {
            noWindow -> Drivers.Outcome.DIDNT_START
            lastFailure != null -> Drivers.Outcome.STOPPED
            windowOpened -> Drivers.Outcome.RAN
            else -> null   // quit while loading, or a test program
        }
        outcome?.let { Drivers.record(context, driver, it) }
        setState(State.STOPPED)
    }

    /**
     * Without a window or GL context Factorio doesn't exit: it spins with nothing on screen, deaf to SIGTERM. Crashing
     * before it has one (a driver failing as it starts), it symbolizes its stack trace for a minute or more, in the dark.
     */
    private fun watchForNoWindow(process: Process, pid: Int, log: File) = thread(name = "pocketfoundry-game-window", isDaemon = true) {
        val deadline = System.currentTimeMillis() + WINDOW_TIMEOUT_MS
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            val text = runCatching { log.readText() }.getOrDefault("")
            if (WINDOW_OPENED in text) {
                windowOpened = true
                return@thread
            }
            if (NO_WINDOW.any { it in text }) {
                noWindow = true
                Os.kill(pid, OsConstants.SIGKILL)
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

/** Runs the runtime's Linux programs through its glibc loader, outside Android's own libc. */
object Glibc {
    fun start(paths: Paths, command: List<String>, log: File, extra: Map<String, String> = emptyMap(),
              libraries: File? = null): Process {
        val libraryPath = listOfNotNull(paths.lib, libraries).joinToString(":") { it.path }
        val pb = ProcessBuilder(listOf(paths.loader.path, "--library-path", libraryPath) + command)
        pb.environment().apply {
            clear()
            put("HOME", paths.home.path)
            put("TMPDIR", paths.tmp.path)
            put("XDG_RUNTIME_DIR", paths.tmp.path)
            put("PATH", "/system/bin")
            put("LANG", "C.UTF-8")
            put("TZ", posixTimeZone(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000))
            // No /tmp on Android, so name the socket itself.
            put("DISPLAY", paths.xSocket.path)
            put("GCONV_PATH", File(paths.lib, "gconv").path)
            put("SDL_VIDEO_DRIVER", "x11")
            // The game's built-in SDL hands everything to ours (runtime/sdl), which has the app's sound and gamepad.
            put("SDL3_DYNAMIC_API", File(paths.lib, "libSDL3.so.0").path)
            put("SDL_AUDIO_DRIVER", "pocketfoundry")
            put("POCKETFOUNDRY_AUDIO", paths.audioSocket.path)
            put("POCKETFOUNDRY_INPUT", paths.inputSocket.path)
            // Only our virtual pad, which SDL never filters. Where /dev/input is readable, SDL would also open the real
            // pad and every press would count twice. No device has id 0000:0000.
            put("SDL_GAMECONTROLLER_IGNORE_DEVICES_EXCEPT", "0x0000/0x0000")
            put("POCKETFOUNDRY_FRAME_RATE", paths.frameRate.path)
            // A program restarting itself (Factorio after a mod change) runs the loader again with only its own
            // arguments: our loader then runs this program, with this library path (runtime/glibc/patches).
            put("POCKETFOUNDRY_PROGRAM", command.first())
            put("GLIBC_LD_LIBRARY_PATH", libraryPath)
            putAll(extra)
        }
        return pb.redirectErrorStream(true).redirectOutput(log).start()
    }

    /** Android's Process doesn't expose its pid: the child of ours whose command line has [needle]. */
    fun childPid(needle: String): Int {
        val me = android.os.Process.myPid().toString()
        val child = File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.firstOrNull { dir ->
            val parent = runCatching { File(dir, "stat").readText().substringAfterLast(") ").split(' ')[1] }.getOrNull()
            parent == me && runCatching { File(dir, "cmdline").readText() }.getOrDefault("").contains(needle)
        }
        return child?.name?.toInt() ?: throw IOException("Can't find the game's process")
    }

    /** No tz database in the runtime: a fixed offset is enough for log and save times. POSIX offsets are west-positive. */
    internal fun posixTimeZone(offsetMinutes: Int): String {
        val sign = if (offsetMinutes >= 0) "-" else "+"
        val abs = Math.abs(offsetMinutes)
        // Locale.ROOT: in some languages format() writes digits glibc can't read.
        return "UTC%s%02d:%02d".format(Locale.ROOT, sign, abs / 60, abs % 60)
    }
}

/**
 * How the game screen joins the X server, in logs/screen.log. Termux:X11 logs this only to logcat, and reading logcat
 * from the app can pop up a system dialog.
 */
object ScreenLog {
    private var file: File? = null
    private var start = 0L
    @Volatile var announced = false
        private set

    @Synchronized
    fun begin(context: Context) {
        file = Paths(context).screenLog.apply { writeText("") }
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
