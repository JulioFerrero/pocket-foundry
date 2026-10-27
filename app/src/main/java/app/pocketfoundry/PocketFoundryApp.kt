package app.pocketfoundry

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

    // Termux:X11 posts a notification of its own while its screen is up; Pocket Foundry's GameService
    // already shows one, with Quit.
    override fun onActivityResumed(activity: MainActivity) {}
}
