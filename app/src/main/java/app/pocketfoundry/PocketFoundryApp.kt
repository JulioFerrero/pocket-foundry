package app.pocketfoundry

import android.content.Intent
import app.pocketfoundry.core.game.DebugPad
import app.pocketfoundry.ui.screens.game.GameScreens
import app.pocketfoundry.core.game.ScreenLog
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieApp
import com.termux.x11.MainActivity

/** Termux:X11's activity and receivers expect their Application class. */
class PocketFoundryApp : LorieApp() {
    override fun onCreate() {
        super.onCreate()
        GameScreens.install(this)
        DebugPad.registerIfDebuggable(this)
    }

    // Keeps Termux:X11 from posting its own notification: GameService shows ours.
    override fun onActivityResumed(activity: MainActivity) {}

    override fun onBroadcastReceive(intent: Intent) {
        if (intent.action == CmdEntryPoint.ACTION_START) ScreenLog.announcement()
        super.onBroadcastReceive(intent)
    }
}
