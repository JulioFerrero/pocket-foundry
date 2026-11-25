package app.pocketfoundry.ui.controller

import android.app.Activity
import android.graphics.Canvas
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
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
import app.pocketfoundry.ui.kit.dp
import app.pocketfoundry.ui.kit.dpi
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.pad
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.stack
import kotlin.math.floor
import kotlin.math.roundToInt

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
     * A keyboard, laid out as one, its keys the keycaps on screen. Ctrl, Shift and Alt light up when tapped: the key
     * tapped next comes with them; Use takes them alone. [onPicked] gets the codes, the modifiers first.
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
        val keyboard = Keyboard(held) { code ->
            if (code in KeyNames.MODIFIERS) {
                if (!held.remove(code)) held += code
                update()
            } else {
                host.close()
                onPicked(KeyNames.withModifiers(held, code))
            }
        }
        host.show(title, PICKER_DP, "Cancel", use, titleEnd = sofar) {
            add(stack(activity, Frame.DEEP, padding = 8) { add(keyboard) })
        }
    }

    /** A key of the picker's keyboard: [left] and [units] in keyboard units, on [row]. */
    private class Placed(val control: Control, val left: Float, val row: Int, val units: Float) {
        val code get() = control.codes[0]
    }

    // Made once: the sprites follow a key's press by its object.
    private val pickerKeys = HashMap<Int, Control>()

    /** The picker's keyboard (KeyNames.KEYBOARD): [held]'s keys lit as a held key is; a tap gives [onKey] its code. */
    private inner class Keyboard(private val held: Set<Int>, private val onKey: (Int) -> Unit) : View(activity) {
        private val unit = activity.dp(KEY_UNIT_DP)
        private val keys = KeyNames.KEYBOARD.flatMapIndexed { row, codes ->
            var x = 0f
            codes.mapNotNull { (code, units) ->
                val left = x
                x += units
                if (code == 0) null
                else Placed(pickerKeys.getOrPut(code) { Keys.own("pick-$code", intArrayOf(code), units) }, left, row, units)
            }
        }
        private val wide = KeyNames.KEYBOARD.maxOf { row -> row.sumOf { it.second.toDouble() } }.toFloat()
        private var down: Placed? = null

        override fun onMeasure(widthSpec: Int, heightSpec: Int) =
            setMeasuredDimension((wide * unit).roundToInt(), (KeyNames.KEYBOARD.size * unit).roundToInt())

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            var moving = false
            for (k in keys) {
                val x = (k.left + k.units / 2) * unit
                val y = (k.row + 0.5f) * unit
                if (painter.preview(canvas, k.control, x, y, unit / 2, now, k.units, held = k.code in held || k === down)) moving = true
            }
            if (moving) postInvalidateOnAnimation()
            // A width not on screen loads now: drawn plain meanwhile, then again as a cap.
            else if (keys.any { !painter.sprites.hasCap(it.control.light, it.units) }) postInvalidateDelayed(LOADING_MS)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val row = floor(event.y / unit).toInt()
            val under = keys.firstOrNull { it.row == row && event.x >= it.left * unit && event.x < (it.left + it.units) * unit }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> down = under
                MotionEvent.ACTION_MOVE -> if (under !== down) down = null
                MotionEvent.ACTION_UP -> {
                    val hit = down
                    down = null
                    if (hit != null && hit === under) onKey(hit.code)
                }
                MotionEvent.ACTION_CANCEL -> down = null
            }
            invalidate()
            return true
        }
    }

    /**
     * What [c] sends, how wide it is, how it presses (while touched, held till tapped again, or only after a long
     * press) and which set it brings, the key itself at the top.
     */
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
        val press = Dropdown(activity, PRESSES.map(::pressName)) {
            c.latch = PRESSES[it] == STAYS_HELD
            c.longPressMs = PRESSES[it].coerceAtLeast(0)
            changed()
        }.apply {
            selected = when {
                c.latch -> PRESSES.indexOf(STAYS_HELD)
                else -> PRESSES.indexOf(c.longPressMs).coerceAtLeast(0)
            }
        }
        val remove = button(activity, "Remove") {
            keys.remove(c)
            host.close()
        }
        host.show(keys.label(c).ifEmpty { c.name }, OPTIONS_DP, "Close", remove, titleEnd = preview) {
            add(stack(activity, Frame.DEEP, padding = 10, gap = 6) {
                add(setting("Sends", sends))
                add(setting("Width", width))
                add(setting("Press", press))
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
        // At the top left: a floating keyboard (the vivo's, sideways) sits over the middle and the right.
        host.show("Set", SET_DP, "Close", delete, gravity = Gravity.TOP or Gravity.START) {
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
        /** A key's Press: 0 while touched, [STAYS_HELD], or held this long first (Keys.LONG_PRESSES). */
        const val STAYS_HELD = -1
        val PRESSES = listOf(0, STAYS_HELD) + Keys.LONG_PRESSES.toList()

        fun pressName(press: Int) = when (press) {
            0 -> "While touched"
            STAYS_HELD -> "Stays held"
            else -> "Long press, ${seconds(press)}"
        }

        /** 300 ms as "0.3 s", 1000 as "1 s". */
        fun seconds(ms: Int) = if (ms % 1000 == 0) "${ms / 1000} s" else "${ms / 1000f} s"

        /** In dp: the picker's keyboard unit, a 1u key's width and height; its caps carry their own gaps. */
        const val KEY_UNIT_DP = 40f
        const val LOADING_MS = 100L
        /** The dialogs' widths, in dp: the keyboard as wide as the phone, the others narrower. */
        const val PICKER_DP = 820
        const val OPTIONS_DP = 520
        const val SET_DP = 380
        const val CONTROL_DP = 190
        const val CONTROL_HEIGHT_DP = 34
        const val PREVIEW_DP = 52
    }
}
