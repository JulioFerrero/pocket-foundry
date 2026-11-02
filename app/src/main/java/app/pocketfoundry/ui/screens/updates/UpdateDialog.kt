package app.pocketfoundry.ui.screens.updates

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.Log
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.system.*
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.updates.Updates
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.ui.*
import app.pocketfoundry.ui.kit.Arrow
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.MaxHeightScrollView
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.background
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.capped
import app.pocketfoundry.ui.kit.dpi
import app.pocketfoundry.ui.kit.heading
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.stack
import java.io.File

/** Release notes since this version, newest first, and an Update button. Shown on start once per release, and from Settings. */
class UpdateDialog(private val launcher: LauncherActivity, private val release: Updates.Release) {
    private val updateButton = button(launcher, "Update", Tone.GREEN, Arrow.RIGHT) { launcher.updates.start() }
    private val status = label(launcher, "", 14f, Factorio.ERROR).capped(2)

    fun show(onClose: () -> Unit) {
        Settings(launcher).updateShown = release.version
        // Room for the title, status and buttons, and some above and below the dialog.
        val tallest = launcher.resources.displayMetrics.heightPixels - launcher.dpi(190)
        launcher.host.show("Pocket Foundry ${release.version} is out", 560, "Later", updateButton, onClose = onClose) {
            add(MaxHeightScrollView(launcher, tallest).apply { addView(notes()) })
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

    fun update(inGame: Boolean, idle: Boolean) {
        val flow = launcher.updates
        updateButton.text = flow.progress ?: "Update"
        updateButton.isEnabled = flow.progress == null && !inGame && idle
        status.text = flow.error ?: if (inGame) "Quit the game first: updating closes Pocket Foundry." else ""
        status.shown = status.text.isNotEmpty()
    }
}

/**
 * The check for a newer release, its download and the hand-over to Android's installer. [onChanged] runs on every
 * step, and [onFound] when a check finds a release whose notes the player hasn't seen, while no game runs.
 */
class UpdateFlow(private val activity: Activity, private val onChanged: () -> Unit, private val onFound: (Updates.Release) -> Unit) {
    /** "Downloading 40%" or "Installing…" while an update is under way. */
    var progress: String? = null
        private set
    var error: String? = null
        private set
    private var downloaded: File? = null
    private var checking = false

    /**
     * Once per app launch unless off in Settings; turning it on there checks right away. Never in debug builds:
     * they're for testing on the phone, and a release would replace them.
     */
    fun check() {
        if (activity.isDebuggable || !activity.isStock || !Settings(activity).checkForUpdates || Updates.checked || checking) return
        checking = true
        background("update-check", { Updates.check(activity) }) { result ->
            result.onFailure { Log.i(TAG, "No update check: ${it.message}") }
            checking = false
            onChanged()
            result.getOrNull()?.takeIf { it.version != Settings(activity).updateShown && !Session.inGame }?.let(onFound)
        }
    }

    /** Downloads the newer release and hands it to Android's installer, which closes the app once it's in. */
    fun start() {
        val release = Updates.available ?: return
        show("Downloading…", null)
        background("update", {
            Updates.download(activity, release) { percent -> activity.runOnUiThread { show("Downloading $percent%", null) } }
        }) { result ->
            result.onSuccess { install(it) }.onFailure {
                Log.w(TAG, "Update failed", it)
                show(null, "The update didn't download: ${it.message}")
            }
        }
    }

    /** Back from allowing Pocket Foundry to install apps: the update it was waiting for. */
    fun resume() {
        downloaded?.takeIf { activity.packageManager.canRequestPackageInstalls() }?.let { install(it) }
    }

    /**
     * Without the install-apps permission, Android's page for it comes first and the install waits for onResume:
     * going there mid-install would cancel it.
     */
    private fun install(file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            downloaded = file
            show(null, "Allow Pocket Foundry to install apps, then come back: the update carries on.")
            activity.startActivity(Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        downloaded = null
        show("Installing…", null)
        background("install", { Updates.install(activity, file) { reason -> activity.runOnUiThread { show(null, reason) } } }) { result ->
            result.onFailure {
                Log.w(TAG, "Update failed", it)
                show(null, "Android didn't take the update: ${it.message}")
            }
        }
    }

    private fun show(progress: String?, error: String?) {
        this.progress = progress
        this.error = error
        onChanged()
    }
}
