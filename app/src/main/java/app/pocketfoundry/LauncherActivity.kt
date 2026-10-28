package app.pocketfoundry

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.x11.MainActivity
import app.pocketfoundry.stage.Stage
import app.pocketfoundry.ui.Arrow
import app.pocketfoundry.ui.BarView
import app.pocketfoundry.ui.Dropdown
import app.pocketfoundry.ui.FButton
import app.pocketfoundry.ui.Factorio
import app.pocketfoundry.ui.GuiSprites
import app.pocketfoundry.ui.Tone
import app.pocketfoundry.ui.checkBox
import app.pocketfoundry.ui.dpi
import app.pocketfoundry.ui.groove
import app.pocketfoundry.ui.heading
import app.pocketfoundry.ui.immersive
import app.pocketfoundry.ui.label
import app.pocketfoundry.ui.slider
import app.pocketfoundry.ui.stack
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.concurrent.thread
import kotlin.math.abs

/** Welcome, import progress and Home; while a game runs, Home is the pause menu. */
class LauncherActivity : Activity() {
    private lateinit var paths: Paths
    // Only while on screen: behind the game it holds ~350 MB the game needs.
    private var stage: Stage? = null
    private lateinit var stageHolder: FrameLayout

    private lateinit var welcome: View
    private lateinit var welcomeError: TextView

    private lateinit var importing: View
    private lateinit var importFile: TextView
    private lateinit var importPercent: TextView
    private lateinit var importBar: BarView

    private lateinit var home: View
    private lateinit var homeTitle: TextView
    private lateinit var continueSave: FButton
    private lateinit var resume: FButton
    private lateinit var mainMenu: FButton
    private lateinit var settingsButton: FButton
    private lateinit var logs: FButton
    private lateinit var quit: FButton
    private lateinit var footerLeft: TextView
    private lateinit var footerRight: TextView
    private lateinit var card: View
    private lateinit var cardName: TextView
    private lateinit var cardTime: TextView
    private lateinit var cardImage: ImageView
    private lateinit var cardFrame: View

    private lateinit var settings: View
    private lateinit var resolution: Dropdown
    private lateinit var border: Dropdown
    private lateinit var layoutLater: TextView
    private lateinit var fps30: FButton
    private lateinit var fps60: FButton
    private lateinit var sprites: Dropdown
    private lateinit var controlsChoice: Dropdown
    private lateinit var settingsNote: TextView
    private lateinit var editLayout: FButton
    private lateinit var importAgain: FButton
    private lateinit var homeGroove: View

    private var dialog: View? = null
    private var beforeDialog: View? = null
    private lateinit var importFirst: FButton
    private var shownScreen: Pair<View?, Boolean>? = null
    private var importingNow = false
    private var settingsOpen = false
    private var busy: String? = null
    private var error: String? = null
    private var pendingPlay = false
    private var pendingSave: File? = null
    private var previewOf: String? = null
    private val sessionChanged: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = Paths(this)
        applyDisplayDefaults(this)
        window.setDecorFitsSystemWindows(false)

