package app.pocketfoundry

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The processes behind a game session, all children of the app:
 * Termux:X11's server (through app_process), pocketfoundry-wm and Factorio
 * (both through our glibc loader).
 *
 * While the game is off screen it's stopped with SIGSTOP: no CPU, no battery,
 * nothing lost. Quitting asks Factorio with SIGTERM, which it handles cleanly
 * (but without saving: the last autosave is what's kept). When the game exits,
 * for whatever reason, the X server and the WM go with it.
 */
object Session {
    enum class State { STOPPED, RUNNING, PAUSED, QUITTING }

    @Volatile var state = State.STOPPED
        private set
    /** How the last session ended: null after a normal quit, else Factorio's exit status. */
    @Volatile var lastFailure: Int? = null
        private set

    private var xServer: Process? = null
    private var wm: Process? = null
    private var game: Process? = null
    private var gamePid = 0
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val gameRunning get() = game?.isAlive == true

    /** [listener] runs on the main thread after every state change. */
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
            // An autosave cut short when the game was killed; Factorio offers it as
            // "Continue" and then can't open it.
            File(paths.gameData, "saves").listFiles { f -> f.name.endsWith(".tmp.zip") }?.forEach { it.delete() }
            writeConfig(paths, Settings.spriteQuality(context))
            val started = glibc(paths, listOf(paths.gameBinary.path, "--config", paths.config.path) + gameArgs,
                                "game.log", Drivers.environment(context) + debugEnvironment(context, "game"))
            game = started
            gamePid = childPid(paths.gameBinary.path)
            lastFailure = null
            setState(State.RUNNING)
            thread(name = "pocketfoundry-game-exit", isDaemon = true) { onGameExit(started, started.waitFor()) }
            context.startForegroundService(Intent(context, GameService::class.java))
        }
    }

    /** Freezes the game while it's off screen. */
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

    /** Asks Factorio to quit; it takes a few seconds. Killed if it hasn't gone in 30 s. */
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

    /** Our child process whose command line mentions [needle]: Android's Process doesn't say its pid. */
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

    /**
     * Debug builds only: `game.KEY=VALUE` and `x11.KEY=VALUE` lines from files/debug.env over the game's
     * and the X server's environment, to try things on the phone without a rebuild.
     */
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

    /** Runs one of the runtime's tools (files/usr/bin) on its own, logging to logs/[name].log. */
    fun runTool(context: Context, name: String): Process {
        val paths = Paths(context)
        paths.logs.mkdirs()
        AudioBridge.start(paths)
        InputBridge.start(context, paths)
        return glibc(paths, listOf(File(paths.prefix, "bin/$name").path), "$name.log")
    }

    /**
     * Runs a program from the runtime: `libld-linux.so --library-path files/usr/lib <program>`,
     * with [extra] over the default environment (the chosen graphics driver's, for the game).
     */
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
            // Our Turnip waits for each frame before presenting it (runtime/mesa/patches);
            // imported ones get the software path instead (Drivers.environment).
            put("SDL_VIDEO_DRIVER", "x11")
            // The game's built-in SDL hands everything to ours, which brings the
            // app's sound and gamepad (runtime/sdl).
            put("SDL3_DYNAMIC_API", File(paths.lib, "libSDL3.so.0").path)
            put("SDL_AUDIO_DRIVER", "pocketfoundry")
            put("POCKETFOUNDRY_AUDIO", paths.audioSocket.path)
            put("POCKETFOUNDRY_INPUT", paths.inputSocket.path)
            put("POCKETFOUNDRY_FRAME_RATE", paths.frameRate.path)
            putAll(extra)
        }
        return pb.redirectErrorStream(true).redirectOutput(File(paths.logs, log)).start()
    }

    /**
     * Factorio derives its paths from its executable, which here is the loader,
     * so the config names them. Factorio fills in the rest of the file itself.
     */
    private fun writeConfig(paths: Paths, spriteQuality: String) {
        var config = if (paths.config.exists()) paths.config.readText() else ""
        // Without its version header Factorio calls the file invalid and offers a reset.
        if (!config.startsWith("; version=")) config = "; version=13\n"
        config = setIni(config, "path", "read-data", File(paths.game, "data").path)
        config = setIni(config, "path", "write-data", paths.gameData.path)
        // Factorio ignores gamepads unless this says so, and the on-screen controller is
        // the main way to play here. A choice made in the game's settings stays.
        if (!Regex("(?m)^input-method=").containsMatchIn(config)) {
            config = setIni(config, "input", "input-method", "game-controller")
        }
        // The automatic UI scale is too small at arm's length; 125% of display points reads
        // well on a phone. Also only a default.
        if (!Regex("(?m)^ui-scale-mode=").containsMatchIn(config)) {
            config = setIni(config, "interface", "ui-scale-mode", "manual-display-points")
            config = setIni(config, "interface", "custom-proportional-ui-scale", "1.25")
        }
        config = setIni(config, "graphics", "graphics-quality", spriteQuality)
        paths.config.parentFile?.mkdirs()
        paths.config.writeText(config)
    }

    /** Sets `key=value` in `[section]` of an INI file, adding the line or the section when missing. */
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
