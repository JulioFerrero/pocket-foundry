package app.pocketfoundry.ui.controller

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.pocketfoundry.ui.kit.Dropdown
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.Window
import app.pocketfoundry.ui.kit.add
import app.pocketfoundry.ui.kit.button
import app.pocketfoundry.ui.kit.checkBox
import app.pocketfoundry.ui.kit.dpi
import app.pocketfoundry.ui.kit.label
import app.pocketfoundry.ui.kit.panel
import app.pocketfoundry.ui.kit.row
import app.pocketfoundry.ui.kit.spacer

/**
 * Mouse & keys' editor's windows over the game, in its look (ui/kit): a keyboard to pick a key from, a key's options,
 * a set's name. Real views: while one is up, touches go to it, not to the game or the keys (InputRouter).
 * [onChange]: something in the layout changed, to draw it again.
 */
class KeyPanels(private val activity: Activity, private val keys: PadLayout, private val onChange: () -> Unit) {
    private val host = FrameLayout(activity).apply {
        setBackgroundColor(SHADE)
        isClickable = true
        visibility = View.GONE
        setOnClickListener { close() }
    }

    init {
        activity.addContentView(host, ViewGroup.LayoutParams(MATCH, MATCH))
    }

    val open get() = host.visibility == View.VISIBLE

    fun close() {
        hideKeyboard()
        host.removeAllViews()
        host.visibility = View.GONE
        onChange()
    }

    /** [content] centred, at most [width] dp wide. */
    private fun show(content: View, width: Int) {
        host.removeAllViews()
        // A tap on the window stays there: only one off it closes it.
        content.isClickable = true
        val window = Window(activity, width).apply { add(content, MATCH, WRAP) }
        host.add(window, MATCH, WRAP, Gravity.CENTER)
        host.visibility = View.VISIBLE
        host.bringToFront()
    }

    /** A keyboard: Ctrl, Shift and Alt as switches, then the key; [onPicked] gets them, the modifiers first. */
    fun pickKey(title: String, onPicked: (IntArray) -> Unit) {
        val held = LinkedHashSet<Int>()
        val content = panel(activity, title) {
            add(row(activity) {
                add(label(activity, "Hold with it:", 15f, Factorio.MUTED), end = 8)
                for (code in KeyNames.MODIFIERS) {
                    val name = KeyNames.name(code)
                    add(button(activity, name, size = 14f) {}.apply {
                        setOnClickListener {
                            if (!held.remove(code)) held += code
                            tone = if (code in held) Tone.ORANGE else Tone.GREY
                        }
                    }, 70, 34, end = 4)
                }
                spacer()
                add(button(activity, "Cancel", Tone.RED, size = 14f) { close() }, height = 34)
            })
            for (codes in KeyNames.ROWS) {
                add(row(activity) {
                    for (code in codes) {
                        add(button(activity, KeyNames.name(code), size = 13f) {
                            close()
                            onPicked(KeyNames.withModifiers(held, code))
                        }.apply {
                            maxLines = 1
                            setPadding(activity.dpi(2), 0, activity.dpi(2), 0)
                        }, keyWidth(code), 36, end = 2)
                    }
                }, top = 0)
            }
        }
        show(content, PICKER_DP)
    }

    /** What [c] sends, how wide it is, whether it stays held and which set it brings. */
    fun options(c: Control) {
        val content = panel(activity, keys.label(c).ifEmpty { c.name }) {
            if (c.kind == Kind.Key) {
                add(setting(activity, "Sends", row(activity) {
                    add(label(activity, if (c.codes.isEmpty()) "Nothing, only a set" else KeyNames.label(c.codes), 16f),
                        end = 10)
                    add(button(activity, "Change", size = 14f) {
                        pickKey("Change ${keys.label(c)}") { codes ->
                            c.codes = codes
                            options(c)
                        }
                    }, height = 34)
                }))
                add(setting(activity, "Width", Dropdown(activity, Keys.WIDTHS.map { "${Keys.widthName(it)}u" }) {
                    c.units = Keys.WIDTHS[it]
                    onChange()
                }.apply { selected = Keys.WIDTHS.indexOfFirst { it == c.units }.coerceAtLeast(0) }, 120))
                add(setting(activity, "Stays held", checkBox(activity, c.latch) {
                    c.latch = it
                    onChange()
                }))
                val sets = keys.setKeys
                add(setting(activity, "Shows", Dropdown(activity, listOf("Nothing") + sets.map(keys::title)) {
                    c.shows = if (it == 0) null else sets[it - 1]
                    onChange()
                }.apply { selected = c.shows?.let { sets.indexOf(it) + 1 } ?: 0 }, 180))
            }
            add(row(activity) {
                spacer()
                add(button(activity, "Done", size = 15f) { close() }, height = 36)
            })
        }
        show(content, OPTIONS_DP)
    }

    /** A set the player made: its name, or gone. */
    fun set(key: String) {
        val name = EditText(activity).apply {
            setText(keys.title(key))
            setSelectAllOnFocus(true)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Factorio.TEXT)
            setBackgroundColor(Factorio.INSET_DARK)
            setPadding(activity.dpi(10), activity.dpi(6), activity.dpi(10), activity.dpi(6))
            textSize = 17f
        }
        val done = {
            keys.rename(key, name.text.toString())
            close()
        }
        name.setOnEditorActionListener { _, _, _ -> done(); true }
        val content = panel(activity, "Set") {
            add(name, MATCH, WRAP)
            add(row(activity) {
                add(button(activity, "Delete set", Tone.RED, size = 14f) {
                    keys.removeSet(key)
                    close()
                }, height = 34)
                spacer()
                add(button(activity, "Done", size = 15f) { done() }, height = 34)
            })
        }
        show(content, SET_DP)
        name.requestFocus()
        name.post { activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(name, 0) }
    }

    private fun hideKeyboard() {
        activity.currentFocus?.let { focus ->
            activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focus.windowToken, 0)
            focus.clearFocus()
        }
    }

    private fun setting(context: Context, title: String, control: View, width: Int = WRAP) = row(context) {
        add(label(context, title, 16f), 110)
        add(control, width, if (control is LinearLayout) WRAP else 34)
    }

    private fun keyWidth(code: Int) = when (KeyNames.name(code).length) {
        1 -> KEY_DP
        2, 3 -> KEY_DP + 6
        4 -> KEY_DP + 14
        else -> KEY_DP + 40
    }.let { if (code == android.view.KeyEvent.KEYCODE_SPACE) KEY_DP * 2 else it }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SHADE = 0x99000000.toInt()
        /** A key's width in the picker, in dp: about the phone's width over fifteen. */
        const val KEY_DP = 44
        /** The windows' widths, in dp: the keyboard as wide as the phone, the others narrower. */
        const val PICKER_DP = 820
        const val OPTIONS_DP = 520
        const val SET_DP = 380
    }
}
