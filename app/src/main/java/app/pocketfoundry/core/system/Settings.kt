package app.pocketfoundry.core.system

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Termux:X11's prefs, with the resolution, are in X11Prefs. */
class Settings(context: Context) {
    private val prefs = prefs(context)

    var pauseInBackground by prefs.bool("pauseInBackground", true)
    var vibration by prefs.bool("vibration", true)
    /**
     * One of TOUCHES. DIRECT: the game's cursor goes under the finger. TRACKPAD: a finger anywhere moves it like a
     * laptop's trackpad. BOX: a trackpad box where the right stick was, direct touch elsewhere.
     */
    var touch by prefs.string("touch", BOX)
    /** How far the trackpad moves the cursor for a finger's movement, in percent: one of TRACKPAD_SPEEDS. */
    var trackpadSpeed by prefs.int("trackpadSpeed", 150)
    var fpsCounter by prefs.bool("fpsCounter", false)
    /**
     * Whether the driver list and its drivers come from pocket-foundry-drivers: outside the app, so outside F-Droid's
     * checks. ASK until the player answers; one of ASK, ON and OFF (Drivers.downloads).
     */
    var driverDownloads by prefs.string("driverDownloads", ASK)
    /** The saves of Pocket Foundry before 0.9, another app, came over (OldApp). */
    var oldSavesBrought by prefs.bool("oldSavesBrought", false)
    /**
     * Every layer between the game and the screen logs what happens to the frames, and the game's memory is watched.
     */
    var detailedLogs by prefs.bool("detailedLogs", false)
    /** In dp, around the game screen. */
    var border by prefs.int("border", 0)
    /** Factorio's graphics-quality, missing from its menus: "medium" halves the sprites, ~600 MB less memory. */
    var spriteQuality by prefs.string(SPRITE_QUALITY, HIGH)
    /**
     * Factorio keeps the sprite atlases it builds on storage (Paths.atlasCache), so the next start reads them instead
     * of building them again: on the vivo X200 Ultra 3 s instead of 20, with a third less memory.
     */
    var fastStart by prefs.bool("fastStart", true)
    /** The catalog's drivers marked testing, in Settings -> Driver: a long press on its title turns them on and off. */
    var testDrivers by prefs.bool("testDrivers", false)
    /** Factorio's extra effects off (smoke, clouds, decoratives, shadows, terrain effects…): fewer pixels to draw. */
    var lightGraphics by prefs.bool(LIGHT_GRAPHICS, false)
    /** The player set the resolution, sprites or graphics: a driver's speed test leaves them as they are. */
    var graphicsChosen by prefs.bool(GRAPHICS_CHOSEN, false)
    /** The driver whose speed test last set them: Drivers.useSpeedTest sets them again only for another. */
    var graphicsDriver by prefs.string("graphicsDriver", "")
    /** Android is asked once for notifications, which carry Quit. */
    var askedNotifications by prefs.bool("askedNotifications", false)

    /** One of [FRAME_RATES]: set through Runtime.setFrameRate, which tells a running game. */
    var frameRate by prefs.int("frameRate", 60)

    fun setGraphicsDefaults(spriteQuality: String, lightGraphics: Boolean, onlyUnset: Boolean) {
        val edit = prefs.edit()
        // Installs from before graphicsChosen: what they have may be the player's.
        if (!prefs.contains(GRAPHICS_CHOSEN)) edit.putBoolean(GRAPHICS_CHOSEN, prefs.contains(SPRITE_QUALITY))
        if (!onlyUnset) edit.putBoolean(GRAPHICS_CHOSEN, false)
        if (!onlyUnset || !prefs.contains(SPRITE_QUALITY)) edit.putString(SPRITE_QUALITY, spriteQuality)
        if (!onlyUnset || !prefs.contains(LIGHT_GRAPHICS)) edit.putBoolean(LIGHT_GRAPHICS, lightGraphics)
        edit.apply()
    }

    companion object {
        const val HIGH = "high"
        const val MEDIUM = "medium"
        val SPRITE_QUALITIES = listOf(HIGH, MEDIUM)
        val FRAME_RATES = listOf(30, 60)
        val TRACKPAD_SPEEDS = listOf(100, 150, 200, 300)
        const val ASK = "ask"
        const val ON = "on"
        const val OFF = "off"
        const val DIRECT = "direct"
        const val TRACKPAD = "trackpad"
        const val BOX = "box"
        val TOUCHES = listOf(DIRECT, TRACKPAD, BOX)

        /** The app's own prefs file, which other features keep their own keys in too (DriverRecords). */
        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("pocketfoundry", Context.MODE_PRIVATE)

        private const val SPRITE_QUALITY = "spriteQuality"
        private const val LIGHT_GRAPHICS = "lightGraphics"
        private const val GRAPHICS_CHOSEN = "graphicsChosen"
    }
}

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
