package app.pocketfoundry.ui.screens.launcher

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.saves.GameFilesProvider
import app.pocketfoundry.core.system.GITHUB
import app.pocketfoundry.core.system.ResolutionScale
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.X11Prefs
import app.pocketfoundry.core.updates.Updates
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.system.appVersion
import app.pocketfoundry.ui.kit.BUTTON_HEIGHT
import app.pocketfoundry.ui.kit.Choice
import app.pocketfoundry.ui.kit.Dropdown
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.MATCH
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.WRAP
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.buttonBar
import app.pocketfoundry.ui.kit.checkBox
import app.pocketfoundry.ui.kit.column
import app.pocketfoundry.ui.kit.displayCompat
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

/**
 * The display and game settings, and a bar to the update, the driver, saves, the game's files and a new import. The
 * version and licence sit by the title, where a game's note goes while one runs.
 */
class SettingsScreen(private val launcher: LauncherActivity) {
    private val settings = Settings(launcher)
    private val x11 = X11Prefs(launcher)
    private val note = label(launcher, "Resolution, sprites, graphics, the FPS counter and detailed logs change when " +
                             "Factorio next starts.", 13f, Factorio.MUTED)
    private val resolution = Dropdown(launcher, resolutionNames()) { x11.resolutionScale = x11.resolutionScales[it] }
    // Named in the order of Settings.BORDERS and Settings.SPRITE_QUALITIES.
    private val border = Dropdown(launcher, listOf("Off", "Thin", "Wide")) { settings.border = Settings.BORDERS[it] }
    private val frameRate = Choice(launcher, listOf("30 FPS", "60 FPS")) { settings.frameRate = FRAME_RATES[it] }
    private val sprites = Dropdown(launcher, listOf("High", "Medium")) { settings.spriteQuality = Settings.SPRITE_QUALITIES[it] }
    private val graphics = Choice(launcher, listOf("Full", "Light")) { settings.lightGraphics = it == LIGHT }
    private val editLayout = button(launcher, "Edit layout", size = 15f, onClick = launcher::editLayout)
    private val layoutLater = label(launcher, "In a game", 15f, Factorio.FAINT)
    // Up by the title, out of the bar below: on a narrow phone the bar has no room for it next to the update.
    private val credit = row(launcher) {
        add(label(launcher, "Pocket Foundry ${launcher.appVersion()} · GPL-3.0 · ", 13f, Factorio.FAINT))
        add(link(launcher, "GitHub", GITHUB, 13f))
    }
    private val updateButton = button(launcher, "", Tone.GREEN, size = 15f) { Updates.available?.let(launcher::showUpdate) }
    private val importAgain = button(launcher, "Import game", size = 15f, onClick = launcher::pickGame)

    val view: View = panel(launcher, header()) {
        add(row(launcher) {
            add(display(), 0, MATCH, weight = 1f)
            add(game(), 0, MATCH, weight = 1f, start = 10)
        }, height = 0, weight = 1f)
        add(buttonBar(launcher, "Back", { launcher.showSettings(false) }).apply {
            add(updateButton, height = BUTTON_HEIGHT, start = 8)
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
        checkSetting("FPS counter", settings.fpsCounter) { settings.fpsCounter = it },
        checkSetting("Detailed logs", settings.detailedLogs) { settings.detailedLogs = it },
        checkSetting("Check for updates", settings.checkForUpdates) {
            settings.checkForUpdates = it
            launcher.updates.check()
            launcher.refresh()
        },
        setting("Controls layout", FrameLayout(launcher).apply {
            add(editLayout, WRAP, 40, Gravity.END)
            add(layoutLater, WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
        }))

    /** Its rows under its heading, scrolling on a screen too short for them all. */
    private fun section(title: String, vararg rows: View) = stack(launcher, Frame.SHALLOW, padding = 10, gap = 6) {
        add(heading(launcher, title, 17f))
        add(ScrollView(launcher).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(launcher.dpi(24))
            keepScrollBar()
            addView(column(launcher, gap = 6) { rows.forEach { add(it) } })
        }, height = 0, weight = 1f)
    }

    private fun setting(text: String, control: View, controlWidth: Int = WRAP, controlHeight: Int = 40) = row(launcher) {
        add(label(launcher, text, 15f), width = 0, weight = 1f)
        add(control, controlWidth, controlHeight)
    }

    private fun dropdownSetting(text: String, dropdown: Dropdown) = setting(text, dropdown, CONTROL_WIDTH, CONTROL_HEIGHT)

    private fun checkSetting(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        setting(text, checkBox(launcher, checked, onChange), controlHeight = CONTROL_HEIGHT)

    /** Each scale as the game's size, with Native, Default and CPU marked. */
    private fun resolutionNames(): List<CharSequence> {
        val mode = launcher.displayCompat()!!.mode
        val long = maxOf(mode.physicalWidth, mode.physicalHeight)
        val short = minOf(mode.physicalWidth, mode.physicalHeight)
        val default = x11.defaultResolutionScale
        return x11.resolutionScales.map { scale ->
            val size = "${long * 100 / scale} × ${short * 100 / scale}"
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
        border.selected = Settings.BORDERS.indexOf(settings.border).coerceAtLeast(0)
        frameRate.selected = FRAME_RATES.indexOf(settings.frameRate)
        sprites.selected = Settings.SPRITE_QUALITIES.indexOf(settings.spriteQuality).coerceAtLeast(0)
        graphics.selected = if (settings.lightGraphics) LIGHT else FULL
        note.shown = Session.inGame
        credit.shown = !Session.inGame
        editLayout.shown = Session.live
        layoutLater.shown = !Session.live
        importAgain.isEnabled = !Session.inGame && idle
    }

    /** The release a check found, while checks are on, or the update's progress once it started. */
    fun showRelease(inGame: Boolean, idle: Boolean) {
        val progress = launcher.updates.progress
        val release = Updates.available?.takeIf { settings.checkForUpdates }
        updateButton.shown = release != null || progress != null
        updateButton.text = progress ?: "Update to ${release?.version}"
        updateButton.isEnabled = progress == null && !inGame && idle
    }

    private companion object {
        const val CONTROL_WIDTH = 170
        const val CONTROL_HEIGHT = 34
        const val FULL = 0
        const val LIGHT = 1
        val FRAME_RATES = listOf(30, 60)
    }
}
