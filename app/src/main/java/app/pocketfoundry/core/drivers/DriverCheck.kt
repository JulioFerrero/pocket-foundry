package app.pocketfoundry.core.drivers

import android.content.Context
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.runtime.Program
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.Paths
import org.json.JSONObject

/**
 * Before the first game on a driver, and again when Android's driver or its Options change: runtime/programs'
 * pocketfoundry-drivercheck draws a few squares through the game's own OpenGL and reads them back. A driver that draws
 * them wrong, or crashes, gets Zink's newer features turned off, newest first, until it draws them right (Arm's r38p1
 * driver on a Mali-G610 draws only black with extended dynamic state; a Mali-G72's crashes with it). Games on that
 * driver then run without what it gets wrong; drivers that draw right keep everything.
 *
 * On a Mali the check then tries the compat layer's transcoder, which keeps the game's BC textures compressed as ASTC
 * and EAC: if its squares come out right too, games use it; if they're wrong, or the check crashes, games load the
 * textures unpacked as before. Either way the game itself never runs into it.
 */
object DriverCheck {
    /** Our Mesa patch 0005: the Vulkan extensions Zink treats as missing, comma-separated. */
    internal const val HIDE = "ZINK_HIDE_EXTENSIONS"
    /** The compat layer's BC transcoder: 1 (on where the driver lacks BC) or 0 (Mesa unpacks the textures). */
    const val TRANSCODER = "HYBRIS_BC_TEXTURES"
    /** Khronos's timeline semaphore layer (runtime/hybris), for Android drivers without the ones Zink needs. */
    internal const val TIMELINE_LAYER = "VK_LAYER_KHRONOS_timeline_semaphore"
    /** What Zink says on such a driver. */
    private const val NO_TIMELINES = "KHR_timeline_semaphore is required"
    private const val TIMEOUT_S = 20L
    /** How the check searches: a new one checks every driver again, those an older one gave up on too. */
    private const val REVISION = 2
    /** The program's exits (pocketfoundry-drivercheck.c); any other is a failure: no OpenGL, or a crash. */
    internal const val RIGHT = 0
    internal const val WRONG = 1

    /** Newest first. Zink drops the newer ones with an older one anyway. */
    private val SUSPECTS = listOf(
        listOf("VK_EXT_extended_dynamic_state3", "VK_EXT_color_write_enable", "VK_EXT_vertex_input_dynamic_state"),
        listOf("VK_EXT_extended_dynamic_state2"),
        listOf("VK_EXT_extended_dynamic_state"),
    )

    private val steps = SUSPECTS.runningFold(emptyList<String>()) { off, step -> off + step }

    enum class Result { RIGHT, WRONG, FAILED, HUNG }

    sealed interface Found {
        data class Right(val off: List<String>) : Found
        data object Wrong : Found
        /** The game likely won't draw either: that's for the driver's game outcome to tell. */
        data object Failed : Found
    }

    /** A crash goes on to the next step like a wrong picture, since what's off may be what crashed. */
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

    /** A game's [environment] with what the check found, and whether its compressed textures stay compressed. */
    class Applied(val environment: Map<String, String>, val transcodes: Boolean)

    /**
     * [driver]'s game [environment] with what its check found, checking first if it hasn't for this version of it.
     * Options with their own list of extensions to hide skip the check.
     */
    fun apply(context: Context, paths: Paths, driver: String, environment: Map<String, String>): Applied {
        if (!Drivers.usesZink(driver) || HIDE in environment) return Applied(environment, transcodes = false)
        val saved = current(context, driver)
        val found = saved ?: check(context, paths, driver, environment)
        if (saved != null) ScreenLog.add("driver check: ${summary(saved)}")
        return Applied(withFindings(environment, found), transcodes = found.optBoolean("transcode"))
    }

    internal fun withFindings(environment: Map<String, String>, found: JSONObject): Map<String, String> {
        val base = if (found.optBoolean("timelines")) withTimelines(environment) else environment
        return hiding(base, offIn(found)) + if (found.optBoolean("transcode")) mapOf(TRANSCODER to "1") else emptyMap()
    }

    /** What the check found for this version of [driver], in a line; null if it hasn't checked it. */
    fun summary(context: Context, driver: String) = current(context, driver)?.let(::summary)

    private fun current(context: Context, driver: String) =
        DriverRecords.check(context, driver)?.takeIf { it.optString("version") == version(context, driver) }

