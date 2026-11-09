package app.pocketfoundry.ui.screens.updates

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.widget.ScrollView
import app.pocketfoundry.core.updates.Updates
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.ui.kit.Arrow
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.capped
import app.pocketfoundry.ui.kit.dpi
import app.pocketfoundry.ui.kit.heading
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.stack

/** Release notes since this version, newest first. Shown on start once per release, and from Settings. */
class UpdateDialog(private val launcher: LauncherActivity, private val release: Updates.Release) {
    private val updateButton = button(launcher, "Update", Tone.GREEN, Arrow.RIGHT) { launcher.updates.start(release) }
    private val status = label(launcher, "", 14f, Factorio.ERROR).capped(2)

    fun show() {
        launcher.updates.shown(release)
        launcher.host.show("Pocket Foundry ${release.version} is out", 560, "Later", updateButton) {
            add(ScrollView(launcher).apply { addView(notes()) }, weight = 1f)
            add(status)
        }
    }

    private fun notes() = stack(launcher, Frame.SHALLOW, padding = 10, gap = 6) {
        for ((version, changes) in release.changes) {
            add(heading(launcher, version, 17f))
            for (change in changes.ifEmpty { listOf("Details on GitHub.") }) {
                // Wrapped lines start under the text, not under the bullet.
                val text = SpannableStringBuilder("•  $change").apply {
                    setSpan(LeadingMarginSpan.Standard(0, launcher.dpi(14)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                add(label(launcher, text, 15f))
            }
        }
    }

    fun update(canUpdate: Boolean, inGame: Boolean) {
        val flow = launcher.updates
        updateButton.text = flow.progress ?: "Update"
        updateButton.isEnabled = canUpdate
        status.text = flow.error ?: if (inGame) "Quit the game first: updating closes Pocket Foundry." else ""
        status.shown = status.text.isNotEmpty()
    }
}
