package app.pocketfoundry.core.game

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import java.io.File

/**
 * Games that ended with the app gone. Short of memory, Android's low-memory killer closes the app, the only process of
 * ours it knows, and the game goes with it: neither can log a word. A game still open at the app's next start ended
 * like that, and Android 11 and later keep why (ActivityManager.getHistoricalProcessExitReasons): it goes at the end of
 * that game's screen.log with the last memory MemoryWatch read, and to Home and the report as Session.lastGame.
 */
object LastRun {
    private const val OPENED = "gameOpened"
    private const val DRIVER = "gameDriver"
    private const val LAUNCHED = "gameLaunched"
    /** Factorio says how far it got early in its log: a long log of errors needn't be read whole at the app's start. */
    private const val LOG_HEAD = 2 shl 20

    /** A game's start, before its driver is set up: [driver] as chosen. */
    fun opened(context: Context, paths: Paths, driver: String) {
        paths.lastMemory.delete()
        Settings.prefs(context).edit().putLong(OPENED, System.currentTimeMillis()).putString(DRIVER, driver)
            .putBoolean(LAUNCHED, false).commit()
    }

    /** Factorio started, on [driver]: game.log is this game's. */
    fun launched(context: Context, driver: String) {
        Settings.prefs(context).edit().putString(DRIVER, driver).putBoolean(LAUNCHED, true).commit()
    }

    /** It ended with the app still here, which said how. */
    fun closed(context: Context) {
        Settings.prefs(context).edit().remove(OPENED).remove(DRIVER).remove(LAUNCHED).apply()
    }

    /**
     * At the app's start, off the main thread: the game the app died in, if it did, said at the end of its screen.log.
     * Returns how it ended, for Home and the report, or null when it wasn't a failure: a force stop or an update.
     */
    fun check(context: Context): Session.Ending? {
        val prefs = Settings.prefs(context)
        val opened = prefs.getLong(OPENED, 0L).takeIf { it > 0 } ?: return null
        val driver = prefs.getString(DRIVER, null).orEmpty()
        val launched = prefs.getBoolean(LAUNCHED, false)
        closed(context)
        val paths = Paths(context)
        val exit = lastExit(context, opened)
        val stage = if (launched) GameEnding.stage(head(paths.gameLog)) else null
        val memory = runCatching { paths.lastMemory.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        ScreenLog.addAfterDeath(paths, exit?.let { (it.timestamp - opened) / 1000.0 },
                           line(exit, Build.VERSION.SDK_INT, memory))
        return sentence(exit, Build.VERSION.SDK_INT, stage)?.let {
            Session.Ending(driver, null, false, closedBy = it,
                           closedForMemory = exit?.reason == ApplicationExitInfo.REASON_LOW_MEMORY)
        }
    }

    /** What Android kept of the app's last end: none before Android 11. [rssKb]: the app's own memory then. */
    internal class Exit(val reason: Int, val status: Int, val description: String?, val rssKb: Long,
                        val importance: Int, val timestamp: Long, val lowMemoryKillsReported: Boolean)

    /**
     * The app's last end after [since]. Android writes it as UNKNOWN when the process goes and fills in the signal a
     * moment later, by which time it may have started the app again (a vivo X200 Ultra, within a second): so an
     * UNKNOWN is asked again for a few seconds.
     */
    private fun lastExit(context: Context, since: Long): Exit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (true) {
            val info = runCatching { manager.getHistoricalProcessExitReasons(null, 0, RECORDS) }.getOrNull()
                ?.filter { it.timestamp >= since && it.processName == context.packageName }
                ?.maxByOrNull { it.timestamp }
            if (info?.reason != ApplicationExitInfo.REASON_UNKNOWN || System.currentTimeMillis() >= deadline) {
                return info?.let {
                    Exit(it.reason, it.status, it.description, it.rss, it.importance, it.timestamp,
                         ActivityManager.isLowMemoryKillReportSupported())
                }
            }
            Thread.sleep(SETTLE_POLL_MS)
        }
    }

