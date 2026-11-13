package app.pocketfoundry.core.drivers

import android.content.Context
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.runtime.Program
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.Paths
import org.json.JSONObject

/**
 * Whether a driver can play here, and how, from what it does rather than what it's called. runtime/programs'
 * pocketfoundry-drivercheck draws through the game's own OpenGL, and the app sets the game up from what it finds:
 *
 * - Thirteen squares the way the game draws them, read back, with the driver's stand-ins for BC off so Mesa unpacks the
 *   compressed ones exactly: a stand-in's own errors are for the next step to judge. Where they come out wrong, or the
 *   program crashes, Zink's
 *   newer features go off, newest first, until they come out right (Arm's r38p1 driver on a Mali-G610 drew only black
 *   with extended dynamic state). A driver without the OpenGL 3.3 the game needs is asked for it anyway, and one
 *   without the timeline semaphores Zink needs gets them from Khronos's layer.
 * - The game's atlases at their size, each way the driver can keep BC textures (Drivers.textureWays): read through a
 *   shader, measured in GPU memory, read back as fast start saves them, and filled with blocks copied in as Factorio's
 *   GPU compressor makes them. The best way that keeps them compressed wins: fast start goes off where they come back
 *   wrong, and the CPU compresses where copied blocks read wrong. A driver that can't keep them compressed can't play:
 *   unpacked, they need four times the memory.
 * - A speed test, for the resolution (SpeedTest).
 *
 * Before the first game on a driver (Session), and again when the runtime, Android's driver or the Options change.
 * A driver that draws wrong, can't draw or can't keep the textures compressed is passed over (Drivers.recommended).
 */
object DriverCheck {
    /** Our Mesa patch 0005: the Vulkan extensions Zink treats as missing, comma-separated. */
    internal const val HIDE = "ZINK_HIDE_EXTENSIONS"
    /** Khronos's timeline semaphore layer (runtime/hybris), for Android drivers without the ones Zink needs. */
    internal const val TIMELINE_LAYER = "VK_LAYER_KHRONOS_timeline_semaphore"
    /** What Zink says on such a driver. */
    private const val NO_TIMELINES = "KHR_timeline_semaphore is required"
    /** What the program says when Zink gives it no OpenGL at all: the game can't open its window either. */
    private const val NO_CONFIGURATION = "drivercheck: no OpenGL configuration"
    private const val NO_OPENGL_33 = "drivercheck: no OpenGL 3.3 context"
    /**
     * Mesa offers OpenGL 3.3 through Zink only on Vulkan drivers with depth clamp and vertex attribute divisors.
     * Asked for anyway, a Mali-G610's driver gave a context that draws right; the instanced square tells.
     */
    private val OPENGL_33 = mapOf("MESA_GL_VERSION_OVERRIDE" to "3.3",
                                  "MESA_EXTENSION_OVERRIDE" to "+GL_ARB_instanced_arrays")
    private const val TIMEOUT_S = 20L
    /** The atlases can go through the converter on the CPU: a few seconds on a fast phone. */
    private const val TEXTURES_TIMEOUT_S = 120L
    /** How the check searches: a new one checks every driver again, those an older one gave up on too. */
    private const val REVISION = 10
    /** The program's exits (pocketfoundry-drivercheck.c); any other is a failure: no OpenGL, or a crash. */
    internal const val RIGHT = 0
    internal const val WRONG = 1
    private val MEMORY = Regex("""drivercheck: memory (\d+) MB, (\d+) MB available""")
    /** Saved when no way keeps the game's textures compressed. */
    private const val NO_WAY = "none"

    /**
     * Mesa's software presentation (MESA_VK_WSI_DEBUG=sw, as PanVK presents) reads each frame back through cached or
     * uncached memory. Left to itself, PanVK-kbase times both while the game runs, moving every frame's buffer between
     * them: black frames over a Mali-G615's Factorio logo. Which is faster depends on the phone, so the speed test
     * times both and the game keeps the faster ("swReadback"). Drivers without that probe ignore it.
     */
    internal const val SW_READBACK = "PANVK_WSI_SW_CACHED"

