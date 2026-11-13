package app.pocketfoundry.ui.screens.launcher

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.saves.GameFilesProvider
import app.pocketfoundry.core.system.GITHUB
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.ResolutionScale
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.WEBSITE
import app.pocketfoundry.core.system.X11Prefs
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.system.appVersion
import app.pocketfoundry.ui.kit.BUTTON_HEIGHT
import app.pocketfoundry.ui.kit.Choice
import app.pocketfoundry.ui.kit.Dropdown
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.MATCH
import app.pocketfoundry.ui.kit.WRAP
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.buttonBar
import app.pocketfoundry.ui.kit.checkBox
import app.pocketfoundry.ui.kit.column
import app.pocketfoundry.ui.kit.dpi
import app.pocketfoundry.ui.kit.heading
import app.pocketfoundry.ui.kit.keepScrollBar
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.link
import app.pocketfoundry.ui.kit.panel
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.stack
import app.pocketfoundry.ui.kit.withNote
import app.pocketfoundry.ui.screens.drivers.DriverDialog
import app.pocketfoundry.ui.screens.saves.SavesDialog

class SettingsScreen(private val launcher: LauncherActivity) {
    private val settings = Settings(launcher)
    private val paths = Paths(launcher)
    private val x11 = X11Prefs(launcher)
    private val note = label(launcher, "Resolution, sprites, graphics, the FPS counter, detailed logs and fast start " +
                             "change when Factorio next starts.", 13f, Factorio.MUTED)
    private val fastStart = label(launcher, FAST_START, 15f)
    private val driverDownloads = checkBox(launcher, settings.driverDownloads == Settings.ON) { on ->
        if (on == (settings.driverDownloads == Settings.ON)) return@checkBox
        if (on) launcher.turnOnDriverDownloads() else settings.driverDownloads = Settings.OFF
    }
    private val resolution = Dropdown(launcher, resolutionNames()) {
        x11.resolutionScale = x11.resolutionScales[it]
        settings.graphicsChosen = true
    }
    private val border = Dropdown(launcher, BORDERS.map { it.second }) {
        settings.border = BORDERS[it].first
    }
    private val frameRate = Choice(launcher, Settings.FRAME_RATES.map { "$it FPS" }) {
        Runtime.setFrameRate(launcher, Settings.FRAME_RATES[it])
    }
    private val sprites = Dropdown(launcher, Settings.SPRITE_QUALITIES.map { it.replaceFirstChar(Char::uppercase) }) {
        settings.spriteQuality = Settings.SPRITE_QUALITIES[it]
        settings.graphicsChosen = true
    }
    private val graphics = Choice(launcher, listOf("Full", "Light")) {
        settings.lightGraphics = it == LIGHT
        settings.graphicsChosen = true
    }
    private val touch = Choice(launcher, listOf("Direct", "Trackpad", "Box"), width = 96) {
        settings.touch = Settings.TOUCHES[it]
        trackpadSpeedRow.shown = settings.touch != Settings.DIRECT
    }
    private val trackpadSpeed = Dropdown(launcher, Settings.TRACKPAD_SPEEDS.map(::speedName)) {
        settings.trackpadSpeed = Settings.TRACKPAD_SPEEDS[it]
    }
    private val trackpadSpeedRow by lazy { dropdownSetting("Trackpad speed", trackpadSpeed) }
    private val editLayout = button(launcher, "Edit layout", size = 15f, onClick = launcher::editLayout)
    private val layoutLater = label(launcher, "In a game", 15f, Factorio.FAINT)
    // Up by the title, out of the bar below: on a narrow phone the bar has no room for it.
    private val credit = row(launcher) {
        add(link(launcher, "Pocket Foundry", WEBSITE, 13f))
        add(label(launcher, " ${launcher.appVersion()} · GPL-3.0 · ", 13f, Factorio.FAINT))
        add(link(launcher, "GitHub", GITHUB, 13f))
    }
    private val importAgain = button(launcher, "Import game", size = 15f, onClick = launcher::pickGame)

    val view: View = panel(launcher, header()) {
        add(row(launcher) {
            add(display(), 0, MATCH, weight = 1f)
            add(game(), 0, MATCH, weight = 1f, start = 10)
        }, height = 0, weight = 1f)
        add(buttonBar(launcher, "Back", { launcher.showSettings(false) }).apply {
            add(button(launcher, "Driver", size = 15f) { DriverDialog(launcher).show() }, height = BUTTON_HEIGHT, start = 12)
            add(button(launcher, "Saves", size = 15f) { SavesDialog(launcher).show() }, height = BUTTON_HEIGHT, start = 4)
            add(button(launcher, "Files", size = 15f) { GameFilesProvider.open(launcher) }, height = BUTTON_HEIGHT, start = 4)
            add(importAgain, height = BUTTON_HEIGHT, start = 4)
        })
    }

    private fun header() = row(launcher) {
        add(heading(launcher, "Settings"), width = 0, weight = 1f)
        add(note, end = 4)
        add(credit, end = 4)
    }