    /**
     * For screen.log, Android's own words: "the app ended (Android: LOW_MEMORY, "lmk"; app 310 MB, importance 125) ·
     * last memory: game 3215 MB, phone free 412 MB".
     */
    internal fun line(exit: Exit?, sdk: Int, memory: String?): String {
        val what = when {
            exit != null -> {
                val status = exit.status.takeIf { it != 0 }?.let { ", status $it" } ?: ""
                val note = exit.description?.takeIf { it.isNotBlank() }?.let { ", \"$it\"" } ?: ""
                val unreported = if (!exit.lowMemoryKillsReported && exit.reason in MAYBE_MEMORY)
                    "; this phone doesn't report low-memory kills" else ""
                // Android doesn't always fill in the app's memory: 0 then.
                val app = exit.rssKb.takeIf { it > 0 }?.let { "app ${it / 1024} MB, " } ?: ""
                "the app ended (Android: ${REASONS[exit.reason] ?: "reason ${exit.reason}"}$status$note; " +
                    "${app}importance ${exit.importance}$unreported)"
            }
            sdk < Build.VERSION_CODES.R -> "the app ended during this game: Android 10 doesn't record why"
            else -> "the app ended during this game: Android kept no reason"
        }
        return what + (memory?.let { " · last memory: $it" } ?: "")
    }

    /**
     * For Home and the report: "The phone ran out of memory loading sprites: Android closed the app." Null for a stop
     * the player or Android's settings asked for, or an update.
     */
    internal fun sentence(exit: Exit?, sdk: Int, stage: GameEnding.Stage?): String? {
        val time = stage?.words ?: "before the game started"
        if (exit == null) return "The app closed $time" +
            (if (sdk < Build.VERSION_CODES.R) " (Android 10 doesn't say why)." else ".")
        return when (exit.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "The phone ran out of memory $time: Android closed the app."
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "The app crashed $time."
            ApplicationExitInfo.REASON_ANR -> "The app stopped responding $time."
            ApplicationExitInfo.REASON_SIGNALED -> "The app was killed (${signal(exit.status)}) $time."
            ApplicationExitInfo.REASON_EXIT_SELF -> "The app closed itself $time."
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                "Android closed the app $time: it used too much of the phone."
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> null
            else -> "Android closed the app $time."
        }
    }

    private fun signal(number: Int) = SIGNALS[number] ?: "signal $number"

    private fun head(file: File): String = runCatching {
        file.bufferedReader().use { reader ->
            val buffer = CharArray(LOG_HEAD)
            var read = 0
            while (read < LOG_HEAD) {
                val n = reader.read(buffer, read, LOG_HEAD - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read)
        }
    }.getOrDefault("")

    private const val RECORDS = 8
    private const val SETTLE_MS = 5_000L
    private const val SETTLE_POLL_MS = 250L
    private val SIGNALS = mapOf(6 to "SIGABRT", 7 to "SIGBUS", 9 to "SIGKILL", 11 to "SIGSEGV", 15 to "SIGTERM")
    /** What a low-memory kill shows as where the phone doesn't report them. */
    private val MAYBE_MEMORY = setOf(ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER,
                                     ApplicationExitInfo.REASON_UNKNOWN)
    private val REASONS = mapOf(
        ApplicationExitInfo.REASON_UNKNOWN to "UNKNOWN",
        ApplicationExitInfo.REASON_EXIT_SELF to "EXIT_SELF",
        ApplicationExitInfo.REASON_SIGNALED to "SIGNALED",
        ApplicationExitInfo.REASON_LOW_MEMORY to "LOW_MEMORY",
        ApplicationExitInfo.REASON_CRASH to "CRASH",
        ApplicationExitInfo.REASON_CRASH_NATIVE to "CRASH_NATIVE",
        ApplicationExitInfo.REASON_ANR to "ANR",
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "INITIALIZATION_FAILURE",
        ApplicationExitInfo.REASON_PERMISSION_CHANGE to "PERMISSION_CHANGE",
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "EXCESSIVE_RESOURCE_USAGE",
        ApplicationExitInfo.REASON_USER_REQUESTED to "USER_REQUESTED",
        ApplicationExitInfo.REASON_USER_STOPPED to "USER_STOPPED",
        ApplicationExitInfo.REASON_DEPENDENCY_DIED to "DEPENDENCY_DIED",
        ApplicationExitInfo.REASON_OTHER to "OTHER",
        ApplicationExitInfo.REASON_FREEZER to "FREEZER",
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to "PACKAGE_STATE_CHANGE",
        ApplicationExitInfo.REASON_PACKAGE_UPDATED to "PACKAGE_UPDATED")
}
