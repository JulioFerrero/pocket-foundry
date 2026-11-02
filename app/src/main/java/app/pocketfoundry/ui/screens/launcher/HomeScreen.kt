package app.pocketfoundry.ui.screens.launcher

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.saves.Saves
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.ui.kit.*
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The main menu, and the newest save's card. While a game runs, it's the pause menu. */
class HomeScreen(private val launcher: LauncherActivity) {
    private val title = heading(launcher, "Pocket Foundry")
    private val continueSave = button(launcher, "Continue", Tone.GREEN, size = 17f) { Saves.newest(launcher.paths)?.let(launcher::play) }
    private val resume = button(launcher, "Resume", Tone.GREEN, size = 17f, onClick = launcher::showGame)
    private val mainMenu = button(launcher, "Main menu") { launcher.play(null) }
    private val settings = button(launcher, "Settings") { launcher.showSettings(true) }
    private val logs = button(launcher, "Logs") { launcher.startActivity(Intent(launcher, LogActivity::class.java)) }
    private val quit = button(launcher, "Quit", Tone.RED, Arrow.LEFT) { confirmQuit() }
    private val groove = groove(launcher)
    private val footerLeft = label(launcher, "", 15f, weight = 700)
    private val footerRight = label(launcher, "", 15f, weight = 700)
    private val card = SaveCard(launcher)

    val view: View = FrameLayout(launcher).apply {
        add(panel(launcher, title) {
            add(stack(launcher, Frame.DEEP, padding = 8, gap = 6) {
                for (button in listOf(continueSave, resume)) add(button, height = BUTTON_HEIGHT)
                add(groove, height = 3)
                for (button in listOf(mainMenu, settings, logs)) add(button, height = 40)
                spacer()
                add(quit, WRAP, 40)
            }, height = 0, weight = 1f)
            add(stack(launcher, Frame.SHALLOW) {
                add(row(launcher) {
                    pad(horizontal = 8, vertical = 6)
                    add(footerLeft, width = 0, weight = 1f)
                    add(footerRight)
                })
            })
        }, 288, MATCH, start = 24, top = 16, bottom = 16)
        add(card.view, 256, WRAP, Gravity.END or Gravity.BOTTOM, end = 24, bottom = 16)
    }

    /** [busy] stands in for the title while a game starts. [error] is why the last start or import failed. */
    fun update(version: String, busy: String?, error: String?) {
        val inGame = Session.inGame
        val save = Saves.newest(launcher.paths)
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

        val failure = error ?: Session.lastFailure?.let { status ->
            when {
                Drivers.missingForGpu(launcher) -> "No driver for this phone's GPU yet: choose CPU in Driver."
                Session.noWindow -> "The graphics driver didn't start. See Logs."
                else -> "Factorio stopped unexpectedly ($status). See Logs."
            }
        }
        footerLeft.setTextColor(if (failure != null) Factorio.ERROR else Factorio.TEXT)
        footerLeft.textSize = if (failure != null) 13f else 15f
        val (left, right) = when {
            failure != null -> failure to ""
            inGame -> "Last saved" to (save?.let { savedAgo(it, short = true) } ?: "Never")
            else -> "Factorio" to version
        }
        footerLeft.text = left
        footerRight.text = right
    }

    /** Quitting a running game, with what's lost: everything since the last save. */
    private fun confirmQuit() {
        val save = Saves.newest(launcher.paths)
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

/** Home's card for the newest save: its name, when it was saved, and the picture the game took. */
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
        time.text = if (inGame) "Paused" else whenSaved(save.lastModified())
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

    private fun whenSaved(millis: Long): String {
        val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val clock = DateFormat.getTimeFormat(context).format(millis)
        return when (time.toLocalDate()) {
            LocalDate.now() -> "Today $clock"
            LocalDate.now().minusDays(1) -> "Yesterday $clock"
            else -> time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }
}

/** "5 minutes ago", or with [short] "5 min. ago". */
private fun savedAgo(save: File, short: Boolean = false): CharSequence {
    val now = System.currentTimeMillis()
    return if (short) DateUtils.getRelativeTimeSpanString(save.lastModified(), now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    else DateUtils.getRelativeTimeSpanString(save.lastModified(), now, DateUtils.MINUTE_IN_MILLIS)
}
