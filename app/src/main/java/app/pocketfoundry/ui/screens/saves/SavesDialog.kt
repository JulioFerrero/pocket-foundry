package app.pocketfoundry.ui.screens.saves

import android.graphics.Color
import android.net.Uri
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.saves.Saves
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.background
import app.pocketfoundry.ui.kit.Arrow
import app.pocketfoundry.ui.kit.ButtonDrawable
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.MATCH
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.WRAP
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.capped
import app.pocketfoundry.ui.kit.heading
import app.pocketfoundry.ui.kit.keepScrollBar
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.pad
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.screenWidthDp
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.stack
import java.io.File

/**
 * The game's saves, as its own Load game shows them: the list, newest first, and the one looked at with its picture,
 * to load, export or delete. Saves come in through Import.
 */
class SavesDialog(private val launcher: LauncherActivity) {
    private val host = launcher.host
    private val paths = Paths(launcher)
    private val list = stack(launcher, Frame.DEEP, padding = LIST_PADDING, gap = ROW_GAP)
    private var rows = emptyMap<File, View>()
    private var viewing: File? = null

    private val name = heading(launcher, "", 20f).capped(1)
    private val facts = label(launcher, "", 14f, Factorio.MUTED).capped(2)
    private val image = ImageView(launcher).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val picture = stack(launcher, Frame.SHALLOW) { add(image, height = MATCH) }
    private val noPicture = label(launcher, "This save has no picture.", 14f, Factorio.FAINT)
    /** The save and its date: a picture shown is still this save's while they match. */
    private var pictureOf: String? = null
    private val status = label(launcher, "", 14f, Factorio.MUTED).capped(2)

    private val importButton = button(launcher, "Import") {
        launcher.pickFiles(Saves.MIME_TYPES, multiple = true) { if (host.isOpen) import(it) }
    }
    private val exportButton = button(launcher, "Export") { viewing?.let(::export) }
    private val deleteButton = button(launcher, "Delete") { viewing?.let(::confirmDelete) }
    private val benchmarkButton = button(launcher, "Benchmark") {
        viewing?.let { save -> BenchmarkDialog(launcher).show(save) { show(save) } }
    }
    private val loadButton = button(launcher, "Load", Tone.GREEN, Arrow.RIGHT) { viewing?.let(::load) }

    fun show(select: File? = null) {
        host.show("Saves", minOf(WIDTH, launcher.screenWidthDp - MARGIN), "Close", importButton, exportButton,
                  deleteButton, benchmarkButton, loadButton) {
            add(row(launcher, Gravity.TOP) {
                add(ScrollView(launcher).apply {
                    isFillViewport = true
                    keepScrollBar()
                    addView(list)
                }, LIST_WIDTH, MATCH)
                add(stack(launcher, Frame.DEEP, padding = 10, gap = 4) {
                    add(name)
                    add(facts)
                    add(picture, height = 0, weight = 1f, top = 6)
                    add(noPicture, top = 6)
                }, 0, MATCH, weight = 1f, start = 8)
            }, height = PANES_HEIGHT, weight = 1f)
            add(status)
        }
        status.shown = false
        listSaves(select)
    }

    private fun listSaves(select: File? = viewing) {
        val saves = Saves.all(paths)
        list.removeAllViews()
        if (saves.isEmpty()) list.add(label(launcher, "No saves yet. Import brings in saves from elsewhere.", 14f,
                                            Factorio.MUTED), start = 6, top = 6)
        rows = saves.associateWith { save -> list.add(saveRow(save)) }
        view(select?.takeIf { it in rows } ?: saves.firstOrNull())
    }

    /** One button: the save's name, and when it was made at the end. */
    private fun saveRow(save: File) = row(launcher) {
        add(label(launcher, save.nameWithoutExtension, 15f, Color.BLACK, 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, 0, WRAP, weight = 1f)
        add(label(launcher, whenSavedShort(launcher, save), 13f, ROW_NOTE, 600), start = 8)
    }.apply {
        background = ButtonDrawable(launcher, Tone.GREY)
        isClickable = true
        isFocusable = true
        pad(ROW_SIDE, ROW_PAD)
        setOnClickListener { view(save) }
    }

    private fun view(save: File?) {
        viewing = save
        rows.forEach { (file, row) -> (row.background as ButtonDrawable).tone = if (file == save) Tone.ORANGE else Tone.GREY }
        for (b in listOf(exportButton, deleteButton)) b.isEnabled = save != null
        // A save can't be loaded, or benchmarked, beside the game that's running.
        loadButton.isEnabled = save != null && !Session.inGame
        benchmarkButton.isEnabled = loadButton.isEnabled
        name.text = save?.nameWithoutExtension.orEmpty()
        facts.text = save?.let { "Saved ${whenSaved(launcher, it)} · ${Formatter.formatShortFileSize(launcher, it.length())}" }
            .orEmpty()
        showPicture(save)
    }

    private fun showPicture(save: File?) {
        val key = save?.let(Saves::previewKey)
        if (key == pictureOf) return
        pictureOf = key
        image.setImageDrawable(null)
        picture.shown = save != null
        noPicture.shown = false
        if (save == null) return
        background("preview", { Saves.preview(save, sample = 2) }) { result ->
            if (pictureOf != key) return@background
            val bitmap = result.getOrNull()
            image.setImageBitmap(bitmap)
            // Saves from servers often carry no picture.
            picture.shown = bitmap != null
            noPicture.shown = bitmap == null
        }
    }

    private fun load(save: File) {
        host.close()
        launcher.play(save)
    }

    private fun confirmDelete(save: File) {
        val delete = button(launcher, "Delete", Tone.RED) {
            host.close()
            val gone = save.delete()
            show()
            say(if (gone) "Deleted ${save.nameWithoutExtension}." else "Couldn't delete ${save.nameWithoutExtension}.")
        }
        host.show("Delete ${save.nameWithoutExtension}?", 440, "Cancel", delete, onBack = { show(save) }) {
            add(stack(launcher, Frame.SHALLOW, padding = 12, gap = 6) {
                add(label(launcher, "Saved ${whenSaved(launcher, save)}", 16f, weight = 600))
                add(label(launcher, "It's gone for good, unless it was exported first.", 14f, Factorio.MUTED))
            })
        }
    }

    private fun say(text: String) {
        status.text = text
        status.shown = true
    }

    /** [work] says what came of it. */
    private fun job(work: () -> String) {
        for (b in listOf(importButton, exportButton, deleteButton, benchmarkButton, loadButton)) b.isEnabled = false
        background("saves", work) { result ->
            importButton.isEnabled = true
            listSaves()
            say(result.getOrElse { "That didn't work: ${it.message ?: "see Logs"}." })
        }
    }

    private fun export(save: File) = job { "Exported ${save.nameWithoutExtension} to ${Saves.export(launcher, listOf(save))}." }

    private fun import(uris: List<Uri>) = job {
        when (val added = Saves.import(launcher, uris)) {
            0 -> "Nothing new: those are already here, or aren't Factorio saves."
            1 -> "Added 1 save."
            else -> "Added $added saves."
        }
    }

    private companion object {
        const val WIDTH = 820
        const val MARGIN = 32           // beside the dialog on a narrow screen
        const val LIST_WIDTH = 320
        const val PANES_HEIGHT = 300
        const val ROW_GAP = 3
        const val ROW_SIDE = 12
        const val ROW_PAD = 10
        const val LIST_PADDING = 6
        val ROW_NOTE = 0xFF3A3A3A.toInt()
    }
}