    /** The textures run's last line: pocketfoundry-drivercheck.c's ATLASES. */
    internal data class Atlases(val draws: Boolean, val comesBack: Boolean, val copies: Boolean, val takesMb: Int,
                                val compressedMb: Int) {
        /**
         * Whether one stayed compressed, by the memory it took; null where the driver doesn't say. Converted, an atlas
         * takes about twice its compressed size: the compat layer keeps the original blocks beside the ASTC, for
         * reading back. Unpacked, four times or more.
         */
        val compressed: Boolean? get() = if (takesMb <= 0) null else takesMb < 3 * compressedMb

        companion object {
            private val LINE =
                Regex("""drivercheck: atlases draws=(\d) comesBack=(\d) copies=(\d) takes=(-?\d+) of=(\d+)""")

            fun parse(log: String) = LINE.findAll(log).lastOrNull()?.destructured?.let { (d, c, p, t, o) ->
                Atlases(d == "1", c == "1", p == "1", t.toInt(), o.toInt())
            }
        }
    }

    /**
     * How good [way] is, from its [atlases]: null if the game can't use it (they draw wrong, or don't stay compressed),
     * then known compressed over not known, then coming back right over not. Where the driver doesn't say its memory,
     * a way that converts what the driver lacks counts as compressed.
     */
    internal fun rank(way: Drivers.TextureWay, atlases: Atlases): Int? {
        val compressed = atlases.compressed ?: way.convertsWhatsMissing.takeIf { it }
        if (!atlases.draws || compressed == false) return null
        return (if (compressed == true) 2 else 0) + (if (atlases.comesBack) 1 else 0)
    }

    private const val BEST = 3

    /** Newest first. Zink drops the newer ones with an older one anyway. */
    private val SUSPECTS = listOf(
        listOf("VK_EXT_extended_dynamic_state3", "VK_EXT_color_write_enable", "VK_EXT_vertex_input_dynamic_state"),
        listOf("VK_EXT_extended_dynamic_state2"),
        listOf("VK_EXT_extended_dynamic_state"),
    )

    private val steps = SUSPECTS.runningFold(emptyList<String>()) { off, step -> off + step }

    enum class Result { RIGHT, WRONG, FAILED, NO_OPENGL, HUNG }

    sealed interface Found {
        data class Right(val off: List<String>) : Found
        data object Wrong : Found
        data object Failed : Found
        /** Zink gave no OpenGL at all, which fewer features can't change: the game wouldn't open. */
        data object NoOpenGL : Found
    }

    /** A crash goes on to the next step like a wrong picture, since what's off may be what crashed. */
    internal fun search(draw: (List<String>) -> Result): Found {
        var drew = false
        for (step in steps) when (draw(step)) {
            Result.RIGHT -> return Found.Right(step)
            Result.WRONG -> drew = true
            Result.FAILED -> {}
            Result.NO_OPENGL -> return if (drew) Found.Wrong else Found.NoOpenGL
            Result.HUNG -> return Found.Failed
        }
        return if (drew) Found.Wrong else Found.Failed
    }

    /**
     * A game's [environment] with what the check found, whether fast start's cache comes back right ([keepsCache]),
     * whether Factorio's GPU compressor can fill the atlases ([gpuCompression]; null for a driver the check doesn't
     * cover), and whether it gets OpenGL at all.
     */
    class Applied(val environment: Map<String, String>, val opens: Boolean = true, val keepsCache: Boolean = true,
                  val gpuCompression: Boolean? = null)

    /**
     * [driver]'s game [environment] with what its check found, checking first if it hasn't for this version of it.
     * Options with their own list of extensions to hide skip the check: the CPU compresses, to be safe.
     */
    fun apply(context: Context, paths: Paths, driver: String, environment: Map<String, String>): Applied {
        if (!Drivers.checked(paths, driver)) return Applied(environment)
        if (HIDE in environment) return Applied(environment, gpuCompression = false)
        val found = current(context, driver) ?: check(context, paths, driver, environment)
        ScreenLog.add("driver check: ${summary(found)}")
        return Applied(withFindings(environment, found) { way, over -> Drivers.textureWay(paths, driver, over, way) },
                       opens = !found.optBoolean("noOpenGL"),
                       keepsCache = found.optBoolean("cache", true),
                       gpuCompression = found.optBoolean("gpuCompression"))
    }

