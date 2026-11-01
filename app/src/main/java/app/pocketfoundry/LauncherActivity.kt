package app.pocketfoundry

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.termux.x11.MainActivity
import app.pocketfoundry.drivers.Drivers
import app.pocketfoundry.game.GameImport
import app.pocketfoundry.game.Runtime
import app.pocketfoundry.game.Session
import app.pocketfoundry.launcher.HomeScreen
import app.pocketfoundry.launcher.ImportScreen
import app.pocketfoundry.launcher.SettingsScreen
import app.pocketfoundry.launcher.WelcomeScreen
import app.pocketfoundry.stage.Stage
import app.pocketfoundry.ui.DialogHost
import app.pocketfoundry.ui.Dropdown
import app.pocketfoundry.ui.WRAP
import app.pocketfoundry.ui.add
import app.pocketfoundry.ui.background
import app.pocketfoundry.ui.edgeToEdge
import app.pocketfoundry.ui.immersive
import app.pocketfoundry.ui.padForCutout
import app.pocketfoundry.ui.shown
import app.pocketfoundry.updates.UpdateDialog
import app.pocketfoundry.updates.UpdateFlow
import app.pocketfoundry.updates.Updates
import java.io.File

/** Welcome, Importing, Home and Settings, and their dialogs, which call back into it. In a game, Home is the pause menu. */
class LauncherActivity : Activity() {
    val paths by lazy { Paths(this) }
    private val settings by lazy { Settings(this) }
    val host = DialogHost(this)
    val updates = UpdateFlow(this, onChanged = ::refreshUpdates, onFound = { if (!host.isOpen && !importingNow) showUpdate(it) })
    // Only while on screen: onStop frees its sprites for the game.
    var stage: Stage? = null
        private set
    private val stageHolder by lazy { FrameLayout(this) }

