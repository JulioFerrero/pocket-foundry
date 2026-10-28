package app.pocketfoundry

import android.content.Context

/** The player's choices on the Settings screen, each read where it applies. */
object Settings {
    const val PREFS = "pocketfoundry"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun x11(context: Context) = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    /** Freeze the game while it's off screen. */
    fun pauseInBackground(context: Context) = prefs(context).getBoolean("pauseInBackground", true)
    fun setPauseInBackground(context: Context, on: Boolean) = prefs(context).edit().putBoolean("pauseInBackground", on).apply()

    /** Button haptics and the game's rumble. */
    fun vibration(context: Context) = prefs(context).getBoolean("vibration", true)
    fun setVibration(context: Context, on: Boolean) = prefs(context).edit().putBoolean("vibration", on).apply()

    /**
     * Frames per second while playing: 60, one per game tick, or 30 for less heat and battery. The game
     * keeps its 60 updates either way; our SDL paces the frames (runtime/sdl), so it applies at once.
     */
    fun frameRate(context: Context) = prefs(context).getInt("frameRate", 60)
    fun setFrameRate(context: Context, fps: Int) {
        prefs(context).edit().putInt("frameRate", fps).apply()
        writeFrameRate(context)
    }
    fun writeFrameRate(context: Context) {
        val file = Paths(context).frameRate
        if (file.parentFile?.isDirectory == true) file.writeText("${frameRate(context)}\n")
    }

    /**
     * The resolution Factorio draws at, as Termux:X11's scale: the panel's size × 100 / scale.
     * 200, half the panel each way, is the default.
     */
    val RESOLUTION_SCALES = listOf(100, 125, 150, 200)
    const val DEFAULT_RESOLUTION_SCALE = 200
    fun resolutionScale(context: Context) = x11(context).getInt("displayScale", DEFAULT_RESOLUTION_SCALE)
    fun setResolutionScale(context: Context, scale: Int) =
        x11(context).edit().putString("displayResolutionMode", "scaled").putInt("displayScale", scale).apply()

    /** The frame round the game, in dp: off, or enough to clear the screen's rounded corners, or more. */
    val BORDERS = listOf(0, 10, 20)
    fun border(context: Context) = prefs(context).getInt("border", 0)
    fun setBorder(context: Context, dp: Int) = prefs(context).edit().putInt("border", dp).apply()

    /**
     * Factorio's graphics-quality, which its own menus don't show: "medium" halves the sprites,
     * ~600 MB less memory (the GPU's is the phone's), and only shows zoomed right in.
     */
    fun spriteQuality(context: Context) = prefs(context).getString("spriteQuality", "high")!!
    fun setSpriteQuality(context: Context, quality: String) = prefs(context).edit().putString("spriteQuality", quality).apply()

    /**
     * How the game is played: "gamepad" (Factorio in its controller mode, so its hints name the
     * on-screen Xbox buttons; touching the game moves its cursor and clicks in its menus) or "touch"
     * (Factorio in keyboard-and-mouse mode: the screen is the mouse, with a stick to walk and its keys).
     * Applies when the game next starts.
     */
    fun controls(context: Context) = prefs(context).getString("controls", CONTROLS[0])!!
    fun setControls(context: Context, controls: String) = prefs(context).edit().putString("controls", controls).apply()
    val CONTROLS = listOf("gamepad", "touch")

    /** The graphics driver's id (Drivers): applies when the game next starts. */
    fun driver(context: Context) = prefs(context).getString("driver", Drivers.ZINK)!!
    fun setDriver(context: Context, id: String) = prefs(context).edit().putString("driver", id).apply()

    /** Asked from Settings during a game: the controller opens its layout editor when the game is back. */
    fun requestLayoutEditor(context: Context) = prefs(context).edit().putBoolean("editLayout", true).apply()
    fun takeLayoutEditorRequest(context: Context): Boolean {
        if (!prefs(context).getBoolean("editLayout", false)) return false
        prefs(context).edit().putBoolean("editLayout", false).apply()
        return true
    }
}
