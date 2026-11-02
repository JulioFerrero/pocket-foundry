package app.pocketfoundry.ui.screens.drivers

import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import app.pocketfoundry.core.drivers.DriverImport
import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.game.Session
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.LauncherActivity
import app.pocketfoundry.ui.kit.*

/** The drivers to choose from, the chosen one lit, and the Options field for testing them. */
class DriverDialog(private val launcher: LauncherActivity) {
    private val host = launcher.host
    private val settings = Settings(launcher)
    private val list = stack(launcher, Frame.DEEP, padding = LIST_PADDING, gap = ROW_GAP)
    private val scroll = ScrollView(launcher).apply { addView(list) }
    private val status = label(launcher, "Applies when Factorio next starts.", 14f, Factorio.MUTED)
    private val removeButton = button(launcher, "Remove") {
        settings.driver?.let { Drivers.remove(launcher, it) }
        choose(null)
    }
    private val importButton = button(launcher, "Import") { launcher.pickFiles { if (host.isOpen) import(it.first()) } }

    fun show() {
        val field = optionsField()
        status.shown = Session.inGame
        host.show("Graphics driver", 460, "Close", removeButton, importButton) {
            add(scroll)
            add(stack(launcher, Frame.DEEP, padding = 6) { add(field) })
            add(status)
        }
        field.clearFocus()   // the dialog would otherwise open typing in it
        listDrivers()
    }

    private fun optionsField() = EditText(launcher).apply {
        setText(settings.driverOptions)
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
                settings.driverOptions = s.toString().trim()
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

    private fun listDrivers() {
        val chosen = Drivers.chosen(launcher)
        val drivers = Drivers.all(launcher)
        list.removeAllViews()
        for (driver in drivers) {
            val tone = if (driver.id == chosen) Tone.ORANGE else Tone.GREY
            list.add(button(launcher, withNote(driver.name, driver.detail), tone, size = 15f) { choose(driver.id) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, height = ROW)
        }
        // MAX_VISIBLE rows in their frame; more scroll.
        val tallest = MAX_VISIBLE * ROW + (MAX_VISIBLE - 1) * ROW_GAP + 2 * (LIST_PADDING + FRAME_BORDER)
        scroll.layoutParams.height = if (drivers.size > MAX_VISIBLE) launcher.dpi(tallest) else WRAP
        removeButton.isEnabled = Drivers.imported(launcher)
    }

    private fun choose(id: String?) {
        Drivers.choose(launcher, id)
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
            status.text = result.fold({ "Added ${it.name}." }, { it.message ?: "That didn't work. See Logs." })
            status.setTextColor(if (result.isSuccess) Factorio.MUTED else Factorio.ERROR)
            result.onSuccess { choose(it.id) }.onFailure { listDrivers() }
        }
    }

    private companion object {
        const val MAX_VISIBLE = 3
        const val ROW = 42
        const val ROW_GAP = 4
        const val LIST_PADDING = 6
    }
}