        val overlay = FrameLayout(this)
        overlay.setOnApplyWindowInsetsListener { view, insets ->
            val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            insets
        }
        welcome = buildWelcome()
        importing = buildImporting()
        home = buildHome()
        settings = buildSettings()
        overlay.addView(welcome, centered(460))
        overlay.addView(importing, centered(560))
        overlay.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay.addView(settings, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dpi(24), dpi(16), dpi(24), dpi(16)) })

        stageHolder = FrameLayout(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF091726.toInt())
            addView(stageHolder, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        })
    }

    override fun onStart() {
        super.onStart()
        if (stage == null) stage = Stage(this, paths).also { stageHolder.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
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
            dialog != null -> closeDialog()
            settingsOpen -> { settingsOpen = false; refresh() }
            importingNow -> {}                       // the import finishes on its own
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun buildWelcome(): View {
        val panel = stack(this, Factorio.PANEL, sunken = false, padding = 8, gap = 10)
        panel.addView(heading(this, "Pocket Foundry"))
        val inset = stack(this, Factorio.INSET_LIGHT, sunken = true, padding = 12, gap = 6)
        inset.addView(label(this, "Your own Factorio, native on this phone.", 17f, weight = 600))
        inset.addView(LinearLayout(this).apply {
            addView(label(context, "The Linux arm64 .tar.xz from ", 14f, Factorio.MUTED))
            addView(label(context, "factorio.com", 14f, Factorio.LINK).apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                                            intArrayOf(Tone.GREY.glow, Factorio.LINK)))
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://factorio.com/download"))) }
            })
        })
        welcomeError = label(this, "", 14f, Factorio.ERROR)
        inset.addView(welcomeError)
        panel.addView(inset)
        importFirst = FButton(this, "Import Factorio", Tone.GREEN, Arrow.RIGHT).apply { setOnClickListener { pickGame() } }
        panel.addView(importFirst, LinearLayout.LayoutParams(WRAP, dpi(44)).apply { gravity = Gravity.END })
        return panel
    }

    private fun buildImporting(): View {
        val panel = stack(this, Factorio.PANEL, sunken = false, padding = 8, gap = 10)
        panel.addView(heading(this, "Importing Factorio"))
        val inset = stack(this, Factorio.INSET_DARK, sunken = true, padding = 12, gap = 10)
        importFile = label(this, "", 14f, Factorio.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        importPercent = label(this, "0%", 18f, Factorio.HEADING, 600)
        inset.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(importFile, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(importPercent, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dpi(16) })
        })
        importBar = BarView(this)
        inset.addView(importBar, LinearLayout.LayoutParams(MATCH, dpi(14)))
        panel.addView(inset)
        return panel
    }

    private fun buildHome(): View {
        val root = FrameLayout(this)
        val panel = stack(this, Factorio.PANEL, sunken = false, padding = 8, gap = 10)
        homeTitle = heading(this, "Pocket Foundry")
        panel.addView(homeTitle)

        val buttons = stack(this, Factorio.INSET_DARK, sunken = true, padding = 8, gap = 6)
        continueSave = FButton(this, "Continue", Tone.GREEN, size = 17f).apply { setOnClickListener { newestSave()?.let { play(it) } } }
        resume = FButton(this, "Resume", Tone.GREEN, size = 17f).apply { setOnClickListener { showGame() } }
        mainMenu = FButton(this, "Main menu").apply { setOnClickListener { play(null) } }
        settingsButton = FButton(this, "Settings").apply { setOnClickListener { settingsOpen = true; refresh() } }
        logs = FButton(this, "Logs").apply { setOnClickListener { startActivity(Intent(context, LogActivity::class.java)) } }
        quit = FButton(this, "Quit", Tone.RED, Arrow.LEFT).apply { setOnClickListener { confirmQuit() } }
        buttons.addView(continueSave, LinearLayout.LayoutParams(MATCH, dpi(44)))
        buttons.addView(resume, LinearLayout.LayoutParams(MATCH, dpi(44)))
        homeGroove = groove(this)
        buttons.addView(homeGroove, LinearLayout.LayoutParams(MATCH, dpi(3)))
        buttons.addView(mainMenu, LinearLayout.LayoutParams(MATCH, dpi(40)))
        buttons.addView(settingsButton, LinearLayout.LayoutParams(MATCH, dpi(40)))
        buttons.addView(logs, LinearLayout.LayoutParams(MATCH, dpi(40)))
        buttons.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))
        buttons.addView(quit, LinearLayout.LayoutParams(WRAP, dpi(40)))
        panel.addView(buttons, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val footer = stack(this, Factorio.INSET_LIGHT, sunken = true, padding = 0, gap = 0)
        footerLeft = label(this, "", 15f, weight = 700)
        footerRight = label(this, "", 15f, weight = 700)
        footer.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8), dpi(6), dpi(8), dpi(6))
            addView(footerLeft, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(footerRight)
        })
        panel.addView(footer)
        root.addView(panel, FrameLayout.LayoutParams(dpi(288), MATCH).apply { setMargins(dpi(24), dpi(16), 0, dpi(16)) })

        val save = stack(this, Factorio.PANEL, sunken = false, padding = 4, gap = 6)
        cardName = heading(this, "", 17f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        cardTime = label(this, "", 13f, Factorio.MUTED)
        save.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(2), 0, dpi(2), 0)
            addView(cardName, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(cardTime)
        })
        val frame = stack(this, Factorio.INSET_LIGHT, sunken = true, padding = 0, gap = 0)
        cardImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(cardImage, LinearLayout.LayoutParams(MATCH, dpi(118)))
        save.addView(frame)
        cardFrame = frame
        card = save
        root.addView(save, FrameLayout.LayoutParams(dpi(256), WRAP, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dpi(24), dpi(16)) })
        return root
    }

    private fun buildSettings(): View {
        val panel = stack(this, Factorio.PANEL, sunken = false, padding = 8, gap = 10)
        settingsNote = label(this, "Resolution, sprites and controls change when Factorio next starts.", 13f, Factorio.MUTED)
        panel.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading(context, "Settings"), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(settingsNote, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dpi(4) })
        })
        val columns = LinearLayout(this)

        val display = stack(this, Factorio.INSET_LIGHT, sunken = true, padding = 10, gap = 6)
        display.addView(heading(this, "Display", 17f))
        resolution = Dropdown(this, resolutionNames()) { Settings.setResolutionScale(this, Settings.resolutionScales(this)[it]) }
        display.addView(row("Resolution", resolution.apply { layoutParams = LinearLayout.LayoutParams(dpi(170), dpi(34)) }))
        border = Dropdown(this, listOf("Off", "Thin", "Wide")) { Settings.setBorder(this, Settings.BORDERS[it]) }
        display.addView(row("Border", border.apply { layoutParams = LinearLayout.LayoutParams(dpi(170), dpi(34)) }))
        fps30 = FButton(this, "30 FPS", size = 15f).apply { setOnClickListener { Settings.setFrameRate(context, 30); refresh() } }
        fps60 = FButton(this, "60 FPS", size = 15f).apply { setOnClickListener { Settings.setFrameRate(context, 60); refresh() } }
        display.addView(row("Frame rate", choice(fps30, fps60)))
        sprites = Dropdown(this, listOf("High", "Medium")) { Settings.setSpriteQuality(this, SPRITE_QUALITIES[it]) }
        display.addView(row("Sprite quality", sprites.apply { layoutParams = LinearLayout.LayoutParams(dpi(170), dpi(34)) }))
        columns.addView(display, LinearLayout.LayoutParams(0, MATCH, 1f))

        val controller = stack(this, Factorio.INSET_LIGHT, sunken = true, padding = 10, gap = 6)
        controller.addView(heading(this, "Game", 17f))
        controller.addView(row("Pause in background", checkBox(this, Settings.pauseInBackground(this)) { Settings.setPauseInBackground(this, it) }
            .apply { layoutParams = LinearLayout.LayoutParams(WRAP, dpi(34)) }))
        controller.addView(row("Vibration", checkBox(this, Settings.vibration(this)) { Settings.setVibration(this, it) }
            .apply { layoutParams = LinearLayout.LayoutParams(WRAP, dpi(34)) }))
        controlsChoice = Dropdown(this, listOf("Gamepad", "Touch")) { Settings.setControls(this, Settings.CONTROLS[it]) }
        controller.addView(row("Controls", controlsChoice.apply { layoutParams = LinearLayout.LayoutParams(dpi(170), dpi(34)) }))
        editLayout = FButton(this, "Edit layout", size = 15f).apply {
            setOnClickListener { Settings.requestLayoutEditor(context); settingsOpen = false; showGame() }
        }
        layoutLater = label(this, "In a game", 15f, Factorio.FAINT)
        controller.addView(row("Controls layout", FrameLayout(this).apply {
            addView(editLayout, FrameLayout.LayoutParams(WRAP, dpi(40), Gravity.END))
            addView(layoutLater, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
            layoutParams = LinearLayout.LayoutParams(WRAP, dpi(40))
        }))
        columns.addView(controller, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dpi(10) })
        panel.addView(columns, LinearLayout.LayoutParams(MATCH, 0, 1f))

        importAgain = FButton(this, "Import game", size = 15f).apply { setOnClickListener { pickGame() } }
        panel.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FButton(context, "Back", Tone.RED, Arrow.LEFT).apply { setOnClickListener { settingsOpen = false; refresh() } },
                    LinearLayout.LayoutParams(WRAP, dpi(44)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(label(context, "Pocket Foundry ${packageManager.getPackageInfo(packageName, 0).versionName} · GPL-3.0", 13f, Factorio.FAINT))
            addView(FButton(context, "Driver", size = 15f).apply { setOnClickListener { showDrivers() } },
                    LinearLayout.LayoutParams(WRAP, dpi(44)).apply { marginStart = dpi(12) })
            addView(FButton(context, "Saves", size = 15f).apply { setOnClickListener { showSaves() } },
                    LinearLayout.LayoutParams(WRAP, dpi(44)).apply { marginStart = dpi(4) })
            addView(importAgain, LinearLayout.LayoutParams(WRAP, dpi(44)).apply { marginStart = dpi(4) })
        })
        return panel
    }

    private fun row(text: String, control: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label(context, text, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(control, control.layoutParams ?: LinearLayout.LayoutParams(WRAP, dpi(40)))
    }

    private fun resolutionNames(): List<CharSequence> {
        val mode = display.mode
        val long = maxOf(mode.physicalWidth, mode.physicalHeight)
        val short = minOf(mode.physicalWidth, mode.physicalHeight)
        val default = Settings.defaultResolutionScale(this)
        return Settings.resolutionScales(this).map { scale ->
            val note = when (scale) { 100 -> "Native"; default -> "Default"; else -> null }
            SpannableStringBuilder("${long * 100 / scale} × ${short * 100 / scale}").apply {
                if (note != null) {
                    val start = length
                    append("  ").append(note)
                    setSpan(ForegroundColorSpan(0xFF3A3A3A.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(AbsoluteSizeSpan(13, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun choice(vararg options: FButton) = LinearLayout(this).apply {
        options.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(dpi(84), dpi(34)).apply { if (i > 0) marginStart = dpi(2) })
        }
    }

    private fun centered(widthDp: Int) = FrameLayout.LayoutParams(dpi(widthDp), WRAP, Gravity.CENTER)

    private fun refresh() {
        val version = GameImport.installedVersion(this)
        val state = Session.state
        val inGame = state != Session.State.STOPPED
        val live = state == Session.State.RUNNING || state == Session.State.PAUSED

        welcome.visibility = if (!importingNow && version == null) View.VISIBLE else View.GONE
        importing.visibility = if (importingNow) View.VISIBLE else View.GONE
        home.visibility = if (!importingNow && version != null && !settingsOpen) View.VISIBLE else View.GONE
        settings.visibility = if (!importingNow && version != null && settingsOpen) View.VISIBLE else View.GONE
        val screen = listOf(welcome, importing, home, settings).firstOrNull { it.visibility == View.VISIBLE }
        // Home and Paused count as two: their first choices differ.
        if (screen to inGame != shownScreen) {
            shownScreen = screen to inGame
            // Touch mode shows no focus, so only a controller sees this.
            if (dialog == null) (if (screen === welcome) importFirst else screen)?.requestFocus()
        }
        when {
            importingNow -> stage?.show(Stage.Scene.IMPORT)
            version == null -> stage?.show(Stage.Scene.WELCOME)
            settingsOpen -> stage?.show(Stage.Scene.FACTORY, dim = 0.55f)
            inGame -> stage?.show(Stage.Scene.FACTORY, dim = 0.55f, saturate = 0.8f)
            else -> stage?.show(Stage.Scene.FACTORY, dim = 0.75f)
        }

        welcomeError.text = error
        welcomeError.visibility = if (error != null && version == null) View.VISIBLE else View.GONE
        if (version == null) return

        val save = newestSave()
        homeTitle.text = busy ?: when (state) {
            Session.State.QUITTING -> "Quitting…"
            Session.State.RUNNING, Session.State.PAUSED -> "Paused"
            Session.State.STOPPED -> "Pocket Foundry"
        }
        continueSave.visibility = if (!inGame && save != null) View.VISIBLE else View.GONE
        if (save != null) continueSave.text = SpannableStringBuilder("Continue  ").apply {
            val start = length
            append(save.nameWithoutExtension)
            setSpan(ForegroundColorSpan(0xFF163218.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(14, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        resume.visibility = if (inGame) View.VISIBLE else View.GONE
        quit.visibility = resume.visibility
        homeGroove.visibility = if (inGame || save != null) View.VISIBLE else View.GONE
        mainMenu.visibility = if (inGame) View.GONE else View.VISIBLE
        val idle = busy == null
        for (b in listOf(continueSave, mainMenu)) b.isEnabled = idle
        for (b in listOf(resume, quit)) b.isEnabled = idle && live
        settingsButton.isEnabled = idle

        val failure = error ?: Session.lastFailure?.let { "Factorio stopped unexpectedly ($it). See Logs." }
        footerLeft.setTextColor(if (failure != null) Factorio.ERROR else Factorio.TEXT)
        footerLeft.textSize = if (failure != null) 13f else 15f
        when {
            failure != null -> { footerLeft.text = failure; footerRight.text = "" }
            inGame -> {
                footerLeft.text = "Last saved"
                footerRight.text = save?.let { DateUtils.getRelativeTimeSpanString(it.lastModified(), System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE) } ?: "Never"
            }
            else -> { footerLeft.text = "Factorio"; footerRight.text = version }
        }

        card.visibility = if (save != null) View.VISIBLE else View.GONE
        if (save != null) {
            cardName.text = save.nameWithoutExtension
            cardTime.text = if (inGame) "Paused" else whenSaved(save.lastModified())
            showPreview(save)
        }

        val scale = Settings.resolutionScale(this)
        val scales = Settings.resolutionScales(this)
        resolution.selected = scales.indices.minBy { abs(scales[it] - scale) }
        val fps = Settings.frameRate(this)
        fps30.tone = if (fps == 30) Tone.ORANGE else Tone.GREY
        fps60.tone = if (fps == 60) Tone.ORANGE else Tone.GREY
        sprites.selected = SPRITE_QUALITIES.indexOf(Settings.spriteQuality(this)).coerceAtLeast(0)
        controlsChoice.selected = Settings.CONTROLS.indexOf(Settings.controls(this)).coerceAtLeast(0)
        settingsNote.visibility = if (inGame) View.VISIBLE else View.GONE
        border.selected = Settings.BORDERS.indexOf(Settings.border(this)).coerceAtLeast(0)
        editLayout.visibility = if (live) View.VISIBLE else View.GONE
        layoutLater.visibility = if (live) View.GONE else View.VISIBLE
        importAgain.isEnabled = !inGame && idle
    }

    private fun whenSaved(millis: Long): String {
        val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val clock = android.text.format.DateFormat.getTimeFormat(this).format(millis)
        return when (time.toLocalDate()) {
            LocalDate.now() -> "Today $clock"
            LocalDate.now().minusDays(1) -> "Yesterday $clock"
            else -> time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }

    private fun showPreview(save: File) {
        val key = "${save.path}@${save.lastModified()}"
        if (key == previewOf) return
        previewOf = key
        cardImage.setImageDrawable(null)
        thread(name = "pocketfoundry-preview") {
            val bitmap = Saves.preview(save, 3)
            runOnUiThread {
                if (previewOf != key) return@runOnUiThread
                cardImage.setImageBitmap(bitmap)
                // Saves from servers often carry no picture.
                cardFrame.visibility = if (bitmap != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun newestSave() = Saves.newest(paths)

    private fun showGame() = startActivity(Intent(this, MainActivity::class.java))

    private fun confirmQuit() {
        val save = newestSave()
        val panel = stack(this, Factorio.PANEL, sunken = false, padding = 8, gap = 10)
        panel.addView(heading(this, "Quit Factorio?"))
        val inset = stack(this, Factorio.INSET_LIGHT, sunken = true, padding = 12, gap = 6)
        inset.addView(label(this, save?.let {
            "Last saved " + DateUtils.getRelativeTimeSpanString(it.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } ?: "Nothing has been saved yet.", 16f, weight = 600))
        inset.addView(label(this, if (save != null) "Anything after that is lost." else "This game's progress is lost.", 14f, Factorio.MUTED))
        panel.addView(inset)
        panel.addView(LinearLayout(this).apply {
            addView(FButton(context, "Cancel", Tone.RED, Arrow.LEFT).apply { setOnClickListener { closeDialog() } },
                    LinearLayout.LayoutParams(WRAP, dpi(44)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(FButton(context, "Quit", Tone.GREEN, Arrow.RIGHT).apply { setOnClickListener { closeDialog(); Session.quit() } },
                    LinearLayout.LayoutParams(WRAP, dpi(44)))
        })
        showDialog(panel, 440)
    }

    private fun showDialog(panel: View, widthDp: Int) {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closeDialog() }
            isFocusable = false
            addView(panel.apply { isClickable = true; isFocusable = false }, centered(widthDp))
        }
        beforeDialog = currentFocus
        findViewById<ViewGroup>(android.R.id.content).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (window.decorView as ViewGroup).addView(scrim, ViewGroup.LayoutParams(MATCH, MATCH))
        dialog = scrim
        scrim.requestFocus()
    }

    private fun closeDialog() {
        findViewById<ViewGroup>(android.R.id.content).descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        dialog?.let { (it.parent as? ViewGroup)?.removeView(it) }
        dialog = null
        beforeDialog?.takeIf { it.isShown }?.requestFocus()
        beforeDialog = null
        savesStatus = null
        driverList = null
        driverStatus = null
    }

    private var driverList: LinearLayout? = null
    private var driverStatus: TextView? = null
    private val driverButtons = ArrayList<FButton>()

    private fun showDrivers() {
        val panel = stack(this, Factorio.PANEL, sunken = false, padding = 8, gap = 10)
        panel.addView(heading(this, "Graphics driver"))
        val list = stack(this, Factorio.INSET_DARK, sunken = true, padding = 6, gap = 4)
        panel.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, WRAP))
        val status = label(this, "Applies when Factorio next starts.", 14f, Factorio.MUTED)
        status.visibility = if (Session.state != Session.State.STOPPED) View.VISIBLE else View.GONE
        panel.addView(status)
        driverButtons.clear()
        panel.addView(LinearLayout(this).apply {
            addView(FButton(context, "Close", Tone.RED, Arrow.LEFT).apply { setOnClickListener { closeDialog() } },
                    LinearLayout.LayoutParams(WRAP, dpi(44)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            val remove = FButton(context, "Remove").apply {
                setOnClickListener {
                    Drivers.remove(context, Settings.driver(context))
                    Settings.setDriver(context, Drivers.ZINK)
                    listDrivers()
                }
            }
            val import = FButton(context, "Import").apply { setOnClickListener { pickDriver() } }
            driverButtons += listOf(remove, import)
            addView(remove, LinearLayout.LayoutParams(WRAP, dpi(44)))
            addView(import, LinearLayout.LayoutParams(WRAP, dpi(44)).apply { marginStart = dpi(4) })
        })
        showDialog(panel, 460)
        driverList = list
        driverStatus = status
        listDrivers()
    }

    private fun listDrivers() {
        val list = driverList ?: return
        val chosen = Drivers.chosen(this)
        val drivers = Drivers.all(this)
        list.removeAllViews()
        for (driver in drivers) {
            val text = SpannableStringBuilder(driver.name).apply {
                val start = length
                append("  ").append(driver.detail)
                setSpan(ForegroundColorSpan(0xFF3A3A3A.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan(13, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            list.addView(FButton(this, text, if (driver.id == chosen) Tone.ORANGE else Tone.GREY, size = 15f).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { Settings.setDriver(context, driver.id); listDrivers() }
            }, LinearLayout.LayoutParams(MATCH, dpi(42)))
        }
        (list.parent as View).layoutParams.height = if (drivers.size > 3) dpi(3 * 46 + 12) else WRAP
        driverButtons.firstOrNull()?.isEnabled = chosen != Drivers.ZINK && chosen != Drivers.FREEDRENO
    }

    private fun pickDriver() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, PICK_DRIVER)
    }

    private fun importDriver(uri: Uri) {
        driverButtons.forEach { it.isEnabled = false }
        driverStatus?.text = "Checking the driver…"
        thread(name = "pocketfoundry-driver") {
            val result = runCatching { Drivers.import(this, uri) }
            runOnUiThread {
                driverButtons.forEach { it.isEnabled = true }
                result.onSuccess { Settings.setDriver(this, it.id) }
                driverStatus?.visibility = View.VISIBLE
                driverStatus?.text = result.fold({ "Added ${it.name}." }, { it.message ?: "That didn't work. See Logs." })
                driverStatus?.setTextColor(if (result.isSuccess) Factorio.MUTED else Factorio.ERROR)
                listDrivers()
            }
        }
    }

    private var savesStatus: TextView? = null
    private var savesDetail: TextView? = null
    private val savesButtons = ArrayList<FButton>()

    private fun showSaves() {
        val panel = stack(this, Factorio.PANEL, sunken = false, padding = 8, gap = 10)
        panel.addView(heading(this, "Saves"))
        val inset = stack(this, Factorio.INSET_LIGHT, sunken = true, padding = 12, gap = 6)
        val status = label(this, "", 16f, weight = 600)
        val detail = label(this, "Export puts a copy in Download/Pocket Foundry.", 14f, Factorio.MUTED)
        inset.addView(status)
        inset.addView(detail)
        panel.addView(inset)
        savesButtons.clear()
        panel.addView(LinearLayout(this).apply {
            addView(FButton(context, "Close", Tone.RED, Arrow.LEFT).apply { setOnClickListener { closeDialog() } },
                    LinearLayout.LayoutParams(WRAP, dpi(44)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            val import = FButton(context, "Import").apply { setOnClickListener { pickSaves() } }
            val export = FButton(context, "Export", Tone.GREEN, Arrow.RIGHT).apply { setOnClickListener { exportSaves() } }
            savesButtons += listOf(import, export)
            addView(import, LinearLayout.LayoutParams(WRAP, dpi(44)))
            addView(export, LinearLayout.LayoutParams(WRAP, dpi(44)).apply { marginStart = dpi(4) })
        })
        showDialog(panel, 440)
        savesStatus = status
        savesDetail = detail
        showSaveCount()
    }

    private fun showSaveCount() {
        val count = Saves.all(paths).size
        savesStatus?.text = when (count) { 0 -> "No saves yet"; 1 -> "1 save"; else -> "$count saves" }
        savesButtons.last().isEnabled = count > 0
    }

    private fun savesJob(work: () -> Pair<String, String>) {
        savesButtons.forEach { it.isEnabled = false }
        thread(name = "pocketfoundry-saves") {
            val (status, detail) = runCatching(work).getOrElse { "That didn't work" to (it.message ?: "See Logs.") }
            runOnUiThread {
                savesButtons.forEach { it.isEnabled = true }
                showSaveCount()
                savesStatus?.text = status
                savesDetail?.text = detail
                refresh()
            }
        }
    }

    private fun exportSaves() = savesJob {
        val count = Saves.all(paths).size
        val folder = Saves.export(this)
        (if (count == 1) "Exported 1 save" else "Exported $count saves") to folder
    }

    private fun pickSaves() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, PICK_SAVES)
    }

    private fun importSaves(uris: List<Uri>) = savesJob {
        when (val added = Saves.import(this, uris)) {
            0 -> "Nothing new" to "Those are already here, or aren't Factorio saves."
            1 -> "Added 1 save" to "It's in Load game."
            else -> "Added $added saves" to "They're in Load game."
        }
    }

    private fun pickGame() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, PICK_GAME)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK_GAME && resultCode == RESULT_OK && uri != null) importGame(uri)
        if (requestCode == PICK_DRIVER && resultCode == RESULT_OK && uri != null && dialog != null) importDriver(uri)
        if (requestCode == PICK_SAVES && resultCode == RESULT_OK && dialog != null) {
            val picked = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(uri)
            if (picked.isNotEmpty()) importSaves(picked)
        }
    }

    private fun importGame(uri: Uri) {
        importingNow = true
        settingsOpen = false
        error = null
        importFile.text = displayName(this, uri) ?: "Factorio"
        showImportProgress(0)
        refresh()
        thread(name = "pocketfoundry-import") {
            val failure = runCatching {
                GameImport.import(this, uri) { p -> runOnUiThread { showImportProgress((p * 100).toInt()) } }
            }.exceptionOrNull()
            runOnUiThread {
                if (failure == null) {
                    GuiSprites.reload()
                    showImportProgress(100)
                    // A moment to watch the factory start.
                    importBar.postDelayed({ importingNow = false; refresh() }, 2500)
                } else {
                    importingNow = false
                    error = failure.message ?: "The import failed. See Logs."
                    refresh()
                }
            }
        }
    }

    private fun showImportProgress(percent: Int) {
        importPercent.text = "$percent%"
        importBar.fraction = percent / 100f
        stage?.progress(percent)
    }

    private fun play(save: File?) {
        if (askForNotifications()) {
            pendingSave = save
            pendingPlay = true
            return
        }
        error = null
        busy = "Starting…"
        refresh()
        thread(name = "pocketfoundry-start") {
            val failure = runCatching {
                if (!Runtime.isInstalled(this)) {
                    runOnUiThread { busy = "Getting ready…"; refresh() }
                    Runtime.install(this)
                }
                Session.start(this, if (save != null) listOf("--load-game", save.path) else emptyList())
            }.exceptionOrNull()
            runOnUiThread {
                busy = null
                if (failure != null) error = failure.message ?: "Factorio didn't start. See Logs."
                refresh()
                if (failure == null) showGame()
            }
        }
    }

    /** Asks once: the session's notification carries Quit. True while asking; play resumes on the answer. */
    private fun askForNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        val prefs = getSharedPreferences(Settings.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return false
        prefs.edit().putBoolean("askedNotifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFICATIONS)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_NOTIFICATIONS && pendingPlay) {
            pendingPlay = false
            play(pendingSave)
        }
    }

    companion object {
        private const val PICK_GAME = 1
        private const val PICK_SAVES = 3
        private const val PICK_DRIVER = 4
        private val SPRITE_QUALITIES = listOf("high", "medium")
        private const val ASK_NOTIFICATIONS = 2
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Termux:X11's prefs, set once so the user can still change them. touchMode 3 is Direct touch: taps click where you touch. */
        fun applyDisplayDefaults(context: Context) {
            val prefs = context.getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
            val scales = Settings.resolutionScales(context)
            val saved = Settings.resolutionScale(context)
            if (saved !in scales) Settings.setResolutionScale(context, scales.minBy { abs(it - saved) })
            if (prefs.getBoolean("pocketFoundryDefaults", false)) return
            prefs.edit()
                .putString("displayResolutionMode", "scaled")
                .putInt("displayScale", Settings.defaultResolutionScale(context))
                .putString("forceOrientation", "landscape")
                .putBoolean("fullscreen", true)
                .putBoolean("hideCutout", true)
                .putBoolean("showAdditionalKbd", false)
                .putBoolean("additionalKbdVisible", false)
                .putString("touchMode", "3")
                .putString("screenIdleTimeout", "never")
                .putBoolean("pocketFoundryDefaults", true)
                .apply()
        }
    }
}
