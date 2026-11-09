package app.pocketfoundry.core.updates

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
import android.util.Log
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.background
import app.pocketfoundry.core.system.isDebuggable
import app.pocketfoundry.core.system.isStock
import java.io.File

/**
 * [onChanged] runs on every step, and [onFound] when a check finds a release whose notes the player hasn't seen, while
 * no game runs.
 */
class UpdateFlow(private val activity: Activity, private val onChanged: () -> Unit, private val onFound: (Updates.Release) -> Unit) {
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
            result.getOrNull()?.takeIf { it.version != Settings(activity).updateShown }?.let(onFound)
        }
    }

    /** Its notes show once: the next check passes over it. */
    fun shown(release: Updates.Release) {
        Settings(activity).updateShown = release.version
    }

    fun start(release: Updates.Release) {
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
