package app.pocketfoundry.ui.screens.drivers

import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.core.drivers.DriverImport
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.drivers.Gpu
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.ui.kit.BUTTON_HEIGHT
import app.pocketfoundry.ui.kit.FButton
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.MATCH
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.WRAP
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.background
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.capped
import app.pocketfoundry.ui.kit.heading
import app.pocketfoundry.ui.kit.immersive
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.link
import app.pocketfoundry.ui.kit.pad
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.shown
import app.pocketfoundry.ui.kit.spacer
import app.pocketfoundry.ui.kit.stack
import app.pocketfoundry.ui.kit.withNote

/**
 * The drivers in sections on the left, the one looked at on the right: Use, what it is, its GPUs, how the last game with
 * it went, and its Options for testing it.
 */
class DriverDialog(private val launcher: LauncherActivity) {
    private val host = launcher.host
    private val list = stack(launcher, Frame.DEEP, padding = LIST_PADDING, gap = ROW_GAP)
    private var rows = emptyMap<String, FButton>()
    private var viewing = ""

    private val name = heading(launcher, "", 22f)
    private val recommended = label(launcher, "Recommended", 12f, Tone.GREEN.dark, 600).apply {
        setBackgroundColor(Tone.GREEN.face)
        pad(6, 2)
    }
    private val detail = label(launcher, "", 14f, Factorio.MUTED)
    private val about = label(launcher, "", 15f)
    private val worksOn = label(launcher, "", 14f)
    private val lastGame = label(launcher, "", 14f)
    // MaliBridge's licence asks for this credit wherever the app shows credits.
    private val credit = link(launcher, "Uses MaliBridge by ASEbrahim", MALIBRIDGE, 13f)
    private val useButton = button(launcher, "Use this driver", Tone.GREEN) { choose(viewing) }
    private val removeButton = button(launcher, "Remove") { remove(viewing) }
    private val options = optionsField()
    private val details = stack(launcher, Frame.DEEP, padding = 10, gap = 8) {
        add(row(launcher) {
            add(name)
            add(recommended, start = 10)
            spacer()
            add(removeButton, height = BUTTON_HEIGHT, start = 8)
            add(useButton, height = BUTTON_HEIGHT, start = 4)
        })
        add(detail)
        add(about)
        add(fact("Works on", worksOn))
        add(fact("Last game", lastGame))
        add(credit)
        add(stack(launcher, Frame.SHALLOW, padding = 4) { add(options) })
    }

    private val status = label(launcher, "Applies when Factorio next starts.", 14f, Factorio.MUTED).capped(2)
    private val importButton = button(launcher, "Import") { launcher.pickFiles { if (host.isOpen) import(it.first()) } }

    fun show() {
        status.shown = Session.inGame
        viewing = Drivers.chosen(launcher)
        val screen = launcher.resources.configuration
        val chrome = CHROME + if (status.shown) STATUS else 0
        val title = Gpu.name?.let { withNote(TITLE, it, Factorio.FAINT, 15, gap = "   ") } ?: TITLE
        host.show(title, minOf(WIDTH, screen.screenWidthDp - MARGIN), "Close", importButton) {
            add(row(launcher, Gravity.TOP) {
                add(pane(list), LIST_WIDTH, MATCH)
                add(pane(details), 0, MATCH, weight = 1f, start = 8)
            }, height = minOf(PANES_HEIGHT, screen.screenHeightDp - chrome))
            add(status)
        }
        listDrivers()
        options.clearFocus()   // the dialog would otherwise open typing in it
    }

    /** A pane that scrolls once [frame]'s content outgrows it; the frame fills it until then. */
    private fun pane(frame: View) = ScrollView(launcher).apply {
        isFillViewport = true
        addView(frame)
    }

    private fun fact(title: String, value: TextView) = row(launcher, Gravity.TOP) {
        add(label(launcher, title, 14f, Factorio.FAINT), FACT_WIDTH, WRAP)
        add(value, 0, WRAP, weight = 1f)
    }