    /** [driver] goes through Zink, its Options don't hide extensions themselves, and this version is unchecked. */
    fun needed(context: Context, paths: Paths, driver: String, environment: Map<String, String>) =
        Drivers.checked(paths, driver) && HIDE !in environment && current(context, driver) == null

    /** Whether this version of [driver] can play here; null if it hasn't been checked. */
    fun passed(context: Context, driver: String) = current(context, driver)?.let(::passed)

    internal fun passed(saved: JSONObject) =
        !saved.optBoolean("failed") && !saved.optBoolean("wrong") && saved.optString("textures") != NO_WAY

    /** The GPU memory this version of [driver] had free when checked, in MB; null where it doesn't say. */
    fun freeMemory(context: Context, driver: String) =
        current(context, driver)?.optInt("available")?.takeIf { it > 0 }

    /**
     * What the game's textures cost on this version of [driver], for each MB they'd take compressed: the memory the
     * check's atlas took, else about 2 through the compat layer (the ASTC and the original blocks) and 1 otherwise.
     */
    fun textureCost(context: Context, driver: String): Double {
        val saved = current(context, driver) ?: return 1.0
        val taken = saved.optInt("atlasMb")
        return when {
            taken > 0 && saved.optInt("atlasOf") > 0 -> taken.toDouble() / saved.optInt("atlasOf")
            saved.optString("textures").startsWith(Drivers.TextureWay.LAYER) -> 2.0
            else -> 1.0
        }
    }

    /** Fast start's cache holds what one version of one driver gave back (AtlasCache): this one's, as a name. */
    fun cacheMaker(context: Context, driver: String) = "$driver · ${version(context, driver)}"

    /** [way]: what keeps the textures the way the check found, by its name, over an environment. */
    internal fun withFindings(environment: Map<String, String>, found: JSONObject,
                              way: (String, Map<String, String>) -> Map<String, String>): Map<String, String> {
        var base = if (found.optBoolean("timelines")) withTimelines(environment) else environment
        if (found.optBoolean("openGL33")) base = withOpenGL33(base)
        val withOff = hiding(base, offIn(found))
        val chosen = found.optString("textures")
        val withWay = if (chosen.isEmpty() || chosen == NO_WAY) withOff else withOff + way(chosen, withOff)
        val readback = found.optString("swReadback")
        return if (readback.isEmpty()) withWay else withWay + (SW_READBACK to readback)
    }

    /** How fast this version of [driver] drew the speed test; empty if it hasn't, or couldn't. */
    fun speed(context: Context, driver: String) =
        SpeedTest.fromJson(current(context, driver)?.optJSONArray("speed"))

    /** In a few words, for the driver screen: "Passed", "Passed · 3 features off"; null if it hasn't checked it. */
    fun brief(context: Context, driver: String) = current(context, driver)?.let(::brief)

    internal fun brief(saved: JSONObject): String = when {
        saved.optBoolean("noOpenGL") -> "No OpenGL"
        saved.optBoolean("failed") -> "Couldn't draw"
        saved.optBoolean("wrong") -> "Wrong picture"
        saved.optString("textures") == NO_WAY -> "Textures don't stay compressed"
        else -> listOfNotNull(
            "Passed",
            offIn(saved).size.takeIf { it > 0 }?.let { if (it == 1) "1 feature off" else "$it features off" },
            "fast start off".takeIf { !saved.optBoolean("cache", true) },
        ).joinToString(" · ")
    }

    /** Whether Factorio's GPU compressor fills the atlases on this version of [driver]; null if it isn't checked. */
    fun gpuCompression(context: Context, driver: String) = current(context, driver)?.optBoolean("gpuCompression")

    /** What the check found for this version of [driver], in a line; null if it hasn't checked it. */
    fun summary(context: Context, driver: String) = current(context, driver)?.let(::summary)

