package app.pocketfoundry.ui.controller

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.ui.kit.GuiSprites

/**
 * Touches reach it from [InputRouter], in this view's coordinates, not through the view system. With [keyboard]
 * Factorio is in its keyboard-and-mouse mode, which ignores the pad: the controls show only in the layout editor.
 */
@SuppressLint("ViewConstructor")
class ControllerView(context: Context, private val keyboard: Boolean) : View(context) {
    private val layout = PadLayout(context)
    private val painter = PadPainter(context, layout)
    private val toolbar = Toolbar(layout, painter)
    private val editor = LayoutEditor(layout, painter, toolbar, ::haptic)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val settings = Settings(context)
    /** The control under each finger, by pointer id. */
    private val pressed = arrayOfNulls<Control>(POINTER_IDS)

    var onKeyboard: () -> Unit = {}

    var padInHand = false
        set(value) {
            if (field == value) return
            field = value
            if (stepAside) releaseAll() else invalidate()
        }

    private val stepAside get() = (padInHand || keyboard) && !editor.active
    private val controlsShown get() = !stepAside && !layout.hidden

    init {
        reload()
    }

    fun reload() {
        layout.load()
        alpha = layout.opacity
        invalidate()
    }

    fun startEditing() {
        releaseAll()
        layout.hidden = false
        editor.active = true
        invalidate()
    }

    /** Returns whether the finger is the controller's: on a tool, or anywhere in the editor, or on a control. */
    fun pointerDown(id: Int, x: Float, y: Float): Boolean {
        when {
            toolbar.press(id, tools(), x, y, controlsShown) -> {}
            !controlsShown -> return false
            editor.active -> editor.down(id, x, y)
            else -> {
                val control = layout.controlAt(x, y) ?: return false
                pressed[id] = control
                if (control.press(id, x, y, layout)) haptic(if (control.kind == Kind.Dpad) TICK else CLICK)
            }
        }
        invalidate()
        return true
    }

    fun pointerMove(id: Int, x: Float, y: Float) {
        if (editor.active) {
            if (editor.move(id, x, y)) invalidate()
            return
        }
        val control = pressed[id] ?: return
        if (control.pointers[0] != id || !control.kind.follows) return
        if (control.follow(x, y, layout)) haptic(TICK)
        invalidate()
    }

    fun pointerUp(id: Int) {
        val tool = toolbar.lift(id)
        if (tool != null) return use(tool)
        editor.up(id)
        val control = pressed[id] ?: return
        pressed[id] = null
        if (control.lift(id)) invalidate()
    }

    fun releaseAll() {
        toolbar.releaseAll()
        editor.releaseAll()
        pressed.fill(null)
        layout.shownControls.forEach { it.pointers.clear(); it.release() }
        invalidate()
    }

    private fun use(tool: Tool) {
        haptic(CLICK)
        when (tool) {
            Tool.ADJUST -> startEditing()
            Tool.KEYBOARD -> onKeyboard()
            Tool.HIDE -> { releaseAll(); layout.hidden = true; layout.save() }
            Tool.SHOW -> { layout.hidden = false; layout.save() }
            else -> { editor.use(tool); alpha = layout.opacity }
        }
        invalidate()
    }

    private fun tools() = when {
        editor.active -> Toolbar.EDITING
        keyboard -> Toolbar.NONE
        // A real controller still needs the phone's keyboard, for typing names.
        padInHand -> Toolbar.PAD_IN_HAND
        layout.hidden -> Toolbar.HIDDEN
        else -> Toolbar.PLAYING
    }

    override fun onDraw(canvas: Canvas) {
        val tools = tools()
        if (tools.isEmpty()) return
        val sheet = GuiSprites.get(context)
        if (editor.active) canvas.drawColor(EDITOR_SHADE)
        val controls = layout.shownControls
        // Indexed: an iterator would be made every frame.
        if (controlsShown) for (i in controls.indices) painter.draw(canvas, sheet, controls[i], controls[i] === editor.selected)
        toolbar.draw(canvas, sheet, tools, editor.adding)
        if (editor.adding) editor.drawAddList(canvas, sheet)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layout.resize(w, h)

    // Termux:X11's activity lives through a display size change, which changes the density but maybe not the size.
    override fun onConfigurationChanged(newConfig: Configuration) = layout.resize(width, height)

    private fun haptic(effect: VibrationEffect) {
        if (settings.vibration) vibrator?.vibrate(effect)
    }

    companion object {
        /** Android numbers a gesture's fingers 0 to 31. */
        const val POINTER_IDS = 32
        // Made once: haptics come with touches.
        val TICK: VibrationEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        val CLICK: VibrationEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        private const val EDITOR_SHADE = 0x55000000
    }
}