    private val welcome by lazy { WelcomeScreen(this) }
    private val importing by lazy { ImportScreen(this) }
    private val home by lazy { HomeScreen(this) }
    private val settingsScreen by lazy { SettingsScreen(this) }
    private var updateDialog: UpdateDialog? = null
    private var focusedFor: Pair<View, Boolean>? = null
    private var importingNow = false
    private var settingsOpen = false
    private var busy: String? = null
    private var error: String? = null
    private var afterAsking: (() -> Unit)? = null
    private var onPicked: ((List<Uri>) -> Unit)? = null
    private val sessionChanged: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        X11Prefs(this).setUp()
        Drivers.fillDefaults(this)
        updates.check()
        edgeToEdge()
        val overlay = FrameLayout(this).apply {
            padForCutout()
            add(welcome.view, 460, WRAP, Gravity.CENTER)
            add(importing.view, 560, WRAP, Gravity.CENTER)
            add(home.view)
            add(settingsScreen.view, start = 24, top = 16, end = 24, bottom = 16)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Stage.BACKGROUND)
            add(stageHolder)
            add(overlay)
        })
    }

    override fun onStart() {
        super.onStart()
        if (stage == null) stage = Stage(this, paths).also { stageHolder.add(it) }
    }

    override fun onStop() {
        stage?.let { stageHolder.removeView(it); it.release() }
        stage = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        stage?.resume()
        immersive()
        Session.addListener(sessionChanged)
        refresh()
        updates.resume()
    }

    override fun onPause() {
        Session.removeListener(sessionChanged)
        stage?.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            Dropdown.dismissOpen() -> {}
            host.isOpen -> host.close()
            settingsOpen -> showSettings(false)
            importingNow -> {}                       // an import can't be cancelled
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    fun refresh() {
        val version = GameImport.installedVersion(this)
        showScreen(version, Session.inGame)
        welcome.update(error)
        if (version != null) {
            home.update(version, busy, error)
            settingsScreen.update(busy == null)
        }
        refreshUpdates()
    }

    private fun showScreen(version: String?, inGame: Boolean) {
        val screen = when {
            importingNow -> importing.view
            version == null -> welcome.view
            settingsOpen -> settingsScreen.view
            else -> home.view
        }
        for (view in listOf(welcome.view, importing.view, home.view, settingsScreen.view)) view.shown = view === screen
        // Home and the pause menu are one view with different first buttons (Continue, Resume), so either change moves focus.
        if (screen to inGame != focusedFor) {
            if (screen !== focusedFor?.first) Dropdown.dismissOpen()
            focusedFor = screen to inGame
            // Touch mode shows no focus, so only a controller sees this.
            if (!host.isOpen) if (screen === welcome.view) welcome.focus() else screen.requestFocus()
        }
        when {
            importingNow -> stage?.show(Stage.Scene.IMPORT)
            version == null -> stage?.show(Stage.Scene.WELCOME)
            settingsOpen -> stage?.show(Stage.Scene.FACTORY, dim = 0.55f)
            inGame -> stage?.show(Stage.Scene.FACTORY, dim = 0.55f, saturate = 0.8f)
            else -> stage?.show(Stage.Scene.FACTORY, dim = 0.75f)
        }
    }

    fun showSettings(show: Boolean) {
        settingsOpen = show
        refresh()
    }

    /** Settings' Edit layout: the game with the editor open. Home is under it for when the game closes. */
    fun editLayout() {
        settings.editLayout = true
        settingsOpen = false
        showGame()
    }

    /** Only the update's views: a download changes them on every percent. */
    private fun refreshUpdates() {
        settingsScreen.showRelease(Session.inGame, busy == null)
        updateDialog?.update(Session.inGame, busy == null)
    }

    fun showUpdate(release: Updates.Release) {
        updateDialog = UpdateDialog(this, release).also { it.show { updateDialog = null } }
        refreshUpdates()
    }

    /** The system's file picker; [onPicked] gets the chosen files, and isn't called if none were. */
    fun pickFiles(mimeTypes: List<String> = emptyList(), multiple: Boolean = false, onPicked: (List<Uri>) -> Unit) {
        this.onPicked = onPicked
        pick(PICK_FILES, mimeTypes, multiple)
    }

    // Its own code, not a callback: Android may recreate the activity while the picker is open.
    fun pickGame() = pick(PICK_GAME)

    private fun pick(requestCode: Int, mimeTypes: List<String> = emptyList(), multiple: Boolean = false) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (mimeTypes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val callback = if (requestCode == PICK_GAME) ::importGame else onPicked.also { onPicked = null }
        val picked = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data?.data)
        if (resultCode == RESULT_OK && picked.isNotEmpty()) callback?.invoke(picked)
    }

    private fun importGame(picked: List<Uri>) {
        importingNow = true
        settingsOpen = false
        error = null
        importing.start(picked.first()) { failure ->
            importingNow = false
            error = failure
            refresh()
        }
        refresh()
    }

    fun showGame() = startActivity(Intent(this, MainActivity::class.java))

    fun play(save: File?) {
        if (askForNotifications(then = { play(save) })) return
        error = null
        busy = "Starting…"
        refresh()
        background("start", {
            if (!Runtime.isInstalled(this)) {
                runOnUiThread { busy = "Getting ready…"; refresh() }
                Runtime.install(this)
            }
            Session.start(this, if (save != null) listOf("--load-game", save.path) else emptyList())
        }) { result ->
            busy = null
            result.onFailure { error = it.message ?: "Factorio didn't start. See Logs." }
            refresh()
            if (result.isSuccess) showGame()
        }
    }

    /** Asks once: the session's notification carries Quit. True while asking; [then] runs on the answer. */
    private fun askForNotifications(then: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        if (settings.askedNotifications) return false
        settings.askedNotifications = true
        afterAsking = then
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFICATIONS)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_NOTIFICATIONS) afterAsking?.also { afterAsking = null }?.invoke()
    }

    private companion object {
        const val PICK_FILES = 1
        const val ASK_NOTIFICATIONS = 2
        const val PICK_GAME = 3
    }
}
