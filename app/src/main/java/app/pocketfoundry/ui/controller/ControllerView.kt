package app.pocketfoundry.ui.controller

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import app.pocketfoundry.core.controller.PadAxis
import app.pocketfoundry.core.controller.VirtualPad
import app.pocketfoundry.core.controller.VirtualPad.Source.SCREEN
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.ui.kit.GuiSprites
import kotlin.math.abs

/**
 * Touches reach it from [InputRouter], in this view's coordinates, not through the view system. With [keyboard]
 * Factorio is in its keyboard-and-mouse mode, which ignores the pad: the controls show only in the layout editor.
 */
@SuppressLint("ViewConstructor")
/** [onSprites]: the controls' rendered sprites as they load and go, for views drawn in the same look (the FPS counter). */
class ControllerView(context: Context, private val keyboard: Boolean, private val counter: Movable,
                     private val onSprites: (SpriteSet?) -> Unit = {}) : View(context) {
    private val layout = PadLayout(context)
    private val painter = PadPainter(context, layout)
    private val toolbar = Toolbar(layout, painter)
    private val editor = LayoutEditor(layout, painter, toolbar, counter, ::haptic)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val settings = Settings(context)
    private val pressed = arrayOfNulls<Control>(POINTER_IDS)
    private val counterBox = RectF()

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
        counter.moveTo(layout.counter, layout.counterScale)
        alpha = layout.opacity
        invalidate()
    }

    fun startEditing() {
        releaseAll()
        layout.hidden = false
        editor.active = true
        invalidate()
    }

    /** With Settings.touch BOX, whether ([x], [y]) is on the trackpad box, where the right stick was. */
    fun trackpadBoxAt(x: Float, y: Float): Boolean {
        if (!controlsShown || editor.active || settings.touch != Settings.BOX) return false
        val pad = layout.shownControls.firstOrNull { (it.kind as? Kind.Stick)?.axisX == PadAxis.RIGHT_X } ?: return false
        // The pad is wider than the stick it stands for: all of it is the pad, but a control drawn over it wins.
        val r = layout.radius(pad)
        val onPad = abs(x - layout.cx(pad)) <= r * TRACKPAD_HALF_WIDTH && abs(y - layout.cy(pad)) <= r
        val over = layout.controlAt(x, y)
        return onPad && (over == null || over === pad)
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
        VirtualPad.release(SCREEN)
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
        painter.box = settings.touch == Settings.BOX
        if (editor.active) canvas.drawColor(EDITOR_SHADE)
        val controls = layout.shownControls
        val sprites = ControlSprites.get(context, layout.frame, ::invalidate)
        if (sprites !== painter.sprites.sprites) {
            painter.sprites.sprites = sprites
            onSprites(sprites)
        }
        val now = SystemClock.uptimeMillis()
        var moving = false
        // Indexed: an iterator would be made every frame.
        if (controlsShown) for (i in controls.indices) {
            if (painter.draw(canvas, sheet, controls[i], controls[i] === editor.selected, now)) moving = true
        }
        if (editor.counterSelected) {
            counter.bounds(counterBox)
            if (!counterBox.isEmpty) painter.drawSelection(canvas, counterBox)
        }
        if (toolbar.draw(canvas, sheet, tools, editor.adding, now)) moving = true
        if (moving) postInvalidateOnAnimation()
        if (editor.adding) editor.drawAddList(canvas, sheet)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layout.resize(w, h)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        painter.sprites.sprites = null
        onSprites(null)
        ControlSprites.release()
    }

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
        /** The rendered trackpad's half width, in the right stick's radii (its picture: 605 x 346). */
        private const val TRACKPAD_HALF_WIDTH = 1.75f
    }
}
