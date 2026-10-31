package app.pocketfoundry

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.pocketfoundry.controller.Gamepads
import app.pocketfoundry.controller.Keyboards
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object Session {
    enum class State { STOPPED, RUNNING, PAUSED, QUITTING }

    @Volatile var state = State.STOPPED
        private set
    /** Whether the running game started in keyboard-and-mouse mode: its input mode can't change until it restarts. */
    @Volatile var keyboard = false
        private set
    /** Factorio's exit status, or null after a normal quit. */
    @Volatile var lastFailure: Int? = null
        private set
    /** Whether that failure was Factorio not opening its window: no GPU our drivers can use. */
    @Volatile var noWindow = false
        private set

    private var xServer: Process? = null
    private var wm: Process? = null
    private var game: Process? = null
    private var gamePid = 0
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Listeners run on the main thread. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    @Synchronized
    fun start(context: Context, gameArgs: List<String> = emptyList()) {
        val paths = Paths(context)
        paths.logs.mkdirs()
        paths.home.mkdirs()
        Runtime.writeResolvConf(context)
        Settings.writeFrameRate(context)
        AudioBridge.start(paths)
        InputBridge.start(context, paths)
        if (game?.isAlive != true) ScreenLog.begin(context)
        startX(context, paths)
        if (wm?.isAlive != true) {
            wm = glibc(paths, listOf(File(paths.prefix, "bin/pocketfoundry-wm").path), "wm.log")
        }
        if (game?.isAlive != true) {
            // Autosaves cut short by a kill: Factorio offers them as "Continue" and can't open them.
            File(paths.gameData, "saves").listFiles { f -> f.name.endsWith(".tmp.zip") }?.forEach { it.delete() }
            keyboard = Keyboards.connected(context) && !Gamepads.connected(context)
            // Light graphics turns the effects off; turning it off puts them back once, then they're the game's own again.
            val light = Settings.lightGraphics(context)
            val effects = if (light || Settings.lightApplied(context)) !light else null
            writeConfig(paths, Settings.spriteQuality(context), keyboard, Drivers.panvk(context), effects)
            Settings.setLightApplied(context, light)
            val stand = debugEnvironment(context, "test")["program"]?.takeIf { it.matches(Regex("[a-z0-9-]+")) }
            val command = if (stand != null) listOf(File(paths.prefix, "bin/$stand").path)
                          else listOf(paths.gameBinary.path, "--config", paths.config.path) + gameArgs
            val started = glibc(paths, command, "game.log", Drivers.environment(context) + uiScale(context) +
                                fpsCounter(context) + Drivers.options(context) + debugEnvironment(context, "game"),
                                Drivers.libraries(context))
            game = started
            gamePid = childPid(command.first())
            ScreenLog.add("game started")
            lastFailure = null
            noWindow = false
            setState(State.RUNNING)
            thread(name = "pocketfoundry-game-exit", isDaemon = true) { onGameExit(started, started.waitFor()) }
            watchForNoWindow(started, gamePid, File(paths.logs, "game.log"))
            context.startForegroundService(Intent(context, GameService::class.java))
        }
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
        main.postDelayed({ if (process.isAlive) Os.kill(pid, OsConstants.SIGKILL) }, 10_000)
    }

    @Synchronized
    private fun onGameExit(process: Process, status: Int) {
        if (process !== game) return
        ScreenLog.add("game exited ($status)")
        val quitting = state == State.QUITTING
        wm?.destroy()
        xServer?.destroy()
        game = null; wm = null; xServer = null; gamePid = 0
        lastFailure = if (quitting || status == 0) null else status
        setState(State.STOPPED)
    }

    /** Without a window or GL context Factorio doesn't exit: it spins with nothing on screen, deaf to SIGTERM. */
    private fun watchForNoWindow(process: Process, pid: Int, log: File) = thread(name = "pocketfoundry-game-window", isDaemon = true) {
        val deadline = System.currentTimeMillis() + 60_000
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            val text = runCatching { log.readText() }.getOrDefault("")
            if (WINDOW_OPENED in text) return@thread
            if (NO_WINDOW.any { it in text }) {
                noWindow = true
                Os.kill(pid, OsConstants.SIGKILL)
                return@thread
            }
            Thread.sleep(250)
        }
    }

    private const val WINDOW_OPENED = "Initialised OpenGL"
    private val NO_WINDOW = listOf("Failed to create an application window", "Failed to create OpenGL context")

    private fun setState(new: State) {
        state = new
        main.post { listeners.forEach { it() } }
    }

    /** Android's Process doesn't expose its pid. */
    private fun childPid(needle: String): Int {
        val me = android.os.Process.myPid().toString()
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.forEach { dir ->
            val parent = runCatching { File(dir, "stat").readText().substringAfterLast(") ").split(' ')[1] }.getOrNull()
            if (parent == me && runCatching { File(dir, "cmdline").readText() }.getOrDefault("").contains(needle)) {
                return dir.name.toInt()
            }
        }
        throw IOException("Can't find the game's process")
    }

    private fun startX(context: Context, paths: Paths) {
        if (xServer?.isAlive == true) return
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
            putAll(debugEnvironment(context, "x11"))
        }
        xServer = pb.redirectErrorStream(true)
            .redirectOutput(File(paths.logs, "x11.log"))
            .start()

        val deadline = System.currentTimeMillis() + 10_000
        while (!paths.xSocket.exists()) {
            if (xServer?.isAlive != true) throw IOException("The X server exited; see logs/x11.log")
            if (System.currentTimeMillis() > deadline) throw IOException("The X server didn't start in 10 s")
            Thread.sleep(50)
        }
        ScreenLog.add("X server started")
    }

    /** files/debug.env, to try things on the phone without a rebuild. */
    private fun debugEnvironment(context: Context, process: String): Map<String, String> {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return emptyMap()
        val file = File(Paths(context).files, "debug.env")
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val (key, value) = line.trim().takeIf { it.startsWith("$process.") }?.removePrefix("$process.")
                ?.split('=', limit = 2)?.takeIf { it.size == 2 } ?: return@mapNotNull null
            key to value
        }.toMap().also { if (it.isNotEmpty()) Log.i("PocketFoundry", "debug.env for $process: $it") }
    }

    fun runTool(context: Context, name: String): Process {
        val paths = Paths(context)
        paths.logs.mkdirs()
        AudioBridge.start(paths)
        InputBridge.start(context, paths)
        return glibc(paths, listOf(File(paths.prefix, "bin/$name").path), "$name.log")
    }

    /**
     * Pixels per display point, which Factorio's UI scale counts: scaled with the resolution so the UI
     * keeps its size on screen, and the view its width below the default. Our SDL takes 0.25 to 10 (runtime/sdl).
     */
    private fun uiScale(context: Context): Map<String, String> {
        val points = Settings.naturalResolutionScale(context) / Settings.resolutionScale(context)
        return mapOf("SDL_VIDEO_X11_SCALING_FACTOR" to "%.4f".format(Locale.ROOT, points.coerceAtLeast(0.25f)))
    }

    /** Mesa's own counter, drawn into the game's frames, whichever driver draws them. */
    private fun fpsCounter(context: Context) =
        if (Settings.fpsCounter(context)) mapOf("GALLIUM_HUD" to "simple,fps") else emptyMap()

    private fun glibc(paths: Paths, command: List<String>, log: String, extra: Map<String, String> = emptyMap(),
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
            put("TZ", posixTimeZone())
            // No /tmp on Android, so name the socket itself.
            put("DISPLAY", paths.xSocket.path)
            put("GCONV_PATH", File(paths.lib, "gconv").path)
            put("VK_DRIVER_FILES", File(paths.prefix, "share/vulkan/icd.d/freedreno_icd.aarch64.json").path)
            put("__EGL_VENDOR_LIBRARY_DIRS", File(paths.prefix, "share/glvnd/egl_vendor.d").path)
            put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
            put("SDL_VIDEO_DRIVER", "x11")
            // The game's built-in SDL hands everything to ours (runtime/sdl), which has the app's sound and gamepad.
            put("SDL3_DYNAMIC_API", File(paths.lib, "libSDL3.so.0").path)
            put("SDL_AUDIO_DRIVER", "pocketfoundry")
            put("POCKETFOUNDRY_AUDIO", paths.audioSocket.path)
            put("POCKETFOUNDRY_INPUT", paths.inputSocket.path)
            // Only the app's virtual pad (which SDL never filters): where a system lets apps read /dev/input,
            // SDL would also open the real controller there, and every press would count twice. No device has this id.
            put("SDL_GAMECONTROLLER_IGNORE_DEVICES_EXCEPT", "0x0000/0x0000")
            put("POCKETFOUNDRY_FRAME_RATE", paths.frameRate.path)
            // A program restarting itself (Factorio after a mod change) runs the loader again with only its own
            // arguments: our loader then runs this program, with this library path (runtime/glibc/patches).
            put("POCKETFOUNDRY_PROGRAM", command.first())
            put("GLIBC_LD_LIBRARY_PATH", libraryPath)
            putAll(extra)
        }
        return pb.redirectErrorStream(true).redirectOutput(File(paths.logs, log)).start()
    }

    /** Factorio derives its paths from its executable, here the loader, so the config names them. */
    private fun writeConfig(paths: Paths, spriteQuality: String, keyboard: Boolean, panvk: Boolean, effects: Boolean?) {
        var config = if (paths.config.exists()) paths.config.readText() else ""
        // Without its version header Factorio calls the file invalid and offers a reset.
        if (!config.startsWith("; version=")) config = "; version=13\n"
        config = setIni(config, "path", "read-data", File(paths.game, "data").path)
        config = setIni(config, "path", "write-data", paths.gameData.path)
        // Factorio doesn't switch between mouse and gamepad by itself.
        config = setIni(config, "input", "input-method", if (keyboard) "keyboard-and-mouse" else "game-controller")
        // Factorio's automatic UI scale is too small on a phone.
        if (!Regex("(?m)^ui-scale-mode=").containsMatchIn(config)) {
            config = setIni(config, "interface", "ui-scale-mode", "manual-display-points")
            config = setIni(config, "interface", "custom-proportional-ui-scale", "1.25")
        }
        config = setIni(config, "graphics", "graphics-quality", spriteQuality)
        // PanVK's BC emulation skips the copies Factorio's GPU texture compressor makes: its self-test fails there,
        // and a G610 faulted right after. The CPU compresses instead.
        for (key in listOf("gpu-accelerated-compression", "gpu-accelerated-mipmap-compression"))
            config = setIni(config, "graphics", key, (!panvk).toString())
        // The settings that halved what the CPU driver draws in a game; all on by default.
        if (effects != null) for (key in EFFECTS) config = setIni(config, "graphics", key, effects.toString())
        paths.config.parentFile?.mkdirs()
        paths.config.writeText(config)
    }

    private val EFFECTS = listOf("show-smoke", "show-clouds", "show-fog", "show-space-dust", "show-decoratives",
                                 "show-particles", "show-item-shadows", "show-inserter-shadows", "additional-terrain-effects",
                                 "high-quality-terrain", "elevated-rail-shadows")

    private fun setIni(ini: String, section: String, key: String, value: String): String {
        val line = "$key=$value"
        val existing = Regex("(?m)^${Regex.escape(key)}=.*$")
        val header = Regex("(?m)^\\[${Regex.escape(section)}\\]$")
        val start = header.find(ini) ?: return ini.trimEnd('\n') + "\n\n[$section]\n$line\n"
        val end = Regex("(?m)^\\[").find(ini, start.range.last + 1)?.range?.first ?: ini.length
        val body = ini.substring(start.range.last + 1, end)
        val newBody = if (existing.containsMatchIn(body)) {
            body.replace(existing, Regex.escapeReplacement(line))
        } else {
            "\n$line" + body
        }
        return ini.substring(0, start.range.last + 1) + newBody + ini.substring(end)
    }

    /** No tz database in the runtime: a fixed offset is enough for log and save times. */
    private fun posixTimeZone(): String {
        val minutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (minutes >= 0) "-" else "+"   // POSIX offsets are west-positive
        val abs = Math.abs(minutes)
        return "UTC%s%02d:%02d".format(sign, abs / 60, abs % 60)
    }
}
