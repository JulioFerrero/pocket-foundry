package app.pocketfoundry.core.system

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/** Termux:X11's own prefs, which its X server and game screen read. */
class X11Prefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    /** The screen's own: our activities draw at a lower density (UI_SCALE). */
    private val dpi get() = context.applicationContext.resources.displayMetrics.densityDpi

    val defaultResolutionScale get() = ResolutionScale.default(dpi)
    val resolutionScales get() = ResolutionScale.options(defaultResolutionScale)
    fun startingResolutionScale(cpu: Boolean) = defaultResolutionScale.let { if (cpu) ResolutionScale.cpu(it) else it }

    var resolutionScale: Int
        get() = if (prefs.contains(SCALE)) prefs.getInt(SCALE, 0) else defaultResolutionScale
        set(scale) = prefs.edit().putString("displayResolutionMode", "scaled").putInt(SCALE, scale).apply()

    val uiScale get() = ResolutionScale.uiScale(dpi, resolutionScale)

    /**
     * The game's screen at [scale], landscape: the screen's pixels as Android runs it now × 100 / scale. Not the
     * panel's: a Galaxy S10 set to FHD+ runs its 3040 × 1440 panel at 2280 × 1080.
     */
    fun gameSize(scale: Int): Pair<Int, Int> {
        val size = Point()
        @Suppress("DEPRECATION")   // its replacement, WindowManager's metrics, needs Android 11
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
        return maxOf(size.x, size.y) * 100 / scale to minOf(size.x, size.y) * 100 / scale
    }

    val hasResolutionScale get() = prefs.contains(SCALE)

    fun setUp() {
        // A saved scale that isn't offered any more (the display size changed, or the list did) becomes the
        // nearest one.
        val scales = resolutionScales
        val saved = resolutionScale
        if (saved !in scales) resolutionScale = ResolutionScale.nearest(scales, saved)
        // Own flag so existing installs get it too. A hardware keyboard's keys go to the game before the keyboard app
        // sees them (scancodes workaround off), so it can't turn them into text and the game sees them held (WASD).
        // Char-based input stays off: it hides the game from keyboard apps as a text field, and Chinese or Japanese
        // ones then can't compose.
        if (!prefs.getBoolean("pocketFoundryKeys", false)) {
            prefs.edit().putBoolean("hardwareKbdScancodesWorkaround", false).putBoolean("enforceCharBasedInput", false)
                .putBoolean("pocketFoundryKeys", true).remove("pocketFoundryCharInput").apply()
        }
        // Written once so later changes stick. touchMode 3 is Direct touch: taps click where you touch.
        if (!prefs.getBoolean("pocketFoundryDefaults", false)) {
            prefs.edit()
                .putString("forceOrientation", "landscape")
                .putBoolean("fullscreen", true)
                .putBoolean("hideCutout", true)
                .putBoolean("showAdditionalKbd", false)
                .putBoolean("additionalKbdVisible", false)
                .putString("touchMode", "3")
                .putString("screenIdleTimeout", "never")
                .putBoolean("pocketFoundryDefaults", true)
                .apply()
        }
    }

    private companion object {
        const val SCALE = "displayScale"
    }
}

/** Termux:X11's resolution scale: game size = panel × 100 / scale. */
object ResolutionScale {
    const val NATIVE = 100
    private const val MIN_ABOVE_NATIVE = 115
    private const val MIN_GAP = 15
    /** The lowest UI scale our SDL accepts (runtime/sdl/patches/0003). */
    internal const val MIN_UI_SCALE = 0.25f

    /** The scale that gives 720 lines per 384 dp on any phone. */
    fun natural(dpi: Int) = dpi / 3f

    fun default(dpi: Int) = natural(dpi).roundToInt().let { if (it < MIN_ABOVE_NATIVE) NATIVE else it }

    fun options(default: Int) =
        listOf(NATIVE, 125, 150).filter { it <= default - MIN_GAP } + default + default * 3 / 2 + cpu(default)

    /** The CPU driver draws a quarter of the pixels. */
    fun cpu(default: Int) = default * 2

    fun nearest(options: List<Int>, scale: Int) = options.minBy { abs(it - scale) }

    /**
     * Pixels per display point, which Factorio's UI scale counts. It follows the resolution, so a lower one keeps
     * the same view and UI size with fewer pixels.
     */
    fun uiScale(dpi: Int, scale: Int) = (natural(dpi) / scale).coerceAtLeast(MIN_UI_SCALE)
}
