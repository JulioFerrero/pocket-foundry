package app.pocketfoundry.ui.screens.launcher

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.drivers.Catalog
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.game.GameEnding
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.saves.Saves
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.background
import app.pocketfoundry.ui.kit.Arrow
import app.pocketfoundry.ui.kit.BUTTON_HEIGHT
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Fitting
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.MATCH
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.WRAP
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.capped
import app.pocketfoundry.ui.kit.column
import app.pocketfoundry.ui.kit.groove
import app.pocketfoundry.ui.kit.heading
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.pad
import app.pocketfoundry.ui.kit.panel
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.spacer
import app.pocketfoundry.ui.kit.stack
import app.pocketfoundry.ui.kit.withNote
import app.pocketfoundry.ui.screens.saves.SavesDialog
import app.pocketfoundry.ui.screens.saves.whenSaved
import app.pocketfoundry.ui.screens.saves.whenSavedShort
import java.io.File

/** The main menu, and the newest save's card. While a game runs, it's the pause menu. */
class HomeScreen(private val launcher: LauncherActivity) {
    private val paths = Paths(launcher)
    private val title = heading(launcher, "Pocket Foundry")
    private val continueSave = button(launcher, "Continue", Tone.GREEN, size = 17f) { Saves.newest(paths)?.let(launcher::play) }
    private val resume = button(launcher, "Resume", Tone.GREEN, size = 17f, onClick = launcher::showGame)
    private val mainMenu = button(launcher, "Main menu") { launcher.play(null) }
    private val settings = button(launcher, "Settings") { launcher.showSettings(true) }
    private val logs = button(launcher, "Logs") { launcher.startActivity(Intent(launcher, LogActivity::class.java)) }
    private val quit = button(launcher, "Quit", Tone.RED, Arrow.LEFT) { confirmQuit() }
    private val groove = groove(launcher)
    private val footerLeft = label(launcher, "", 15f, weight = 700).capped(2)
    private val footerRight = label(launcher, "", 15f, weight = 700)
    /** After a game the driver failed: the Logs, copied into a report (LogActivity). */
    private val report = button(launcher, "Report it") {
        launcher.startActivity(Intent(launcher, LogActivity::class.java).putExtra(LogActivity.REPORT, true))
    }
    /** Only when it has something to say: why the last game failed, or when this one last saved. */
    private val footer = stack(launcher, Frame.SHALLOW) {
        add(row(launcher) {
            pad(horizontal = 8, vertical = 6)
            add(footerLeft, width = 0, weight = 1f)
            add(footerRight)
        })
    }
    private val card = SaveCard(launcher)
    /** The saves after Continue's, as many as the menu has room for, and the way to all of them. */
    private val recentSaves = Fitting(launcher)
    private val recentTitle = label(launcher, "Recent", 13f, Factorio.MUTED, 600)
    private val recent = column(launcher, gap = 2) {
        // The whole row opens them: a word alone was too small to hit.
        add(row(launcher) {
            add(recentTitle, width = 0, weight = 1f)
            add(label(launcher, "All saves ›", 13f, Factorio.LINK, 600))
        }.apply {
            pad(6, 3)
            background = pressable()
            setOnClickListener { SavesDialog(launcher).show() }
        }, top = 2)
        add(recentSaves, height = 0, weight = 1f)
    }
    private var recentOf: List<String>? = null
    private lateinit var inGameGap: View

    val view: View = FrameLayout(launcher).apply {
        add(panel(launcher, title) {
            add(stack(launcher, Frame.DEEP, padding = 8, gap = 4) {
                for (button in listOf(continueSave, resume)) add(button, height = BUTTON_HEIGHT)
                add(groove, height = 3)
                for (button in listOf(mainMenu, settings, logs, report)) add(button, height = 40)
                add(recent, height = 0, weight = 1f)
                inGameGap = spacer()
                add(quit, WRAP, 40)
            }, height = 0, weight = 1f)
            add(footer)
        }, 288, MATCH, start = 24, top = 16, bottom = 16)
        add(card.view, 256, WRAP, Gravity.END or Gravity.BOTTOM, end = 24, bottom = 16)
    }

    /** [busy] stands in for the title while a game starts. [error] is why the last start or import failed. */
    fun update(busy: String?, error: String?) {
        val inGame = Session.inGame
        val save = Saves.newest(paths)
        title.text = busy ?: when (Session.state) {
            Session.State.QUITTING -> "Quitting…"
            Session.State.RUNNING, Session.State.PAUSED -> "Paused"
            Session.State.STOPPED -> "Pocket Foundry"
        }
        continueSave.shown = !inGame && save != null
        if (save != null) continueSave.text = withNote("Continue  ", save.nameWithoutExtension, Tone.GREEN.dark, 14, gap = "")
        resume.shown = inGame
        quit.shown = inGame
        groove.shown = inGame || save != null
        mainMenu.shown = !inGame
        val idle = busy == null
        for (b in listOf(continueSave, mainMenu, settings)) b.isEnabled = idle
        for (b in listOf(resume, quit)) b.isEnabled = idle && Session.live
        card.update(save, inGame)
        recent.shown = !inGame
        inGameGap.shown = inGame
        if (!inGame) showRecent(Saves.all(paths).drop(1), idle)

        val ending = Session.lastGame?.takeIf { error == null && !inGame }
        val failure = error ?: ending?.let {
            val exit = it.exit ?: return@let it.closedBy
            when {
                it.noWindow -> driverDidntStart(it.driver)
                it.failure != null -> driverFailed(it)
                else -> it.gameError ?: "Factorio stopped unexpectedly (${exit.status}). See Logs."
            }
        }
        report.shown = ending?.failure != null || ending?.closedBy != null
        footerLeft.setTextColor(if (failure != null) Factorio.ERROR else Factorio.TEXT)
        footerLeft.textSize = if (failure != null) 13f else 15f
        footer.shown = failure != null || inGame
        val (left, right) = when {
            failure != null -> failure to ""
            inGame -> "Last saved" to (save?.let { savedAgo(it, short = true) } ?: "Never")
            else -> "" to ""
        }
        footerLeft.text = left
        footerRight.text = right
    }