    private fun current(context: Context, driver: String) =
        DriverRecords.check(context, driver)?.takeIf { it.optString("version") == version(context, driver) }

    internal fun summary(saved: JSONObject): String {
        val drew = passed(saved) || saved.optString("textures") == NO_WAY
        return (listOfNotNull(
            "Zink got no OpenGL from the driver".takeIf { saved.optBoolean("noOpenGL") },
            "the test couldn't draw".takeIf { saved.optBoolean("failed") && !saved.optBoolean("noOpenGL") },
            "the test picture is wrong whatever is off".takeIf { saved.optBoolean("wrong") },
            saved.optString("why").takeIf { it.isNotEmpty() }?.let { "first try: $it" },
            "OpenGL 3.3 asked for".takeIf { saved.optBoolean("openGL33") },
            "timeline semaphores from Khronos's layer".takeIf { saved.optBoolean("timelines") },
            offIn(saved).takeIf { it.isNotEmpty() }?.let { it.joinToString(", ") + " off" },
            "the game's textures don't stay compressed any way (${saved.optString("tried")})"
                .takeIf { saved.optString("textures") == NO_WAY },
            "textures through the compat layer".takeIf { saved.optString("textures") == Drivers.TextureWay.LAYER },
            "textures through the compat layer, Vulkan 1.3 asked for"
                .takeIf { saved.optString("textures") == Drivers.TextureWay.LAYER_VULKAN_13 },
            "fast start off: the atlases come back wrong".takeIf { drew && !saved.optBoolean("cache", true) },
            saved.optString("swReadback").takeIf { it.isNotEmpty() }
                ?.let { "frames read back through ${if (it == "1") "cached" else "uncached"} memory" },
            "the CPU compresses: copied blocks read wrong"
                .takeIf { passed(saved) && saved.has("gpuCompression") && !saved.optBoolean("gpuCompression") },
        ).ifEmpty { listOf("the test picture is right") } + listOfNotNull(
            saved.optString("formats").takeIf { it.isNotEmpty() },
            saved.optInt("memory").takeIf { it > 0 }?.let { "GPU memory $it MB, ${saved.optInt("available")} free" },
        )).joinToString(" · ")
    }

    private fun offIn(saved: JSONObject) = saved.optString("off").split(',').filter { it.isNotEmpty() }

    private fun hiding(environment: Map<String, String>, off: List<String>) =
        if (off.isEmpty()) environment else environment + (HIDE to off.joinToString(","))

    /** Vulkan layers listed first sit above: Khronos's layer goes above libhybris's own. */
    private fun withTimelines(environment: Map<String, String>) = environment +
        ("VK_INSTANCE_LAYERS" to listOfNotNull(TIMELINE_LAYER, environment["VK_INSTANCE_LAYERS"]).joinToString(":"))

    /** Over Options that override extensions too: both lists count. */
    private fun withOpenGL33(environment: Map<String, String>) = environment + OPENGL_33 +
        ("MESA_EXTENSION_OVERRIDE" to listOfNotNull(environment["MESA_EXTENSION_OVERRIDE"],
                                                    OPENGL_33["MESA_EXTENSION_OVERRIDE"]).joinToString(" "))