    internal fun summary(saved: JSONObject) = listOfNotNull(
        "the test couldn't draw".takeIf { saved.optBoolean("failed") },
        "the test picture is wrong whatever is off".takeIf { saved.optBoolean("wrong") },
        "timeline semaphores from Khronos's layer".takeIf { saved.optBoolean("timelines") },
        offIn(saved).takeIf { it.isNotEmpty() }?.let { it.joinToString(", ") + " off" },
        "compressed textures kept compressed".takeIf { saved.optBoolean("transcode") },
    ).joinToString(" · ").ifEmpty { "the test picture is right" }

    private fun offIn(saved: JSONObject) = saved.optString("off").split(',').filter { it.isNotEmpty() }

    private fun hiding(environment: Map<String, String>, off: List<String>) =
        if (off.isEmpty()) environment else environment + (HIDE to off.joinToString(","))

    /** Vulkan layers listed first sit above: Khronos's layer goes above libhybris's own. */
    private fun withTimelines(environment: Map<String, String>) = environment +
        ("VK_INSTANCE_LAYERS" to listOfNotNull(TIMELINE_LAYER, environment["VK_INSTANCE_LAYERS"]).joinToString(":"))

    /**
     * Remembers what it found also when it's wrong or failed: a driver isn't checked before every game, nor waited on
     * when it hangs. Drawing needs an X server: Session starts one.
     */
    fun check(context: Context, paths: Paths, driver: String, environment: Map<String, String>): JSONObject {
        ScreenLog.add("driver check: drawing a test picture")
        paths.driverCheckLog.writeText("")
        var timelines = false
        var base = environment
        val found = search { step ->
            paths.driverCheckLog.appendText("── off: ${step.joinToString(", ").ifEmpty { "nothing" }}\n")
            val result = draw(paths, driver, hiding(base, step))
            // A driver from before Vulkan 1.2 without timeline semaphores (a Huawei P40's Mali-G76 r18p0): Khronos's
            // layer adds them, for this try and every one after.
            if (result != Result.FAILED || timelines || !Drivers.isPhone(driver) ||
                NO_TIMELINES !in paths.driverCheckLog.readText()) return@search result
            timelines = true
            base = withTimelines(environment)
            paths.driverCheckLog.appendText("── with Khronos's timeline semaphore layer\n")
            draw(paths, driver, hiding(base, step))
        }
        val transcoded = found is Found.Right && Drivers.transcodable(driver) &&
                         transcoderDraws(paths, driver, hiding(base, found.off))
        val off = (found as? Found.Right)?.off.orEmpty()
        val result = JSONObject().put("version", version(context, driver)).put("off", off.joinToString(","))
            .put("transcode", transcoded).put("failed", found == Found.Failed).put("wrong", found == Found.Wrong)
            .put("timelines", timelines)
        DriverRecords.setCheck(context, driver, result)
        ScreenLog.add("driver check: ${summary(result)}")
        return result
    }

    private fun transcoderDraws(paths: Paths, driver: String, environment: Map<String, String>): Boolean {
        paths.driverCheckLog.appendText("── compressed textures through the transcoder\n")
        return draw(paths, driver, environment + (TRANSCODER to "1")) == Result.RIGHT
    }

    private fun draw(paths: Paths, driver: String, environment: Map<String, String>): Result {
        val command = listOf(paths.driverCheck.path)
        val program = Program.start(paths, command, paths.driverCheckLog, environment, Drivers.libraries(paths, driver),
                                    appendLog = true)
        val exit = program.waitFor(TIMEOUT_S) ?: run {
            program.kill()
            program.waitFor()
            paths.driverCheckLog.appendText("── still drawing after $TIMEOUT_S s: stopped\n")
            return Result.HUNG
        }
        return when (exit.status) {
            RIGHT -> Result.RIGHT
            WRONG -> Result.WRONG
            else -> Result.FAILED.also { paths.driverCheckLog.appendText("── exited with $exit\n") }
        }
    }

    /**
     * What a check holds for: the way it searches; the runtime, which brings Zink; Android's own driver, which changes
     * with phone updates; and what's laid over the driver's environment, which changes what it draws.
     */
    private fun version(context: Context, driver: String) = listOf(
        REVISION, Runtime.version(context), if (Drivers.isPhone(driver)) Gpu.driverVersion.orEmpty() else "",
        Drivers.overrides(context, driver),
    ).joinToString(" · ")
}
