package app.pocketfoundry.ui.screens.launcher

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import app.pocketfoundry.core.drivers.Gpu
import app.pocketfoundry.core.game.GameImport
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.Uris
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.ui.kit.Arrow
import app.pocketfoundry.ui.kit.BUTTON_HEIGHT
import app.pocketfoundry.ui.kit.BarView
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.GuiSprites
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.core.system.background
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.capped
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.link
import app.pocketfoundry.ui.kit.panel
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.spacer
import app.pocketfoundry.ui.kit.stack
import app.pocketfoundry.ui.screens.drivers.DriverDialog

/**
 * Before the game is imported: what to import and where to get it. Driver and Logs are here too, to try the phone's
 * drivers (Check) before there's a game.
 */
class WelcomeScreen(private val launcher: LauncherActivity) {
    private val error = label(launcher, "", 14f, Factorio.ERROR).capped(3)
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
                val note = if (Drivers.cpu(launcher)) "No GPU driver for this phone's ${Gpu.name} yet: the game runs on the CPU, slowly."
                           else "This phone's ${Gpu.name} runs the game through its own driver, still experimental."
                add(label(launcher, note, 14f, Factorio.MUTED))
            }
            add(error)
        })
        add(row(launcher) {
            add(button(launcher, "Driver", size = 15f) { DriverDialog(launcher).show() }, height = BUTTON_HEIGHT)
            add(button(launcher, "Logs", size = 15f) {
                launcher.startActivity(Intent(launcher, LogActivity::class.java))
            }, height = BUTTON_HEIGHT, start = 4)
            spacer()
            add(importButton, height = BUTTON_HEIGHT, start = 12)
        })
    }

    fun focus() = importButton.requestFocus()

    /** [error]: why the last import failed. */
    fun update(error: String?) {
        this.error.text = error
        this.error.shown = error != null
    }
}

/** The game's import: the file, how far it got, and the stage's blueprint filling in. */
class ImportScreen(private val launcher: LauncherActivity, private val onProgress: (Int) -> Unit) {
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

    fun start(uri: Uri, onFinished: (error: String?) -> Unit) {
        file.text = Uris.displayName(launcher, uri) ?: "Factorio"
        show(0)
        background("import", { GameImport.import(launcher, uri) { percent -> launcher.runOnUiThread { show(percent) } } }) { result ->
            val failure = result.exceptionOrNull()
            if (failure != null) {
                Log.w(TAG, "The import failed", failure)
                return@background onFinished(failure.message ?: "The import failed.")
            }
            GuiSprites.reload()
            show(100)
            // A moment to watch the factory start.
            bar.postDelayed({ onFinished(null) }, 2500)
        }
    }

    private fun show(value: Int) {
        percent.text = "$value%"
        bar.fraction = value / 100f
        onProgress(value)
    }
}