    /**
     * What the driver did and when, and what's next: the app's own choice has moved on (Drivers.recommended), a
     * player's own stays theirs to change.
     */
    private fun driverFailed(ending: Session.Ending): String {
        val failure = ending.failure ?: return "The graphics driver failed. See Logs."
        val what = GameEnding.sentence(Drivers.find(launcher, ending.driver)?.name ?: "The graphics driver",
                                       failure, ending.stage)
        val next = Drivers.chosen(launcher)
        if (next != ending.driver) return "$what Next game: ${Drivers.find(launcher, next)?.name ?: next}."
        val gles = Drivers.all(launcher).firstOrNull { it.entry?.kind == Catalog.Kind.GLES && it.id != next }
        return if (gles != null) "$what Try ${gles.name}: Settings -> Driver." else "$what See Logs."
    }

    /** Which driver didn't start, and the one the next game moves on to when the choice is the app's. */
    private fun driverDidntStart(driver: String): String {
        val next = Drivers.chosen(launcher).takeIf { it != driver } ?: return "The graphics driver didn't start. See Logs."
        return "${Drivers.find(launcher, driver)?.name} didn't start. Next game: ${Drivers.find(launcher, next)?.name}."
    }

    private fun showRecent(saves: List<File>, idle: Boolean) {
        recentTitle.text = if (saves.isEmpty()) "Saves" else "Recent"
        val key = saves.map(Saves::previewKey) + idle.toString()
        if (key == recentOf) return
        recentOf = key
        recentSaves.removeAllViews()
        for (save in saves.take(RECENT_MOST)) recentSaves.addView(recentRow(save, idle))
    }

    /** A save to load with one tap: its name, and when. */
    private fun recentRow(save: File, idle: Boolean) = row(launcher) {
        add(label(launcher, save.nameWithoutExtension, 14f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, width = 0, weight = 1f)
        add(label(launcher, whenSavedShort(launcher, save), 13f, Factorio.FAINT), start = 8)
    }.apply {
        pad(6, 3)
        background = pressable()
        isEnabled = idle
        alpha = if (idle) 1f else 0.5f
        setOnClickListener { launcher.play(save) }
    }

    /** Lights up while pressed, as Home's buttons do. */
    private fun pressable() = RippleDrawable(ColorStateList.valueOf(Factorio.INSET_LIGHT), null, ColorDrawable(Color.WHITE))

    private fun confirmQuit() {
        val save = Saves.newest(paths)
        val host = launcher.host
        val quit = button(launcher, "Quit", Tone.GREEN, Arrow.RIGHT) {
            host.close()
            Session.quit()
        }
        host.show("Quit Factorio?", 440, "Cancel", quit) {
            add(stack(launcher, Frame.SHALLOW, padding = 12, gap = 6) {
                add(label(launcher, save?.let { "Last saved " + savedAgo(it) } ?: "Nothing has been saved yet.", 16f, weight = 600))
                add(label(launcher, if (save != null) "Anything after that is lost." else "This game's progress is lost.", 14f, Factorio.MUTED))
            })
        }
    }
}

private const val RECENT_MOST = 6

/** Home's card for the newest save, with the picture the game took. */
private class SaveCard(private val context: Context) {
    private val name = heading(context, "", 17f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val time = label(context, "", 13f, Factorio.MUTED)
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val frame = stack(context, Frame.SHALLOW) { add(image, height = 118) }
    /** The save and its date: a preview shown is still this save's while they match. */
    private var previewOf: String? = null

    val view: View = stack(context, Frame.PANEL, padding = 4, gap = 6) {
        add(row(context) {
            pad(horizontal = 2, vertical = 0)
            add(name, width = 0, weight = 1f)
            add(time)
        })
        add(frame)
    }

    fun update(save: File?, inGame: Boolean) {
        view.shown = save != null
        if (save == null) return
        name.text = save.nameWithoutExtension
        time.text = if (inGame) "Paused" else whenSaved(context, save)
        val key = Saves.previewKey(save)
        if (key == previewOf) return
        previewOf = key
        image.setImageDrawable(null)
        background("preview", { Saves.preview(save, sample = 3) }) { result ->
            if (previewOf != key) return@background
            val bitmap = result.getOrNull()
            image.setImageBitmap(bitmap)
            // Saves from servers often carry no picture.
            frame.shown = bitmap != null
        }
    }
}

/** "5 minutes ago", or with [short] "5 min. ago". */
private fun savedAgo(save: File, short: Boolean = false): CharSequence {
    val now = System.currentTimeMillis()
    return if (short) DateUtils.getRelativeTimeSpanString(save.lastModified(), now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    else DateUtils.getRelativeTimeSpanString(save.lastModified(), now, DateUtils.MINUTE_IN_MILLIS)
}
