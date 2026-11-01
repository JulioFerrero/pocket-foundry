package app.pocketfoundry.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout

enum class Arrow { NONE, LEFT, RIGHT }

/** A button's face: the game's sprites once it's imported, a drawn look before. It reads its state as it draws. */
open class ButtonDrawable(context: Context, tone: Tone, private val arrow: Arrow = Arrow.NONE) : PlainDrawable() {
    private val app = context.applicationContext
    protected val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    protected val face = RectF()
    private val caretBox = RectF()
    private val caretPath = Path()
    private var sides: LinearGradient? = null
    var caret = false
    var tone = tone
        set(value) { field = value; invalidateSelf() }

    private val pressed get() = android.R.attr.state_pressed in state
    private val hovered get() = android.R.attr.state_focused in state || android.R.attr.state_hovered in state
    private val enabled get() = android.R.attr.state_enabled in state

    override fun isStateful() = true
    override fun onStateChange(state: IntArray) = true

    override fun onBoundsChange(bounds: Rect) {
        val m = MARGIN * density
        face.set(bounds.left + m, bounds.top + m, bounds.right - m, bounds.bottom - m)
        // The dropdown's caret: a 16-dp square 6 dp in from the right. Drawn, it's a 9 × 5.5 dp triangle in the middle.
        caretBox.set(face.right - 22 * density, face.centerY() - 8 * density, face.right - 6 * density, face.centerY() + 8 * density)
        val w = 9 * density
        val h = 5.5f * density
        val x = face.right - 14 * density
        val y = face.centerY() - h / 3
        caretPath.reset()
        caretPath.moveTo(x - w / 2, y); caretPath.lineTo(x + w / 2, y); caretPath.lineTo(x, y + h); caretPath.close()
        val edge = 5 * density / face.width()   // the shaded band down each side
        sides = LinearGradient(face.left, 0f, face.right, 0f, intArrayOf(SIDE, 0, 0, SIDE),
                               floatArrayOf(0f, edge, 1 - edge, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val sheet = GuiSprites.get(app)
        if (sheet != null) drawSprites(canvas, sheet) else drawPlain(canvas)
    }

    protected fun sheetState() = when {
        !enabled -> GuiSheet.DISABLED
        pressed -> GuiSheet.PRESSED
        hovered -> GuiSheet.HOVERED
        else -> GuiSheet.IDLE
    }

    /** The face's outline, with a point [tip] long on the arrow's side. */
    private fun outline(tip: Float): Path {
        val left = face.left + if (arrow == Arrow.LEFT) tip else 0f
        val right = face.right - if (arrow == Arrow.RIGHT) tip else 0f
        outline.rewind()
        outline.moveTo(left, face.top)
        outline.lineTo(right, face.top)
        if (arrow == Arrow.RIGHT) outline.lineTo(face.right, face.centerY())
        outline.lineTo(right, face.bottom)
        outline.lineTo(left, face.bottom)
        if (arrow == Arrow.LEFT) outline.lineTo(face.left, face.centerY())
        outline.close()
        return outline
    }

    protected open fun drawSprites(canvas: Canvas, sheet: GuiSheet) {
        val state = sheetState()
        canvas.drawPath(outline(face.height() * GuiSheet.ARROW_TIP), glow(Color.BLACK, state == GuiSheet.HOVERED))
        if (arrow == Arrow.NONE) sheet.button(tone, state).draw(canvas, face, GuiSheet.CORNER * density, sprite)
        else sheet.arrow(tone, state).draw(canvas, face, arrow == Arrow.LEFT, sprite)
        if (caret) sheet.caret?.let { canvas.drawBitmap(it, null, caretBox, sprite) }
    }

    protected open fun drawPlain(canvas: Canvas) {
        val path = outline(TIP * density)
        val lit = hovered && !pressed && enabled
        // Grey turns orange when lit or pressed, as the game's does; the other tones go lighter, or darker.
        val shown = if ((pressed || lit) && tone == Tone.GREY) Tone.ORANGE else tone
        canvas.drawPath(path, glow(shown.face, lit))
        paint.shader = null
        paint.color = when {
            tone == Tone.GREY -> shown.face
            pressed -> darken(tone.face)
            lit -> tone.light
            else -> tone.face
        }
        canvas.drawPath(path, paint)
        canvas.save()
        canvas.clipPath(path)
        paint.shader = sides
        canvas.drawRect(face, paint)
        paint.shader = null
        val line = 2 * density
        paint.color = if (pressed) shown.dark else shown.light
        canvas.drawRect(face.left, face.top, face.right, face.top + line, paint)
        paint.color = SHADOW
        canvas.drawRect(face.left, face.bottom - line * 0.75f, face.right, face.bottom, paint)
        canvas.restore()
        if (caret) {
            paint.color = 0xFF1E1E1E.toInt()
            canvas.drawPath(caretPath, paint)
        }
    }

    /** Fills with [fill], casting the tone's glow round the face when [lit], else a shadow. */
    private fun glow(fill: Int, lit: Boolean) = glowPaint.also {
        it.color = fill
        it.setShadowLayer(MARGIN * density, 0f, 0f, if (lit) tone.glow else SHADOW)
    }

    private fun darken(color: Int) = Color.rgb((Color.red(color) * DARKEN).toInt(), (Color.green(color) * DARKEN).toInt(),
                                                (Color.blue(color) * DARKEN).toInt())

    companion object {
        const val MARGIN = 3f   // dp round the face, for the glow
        const val TIP = 12f     // dp an arrow tip sticks out
        private const val SIDE = 0x88000000.toInt()
        private const val SHADOW = 0xCC000000.toInt()
        private const val DARKEN = 0.82f
    }
}

/** A Factorio button. Its size includes the [ButtonDrawable.MARGIN] glow round the face. */
open class FButton(context: Context, label: CharSequence, tone: Tone = Tone.GREY, arrow: Arrow = Arrow.NONE,
                   size: Float = 16f) : Button(context) {
    protected val bevel = ButtonDrawable(context, tone, arrow)
    var tone by bevel::tone

    init {
        background = bevel
        text = label
        isAllCaps = false
        stateListAnimator = null
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        typeface = Factorio.font(context, if (tone == Tone.GREY) 600 else 700)
        textSize = size
        val side = context.dpi(16)
        val tip = context.dpi(ButtonDrawable.TIP.toInt())
        setPadding(side + if (arrow == Arrow.LEFT) tip else 0, 0, side + if (arrow == Arrow.RIGHT) tip else 0, 0)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        val sprites = GuiSprites.get(context) != null
        alpha = if (enabled || sprites) 1f else 0.45f
        setTextColor(if (enabled || !sprites) Color.BLACK else 0xFFB3B3B3.toInt())
    }
}

fun button(context: Context, text: CharSequence, tone: Tone = Tone.GREY, arrow: Arrow = Arrow.NONE, size: Float = 16f,
           onClick: () -> Unit) = FButton(context, text, tone, arrow, size).apply { setOnClickListener { onClick() } }

/** Buttons side by side with the selected one lit: the game's toggle. */
@SuppressLint("ViewConstructor")
class Choice(context: Context, labels: List<String>, onSelect: (Int) -> Unit) : LinearLayout(context) {
    private val buttons = labels.mapIndexed { i, label ->
        add(button(context, label, size = 15f) { selected = i; onSelect(i) }, 84, 34, start = if (i > 0) 2 else 0)
    }
    var selected = -1
        set(value) {
            field = value
            buttons.forEachIndexed { i, button -> button.tone = if (i == value) Tone.ORANGE else Tone.GREY }
        }
}

fun checkBox(context: Context, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(context).apply {
    buttonDrawable = CheckDrawable(context)
    background = null
    isChecked = checked
    setOnCheckedChangeListener { _, value -> onChange(value) }
}

/** The game's checkbox; before an import, a grey button with a tick drawn on. */
private class CheckDrawable(context: Context) : ButtonDrawable(context, Tone.GREY) {
    private val size = context.dpi(30)
    private val checked get() = android.R.attr.state_checked in state
    private val tick = Path()
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.SQUARE
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val s = face.width()
        tick.reset()
        tick.moveTo(face.left + s * 0.22f, face.top + s * 0.52f)
        tick.lineTo(face.left + s * 0.42f, face.top + s * 0.71f)
        tick.lineTo(face.left + s * 0.79f, face.top + s * 0.31f)
    }

    override fun drawSprites(canvas: Canvas, sheet: GuiSheet) {
        canvas.drawBitmap(sheet.checkbox[sheetState()], null, bounds, sprite)
        if (checked) canvas.drawBitmap(sheet.check, null, bounds, sprite)
    }

    override fun drawPlain(canvas: Canvas) {
        super.drawPlain(canvas)
        if (checked) canvas.drawPath(tick, tickPaint)
    }
}
