package app.pocketfoundry.core.drivers

import android.content.Context
import app.pocketfoundry.core.runtime.Program
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.X11Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * How fast a driver draws frames like the game's: runtime/programs' pocketfoundry-speedtest draws the same scene at the
 * default resolution and at the coarsest, as fast as it can. Between the two, a frame's time splits into what the
 * driver costs whatever the size and what each pixel costs, which tells how fast every resolution would go. It runs
 * with the driver check, which keeps what it measured; a new driver build, runtime or Options measure again.
 */
object SpeedTest {
    private const val TIMEOUT_S = 40L
    /**
     * The game draws about this share of the test's frames at the same size. At their default resolutions a Galaxy
     * S10 with PanVK drew 86 test frames a second and 17 to 24 of Factorio's (0.23); a Galaxy S20 with PanVK-kbase
     * 121 and 37 to 45 (0.34).
     */
    private const val GAME_SHARE = 0.28
    /**
     * Of the game's frame rate, what keeps a finer resolution: below it, a coarser one is worth its blur. At 60 FPS
     * this keeps the S20's default and moves the S10 one step coarser.
     */
    private const val ENOUGH = 0.5

    /** [ms] per frame at [width] x [height]. */
    class Measured(val width: Int, val height: Int, val ms: Double) {
        val pixels get() = width.toLong() * height
        fun toJson(): JSONObject = JSONObject().put("width", width).put("height", height).put("ms", ms)
    }

    /** A frame takes [fixedMs] whatever the size, plus [msPerMegapixel] for each million pixels. */
    class Model(val fixedMs: Double, val msPerMegapixel: Double) {
        fun fps(pixels: Long) = 1000 / (fixedMs + msPerMegapixel * pixels / 1e6)
    }

    /** What suits the driver: the finest resolution that keeps the frame rate, else the coarsest and Light graphics. */
    class Fit(val scale: Int, val light: Boolean)

    /** The resolutions it tries: the default and coarser, finest first. Finer than the default isn't for speed. */
    fun scales(x11: X11Prefs) = x11.resolutionScales.filter { it >= x11.defaultResolutionScale }

    /**
     * Off the main thread, with an X server running: [driver] at the finest and coarsest of [scales], in the game's
     * [environment]. Empty when it couldn't draw; the log says why.
     */
    fun measure(context: Context, paths: Paths, driver: String, environment: Map<String, String>): List<Measured> {
        val x11 = X11Prefs(context)
        val sizes = scales(x11).let { listOf(it.first(), it.last()).distinct() }.map(x11::gameSize)
        paths.driverCheckLog.appendText("── speed test\n")
        val command = listOf(paths.speedTest.path) + sizes.map { (width, height) -> "${width}x$height" }
        // No frame counter drawn into its frames, and no waiting for the display.
        val program = Program.start(paths, command, paths.driverCheckLog,
                                    environment - "GALLIUM_HUD" + ("vblank_mode" to "0"),
                                    Drivers.libraryPath(paths, driver), appendLog = true)
        val exit = program.waitFor(TIMEOUT_S) ?: run {
            program.kill()
            program.waitFor()
            paths.driverCheckLog.appendText("── still drawing after $TIMEOUT_S s: stopped\n")
            return emptyList()
        }
        if (exit.status != 0) paths.driverCheckLog.appendText("── exited with $exit\n")
        return parse(paths.driverCheckLog.readText())
    }

    private val LINE = Regex("""speedtest: (\d+)x(\d+) (\d+) frames in (\d+) ms""")

    /** The program's lines in [log]: the last run's, one per size. */
    internal fun parse(log: String): List<Measured> =
        LINE.findAll(log.substringAfterLast("── speed test")).map { line ->
            val (width, height, frames, ms) = line.destructured
            Measured(width.toInt(), height.toInt(), ms.toDouble() / frames.toInt())
        }.toList()

    fun toJson(measured: List<Measured>) = JSONArray(measured.map { it.toJson() })

    fun fromJson(json: JSONArray?): List<Measured> = (0 until (json?.length() ?: 0)).map {
        val m = json!!.getJSONObject(it)
        Measured(m.getInt("width"), m.getInt("height"), m.getDouble("ms"))
    }

    /** Through the two sizes measured; with one, all of its time per pixel. Null with none. */
    internal fun model(measured: List<Measured>): Model? {
        val fine = measured.maxByOrNull { it.pixels } ?: return null
        val coarse = measured.minBy { it.pixels }
        if (fine.pixels == coarse.pixels) return Model(0.0, fine.ms / (fine.pixels / 1e6))
        // A coarser frame that took longer (a hiccup) says nothing per pixel: all of it is fixed.
        val perMegapixel = ((fine.ms - coarse.ms) / ((fine.pixels - coarse.pixels) / 1e6)).coerceAtLeast(0.0)
        return Model((fine.ms - perMegapixel * fine.pixels / 1e6).coerceAtLeast(0.0), perMegapixel)
    }

    /** [sizes]: each scale's pixels, finest first. [target]: the frames per second the test must reach. */
    internal fun fit(model: Model, sizes: List<Pair<Int, Long>>, target: Double): Fit {
        val scale = sizes.firstOrNull { (_, pixels) -> model.fps(pixels) >= target }?.first
        return if (scale != null) Fit(scale, light = false) else Fit(sizes.last().first, light = true)
    }

    /** What [measured] suits on this phone at [frameRate]; null when nothing was measured. */
    fun fit(context: Context, measured: List<Measured>, frameRate: Int): Fit? {
        val model = model(measured) ?: return null
        val x11 = X11Prefs(context)
        val sizes = scales(x11).map { scale ->
            x11.gameSize(scale).let { (width, height) -> scale to width.toLong() * height }
        }
        return fit(model, sizes, target = frameRate * ENOUGH / GAME_SHARE)
    }

    /** "23.1 ms a frame at 1624x770, 9.0 at 812x385". */
    fun summary(measured: List<Measured>) = measured.mapIndexed { i, m ->
        "%.1f%s at %dx%d".format(java.util.Locale.ROOT, m.ms, if (i == 0) " ms a frame" else "", m.width, m.height)
    }.joinToString(", ")
}
