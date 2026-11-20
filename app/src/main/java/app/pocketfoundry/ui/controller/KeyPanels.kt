package app.pocketfoundry.ui.controller

import android.app.Activity
import android.graphics.Canvas
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import app.pocketfoundry.ui.kit.DialogHost
import app.pocketfoundry.ui.kit.Dropdown
import app.pocketfoundry.ui.kit.FButton
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Frame
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.checkBox
import app.pocketfoundry.ui.kit.dpi
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.pad
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.stack

/**
 * Mouse & keys' editor's dialogs over the game, the launcher's own (DialogHost): a keyboard to pick a key from, a key's
 * options, a set's name. While one is up, touches and Back are its (InputRouter). [onChange]: the layout changed, to
 * draw it again; also when a dialog closes.
 */
class KeyPanels(private val activity: Activity, private val keys: PadLayout, private val painter: PadPainter,
                private val onChange: () -> Unit) {
    private val host = DialogHost(activity, onChange)

    val open get() = host.isOpen

    fun close() {
        if (host.isOpen) host.close()
    }

    /**
     * A keyboard, laid out as one. Ctrl, Shift and Alt light up when tapped: the key tapped next comes with them;
     * Use takes them alone. [onPicked] gets the codes, the modifiers first.
     */
    fun pickKey(title: String, onPicked: (IntArray) -> Unit) {
        close()
        val held = LinkedHashSet<Int>()
        val sofar = label(activity, "", 15f, Factorio.MUTED)
        val use = button(activity, "Use", Tone.GREEN) {
            if (held.isEmpty()) return@button
            host.close()
            onPicked(held.sortedBy { KeyNames.MODIFIERS.indexOf(it) }.toIntArray())
        }
        fun update() {
            sofar.text = if (held.isEmpty()) "" else KeyNames.label(held.toIntArray()) + "+…"
            use.isEnabled = held.isNotEmpty()
        }
        update()
        val unit = activity.dpi(KEY_UNIT_DP)
        val gap = activity.dpi(KEY_GAP_DP)
        host.show(title, PICKER_DP, "Cancel", use, titleEnd = sofar) {
            add(stack(activity, Frame.DEEP, padding = 6, gap = KEY_GAP_DP) {
                for (keyRow in KeyNames.KEYBOARD) {
                    add(row(activity) {
                        for ((code, units) in keyRow) {
                            val params = LinearLayout.LayoutParams((units * unit).toInt() - gap, activity.dpi(KEY_HEIGHT_DP))
                            params.marginEnd = gap
                            if (code == 0) {
                                addView(View(activity), params)
                                continue
                            }
                            val name = KeyNames.name(code)
                            val key = button(activity, name, size = if (name.length > 4 && units < 1.5f) 11f else 13f) {}.apply {
                                maxLines = 1
                                setPadding(0, 0, 0, 0)
                            }
                            key.setOnClickListener {
                                if (code in KeyNames.MODIFIERS) {
                                    if (!held.remove(code)) held += code
                                    key.tone = if (code in held) Tone.ORANGE else Tone.GREY
                                    update()
                                } else {
                                    host.close()
                                    onPicked(KeyNames.withModifiers(held, code))
                                }
                            }
                            addView(key, params)
                        }
                    })
                }
            })
        }
    }

    /** What [c] sends, how wide it is, whether it stays held and which set it brings, the key itself at the top. */
    fun options(c: Control) {
        close()
        val preview = KeyPreview(c)
        fun changed() {
            preview.requestLayout()
            preview.invalidate()
            onChange()
        }
        val sends = dropdownLike(if (c.codes.isEmpty()) "Only its set" else KeyNames.label(c.codes)) {
            pickKey("Change ${keys.label(c)}") { codes ->
                c.codes = codes
                options(c)
            }
        }
        val width = Dropdown(activity, Keys.WIDTHS.map { "${Keys.widthName(it)}u" }) {
            c.units = Keys.WIDTHS[it]
            changed()
        }.apply { selected = Keys.WIDTHS.indexOfFirst { it == c.units }.coerceAtLeast(0) }
        val sets = keys.setKeys
        val shows = Dropdown(activity, listOf("No set") + sets.map(keys::title)) {
            c.shows = if (it == 0) null else sets[it - 1]
            changed()
        }.apply { selected = c.shows?.let { sets.indexOf(it) + 1 } ?: 0 }
        val held = checkBox(activity, c.latch) {
            c.latch = it
            changed()
        }
        val remove = button(activity, "Remove") {
            keys.remove(c)
            host.close()
        }
        host.show(keys.label(c).ifEmpty { c.name }, OPTIONS_DP, "Close", remove, titleEnd = preview) {
            add(stack(activity, Frame.DEEP, padding = 10, gap = 6) {
                add(setting("Sends", sends))
                add(setting("Width", width))
                add(setting("Stays held", held, wide = false))
                add(setting("Brings the keys of", shows))
            })
        }
    }

    /** A set the player made: its name, as it's typed, or the set gone. */
    fun set(key: String) {
        close()
        val name = EditText(activity).apply {
            setText(keys.title(key))
            setSelectAllOnFocus(true)
            textSize = 17f
            setTextColor(Factorio.TEXT)
            background = null
            pad(horizontal = 8, vertical = 6)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, _, _ -> host.close(); true }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) = keys.rename(key, s.toString())
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            })
        }
        val delete = button(activity, "Delete set", Tone.RED) {
            keys.removeSet(key)
            host.close()
        }
        host.show("Set", SET_DP, "Close", delete) {
            add(label(activity, "Name", 15f, Factorio.MUTED), bottom = 4)
            add(stack(activity, Frame.SHALLOW, padding = 4) { add(name) })
        }
        name.requestFocus()
        name.post { activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(name, 0) }
    }

    /** A button that looks as a dropdown does: its value and a caret, a list or a keyboard behind it. */
    private fun dropdownLike(text: String, onClick: () -> Unit) = FButton(activity, text, size = 15f).apply {
        caret = true
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        setPadding(activity.dpi(14), 0, activity.dpi(30), 0)
        setOnClickListener { onClick() }
    }

    /** As Settings' rows: the name, then the control, all of a width. */
    private fun setting(title: String, control: View, wide: Boolean = true) = row(activity) {
        add(label(activity, title, 15f), width = 0, weight = 1f)
        add(control, if (wide) CONTROL_DP else LinearLayout.LayoutParams.WRAP_CONTENT, CONTROL_HEIGHT_DP)
    }

    /** The key a dialog is about, as it is on screen. */
    private inner class KeyPreview(private val c: Control) : View(activity) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val h = activity.dpi(PREVIEW_DP)
            setMeasuredDimension((h * c.units).toInt() + activity.dpi(6), h)
        }

        override fun onDraw(canvas: Canvas) {
            val r = height * 0.47f
            if (painter.preview(canvas, c, width / 2f, height / 2f, r, SystemClock.uptimeMillis())) postInvalidateOnAnimation()
        }
    }

    private companion object {
        /** In dp: the keyboard's unit, a key's height and the gap between keys. */
        const val KEY_UNIT_DP = 41
        const val KEY_HEIGHT_DP = 32
        const val KEY_GAP_DP = 2
        /** The dialogs' widths, in dp: the keyboard as wide as the phone, the others narrower. */
        const val PICKER_DP = 820
        const val OPTIONS_DP = 520
        const val SET_DP = 380
        const val CONTROL_DP = 190
        const val CONTROL_HEIGHT_DP = 34
        const val PREVIEW_DP = 52
    }
}
