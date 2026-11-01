package app.pocketfoundry.launcher

import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.Uris
import app.pocketfoundry.drivers.Gpu
import app.pocketfoundry.game.GameImport
import app.pocketfoundry.ui.Arrow
import app.pocketfoundry.ui.BUTTON_HEIGHT
import app.pocketfoundry.ui.BarView
import app.pocketfoundry.ui.Factorio
import app.pocketfoundry.ui.Frame
import app.pocketfoundry.ui.GuiSprites
import app.pocketfoundry.ui.Tone
import app.pocketfoundry.ui.WRAP
import app.pocketfoundry.ui.add
import app.pocketfoundry.ui.background
import app.pocketfoundry.ui.button
import app.pocketfoundry.ui.label
import app.pocketfoundry.ui.link
import app.pocketfoundry.ui.panel
import app.pocketfoundry.ui.row
import app.pocketfoundry.ui.shown
import app.pocketfoundry.ui.stack

/** Before the game is imported: what to import and where to get it. */
class WelcomeScreen(private val launcher: LauncherActivity) {
    private val error = label(launcher, "", 14f, Factorio.ERROR)
    private val importButton = button(launcher, "Import Factorio", Tone.GREEN, Arrow.RIGHT, onClick = launcher::pickGame)

    val view: View = panel(launcher, "Pocket Foundry") {
        add(stack(launcher, Frame.SHALLOW, padding = 12, gap = 6) {
            add(label(launcher, "Your own Factorio, running on this phone.", 17f, weight = 600))
            // Two labels on one baseline.
            add(row(launcher, Gravity.TOP) {
                add(label(launcher, "The Linux arm64 .tar.xz from ", 14f, Factorio.MUTED))
                add(link(launcher, "factorio.com", "https://factorio.com/download"))
            })
            if (!Gpu.supported) {
                add(label(launcher, "No GPU driver for this phone's ${Gpu.name} yet: the game runs on the CPU, slowly.", 14f, Factorio.MUTED))
            }
            add(error)
        })
        add(importButton, WRAP, BUTTON_HEIGHT, gravity = Gravity.END)
    }

    fun focus() = importButton.requestFocus()

    /** [error]: why the last import failed. */
    fun update(error: String?) {
        this.error.text = error
        this.error.shown = error != null
    }
}

/** The game's import: the file, how far it got, and the stage's blueprint filling in. */
class ImportScreen(private val launcher: LauncherActivity) {
    private val file = label(launcher, "", 14f, Factorio.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
    private val percent = label(launcher, "0%", 18f, Factorio.HEADING, 600)
    private val bar = BarView(launcher)

    val view: View = panel(launcher, "Importing Factorio") {
        add(stack(launcher, Frame.DEEP, padding = 12, gap = 10) {
            add(row(launcher) {
                add(file, width = 0, weight = 1f)
                add(percent, start = 16)
            })
            add(bar, height = 14)
        })
    }

    /** [onFinished] gets why it failed, or null once it's in. */
    fun start(uri: Uri, onFinished: (error: String?) -> Unit) {
        file.text = Uris.displayName(launcher, uri) ?: "Factorio"
        show(0)
        background("import", { GameImport.import(launcher, uri) { percent -> launcher.runOnUiThread { show(percent) } } }) { result ->
            val failure = result.exceptionOrNull()
            if (failure != null) return@background onFinished(failure.message ?: "The import failed. See Logs.")
            GuiSprites.reload()
            show(100)
            // A moment to watch the factory start.
            bar.postDelayed({ onFinished(null) }, 2500)
        }
    }

    private fun show(value: Int) {
        percent.text = "$value%"
        bar.fraction = value / 100f
        launcher.stage?.progress(value)
    }
}
