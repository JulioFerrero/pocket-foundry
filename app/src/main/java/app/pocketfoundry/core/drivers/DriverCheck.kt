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
 * Before the first game on a driver, and again when Android's driver or its Options change: runtime/wm's check draws a
 * few squares through the game's own OpenGL and reads them back. A driver that draws them wrong, or crashes, gets
 * Zink's newer features turned off, newest first, until it draws them right (Arm's r38p1 driver on a Mali-G610 draws
 * only black with extended dynamic state; a Mali-G72's crashes with it). Games on that driver then run without what it
 * gets wrong; drivers that draw right keep everything.
 *
 * On a Mali the check then tries the compat layer's transcoder, which keeps the game's BC textures compressed as ASTC
 * and EAC: if its squares come out right too, games use it; if they're wrong, or the check crashes, games load the
 * textures unpacked as before. Either way the game itself never runs into it.
 */
object DriverCheck {
    /** Our Mesa patch 0005: the Vulkan extensions Zink treats as missing. */
    const val HIDE = "ZINK_HIDE_EXTENSIONS"
    /** The compat layer's BC transcoder: 1 (on where the driver lacks BC) or 0 (Mesa unpacks the textures). */
    private const val TRANSCODER = "HYBRIS_BC_TEXTURES"
    /** Khronos's timeline semaphore layer (runtime/hybris), for Android drivers without the ones Zink needs. */
    private const val TIMELINE_LAYER = "VK_LAYER_KHRONOS_timeline_semaphore"
    /** What Zink says on such a driver. */
    private const val NO_TIMELINES = "KHR_timeline_semaphore is required"
    private const val TIMEOUT_S = 20L
    /** How the check searches: a new one checks every driver again, those an older one gave up on too. */
    private const val REVISION = 2

    /** The features the check can turn off, newest first. Zink drops the newer ones with an older one anyway. */
    private val SUSPECTS = listOf(
        listOf("VK_EXT_extended_dynamic_state3", "VK_EXT_color_write_enable", "VK_EXT_vertex_input_dynamic_state"),
        listOf("VK_EXT_extended_dynamic_state2"),
        listOf("VK_EXT_extended_dynamic_state"),
    )

    /** What each try turns off: nothing first, then more each time. */
    internal val steps = SUSPECTS.runningFold(emptyList<String>()) { off, step -> off + step }

    /** How one drawing came out: FAILED when the check crashed or OpenGL didn't start, HUNG when it never finished. */
    enum class Result { RIGHT, WRONG, FAILED, HUNG }

    /** What the steps found. */
    sealed interface Found {
        /** Right with [off] turned off: nothing, for a driver that draws right as it is. */
        data class Right(val off: List<String>) : Found
        /** Wrong whatever was off. */
        data object Wrong : Found
        /**
         * Didn't draw at all whatever was off, or hung, and the game likely won't either: that's for the driver's game
         * outcome to tell. Games run with everything on, without checking again first.
         */
        data object Failed : Found
    }

    /** A crash goes on to the next step like a wrong picture, since what's off may be what crashed; a hang stops. */
    internal fun search(draw: (List<String>) -> Result): Found {
        var drew = false
        for (step in steps) when (draw(step)) {
            Result.RIGHT -> return Found.Right(step)
            Result.WRONG -> drew = true
            Result.FAILED -> {}
            Result.HUNG -> return Found.Failed
        }
        return if (drew) Found.Wrong else Found.Failed
    }

    /**
     * The game's [environment] plus what the check turned off for [driver], checking first if it hasn't since the
     * driver or its Options last changed. Options with their own list of extensions to hide skip the check.
     */
    fun environment(context: Context, paths: Paths, driver: String,
                    environment: Map<String, String>): Map<String, String> {
        if (!Drivers.usesZink(driver) || HIDE in environment) return environment
        val version = version(context, driver)
        val saved = saved(context, driver)?.takeIf { it.optString("version") == version }
        val found = saved ?: check(context, paths, driver, environment, version)
        if (saved != null) summary(saved).takeIf { it.isNotEmpty() }?.let { ScreenLog.add("driver check: $it") }
        val transcoded = found.optBoolean("transcode")
        val base = if (found.optBoolean("timelines")) withTimelines(environment) else environment
        return hiding(base, offIn(found)) + if (transcoded) mapOf(TRANSCODER to "1") else emptyMap()
    }

    /** Checks [driver] again now, out of a game and whatever it found before (Settings -> Driver -> Check). */
    fun recheck(context: Context, paths: Paths, driver: String, environment: Map<String, String>) {
        check(context, paths, driver, environment, version(context, driver))
    }

    /** Whether the last check found [driver] draws the game's compressed textures right through the transcoder. */
    fun transcodes(context: Context, driver: String) = saved(context, driver)?.optBoolean("transcode") == true

    /** What the last check of [driver] found, in a line, or null before its first. */
    fun result(context: Context, driver: String) =
        saved(context, driver)?.let { summary(it).ifEmpty { "the test picture is right" } }

    /** What the last check of [driver] changes for its games, in a line: empty when nothing. */
    fun summary(context: Context, driver: String) = saved(context, driver)?.let(::summary).orEmpty()

