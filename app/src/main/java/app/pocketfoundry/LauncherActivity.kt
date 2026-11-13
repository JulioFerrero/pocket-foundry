package app.pocketfoundry

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.termux.x11.MainActivity
import app.pocketfoundry.core.drivers.Catalog
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.drivers.Gpu
import app.pocketfoundry.core.game.GameImport
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.logs.ScreenLog
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.saves.OldApp
import app.pocketfoundry.core.system.GITHUB
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.isStock
import app.pocketfoundry.ui.screens.game.GameScreens
import app.pocketfoundry.ui.screens.launcher.HomeScreen
import app.pocketfoundry.ui.screens.launcher.ImportScreen
import app.pocketfoundry.ui.screens.launcher.SettingsScreen
import app.pocketfoundry.ui.screens.launcher.StartScreen
import app.pocketfoundry.ui.screens.launcher.WelcomeScreen
import app.pocketfoundry.ui.stage.Stage
import app.pocketfoundry.ui.kit.Arrow
import app.pocketfoundry.ui.kit.DialogHost
import app.pocketfoundry.ui.kit.Dropdown
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.WRAP
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.stack
import app.pocketfoundry.core.system.background
import app.pocketfoundry.ui.kit.edgeToEdge
import app.pocketfoundry.ui.kit.immersive
import app.pocketfoundry.ui.kit.padForCutout
import app.pocketfoundry.ui.kit.scaleUi
import app.pocketfoundry.ui.kit.shown
import java.io.File

/** Its screens and their dialogs call back into it. In a game, Home is the pause menu. */
class LauncherActivity : Activity() {
    private val paths by lazy { Paths(this) }
    private val settings by lazy { Settings(this) }
    val host = DialogHost(this, onClosed = { refresh() })
    // Only while on screen: onStop frees its sprites for the game.
    private var stage: Stage? = null
    private val stageHolder by lazy { FrameLayout(this) }

