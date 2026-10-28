package app.pocketfoundry

import android.content.Context
import kotlin.math.roundToInt

object Settings {
    const val PREFS = "pocketfoundry"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun x11(context: Context) = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    fun pauseInBackground(context: Context) = prefs(context).getBoolean("pauseInBackground", true)
    fun setPauseInBackground(context: Context, on: Boolean) = prefs(context).edit().putBoolean("pauseInBackground", on).apply()

    fun vibration(context: Context) = prefs(context).getBoolean("vibration", true)
    fun setVibration(context: Context, on: Boolean) = prefs(context).edit().putBoolean("vibration", on).apply()

    /** 60 or 30 fps; our SDL (runtime/sdl) paces the frames, so a change applies at once. */
    fun frameRate(context: Context) = prefs(context).getInt("frameRate", 60)
    fun setFrameRate(context: Context, fps: Int) {
        prefs(context).edit().putInt("frameRate", fps).apply()
        writeFrameRate(context)
    }
    fun writeFrameRate(context: Context) {
        val file = Paths(context).frameRate
        if (file.parentFile?.isDirectory == true) file.writeText("${frameRate(context)}\n")
    }

    /** Termux:X11's scale (game size = panel × 100 / scale) that gives 720 lines per 384 dp on any phone. */
    fun naturalResolutionScale(context: Context) = context.resources.displayMetrics.densityDpi / 3f
    fun defaultResolutionScale(context: Context) = naturalResolutionScale(context).roundToInt().let { if (it < 115) 100 else it }
    fun resolutionScales(context: Context): List<Int> {
        val default = defaultResolutionScale(context)
        return listOf(100, 125, 150).filter { it <= default - 15 } + default
    }
    fun resolutionScale(context: Context) = x11(context).getInt("displayScale", defaultResolutionScale(context))
    fun setResolutionScale(context: Context, scale: Int) =
        x11(context).edit().putString("displayResolutionMode", "scaled").putInt("displayScale", scale).apply()

    val BORDERS = listOf(0, 10, 20)
    fun border(context: Context) = prefs(context).getInt("border", 0)
    fun setBorder(context: Context, dp: Int) = prefs(context).edit().putInt("border", dp).apply()

    /** Factorio's graphics-quality, missing from its menus: "medium" halves the sprites, ~600 MB less memory. */
    fun spriteQuality(context: Context) = prefs(context).getString("spriteQuality", "high")!!
    fun setSpriteQuality(context: Context, quality: String) = prefs(context).edit().putString("spriteQuality", quality).apply()

    /** "gamepad" plays Factorio in its controller mode, "touch" in keyboard-and-mouse mode. */
    fun controls(context: Context) = prefs(context).getString("controls", CONTROLS[0])!!
    fun setControls(context: Context, controls: String) = prefs(context).edit().putString("controls", controls).apply()
    val CONTROLS = listOf("gamepad", "touch")

    fun driver(context: Context) = prefs(context).getString("driver", Drivers.ZINK)!!
    fun setDriver(context: Context, id: String) = prefs(context).edit().putString("driver", id).apply()

    fun requestLayoutEditor(context: Context) = prefs(context).edit().putBoolean("editLayout", true).apply()
    fun takeLayoutEditorRequest(context: Context): Boolean {
        if (!prefs(context).getBoolean("editLayout", false)) return false
        prefs(context).edit().putBoolean("editLayout", false).apply()
        return true
    }
}