    private fun display() = section("Display",
        dropdownSetting("Resolution", resolution),
        dropdownSetting("Border", border),
        setting("Frame rate", frameRate),
        dropdownSetting("Sprite quality", sprites),
        setting("Graphics", graphics))

    private fun game() = section("Game",
        checkSetting("Pause in background", settings.pauseInBackground) { settings.pauseInBackground = it },
        checkSetting("Vibration", settings.vibration) { settings.vibration = it },
        setting("Touch", touch),
        trackpadSpeedRow,
        checkSetting("FPS counter", settings.fpsCounter) { settings.fpsCounter = it },
        setting(fastStart, checkBox(launcher, settings.fastStart) {
            settings.fastStart = it
            if (!it) paths.atlasCache.delete()
            showFastStart()
        }, controlHeight = CONTROL_HEIGHT),
        checkSetting("Detailed logs", settings.detailedLogs) { settings.detailedLogs = it },
        setting(label(launcher, "", 15f).apply {
            text = withNote("Driver downloads", "from GitHub, outside F-Droid's checks", Factorio.FAINT)
        }, driverDownloads, controlHeight = CONTROL_HEIGHT),
        setting("Controls layout", FrameLayout(launcher).apply {
            add(editLayout, WRAP, 40, Gravity.END)
            add(layoutLater, WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
        }))

    private fun section(title: String, vararg rows: View) = stack(launcher, Frame.SHALLOW, padding = 10, gap = 6) {
        add(heading(launcher, title, 17f))
        add(ScrollView(launcher).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(launcher.dpi(24))
            keepScrollBar()
            addView(column(launcher, gap = 6) { rows.forEach { add(it) } })
        }, height = 0, weight = 1f)
    }

    private fun setting(text: String, control: View, controlWidth: Int = WRAP, controlHeight: Int = 40) =
        setting(label(launcher, text, 15f), control, controlWidth, controlHeight)

    private fun setting(name: TextView, control: View, controlWidth: Int = WRAP, controlHeight: Int = 40) =
        row(launcher) {
            add(name, width = 0, weight = 1f)
            add(control, controlWidth, controlHeight)
        }

    /** With the room its atlases take, once Factorio has kept them. */
    private fun showFastStart() {
        val size = paths.atlasCache.length()
        fastStart.text = if (size > 0) withNote(FAST_START, "${size / 1_000_000} MB", Factorio.FAINT) else FAST_START
    }

    private fun dropdownSetting(text: String, dropdown: Dropdown) = setting(text, dropdown, CONTROL_WIDTH, CONTROL_HEIGHT)

    private fun checkSetting(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        setting(text, checkBox(launcher, checked, onChange), controlHeight = CONTROL_HEIGHT)

    private fun resolutionNames(): List<CharSequence> {
        val default = x11.defaultResolutionScale
        return x11.resolutionScales.map { scale ->
            val size = x11.gameSize(scale).let { (width, height) -> "$width × $height" }
            val note = when (scale) {
                ResolutionScale.NATIVE -> "Native"
                default -> "Default"
                ResolutionScale.cpu(default) -> "CPU"
                else -> null
            }
            if (note != null) withNote(size, note) else size
        }
    }

    fun update(idle: Boolean) {
        val scales = x11.resolutionScales
        resolution.selected = scales.indexOf(ResolutionScale.nearest(scales, x11.resolutionScale))
        border.selected = BORDERS.indexOfFirst { it.first == settings.border }.coerceAtLeast(0)
        frameRate.selected = Settings.FRAME_RATES.indexOf(settings.frameRate)
        sprites.selected = Settings.SPRITE_QUALITIES.indexOf(settings.spriteQuality).coerceAtLeast(0)
        graphics.selected = if (settings.lightGraphics) LIGHT else FULL
        touch.selected = Settings.TOUCHES.indexOf(settings.touch).coerceAtLeast(0)
        trackpadSpeed.selected = Settings.TRACKPAD_SPEEDS.indexOf(settings.trackpadSpeed).coerceAtLeast(0)
        trackpadSpeedRow.shown = settings.touch != Settings.DIRECT
        showFastStart()
        note.shown = Session.inGame
        credit.shown = !Session.inGame
        editLayout.shown = Session.live
        layoutLater.shown = !Session.live
        importAgain.isEnabled = !Session.inGame && idle
        driverDownloads.isChecked = settings.driverDownloads == Settings.ON
    }

    private companion object {
        const val CONTROL_WIDTH = 170
        const val CONTROL_HEIGHT = 34
        const val FULL = 0
        const val LIGHT = 1

        /** 150 -> "1.5×". */
        fun speedName(percent: Int): String =
            (if (percent % 100 == 0) "${percent / 100}" else "${percent / 100.0}") + "×"
        const val FAST_START = "Fast start"
        /** Settings.border's choices. */
        val BORDERS = listOf(0 to "Off", 10 to "Thin", 20 to "Wide")
    }
}
