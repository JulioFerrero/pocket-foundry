package app.pocketfoundry.ui.screens.launcher

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.TextUtils
import app.pocketfoundry.core.controller.gamepads
import app.pocketfoundry.core.drivers.Drivers
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
import app.pocketfoundry.ui.kit.FrameTabs
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

class SettingsScreen(private val launcher: LauncherActivity) {
    private val settings = Settings(launcher)
    private val paths = Paths(launcher)
    private val x11 = X11Prefs(launcher)
    private val note = label(launcher, "Resolution, sprites, graphics, detailed logs and fast start change when " +
                             "Factorio next starts.", 13f, Factorio.MUTED)
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
    private val controls = Choice(launcher, listOf("Gamepad", "Mouse & keys"), width = 132) {
        settings.controls = Settings.CONTROLS[it]
    }
    private val touch = Choice(launcher, listOf("Direct", "Trackpad", "Box"), width = 96) {
        settings.touch = Settings.TOUCHES[it]
        trackpadSpeedRow.shown = settings.touch != Settings.DIRECT
    }
    private val trackpadSpeed = Dropdown(launcher, Settings.TRACKPAD_SPEEDS.map(::speedName)) {
        settings.trackpadSpeed = Settings.TRACKPAD_SPEEDS[it]
    }
    private val trackpadSpeedRow by lazy { dropdownSetting("Trackpad speed", trackpadSpeed) }
    // What a long press does depends on the controls (X with the gamepad's, a right click with Mouse & keys): only
    // its wait is set here.
    private val longPress = Dropdown(launcher, Settings.LONG_PRESSES.map {
        when { it == 0 -> "Off"; it % 1000 == 0 -> "${it / 1000} s"; else -> "$it ms" }
    }) { settings.longPress = Settings.LONG_PRESSES[it] }
    private val editLayout = button(launcher, "Edit layout", size = 15f, onClick = launcher::editLayout)
    // The first controller connected: a handheld's own, or the one plugged in.
    private val controllerButtons = button(launcher, "Change", size = 15f) {
        gamepads(launcher).firstOrNull()?.let { ButtonsDialog(launcher, it).show() }
    }
    private val layoutLater = label(launcher, "In a game", 15f, Factorio.FAINT)
    // Up by the title, out of the bar below: on a narrow phone the bar has no room for it.
    private val credit = row(launcher) {
        add(link(launcher, "Pocket Foundry", WEBSITE, 13f))
        add(label(launcher, " ${launcher.appVersion()} · GPL-3.0 · ", 13f, Factorio.FAINT))
        add(link(launcher, "GitHub", GITHUB, 13f))
    }
    private val importAgain = button(launcher, "Import game", size = 15f, onClick = launcher::pickGame)
    // Its name where the dropdowns beside it have theirs.
    private val driver = button(launcher, "", size = 15f) { DriverDialog(launcher).show() }.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(launcher.dpi(14), 0, launcher.dpi(14), 0)
    }

    // As in the game's own settings: a tab for each kind, none long enough to scroll on a phone.
    private val pages = listOf(graphicsPage(), interfacePage(), controlsPage(), otherPage())
    private val pageFrame = FrameLayout(launcher).apply { pages.forEach { add(it) } }
    private val tabs = FrameTabs(launcher, listOf("Graphics", "Interface", "Controls", "Other"), Frame.SHALLOW, vertical = true, ::open)

    init {
        open(0)
    }

    val view: View = panel(launcher, header()) {
        add(row(launcher, Gravity.TOP) {
            add(tabs)
            add(stack(launcher, Frame.SHALLOW, padding = 10) { add(pageFrame, height = MATCH) }, 0, MATCH, weight = 1f)
                .apply { (layoutParams as MarginLayoutParams).marginStart = -launcher.dpi(FrameTabs.OVERLAP) }
        }, height = 0, weight = 1f)
        add(buttonBar(launcher, "Back", { launcher.showSettings(false) }).apply {
            add(button(launcher, "Files", size = 15f) { GameFilesProvider.open(launcher) }, height = BUTTON_HEIGHT, start = 12)
            add(importAgain, height = BUTTON_HEIGHT, start = 4)
        })
    }

    private fun header() = row(launcher) {
        add(heading(launcher, "Settings"), width = 0, weight = 1f)
        add(note, end = 4)
        add(credit, end = 4)
    }

    private fun open(tab: Int) {
        tabs.selected = tab
        pages.forEachIndexed { i, page -> page.shown = i == tab }
    }

    private fun graphicsPage() = page(
        dropdownSetting("Resolution", resolution),
        setting("Frame rate", frameRate),
        dropdownSetting("Sprite quality", sprites),
        setting("Graphics", graphics),
        setting("Driver", driver, CONTROL_WIDTH, CONTROL_HEIGHT))

    private fun interfacePage() = page(
        dropdownSetting("Border", border),
        checkSetting("FPS counter", settings.fpsCounter) { settings.fpsCounter = it },
        checkSetting("Pause in background", settings.pauseInBackground) { settings.pauseInBackground = it })

    private fun controlsPage() = page(
        setting("Controls", controls),
        setting("Touch", touch),
        trackpadSpeedRow,
        dropdownSetting("Long press", longPress),
        checkSetting("Vibration", settings.vibration) { settings.vibration = it },
        setting("Controller buttons", controllerButtons),
        setting("Layout", FrameLayout(launcher).apply {
            add(editLayout, WRAP, 40, Gravity.END)
            add(layoutLater, WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
        }))

    private fun otherPage() = page(
        setting(fastStart, checkBox(launcher, settings.fastStart) {
            settings.fastStart = it
            if (!it) paths.atlasCache.delete()
            showFastStart()
        }, controlHeight = CONTROL_HEIGHT),
        setting(label(launcher, "", 15f).apply { text = withNote("Fast CPU", "warmer, more battery", Factorio.FAINT) },
                checkBox(launcher, settings.fastCpu) { settings.fastCpu = it }, controlHeight = CONTROL_HEIGHT),
        checkSetting("Detailed logs", settings.detailedLogs) { settings.detailedLogs = it },
        setting(label(launcher, "", 15f).apply {
            text = withNote("Driver downloads", "from GitHub, outside F-Droid's checks", Factorio.FAINT)
        }, driverDownloads, controlHeight = CONTROL_HEIGHT))

    /** One tab's rows. Scrolls only on the smallest phones. */
    private fun page(vararg rows: View) = ScrollView(launcher).apply {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(launcher.dpi(24))
        keepScrollBar()
        addView(column(launcher, gap = 6) { rows.forEach { add(it) } })
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
        controls.selected = Settings.CONTROLS.indexOf(settings.controls).coerceAtLeast(0)
        trackpadSpeed.selected = Settings.TRACKPAD_SPEEDS.indexOf(settings.trackpadSpeed).coerceAtLeast(0)
        trackpadSpeedRow.shown = settings.touch != Settings.DIRECT
        longPress.selected = Settings.LONG_PRESSES.indexOf(settings.longPress).coerceAtLeast(0)
        showFastStart()
        note.shown = Session.inGame
        credit.shown = !Session.inGame
        editLayout.shown = Session.live
        layoutLater.shown = !Session.live
        importAgain.isEnabled = !Session.inGame && idle
        driverDownloads.isChecked = settings.driverDownloads == Settings.ON
        val pad = gamepads(launcher).any()
        controllerButtons.isEnabled = pad
        controllerButtons.text = if (pad) "Change" else "None connected"
        driver.text = Drivers.find(launcher, Drivers.chosen(launcher))?.name.orEmpty()
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
