package app.pocketfoundry.saves

import android.net.Uri
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.ui.Arrow
import app.pocketfoundry.ui.Factorio
import app.pocketfoundry.ui.Frame
import app.pocketfoundry.ui.Tone
import app.pocketfoundry.ui.add
import app.pocketfoundry.ui.background
import app.pocketfoundry.ui.button
import app.pocketfoundry.ui.label
import app.pocketfoundry.ui.stack

/** How many saves there are, Export to Downloads, and Import from wherever the file picker reaches. */
class SavesDialog(private val launcher: LauncherActivity) {
    private val host = launcher.host
    private val status = label(launcher, "", 16f, weight = 600)
    private val detail = label(launcher, "Export puts a copy in Download/Pocket Foundry.", 14f, Factorio.MUTED)
    private val importButton = button(launcher, "Import") { launcher.pickFiles(SAVE_TYPES, multiple = true) { if (host.isOpen) import(it) } }
    private val exportButton = button(launcher, "Export", Tone.GREEN, Arrow.RIGHT) { export() }

    fun show() {
        host.show("Saves", 440, "Close", importButton, exportButton) {
            add(stack(launcher, Frame.SHALLOW, padding = 12, gap = 6) {
                add(status)
                add(detail)
            })
        }
        showCount()
    }

    private fun showCount() {
        val count = Saves.all(launcher.paths).size
        status.text = when (count) { 0 -> "No saves yet"; 1 -> "1 save"; else -> "$count saves" }
        exportButton.isEnabled = count > 0
    }

    /** [work] gives the status and the detail to show. */
    private fun job(work: () -> Pair<String, String>) {
        importButton.isEnabled = false
        exportButton.isEnabled = false
        background("saves", work) { result ->
            val (status, detail) = result.getOrElse { "That didn't work" to (it.message ?: "See Logs.") }
            importButton.isEnabled = true
            showCount()
            this.status.text = status
            this.detail.text = detail
            launcher.refresh()
        }
    }

    private fun export() = job {
        val count = Saves.all(launcher.paths).size
        val folder = Saves.export(launcher)
        (if (count == 1) "Exported 1 save" else "Exported $count saves") to folder
    }

    private fun import(uris: List<Uri>) = job {
        when (val added = Saves.import(launcher, uris)) {
            0 -> "Nothing new" to "Those are already here, or aren't Factorio saves."
            1 -> "Added 1 save" to "It's in Load game."
            else -> "Added $added saves" to "They're in Load game."
        }
    }

    private companion object {
        val SAVE_TYPES = listOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
    }
}