    private val welcome by lazy { WelcomeScreen(this) }
    private val startScreen by lazy { StartScreen(this) }
    private val importing by lazy { ImportScreen(this) { stage?.progress(it) } }
    private val home by lazy { HomeScreen(this) }
    private val settingsScreen by lazy { SettingsScreen(this) }
    private var focusedFor: Pair<View, Boolean>? = null
    private var importingNow = false
    /** The start screen shows: the app's start, or a driver downloading before a game. */
    private var loading = false
    private var settingsOpen = false
    private var busy: String? = null
    private var error: String? = null
    private var afterAsking: (() -> Unit)? = null
    private var onPicked: ((List<Uri>) -> Unit)? = null
    private val sessionChanged: () -> Unit = { refresh() }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        scaleUi(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!started) startUp()
        edgeToEdge()
        val overlay = FrameLayout(this).apply {
            padForCutout()
            add(welcome.view, 460, WRAP, Gravity.CENTER)
            add(importing.view, 560, WRAP, Gravity.CENTER)
            add(startScreen.view, 560, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, bottom = 24)
            add(home.view)
            add(settingsScreen.view, start = 24, top = 16, end = 24, bottom = 16)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Stage.BACKGROUND)
            add(stageHolder)
            add(overlay)
        })
        if (!checked) checkBuild()
    }

    private fun checkBuild() {
        checked = true
        if (isStock) return
        val github = button(this, "GitHub", Tone.GREEN, Arrow.RIGHT) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) }
        host.show("Modified copy", 460, "Continue", github) {
            add(stack(this@LauncherActivity, Frame.SHALLOW, padding = 12, gap = 6) {
                add(label(this@LauncherActivity, "This isn't the official Pocket Foundry", 16f, weight = 600))
                add(label(this@LauncherActivity, "Someone changed it, and their code runs with your saves and factorio.com " +
                          "login. The real one is free on GitHub.", 14f, Factorio.MUTED))
            })
        }
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
            importingNow || loading -> {}            // an import or a download can't be cancelled
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
    }

    private fun showScreen(version: String?, inGame: Boolean) {
        val screen = when {
            loading -> startScreen.view
            importingNow -> importing.view
            version == null -> welcome.view
            settingsOpen -> settingsScreen.view
            else -> home.view
        }
        for (view in listOf(startScreen.view, welcome.view, importing.view, home.view, settingsScreen.view)) {
            view.shown = view === screen
        }
        // Home and the pause menu are one view with different first buttons (Continue, Resume), so either change
        // moves focus.
        if (screen to inGame != focusedFor) {
            if (screen !== focusedFor?.first) Dropdown.dismissOpen()
            focusedFor = screen to inGame
            // Touch mode shows no focus, so only a controller sees this.
            if (!host.isOpen) if (screen === welcome.view) welcome.focus() else screen.requestFocus()
        }
        when {
            loading && version == null -> stage?.show(Stage.Scene.WELCOME)
            loading -> stage?.show(Stage.Scene.FACTORY, dim = 0.75f)
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
        GameScreens.openEditor()
        settingsOpen = false
        showGame()
    }

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
        if (requestCode == PICK_OLD_APP) {
            if (resultCode == RESULT_OK) data?.data?.let(::bringOldSaves)
            return
        }
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
            if (failure == null && settings.driverDownloads == Settings.ASK) askDriverDownloads()
        }
        refresh()
    }

    fun showGame() = startActivity(Intent(this, MainActivity::class.java))

    /** Once per launch: the drivers ready (fetchDrivers), then a question still open, one at a time. */
    private fun startUp() {
        started = true
        fetchDrivers { if (!Session.inGame && !host.isOpen) nextQuestion() }
    }

    /**
     * On the start screen: with downloads on, the newest driver list, then the driver for this phone, downloaded and
     * checked where it hasn't been, or the next in line where it can't play (Session.setUpDriver). Offline, the game's
     * start tries again.
     */
    private fun fetchDrivers(then: () -> Unit) {
        loading = true
        startScreen.show(if (Drivers.downloads(this)) "Fetching the driver list" else finding(), 0f)
        refresh()
        background("drivers", {
            Drivers.refreshCatalog(this)
            step(finding(), 0.1f)
            runCatching { if (!Session.inGame) Session.setUpDriver(this, ::step) }
        }) { result ->
            loading = false
            result.getOrThrow().onFailure { error = it.message }
            refresh()
            then()
        }
    }

    private fun finding() = "Finding the driver for your ${Gpu.name ?: "GPU"}"

    /** Driver downloads once the game is here, else the old app's saves. */
    private fun nextQuestion() {
        if (GameImport.installedVersion(this) != null && settings.driverDownloads == Settings.ASK) askDriverDownloads()
        else offerOldSaves()
    }

    /**
     * Before any driver download, what F-Droid asks of code from elsewhere: they come from pocket-foundry-drivers,
     * outside its checks, and saying no is as easy as yes. Settings changes the answer.
     */
    private fun askDriverDownloads() {
        val download = button(this, "Download", Tone.GREEN, Arrow.RIGHT) {
            host.close()
            turnOnDriverDownloads()
        }
        host.show("More drivers", 460, "Not now", download, onBack = {
            settings.driverDownloads = Settings.OFF
            refresh()
        }) {
            add(stack(this@LauncherActivity, Frame.SHALLOW, padding = 12, gap = 6) {
                add(label(this@LauncherActivity, "Connect to the internet to download more drivers?", 16f,
                    weight = 600))
                add(label(this@LauncherActivity, "From pocket-foundry-drivers on GitHub. They skip F-Droid's checks.",
                    14f, Factorio.MUTED))
            })
        }
    }

    /** From the question, Settings or the Driver screen: the driver list and this GPU's driver, now. */
    fun turnOnDriverDownloads(then: () -> Unit = {}) {
        settings.driverDownloads = Settings.ON
        fetchDrivers(then)
    }

    /**
     * 0.9 is a new app to Android (OldApp): once per launch until it's done, the saves, mods and config of the one
     * before. 0.8.18 hands its folder over; an older one is picked in Android's folder picker, where both apps'
     * folders are called Pocket Foundry.
     */
    private fun offerOldSaves() {
        if (settings.oldSavesBrought || !OldApp.installed(this)) return
        val sends = OldApp.sends(this)
        val bring = button(this, "Bring them", Tone.GREEN, Arrow.RIGHT) {
            host.close()
            val ask = if (sends) OldApp.sendSaves else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, OldApp.folder)
            @Suppress("DEPRECATION")
            startActivityForResult(ask, PICK_OLD_APP)
        }
        host.show("Your saves from before 0.9", 460, "Not now", bring) {
            add(stack(this@LauncherActivity, Frame.SHALLOW, padding = 12, gap = 6) {
                add(label(this@LauncherActivity, "Bring the old Pocket Foundry's saves, mods and config here?", 16f,
                    weight = 600))
                add(label(this@LauncherActivity,
                    if (sends) "Factorio needs importing once more." else "In Android's menu, pick Pocket Foundry, " +
                        "then Use this folder and Allow. Updating the old app to 0.8.18 first skips this.",
                    14f, Factorio.MUTED))
            })
        }
    }

    private fun bringOldSaves(tree: Uri) {
        if (!OldApp.isOldAppsFolder(tree)) {
            error = "That folder isn't the old Pocket Foundry's: try the other Pocket Foundry in the menu."
            refresh()
            return
        }
        loading = true
        startScreen.show("Bringing your saves", 0f)
        refresh()
        val bring = { OldApp.bring(this, tree, paths.gameData) { step("Bringing your saves", it / 100f) } }
        background("old-saves", bring) {
            loading = false
            it.onSuccess { files ->
                settings.oldSavesBrought = true
                host.show("Your saves are here", 420, "OK") {
                    add(label(this@LauncherActivity, "$files files from the old Pocket Foundry.", 16f, weight = 600))
                }
            }.onFailure { e -> error = "Couldn't bring the saves: ${e.message}" }
            refresh()
        }
    }

    private fun step(text: String, done: Float) = runOnUiThread { startScreen.show(text, done) }

    /** Before a game, its driver downloading or being checked: on the start screen too. */
    private fun progress(text: String, done: Float) = runOnUiThread {
        startScreen.show(text, done)
        if (!loading) {
            loading = true
            refresh()
        }
    }

    fun play(save: File?) {
        if (askForNotifications(then = { play(save) })) return
        error = null
        busy = "Starting…"
        refresh()
        background("start", {
            // The start couldn't get the driver list (no connection yet): without it the GPU may get no driver.
            if (!Catalog.fetched && Drivers.downloads(this)) {
                runOnUiThread { loading = true; startScreen.show("Fetching the driver list", 0f); refresh() }
                Drivers.refreshCatalog(this)
            }
            val args = if (save != null) listOf("--load-game", save.path) else emptyList()
            try {
                start(args)
            } catch (e: Session.NoOpenGL) {
                // Passed over now, and no longer chosen: the next driver in line, downloaded first if it has to be.
                start(args)
            }
        }) { result ->
            busy = null
            loading = false   // a driver that didn't download
            // Logs keeps the whole of it: Home has room for two lines.
            result.onFailure { error = it.message ?: "Factorio didn't start. See Logs."; ScreenLog.add("didn't start: $it") }
            refresh()
            if (result.isSuccess) showGame()
        }
    }

    /** Off the main thread: the game, once its driver is downloaded and checked, if it has to be. */
    private fun start(args: List<String>) {
        // The first start after an update unpacks the runtime.
        if (!Runtime.isInstalled(this)) runOnUiThread { busy = "Getting ready…"; refresh() }
        Session.start(this, args, ::progress)
    }

    /** The session's notification carries Quit, so it's worth asking for. */
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
        const val PICK_OLD_APP = 4
        var checked = false
        /** The start screen's steps ran: once per launch. */
        var started = false
    }
}
