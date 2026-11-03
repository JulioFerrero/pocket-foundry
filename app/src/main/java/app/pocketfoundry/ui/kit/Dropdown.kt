package app.pocketfoundry.ui.kit

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.PopupWindow
import android.widget.ScrollView

class Dropdown(context: Context, private val options: List<CharSequence>, private val onSelect: (Int) -> Unit) :
    FButton(context, "", size = 15f) {
    var selected = -1
        set(value) { field = value; text = options.getOrNull(value) ?: "" }
    private var popup: PopupWindow? = null
    private val items = ArrayList<FButton>()
    private var highlighted = -1
    private var touchDown = -1L
    private var closedAt = -1L

    init {
        bevel.caret = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(context.dpi(14), 0, context.dpi(30), 0)
        setOnClickListener {
            // A tap on the button while the list is open closes it as the touch starts; the same tap mustn't reopen it.
            val closedByThisTap = touchDown >= 0 && closedAt >= touchDown
            touchDown = -1
            if (!closedByThisTap) open()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchDown = event.downTime
            MotionEvent.ACTION_CANCEL -> touchDown = -1
        }
        return super.onTouchEvent(event)
    }

    private fun open() {
        val list = column(context, gap = 2)
        items.clear()
        options.forEachIndexed { i, option ->
            val item = button(context, option, if (i == selected) Tone.ORANGE else Tone.GREY, size = 15f) {
                popup?.dismiss()
                selected = i
                onSelect(i)
            }
            item.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            item.pad(horizontal = 14, vertical = 0)
            items += list.add(item, height = 40)
        }
        val scroll = ScrollView(context).apply { addView(list) }
        val frame = stack(context, Frame.PANEL, padding = 2) { add(scroll, height = 0, weight = 1f) }
        // Not focusable: a focused popup brings back the system bars. Keys stay with this button (onKeyDown).
        val window = PopupWindow(frame, width, WRAP, false)
        // A list longer than the room below the button scrolls.
        val at = IntArray(2).also { getLocationInWindow(it) }
        val room = rootView.height - at[1] - height - context.dpi(12)
        frame.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        if (frame.measuredHeight > room) {
            window.height = room.coerceAtLeast(context.dpi(130))
            scroll.keepScrollBar()
            // Wider by the scroll bar's gap, so the items keep the button's width.
            window.width = width + scroll.paddingRight
            scroll.post { items.getOrNull(selected)?.let { scroll.scrollTo(0, it.top - (scroll.height - it.height) / 2) } }
        }
        window.isOutsideTouchable = true
        window.setOnDismissListener {
            closedAt = SystemClock.uptimeMillis()
            if (showing === window) showing = null
        }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.showAsDropDown(this, 0, context.dpi(2))
        popup = window
        showing = window
        highlight(if (isFocused) selected.coerceAtLeast(0) else -1)
    }

    private fun highlight(index: Int) {
        highlighted = index
        items.forEachIndexed { i, item -> item.isHovered = i == index }
        items.getOrNull(index)?.takeIf { it.height > 0 }?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), false) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val popup = popup?.takeIf { it.isShowing } ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> highlight((highlighted - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN -> highlight((highlighted + 1).coerceAtMost(items.lastIndex))
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {}   // swallowed, so focus can't leave the button while the list is open
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A ->
                items.getOrNull(highlighted)?.performClick() ?: popup.dismiss()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> popup.dismiss()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    companion object {
        /** The open list. It is a window of its own: hiding the screen it opened from leaves it showing. */
        private var showing: PopupWindow? = null

        /** Closes the open list, if any. True when there was one. */
        fun dismissOpen(): Boolean {
            val popup = showing?.takeIf { it.isShowing } ?: return false
            popup.dismiss()
            return true
        }
    }
}