    internal fun summary(saved: JSONObject) = listOfNotNull(
        "the test couldn't draw".takeIf { saved.optBoolean("failed") },
        "the test picture is wrong whatever is off".takeIf { saved.optBoolean("wrong") },
        "timeline semaphores from Khronos's layer".takeIf { saved.optBoolean("timelines") },
        offIn(saved).takeIf { it.isNotEmpty() }?.let { it.joinToString(", ") + " off" },
        "compressed textures kept compressed".takeIf { saved.optBoolean("transcode") },
    ).joinToString(" · ")

    private fun offIn(saved: JSONObject) = saved.optString("off").split(',').filter { it.isNotEmpty() }

    private fun hiding(environment: Map<String, String>, off: List<String>) =
        if (off.isEmpty()) environment else environment + (HIDE to off.joinToString(","))

    /** [environment] with Khronos's timeline semaphore layer above libhybris's own. */
    private fun withTimelines(environment: Map<String, String>) = environment +
        ("VK_INSTANCE_LAYERS" to listOfNotNull(TIMELINE_LAYER, environment["VK_INSTANCE_LAYERS"]).joinToString(":"))

    /** A new runtime brings a new Mesa: every driver is checked again. */
    fun forget(context: Context) {
        Settings(context).driverChecks = "{}"
    }

    /** Checks [driver] and remembers what it found. */
    private fun check(context: Context, paths: Paths, driver: String, environment: Map<String, String>,
                      version: String): JSONObject {
        ScreenLog.add("driver check: drawing a test picture")
        paths.driverCheckLog.writeText("")
        var base = environment
        val found = search { step ->
            paths.driverCheckLog.appendText("── off: ${step.joinToString(", ").ifEmpty { "nothing" }}\n")
            val result = draw(paths, driver, hiding(base, step))
            // A driver from before Vulkan 1.2 without timeline semaphores (a Huawei P40's Mali-G76 r18p0): Khronos's
            // layer adds them, for this try and every one after.
            if (result != Result.FAILED || base !== environment || !Drivers.isPhone(driver) ||
                NO_TIMELINES !in paths.driverCheckLog.readText()) return@search result
            base = withTimelines(environment)
            paths.driverCheckLog.appendText("── with Khronos's timeline semaphore layer\n")
            draw(paths, driver, hiding(base, step))
        }
        val timelines = base !== environment
        if (timelines) ScreenLog.add("driver check: the driver has no timeline semaphores: Khronos's layer adds them")
        ScreenLog.add("driver check: " + when (found) {
            is Found.Right -> if (found.off.isEmpty()) "the test picture is right"
                              else "the test picture is right only with ${found.off.joinToString(", ")} off: " +
                                   "games on this driver run that way"
            Found.Wrong -> "the test picture is wrong whatever is off: games run with everything on"
            Found.Failed -> "the test couldn't draw (drivercheck.log): games on this driver run with everything on"
        })
        // Wrong or failed is remembered too, so a driver isn't checked before every game, nor waited on when it hangs.
        val transcoded = found is Found.Right && Drivers.transcodable(driver) &&
                         transcoderDraws(paths, driver, hiding(base, found.off))
        return save(context, driver, version, found, transcoded, timelines)
    }

    /** Whether the game's compressed textures draw right through the transcoder, on top of what's off. */
    private fun transcoderDraws(paths: Paths, driver: String, environment: Map<String, String>): Boolean {
        paths.driverCheckLog.appendText("── compressed textures through the transcoder\n")
        val right = draw(paths, driver, environment + (TRANSCODER to "1")) == Result.RIGHT
        ScreenLog.add("driver check: " +
                      if (right) "compressed textures stay compressed on this driver"
                      else "compressed textures come out wrong through the transcoder: they load unpacked")
        return right
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
            return Result.HUNG
        }
        return when (val status = process.exitValue()) {
            0 -> Result.RIGHT
            1 -> Result.WRONG
            // 2 when OpenGL didn't start, above 128 when a signal killed it (139: SIGSEGV).
            else -> Result.FAILED.also { paths.driverCheckLog.appendText("drivercheck: exited with $status\n") }
        }
    }

    /**
     * What a check holds for: the way it searches; Android's own driver, which changes with phone updates, the others
     * only with a runtime, which forgets every check; and a driver's Options, which change what it draws.
     */
    private fun version(context: Context, driver: String) = "$REVISION · " +
        (if (Drivers.isPhone(driver)) Gpu.driverVersion.orEmpty() else "") + " · " + Drivers.options(context, driver)

    private fun saved(context: Context, driver: String): JSONObject? =
        json(Settings(context).driverChecks).optJSONObject(driver)

    private fun save(context: Context, driver: String, version: String, found: Found, transcoded: Boolean,
                     timelines: Boolean): JSONObject {
        val settings = Settings(context)
        val off = (found as? Found.Right)?.off.orEmpty()
        val result = JSONObject().put("version", version).put("off", off.joinToString(",")).put("transcode", transcoded)
            .put("failed", found == Found.Failed).put("wrong", found == Found.Wrong).put("timelines", timelines)
        settings.driverChecks = json(settings.driverChecks).put(driver, result).toString()
        return result
    }

    private fun json(text: String) = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
}
