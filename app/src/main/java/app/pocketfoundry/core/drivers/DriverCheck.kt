package app.pocketfoundry.core.drivers

import android.content.Context
import android.system.Os
import android.system.OsConstants
import app.pocketfoundry.core.game.Glibc
import app.pocketfoundry.core.game.ScreenLog
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Before the first game on a driver, and again when Android's own driver changes: runtime/wm's driver check draws a
 * few squares through the game's own OpenGL and reads them back. A driver that draws them wrong gets Zink's newer
 * features turned off, newest first, until it draws them right (Arm's r38p1 driver on a Mali-G610 draws only black
 * with extended dynamic state). Games on that driver then run without what it gets wrong; drivers that draw right
 * keep everything.
 */
object DriverCheck {
    /** Our Mesa patch 0005: the Vulkan extensions Zink treats as missing. */
    const val HIDE = "ZINK_HIDE_EXTENSIONS"
    private const val TIMEOUT_S = 20L

    /** The features the check can turn off, newest first. Zink drops the newer ones with an older one anyway. */
    private val SUSPECTS = listOf(
        listOf("VK_EXT_extended_dynamic_state3", "VK_EXT_color_write_enable", "VK_EXT_vertex_input_dynamic_state"),
        listOf("VK_EXT_extended_dynamic_state2"),
        listOf("VK_EXT_extended_dynamic_state"),
    )

    /** What each try turns off: nothing first, then more each time. */
    internal val steps = SUSPECTS.runningFold(emptyList<String>()) { off, step -> off + step }

    /** How one drawing came out. */
    enum class Result { RIGHT, WRONG, FAILED }

    /** What the steps found. */
    sealed interface Found {
        /** Right with [off] turned off: nothing, for a driver that draws right as it is. */
        data class Right(val off: List<String>) : Found
        /** Wrong whatever was off. */
        data object Wrong : Found
        /** Didn't draw at all, and neither will the game: that's for the driver's game outcome to tell. */
        data object Failed : Found
    }

    internal fun search(draw: (List<String>) -> Result): Found {
        for (step in steps) when (draw(step)) {
            Result.RIGHT -> return Found.Right(step)
            Result.WRONG -> {}
            Result.FAILED -> return Found.Failed
        }
        return Found.Wrong
    }

    /**
     * The game's [environment] plus what the check turned off for [driver], checking first if it hasn't since the
     * driver last changed. Options with their own list of extensions to hide skip the check.
     */
    fun environment(context: Context, paths: Paths, driver: String,
                    environment: Map<String, String>): Map<String, String> {
        if (!Drivers.usesZink(driver) || HIDE in environment) return environment
        val version = version(driver)
        val saved = saved(context, driver)?.takeIf { it.optString("version") == version }
        val off = if (saved != null) offIn(saved)
                  else check(context, paths, driver, environment, version) ?: return environment
        if (off.isEmpty()) return environment
        if (saved != null) ScreenLog.add("driver check: ${off.joinToString(", ")} off on this driver")
        return environment + (HIDE to off.joinToString(","))
    }

    /** What the last check turned off for [driver]. */
    fun off(context: Context, driver: String): List<String> = saved(context, driver)?.let(::offIn).orEmpty()

    private fun offIn(saved: JSONObject) = saved.optString("off").split(',').filter { it.isNotEmpty() }

    /** A new runtime brings a new Mesa: every driver is checked again. */
    fun forget(context: Context) {
        Settings(context).driverChecks = "{}"
    }

    /** Checks [driver] and remembers what it found: what to turn off, or null when the check couldn't draw. */
    private fun check(context: Context, paths: Paths, driver: String, environment: Map<String, String>,
                      version: String): List<String>? {
        ScreenLog.add("driver check: drawing a test picture")
        paths.driverCheckLog.writeText("")
        val found = search { step ->
            paths.driverCheckLog.appendText("── off: ${step.joinToString(", ").ifEmpty { "nothing" }}\n")
            draw(paths, driver, if (step.isEmpty()) environment else environment + (HIDE to step.joinToString(",")))
        }
        ScreenLog.add("driver check: " + when (found) {
            is Found.Right -> if (found.off.isEmpty()) "the test picture is right"
                              else "the test picture is right only with ${found.off.joinToString(", ")} off: " +
                                   "games on this driver run that way"
            Found.Wrong -> "the test picture is wrong whatever is off: games run with everything on"
            Found.Failed -> "the test couldn't draw (drivercheck.log)"
        })
        // A driver that's wrong whatever is off is remembered too, so it isn't checked before every game.
        val off = when (found) {
            is Found.Right -> found.off
            Found.Wrong -> emptyList()
            Found.Failed -> return null
        }
        save(context, driver, version, off)
        return off
    }

    private fun draw(paths: Paths, driver: String, environment: Map<String, String>): Result {
        val program = File(paths.prefix, "bin/pocketfoundry-drivercheck").path
        val process = Glibc.start(paths, listOf(program), paths.driverCheckLog, environment,
                                  Drivers.libraries(paths, driver), append = true)
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            // A driver hung drawing: not destroy(), which sends SIGTERM on Android.
            runCatching { Os.kill(Glibc.childPid(program), OsConstants.SIGKILL) }
            process.waitFor()
            paths.driverCheckLog.appendText("drivercheck: still drawing after $TIMEOUT_S s\n")
            return Result.FAILED
        }
        return when (process.exitValue()) {
            0 -> Result.RIGHT
            1 -> Result.WRONG
            else -> Result.FAILED
        }
    }

    /** Android's own driver changes with phone updates; the others only with a runtime, which forgets every check. */
    private fun version(driver: String) = if (Drivers.isPhone(driver)) Gpu.driverVersion.orEmpty() else ""

    private fun saved(context: Context, driver: String): JSONObject? =
        json(Settings(context).driverChecks).optJSONObject(driver)

    private fun save(context: Context, driver: String, version: String, off: List<String>) {
        val settings = Settings(context)
        val result = JSONObject().put("version", version).put("off", off.joinToString(","))
        settings.driverChecks = json(settings.driverChecks).put(driver, result).toString()
    }

    private fun json(text: String) = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
}
