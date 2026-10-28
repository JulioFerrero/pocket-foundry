package app.pocketfoundry

import android.content.Intent
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieApp
import com.termux.x11.MainActivity
import app.pocketfoundry.controller.OnScreenController

/** Termux:X11's activity and receivers expect their Application class. */
class PocketFoundryApp : LorieApp() {
    override fun onCreate() {
        super.onCreate()
        OnScreenController.install(this)
        DebugPad.registerIfDebuggable(this)
    }

    // Keeps Termux:X11 from posting its own notification: GameService shows ours.
    override fun onActivityResumed(activity: MainActivity) {}

    override fun onBroadcastReceive(intent: Intent) {
        if (intent.action == CmdEntryPoint.ACTION_START) ScreenLog.announcement()
        super.onBroadcastReceive(intent)
    }
}
