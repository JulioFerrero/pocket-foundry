package app.pocketfoundry.ui.screens.launcher

import android.view.View
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.ui.kit.Progress
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.panel

/** What the app does before Home (the driver list, the driver, updates), one step at a time along a bar. */
class StartScreen(launcher: LauncherActivity) {
    private val progress = Progress(launcher)

    val view: View = panel(launcher, "Getting ready") { add(progress.view) }

    /** [done]: of everything, 0 to 1. */
    fun show(text: String, done: Float) = progress.show(text, done)
}
