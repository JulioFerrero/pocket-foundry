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
    /** What the running game started with: its input mode can't change until it restarts. */
    @Volatile var controls = Settings.CONTROLS[0]
        private set
    /** Factorio's exit status, or null after a normal quit. */
    @Volatile var lastFailure: Int? = null
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
        startX(context, paths)
        if (wm?.isAlive != true) {
            wm = glibc(paths, listOf(File(paths.prefix, "bin/pocketfoundry-wm").path), "wm.log")
        }
        if (game?.isAlive != true) {
            // Autosaves cut short by a kill: Factorio offers them as "Continue" and can't open them.
            File(paths.gameData, "saves").listFiles { f -> f.name.endsWith(".tmp.zip") }?.forEach { it.delete() }
            // A keyboard plays in "touch", Factorio's keyboard-and-mouse mode.
            controls = when {
                Gamepads.connected(context) -> Settings.CONTROLS[0]
                Keyboards.connected(context) -> Settings.CONTROLS[1]
                else -> Settings.controls(context)
            }
            writeConfig(paths, Settings.spriteQuality(context), controls)
            val stand = debugEnvironment(context, "test")["program"]?.takeIf { it.matches(Regex("[a-z0-9-]+")) }
            val command = if (stand != null) listOf(File(paths.prefix, "bin/$stand").path)
                          else listOf(paths.gameBinary.path, "--config", paths.config.path) + gameArgs
            val started = glibc(paths, command, "game.log",
                                Drivers.environment(context) + uiScale(context) + debugEnvironment(context, "game"))
            game = started
            gamePid = childPid(command.first())
            lastFailure = null
            setState(State.RUNNING)
            thread(name = "pocketfoundry-game-exit", isDaemon = true) { onGameExit(started, started.waitFor()) }
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
        Os.kill(gamePid, OsConstants.SIGCONT)
        Os.kill(gamePid, OsConstants.SIGTERM)
        setState(State.QUITTING)
        main.postDelayed({ if (process.isAlive) process.destroyForcibly() }, 30_000)
    }

    @Synchronized
    private fun onGameExit(process: Process, status: Int) {
        if (process !== game) return
        val quitting = state == State.QUITTING
        wm?.destroy()
        xServer?.destroy()
        game = null; wm = null; xServer = null; gamePid = 0
        lastFailure = if (quitting || status == 0) null else status
        setState(State.STOPPED)
    }

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
     * keeps its size on screen. SDL takes 1 to 10.
     */
    private fun uiScale(context: Context): Map<String, String> {
        val points = Settings.naturalResolutionScale(context) / Settings.resolutionScale(context)
        return mapOf("SDL_VIDEO_X11_SCALING_FACTOR" to "%.4f".format(Locale.ROOT, points.coerceAtLeast(1f)))
    }

    private fun glibc(paths: Paths, command: List<String>, log: String, extra: Map<String, String> = emptyMap()): Process {
        val pb = ProcessBuilder(listOf(paths.loader.path, "--library-path", paths.lib.path) + command)
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
            put("POCKETFOUNDRY_FRAME_RATE", paths.frameRate.path)
            putAll(extra)
        }
        return pb.redirectErrorStream(true).redirectOutput(File(paths.logs, log)).start()
    }

    /** Factorio derives its paths from its executable, here the loader, so the config names them. */
    private fun writeConfig(paths: Paths, spriteQuality: String, controls: String) {
        var config = if (paths.config.exists()) paths.config.readText() else ""
        // Without its version header Factorio calls the file invalid and offers a reset.
        if (!config.startsWith("; version=")) config = "; version=13\n"
        config = setIni(config, "path", "read-data", File(paths.game, "data").path)
        config = setIni(config, "path", "write-data", paths.gameData.path)
        // Factorio doesn't switch between mouse and gamepad by itself.
        config = setIni(config, "input", "input-method", if (controls == "touch") "keyboard-and-mouse" else "game-controller")
        // Factorio's automatic UI scale is too small on a phone.
        if (!Regex("(?m)^ui-scale-mode=").containsMatchIn(config)) {
            config = setIni(config, "interface", "ui-scale-mode", "manual-display-points")
            config = setIni(config, "interface", "custom-proportional-ui-scale", "1.25")
        }
        config = setIni(config, "graphics", "graphics-quality", spriteQuality)
        paths.config.parentFile?.mkdirs()
        paths.config.writeText(config)
    }

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
