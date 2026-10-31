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

    /** The release whose What's new already showed on start: it shows once. */
    fun updateShown(context: Context) = prefs(context).getString("updateShown", "")!!
    fun setUpdateShown(context: Context, version: String) = prefs(context).edit().putString("updateShown", version).apply()

    /** Whether Pocket Foundry asks GitHub for a newer version when it opens. */
    fun checkForUpdates(context: Context) = prefs(context).getBoolean("checkForUpdates", true)
    fun setCheckForUpdates(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("checkForUpdates", enabled).apply()

    fun fpsCounter(context: Context) = prefs(context).getBoolean("fpsCounter", false)
    fun setFpsCounter(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fpsCounter", on).apply()

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
    /** Two below the default, for the CPU driver: our SDL scales the game down with them, so the view stays the same. */
    fun resolutionScales(context: Context): List<Int> {
        val default = defaultResolutionScale(context)
        return listOf(100, 125, 150).filter { it <= default - 15 } + default + default * 3 / 2 + default * 2
    }
    fun startingResolutionScale(context: Context) = defaultResolutionScale(context) * if (Drivers.cpu(context)) 2 else 1
    fun resolutionScale(context: Context) = x11(context).getInt("displayScale", startingResolutionScale(context))
    fun setResolutionScale(context: Context, scale: Int) =
        x11(context).edit().putString("displayResolutionMode", "scaled").putInt("displayScale", scale).apply()

    val BORDERS = listOf(0, 10, 20)
    fun border(context: Context) = prefs(context).getInt("border", 0)
    fun setBorder(context: Context, dp: Int) = prefs(context).edit().putInt("border", dp).apply()

    /** Factorio's graphics-quality, missing from its menus: "medium" halves the sprites, ~600 MB less memory. */
    fun spriteQuality(context: Context) = prefs(context).getString("spriteQuality", null) ?: if (Drivers.cpu(context)) "medium" else "high"
    fun setSpriteQuality(context: Context, quality: String) = prefs(context).edit().putString("spriteQuality", quality).apply()

    /** Factorio's extra effects off (smoke, clouds, decoratives, shadows, terrain effects…): fewer pixels to draw. */
    fun lightGraphics(context: Context) = prefs(context).getBoolean("lightGraphics", Drivers.cpu(context))
    fun setLightGraphics(context: Context, on: Boolean) = prefs(context).edit().putBoolean("lightGraphics", on).apply()
    /** Whether the game's config has them off, so turning Light off can put them back once. */
    fun lightApplied(context: Context) = prefs(context).getBoolean("lightApplied", false)
    fun setLightApplied(context: Context, on: Boolean) = prefs(context).edit().putBoolean("lightApplied", on).apply()

    /** Without a GPU driver for the phone, the CPU's. */
    fun driver(context: Context) = prefs(context).getString("driver", null) ?: if (Gpu.supported) Drivers.ZINK else Drivers.CPU
    fun setDriver(context: Context, id: String) = prefs(context).edit().putString("driver", id).apply()
    fun clearDriver(context: Context) = prefs(context).edit().remove("driver").apply()

    /** Moving to or from the CPU driver: the sprites, effects and resolution that suit it, or the GPU's. */
    fun applyDriverDefaults(context: Context) {
        val cpu = Drivers.cpu(context)
        setSpriteQuality(context, if (cpu) "medium" else "high")
        setLightGraphics(context, cpu)
        setResolutionScale(context, startingResolutionScale(context))
    }

    fun driverOptions(context: Context) = prefs(context).getString("driverOptions", "")!!
    fun setDriverOptions(context: Context, options: String) = prefs(context).edit().putString("driverOptions", options).apply()

    fun requestLayoutEditor(context: Context) = prefs(context).edit().putBoolean("editLayout", true).apply()
    fun takeLayoutEditorRequest(context: Context): Boolean {
        if (!prefs(context).getBoolean("editLayout", false)) return false
        prefs(context).edit().putBoolean("editLayout", false).apply()
        return true
    }
}