    /**
     * Remembers what it found also when it's wrong or failed: a driver isn't checked before every game, nor waited on
     * when it hangs. Drawing needs an X server: Session starts one. [onStep]: what it's doing ("drawing", "textures",
     * "speed") and how far along, 0 to 1.
     */
    fun check(context: Context, paths: Paths, driver: String, environment: Map<String, String>,
              onStep: (String, Float) -> Unit = { _, _ -> }): JSONObject {
        ScreenLog.add("driver check: drawing a test picture")
        paths.driverCheckLog.writeText("")
        var timelines = false
        var openGL33 = false
        var base = environment + Drivers.withoutStandIns(paths, driver, environment)
        var tries = 0
        var first = ""
        val found = search { step ->
            onStep("drawing", 0.3f * tries++ / steps.size)
            var run = ""
            fun attempt(): Result {
                paths.driverCheckLog.appendText("── off: ${step.joinToString(", ").ifEmpty { "nothing" }}\n")
                val (result, output) = drawRun(paths, driver, hiding(base, step))
                run = output
                return result
            }
            var result = attempt()
            // A driver from before Vulkan 1.2 without timeline semaphores (a Huawei P40's Mali-G76 r18p0): Khronos's
            // layer adds them, for this try and every one after. Only the phone's driver has it beside it.
            if ((result == Result.FAILED || result == Result.NO_OPENGL) && !timelines && Drivers.isPhone(driver) &&
                NO_TIMELINES in run) {
                timelines = true
                base = withTimelines(base)
                paths.driverCheckLog.appendText("── with Khronos's timeline semaphore layer\n")
                result = attempt()
            }
            if (result == Result.NO_OPENGL && !openGL33 && NO_OPENGL_33 in run) {
                openGL33 = true
                base = withOpenGL33(base)
                paths.driverCheckLog.appendText("── asking for OpenGL 3.3 anyway\n")
                result = attempt()
            }
            // The first step's, after what it was given: what the summary says went wrong.
            if (first.isEmpty()) first = run
            result
        }
        onStep("drawing", 0.3f)
        val off = (found as? Found.Right)?.off.orEmpty()
        val result = JSONObject().put("version", version(context, driver)).put("off", off.joinToString(","))
            .put("failed", found == Found.Failed || found == Found.NoOpenGL)
            .put("noOpenGL", found == Found.NoOpenGL).put("wrong", found == Found.Wrong)
            .put("timelines", timelines).put("openGL33", openGL33)
        if (found !is Found.Right) result.put("why", why(first))
        if (found is Found.Right) {
            textures(paths, driver, withFindings(environment, result) { _, _ -> emptyMap() }, result, onStep)
            // On what can play, with what the game gets.
            if (passed(result)) {
                onStep("speed", 0.7f)
                val game = withFindings(environment, result) { way, over ->
                    Drivers.textureWay(paths, driver, over, way)
                }
                val readbacks = if (game["MESA_VK_WSI_DEBUG"] == "sw") listOf("0", "1") else listOf(null)
                val timed = readbacks.map { mode ->
                    mode to SpeedTest.measure(context, paths, driver,
                                              game + listOfNotNull(mode?.let { SW_READBACK to it }))
                }
                val (readback, speed) = timed.filter { it.second.isNotEmpty() }
                    .minByOrNull { (_, measured) -> measured.sumOf { it.ms } } ?: timed.first()
                if (readback != null) result.put("swReadback", readback)
                result.put("speed", SpeedTest.toJson(speed))
                if (speed.isNotEmpty()) ScreenLog.add("speed test: ${SpeedTest.summary(speed)}")
            }
        }
        MEMORY.findAll(paths.driverCheckLog.readText()).lastOrNull()?.destructured?.let { (total, available) ->
            result.put("memory", total.toInt()).put("available", available.toInt())
        }
        onStep("speed", 1f)
        DriverRecords.setCheck(context, driver, result)
        return result
    }

    /** Each way the driver can keep the game's textures, until the best: into [result]. */
    private fun textures(paths: Paths, driver: String, drawn: Map<String, String>, result: JSONObject,
                         onStep: (String, Float) -> Unit) {
        val ways = Drivers.textureWays(paths, driver, drawn)
        var best: Pair<Drivers.TextureWay, Atlases>? = null
        var bestRank = -1
        val tried = mutableListOf<String>()
        for ((index, way) in ways.withIndex()) {
            onStep("textures", 0.35f + 0.3f * index / ways.size)
            paths.driverCheckLog.appendText("── the game's atlases, ${way.saved}\n")
            // Its results stand even if asking Vulkan for its formats, last, crashed.
            val (_, run) = drawRun(paths, driver, drawn + way.environment, listOf("textures"), TEXTURES_TIMEOUT_S)
            val atlases = Atlases.parse(run)
            val formats = formats(run)
            if (index == 0 && formats != null) result.put("formats", formats)
            val rank = atlases?.let { rank(way, it) }
            val outcome = when {
                atlases == null -> "the program didn't finish"
                rank == null && !atlases.draws -> "read wrong"
                rank == null -> "${atlases.takesMb} MB for ${atlases.compressedMb} compressed: not kept compressed"
                else -> listOf(if (atlases.comesBack) "come back right" else "come back wrong",
                               if (atlases.copies) "copied blocks right" else "copied blocks wrong",
                               if (atlases.takesMb > 0) "${atlases.takesMb} MB" else "memory unknown")
                    .joinToString(", ")
            }
            tried += "${way.saved}: " + listOfNotNull(formats, outcome).joinToString(", ")
            ScreenLog.add("driver check: the game's atlases, ${way.saved}: $outcome")
            if (rank != null && rank > bestRank) {
                best = way to atlases
                bestRank = rank
            }
            if (bestRank == BEST) break
        }
        result.put("tried", tried.joinToString("; "))
        val (way, atlases) = best ?: run {
            result.put("textures", NO_WAY)
            return
        }
        result.put("textures", way.saved).put("cache", atlases.comesBack).put("gpuCompression", atlases.copies)
            .put("atlasMb", atlases.takesMb).put("atlasOf", atlases.compressedMb)
    }

