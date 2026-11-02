package app.pocketfoundry.ui.kit

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/** One dialog at a time over the activity's screens, which keep no focus while it's open. */
class DialogHost(private val activity: Activity) {
    private var scrim: View? = null
    private var beforeDialog: View? = null
    private var onClose: (() -> Unit)? = null

    val isOpen get() = scrim != null

    /**
     * A [panel] titled [title], built by [build], with a [buttonBar] at the bottom: [back], which closes it, and [actions].
     * [onClose] runs once it's closed, by a tap beside it, Back, or [close].
     */
    fun show(title: CharSequence, widthDp: Int, back: CharSequence, vararg actions: View, onClose: () -> Unit = {},
             build: LinearLayout.() -> Unit) {
        Dropdown.dismissOpen()
        val panel = panel(activity, title) {
            build()
            add(buttonBar(activity, back, ::close, *actions))
        }
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { close() }
            isFocusable = false
            add(panel.apply { isClickable = true; isFocusable = false }, widthDp, WRAP, Gravity.CENTER)
        }
        // Full screen, Android makes no room for the keyboard: the dialog moves up until the field being typed in clears it.
        // Android 10 doesn't tell full-screen windows where the keyboard is, so there it stays put.
        scrim.setOnApplyWindowInsetsListener { _, insets ->
            val keyboard = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
            val field = activity.currentFocus.takeIf { keyboard > 0 && it is EditText }
            val bottom = field?.let { IntArray(2).also(it::getLocationInWindow)[1] - panel.translationY + it.height + activity.dpi(8) }
            panel.translationY = if (bottom == null) 0f else minOf(0f, scrim.height - keyboard - bottom)
            insets
        }
        beforeDialog = activity.currentFocus
        content().descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (activity.window.decorView as FrameLayout).add(scrim)
        this.scrim = scrim
        this.onClose = onClose
        scrim.requestFocus()
    }

    fun close() {
        content().descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        scrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        scrim = null
        beforeDialog?.takeIf { it.isShown }?.requestFocus()
        beforeDialog = null
        onClose?.also { onClose = null }?.invoke()
    }

    private fun content() = activity.findViewById<ViewGroup>(android.R.id.content)
}
