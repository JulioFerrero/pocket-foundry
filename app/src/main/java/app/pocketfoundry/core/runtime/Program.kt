package app.pocketfoundry.core.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.pocketfoundry.core.system.Paths
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A Linux program of the runtime, run through its glibc loader, outside Android's own libc. */
class Program private constructor(private val process: Process, val pid: Int) {
    val isAlive get() = process.isAlive

    fun waitFor(): Exit = Exit(process.waitFor())

    fun waitFor(seconds: Long): Exit? = if (process.waitFor(seconds, TimeUnit.SECONDS)) Exit(process.exitValue()) else null

    /** A program that has just exited may already be gone: then there's nothing to signal. */
    fun signal(signal: Int) {
        try {
            Os.kill(pid, signal)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.ESRCH) throw e
        }
    }

    /**
     * Not destroy() or destroyForcibly(): on Android both send SIGTERM, which a program hung in a GPU driver ignores.
     */
    fun kill() = signal(OsConstants.SIGKILL)

    /** A program's exit status: a shell's, where above 128 means a signal killed it (139: SIGSEGV). */
    class Exit(val status: Int) {
        val signal: String? get() = if (status > SIGNALLED) SIGNALS[status - SIGNALLED] ?: "signal ${status - SIGNALLED}" else null
        override fun toString() = signal?.let { "$status, killed by $it" } ?: "$status"
    }

    companion object {
        private const val SIGNALLED = 128
        internal const val RESTART_PROGRAM = "POCKETFOUNDRY_PROGRAM"
        internal const val RESTART_LIBRARIES = "GLIBC_LD_LIBRARY_PATH"
        private val SIGNALS = mapOf(OsConstants.SIGILL to "SIGILL", OsConstants.SIGABRT to "SIGABRT",
                                    OsConstants.SIGBUS to "SIGBUS", OsConstants.SIGKILL to "SIGKILL",
                                    OsConstants.SIGSEGV to "SIGSEGV", OsConstants.SIGTERM to "SIGTERM")

        fun start(paths: Paths, command: List<String>, log: File, environment: Map<String, String> = emptyMap(),
                  libraryPath: List<File> = listOf(paths.lib), appendLog: Boolean = false): Program {
            val libraries = libraryPath.joinToString(":") { it.path }
            val pb = ProcessBuilder(listOf(paths.loader.path, "--library-path", libraries) + command)
            pb.environment().apply {
                clear()
                put("HOME", paths.home.path)
                put("TMPDIR", paths.tmp.path)
                put("XDG_RUNTIME_DIR", paths.tmp.path)
                put("PATH", "/system/bin")
                put("LANG", "C.UTF-8")
                put("XLOCALEDIR", paths.xLocales.path)
                put("GCONV_PATH", paths.gconv.path)
                put("TZ", posixTimeZone(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000))
                put("DISPLAY", paths.xSocket.path)
                // A program restarting itself (Factorio after a mod change) runs the loader again with only its own
                // arguments: our loader then runs this program, with this library path (runtime/glibc/patches).
                put(RESTART_PROGRAM, command.first())
                put(RESTART_LIBRARIES, libraries)
                putAll(environment)
            }
            val output = if (appendLog) ProcessBuilder.Redirect.appendTo(log) else ProcessBuilder.Redirect.to(log)
            val process = pb.redirectErrorStream(true).redirectOutput(output).start()
            val pid = try {
                childPid(command.first())
            } catch (e: IOException) {
                process.destroy()
                throw e
            }
            return Program(process, pid)
        }

        /** Android's Process doesn't expose its pid. */
        private fun childPid(needle: String): Int {
            val me = android.os.Process.myPid().toString()
            val child = File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.firstOrNull { dir ->
                val parent = runCatching { File(dir, "stat").readText().substringAfterLast(") ").split(' ')[1] }.getOrNull()
                parent == me && runCatching { File(dir, "cmdline").readText() }.getOrDefault("").contains(needle)
            }
            return child?.name?.toInt() ?: throw IOException("Can't find the process of $needle")
        }

        /**
         * No tz database in the runtime: a fixed offset is enough for log and save times.
         * POSIX offsets are west-positive.
         */
        internal fun posixTimeZone(offsetMinutes: Int): String {
            val sign = if (offsetMinutes >= 0) "-" else "+"
            val abs = Math.abs(offsetMinutes)
            // Locale.ROOT: in some languages format() writes digits glibc can't read.
            return "UTC%s%02d:%02d".format(Locale.ROOT, sign, abs / 60, abs % 60)
        }
    }
}
