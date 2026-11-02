package app.pocketfoundry

import android.content.Context
import android.content.SharedPreferences
import app.pocketfoundry.drivers.Drivers
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** The app's own prefs. Termux:X11's, with the resolution, are in X11Prefs. */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("pocketfoundry", Context.MODE_PRIVATE)

    var pauseInBackground by prefs.bool("pauseInBackground", true)
    var vibration by prefs.bool("vibration", true)
    var checkForUpdates by prefs.bool("checkForUpdates", true)
    var fpsCounter by prefs.bool("fpsCounter", false)
    /** The release whose What's new already showed on start: it shows once. */
    var updateShown by prefs.string("updateShown", "")
    var border by prefs.int("border", 0)
    /** Factorio's graphics-quality, missing from its menus: "medium" halves the sprites, ~600 MB less memory. */
    var spriteQuality by prefs.string(SPRITE_QUALITY, "high")
    /** Factorio's extra effects off (smoke, clouds, decoratives, shadows, terrain effects…): fewer pixels to draw. */
    var lightGraphics by prefs.bool(LIGHT_GRAPHICS, false)
    /** Whether the game's config has them off, so turning Light off can put them back once. */
    var lightApplied by prefs.bool("lightApplied", false)
    /** The player's choice, or null to follow Drivers.recommended. */
    var driver by prefs.stringOrNull("driver")
    /** The old Options field, one for every driver: Drivers.moveOldOptions hands it to the driver in use. */
    var driverOptions by prefs.string("driverOptions", "")
    /** Settings -> Driver -> Options: each driver's `NAME=value` text by driver id, as JSON. */
    var driverOptionsById by prefs.string("driverOptionsById", "{}")
    /** How the last game ended with each driver: Drivers.Outcome names by driver id, as JSON. */
    var driverGames by prefs.string("driverGames", "{}")
    /** Android is asked once for notifications, which carry Quit. */
    var askedNotifications by prefs.bool("askedNotifications", false)
    /** Settings' Edit layout asks the game screen to open the editor. */
    var editLayout by prefs.bool("editLayout", false)

    /** 60 or 30 fps. Our SDL rereads it every second (runtime/sdl), so a change applies in game. */
    var frameRate: Int
        get() = prefs.getInt("frameRate", 60)
        set(fps) {
            prefs.edit().putInt("frameRate", fps).apply()
            writeFrameRate()
        }

    fun writeFrameRate() {
        val file = Paths(context).frameRate
        if (file.parentFile?.isDirectory == true) file.writeText("$frameRate\n")
    }

    fun takeLayoutEditorRequest() = editLayout.also { if (it) editLayout = false }

    /** The sprites and effects that suit the CPU driver, or a GPU's. [onlyUnset] keeps what the player chose. */
    fun setDriverDefaults(cpu: Boolean, onlyUnset: Boolean = false) {
        val edit = prefs.edit()
        if (!onlyUnset || !prefs.contains(SPRITE_QUALITY)) edit.putString(SPRITE_QUALITY, if (cpu) "medium" else "high")
        if (!onlyUnset || !prefs.contains(LIGHT_GRAPHICS)) edit.putBoolean(LIGHT_GRAPHICS, cpu)
        edit.apply()
    }

    companion object {
        val BORDERS = listOf(0, 10, 20)
        val SPRITE_QUALITIES = listOf("high", "medium")
        private const val SPRITE_QUALITY = "spriteQuality"
        private const val LIGHT_GRAPHICS = "lightGraphics"
    }
}

/** Termux:X11's own prefs, which its X server and game screen read. */
class X11Prefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    private val dpi get() = context.resources.displayMetrics.densityDpi

    val defaultResolutionScale get() = ResolutionScale.default(dpi)
    val resolutionScales get() = ResolutionScale.options(defaultResolutionScale)
    val startingResolutionScale get() =
        defaultResolutionScale.let { if (Drivers.cpu(context)) ResolutionScale.cpu(it) else it }

    var resolutionScale: Int
        get() = if (prefs.contains(SCALE)) prefs.getInt(SCALE, 0) else startingResolutionScale
        set(scale) = prefs.edit().putString("displayResolutionMode", "scaled").putInt(SCALE, scale).apply()

    val uiScale get() = ResolutionScale.uiScale(dpi, resolutionScale)

    /** On every launch. */
    fun setUp() {
        // A saved scale that isn't offered any more (the display size changed, or the list did) becomes the nearest one.
        val scales = resolutionScales
        val saved = resolutionScale
        if (saved !in scales) resolutionScale = ResolutionScale.nearest(scales, saved)
        // Own flag so existing installs get it too. Without it the keyboard app turns a hardware keyboard's keys into
        // text, so the game never sees one held (WASD). The on-screen keyboard still types.
        if (!prefs.getBoolean("pocketFoundryCharInput", false)) {
            prefs.edit().putBoolean("enforceCharBasedInput", true).putBoolean("pocketFoundryCharInput", true).apply()
        }
        // Written once so later changes stick. touchMode 3 is Direct touch: taps click where you touch.
        if (!prefs.getBoolean("pocketFoundryDefaults", false)) {
            resolutionScale = startingResolutionScale
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
    /** The panel's own resolution. */
    const val NATIVE = 100
    /** A default closer to Native than this is Native. */
    private const val MIN_ABOVE_NATIVE = 115
    /** How far under the default Native, 125 and 150 must be to be offered. */
    private const val MIN_GAP = 15
    /** The lowest UI scale our SDL accepts (runtime/sdl/patches/0003). */
    private const val MIN_UI_SCALE = 0.25f

    /** The scale that gives 720 lines per 384 dp on any phone. */
    fun natural(dpi: Int) = dpi / 3f

    fun default(dpi: Int) = natural(dpi).roundToInt().let { if (it < MIN_ABOVE_NATIVE) NATIVE else it }

    /** Native, 125 and 150 when far enough under the default, the default, then 1.5x and 2x for the CPU driver. */
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

// Property delegates, each for one key in the prefs.

private fun SharedPreferences.bool(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
    override fun getValue(thisRef: Any?, property: KProperty<*>) = getBoolean(key, default)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = edit().putBoolean(key, value).apply()
}

private fun SharedPreferences.int(key: String, default: Int) = object : ReadWriteProperty<Any?, Int> {
    override fun getValue(thisRef: Any?, property: KProperty<*>) = getInt(key, default)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = edit().putInt(key, value).apply()
}

private fun SharedPreferences.string(key: String, default: String) = object : ReadWriteProperty<Any?, String> {
    override fun getValue(thisRef: Any?, property: KProperty<*>) = getString(key, default)!!
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = edit().putString(key, value).apply()
}

/** Null removes the key. */
private fun SharedPreferences.stringOrNull(key: String) = object : ReadWriteProperty<Any?, String?> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): String? = getString(key, null)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) = edit().putString(key, value).apply()
}