    /** What the first try found wrong, in its words: the first squares that came out wrong, or where it crashed. */
    internal fun why(run: String) = run.lines().filter { it.startsWith("drivercheck: ") }
        .map { it.removePrefix("drivercheck: ") }
        .filter { "instead of" in it || "crashed" in it || "X error" in it || it.startsWith("no ") ||
                  it.startsWith("in ") }
        .take(3).joinToString("; ")

    /** What the program wrote in its last run. */
    private fun lastRun(paths: Paths) = paths.driverCheckLog.readText().substringAfterLast("\n── ")

    /** [draw], with the program's own lines: draw() adds its verdict after a crash, all that lastRun would give. */
    private fun drawRun(paths: Paths, driver: String, environment: Map<String, String>,
                        args: List<String> = emptyList(), timeoutS: Long = TIMEOUT_S): Pair<Result, String> {
        val start = paths.driverCheckLog.readText().length
        val result = draw(paths, driver, environment, args, timeoutS)
        return result to paths.driverCheckLog.readText().substring(start).substringBefore("── exited with")
    }

    private val FORMATS =
        Regex("""drivercheck: formats Vulkan (\d+\.\d+) \((.*)\): BC (\w+), ETC2 (\w+), EAC (\w+), ASTC (\w+)""")

    /**
     * What the Vulkan driver beneath samples, from a textures run (pocketfoundry-drivercheck.c's FORMATS):
     * "Vulkan 1.4, samples ETC2, EAC, ASTC". Through the compat layer, BC among them is the layer converting it.
     */
    internal fun formats(run: String): String? = FORMATS.findAll(run).lastOrNull()?.destructured
        ?.let { (version, _, bc, etc2, eac, astc) ->
            val sampled = listOf("BC" to bc, "ETC2" to etc2, "EAC" to eac, "ASTC" to astc)
                .filter { it.second != "no" }.map { (name, how) -> if (how == "some") "some $name" else name }
            "Vulkan $version, samples " + sampled.joinToString(", ").ifEmpty { "no compressed formats" }
        }

    private fun draw(paths: Paths, driver: String, environment: Map<String, String>, args: List<String> = emptyList(),
                     timeoutS: Long = TIMEOUT_S): Result {
        val command = listOf(paths.driverCheck.path) + args
        val program = Program.start(paths, command, paths.driverCheckLog, environment,
                                    Drivers.libraryPath(paths, driver), appendLog = true)
        val exit = program.waitFor(timeoutS) ?: run {
            program.kill()
            program.waitFor()
            paths.driverCheckLog.appendText("── still drawing after $timeoutS s: stopped\n")
            return Result.HUNG
        }
        return when (exit.status) {
            RIGHT -> Result.RIGHT
            WRONG -> Result.WRONG
            else -> {
                val run = lastRun(paths)
                paths.driverCheckLog.appendText("── exited with $exit\n")
                if (NO_CONFIGURATION in run || NO_OPENGL_33 in run) Result.NO_OPENGL else Result.FAILED
            }
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