    private fun optionsField() = EditText(launcher).apply {
        hint = "Options  NAME=value …"
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextColor(Factorio.TEXT)
        setHintTextColor(0xFF8A8580.toInt())
        background = null
        pad(horizontal = 8, vertical = 6)
        isSingleLine = true
        // No suggestions, without the password-field trick: some phones type those on a secure keyboard that can't
        // paste. NO_EXTRACT_UI: the dialog moves above the keyboard instead.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                Drivers.setOptions(launcher, viewing, s.toString().trim())
            }
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })
        setOnEditorActionListener { view, _, _ ->
            view.clearFocus()
            launcher.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
            launcher.immersive()
            true
        }
    }

    /** The sections and their rows, which say which driver is in use and which is recommended. */
    private fun listDrivers() {
        val chosen = Drivers.chosen(launcher)
        val best = Drivers.recommended(launcher)
        val drivers = Drivers.all(launcher)
        if (drivers.none { it.id == viewing }) viewing = chosen
        list.removeAllViews()
        rows = buildMap {
            for ((section, members) in Drivers.groups(drivers)) {
                list.add(label(launcher, section, 14f, Factorio.MUTED, 600), start = 4, top = 4)
                for (driver in members) {
                    val note = listOfNotNull("In use".takeIf { driver.id == chosen }, "Recommended".takeIf { driver.id == best })
                        .ifEmpty { listOf(if (Drivers.fits(driver.id)) driver.detail.substringAfter(" · ") else "Needs an Adreno") }
                        .joinToString(" · ")
                    put(driver.id, list.add(button(launcher, withNote(driver.name, note), size = 15f) { view(driver.id) }.apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }, height = ROW))
                }
            }
        }
        view(viewing)
    }

    /** Lights [id]'s row and explains it on the right, without rebuilding the list: focus stays where it is. */
    private fun view(id: String) {
        viewing = id
        rows.forEach { (row, button) -> button.tone = if (row == id) Tone.ORANGE else Tone.GREY }
        val driver = Drivers.find(launcher, id) ?: return
        val inUse = id == Drivers.chosen(launcher)
        name.text = driver.name
        recommended.shown = id == Drivers.recommended(launcher)
        detail.text = driver.detail
        about.text = driver.about.ifEmpty { "A driver you imported." }
        worksOn.text = driver.worksOn.ifEmpty { "The GPUs it was built for" } +
            if (Drivers.fits(id)) "" else ", not your ${Gpu.name}"
        lastGame.text = when (Drivers.lastGame(launcher, id)) {
            Drivers.Outcome.RAN -> "Ran fine"
            Drivers.Outcome.STOPPED -> "Stopped unexpectedly"
            Drivers.Outcome.DIDNT_START -> "Didn't start"
            null -> "Not tried yet"
        }
        credit.shown = Drivers.isPhone(id)
        options.setText(Drivers.options(launcher, id))
        useButton.text = if (inUse) "In use" else "Use this driver"
        useButton.isEnabled = !inUse
        removeButton.shown = driver.imported
    }

    private fun choose(id: String) {
        Drivers.choose(launcher, id)
        listDrivers()
        launcher.refresh()
    }

    /** Removing the driver in use hands the choice back to the app. */
    private fun remove(id: String) {
        if (id == Drivers.chosen(launcher)) Drivers.choose(launcher, null)
        Drivers.remove(launcher, id)
        viewing = ""
        listDrivers()
        launcher.refresh()
    }

    private fun import(uri: Uri) {
        removeButton.isEnabled = false
        importButton.isEnabled = false
        // Shown even out of a game, where the status is otherwise hidden.
        status.text = "Checking the driver…"
        status.setTextColor(Factorio.MUTED)
        status.shown = true
        background("driver", { DriverImport.import(launcher, uri) }) { result ->
            importButton.isEnabled = true
            removeButton.isEnabled = true
            status.text = result.fold({ "Added ${it.name}." }, { it.message ?: "That didn't work. See Logs." })
            status.setTextColor(if (result.isSuccess) Factorio.MUTED else Factorio.ERROR)
            result.onSuccess { viewing = it.id; choose(it.id) }.onFailure { listDrivers() }
        }
    }

    private companion object {
        const val TITLE = "Graphics driver"
        const val MALIBRIDGE = "https://github.com/ASEbrahim/MaliBridge"
        const val WIDTH = 880
        const val MARGIN = 32           // beside the dialog on a narrow screen
        const val LIST_WIDTH = 330
        const val PANES_HEIGHT = 330
        const val CHROME = 130          // the dialog's frame, title and button bar around the panes
        const val STATUS = 26           // the status line under the panes, in a game
        const val FACT_WIDTH = 84
        const val ROW = 40
        const val ROW_GAP = 4
        const val LIST_PADDING = 6
    }
}
