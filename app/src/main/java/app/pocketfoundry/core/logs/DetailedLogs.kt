package app.pocketfoundry.core.logs

import android.content.Context
import app.pocketfoundry.core.system.Settings

/**
 * Settings' Detailed logs. The game and the X server get [environment]: our Mesa and Termux:X11 patches then log how
 * each frame gets to the screen (POCKETFOUNDRY_DETAILED_LOGS: runtime/mesa/patches 0004 and 0005,
 * third_party/patches/termux-x11 0011), the X server's renderer among them, and Mesa prints every GL error.
 */
object DetailedLogs {
    internal const val SWITCH = "POCKETFOUNDRY_DETAILED_LOGS"

    fun on(context: Context) = Settings(context).detailedLogs

    fun environment(context: Context) =
        if (on(context)) mapOf(SWITCH to "1", "MESA_DEBUG" to "1") else emptyMap()
}
