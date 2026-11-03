package app.pocketfoundry

import android.content.Intent
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieApp
import com.termux.x11.MainActivity
import app.pocketfoundry.game.DebugPad
import app.pocketfoundry.game.GameScreens
import app.pocketfoundry.game.ScreenLog
import app.pocketfoundry.game.Session

/** Termux:X11's activity and receivers expect their Application class. */
class PocketFoundryApp : LorieApp() {
    override fun onCreate() {
        super.onCreate()
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
