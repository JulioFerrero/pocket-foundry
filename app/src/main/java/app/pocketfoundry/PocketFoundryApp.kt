package app.pocketfoundry

import android.content.Intent
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.game.DebugPad
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.system.X11Prefs
import app.pocketfoundry.ui.screens.game.GameScreens
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieApp
import com.termux.x11.MainActivity

/** Termux:X11's activity and receivers expect their Application class. */
class PocketFoundryApp : LorieApp() {
    override fun onCreate() {
        super.onCreate()
        ScreenLog.install()
        X11Prefs(this).setUp()
        Drivers.setUp(this)
        GameScreens.install(this)
        DebugPad.registerIfDebuggable(this)
    }

    /** Android's memory warnings during a game: next to how the game ended, they say whether memory ended it. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (Session.inGame && level != TRIM_MEMORY_UI_HIDDEN) ScreenLog.add("Android asks to free memory (trim level $level)")
    }

    // Keeps Termux:X11 from posting its own notification: GameService shows ours.
    override fun onActivityResumed(activity: MainActivity) {}

    override fun onBroadcastReceive(intent: Intent) {
        if (intent.action == CmdEntryPoint.ACTION_START) ScreenLog.announcement()
        super.onBroadcastReceive(intent)
    }
}
