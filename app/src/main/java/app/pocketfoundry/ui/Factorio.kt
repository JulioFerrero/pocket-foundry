package app.pocketfoundry.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import app.pocketfoundry.R
import kotlin.math.roundToInt

/*
 * Factorio's own GUI look, drawn in code from what factorio.com and the game's menus use: dark
 * panels in a brown-black frame, sunken insets, cream headings, bevelled buttons (grey, green to
 * go on, orange while pressed or chosen, red to go back) in Titillium Web.
 */

object Factorio {
    const val PANEL = 0xFF313031.toInt()
    const val FRAME = 0xFF2E2623.toInt()
    const val INSET_DARK = 0xFF242324.toInt()
    const val INSET_LIGHT = 0xFF414040.toInt()
    const val HEADING = 0xFFF4F1EC.toInt()
    const val TEXT = Color.WHITE
    const val MUTED = 0xFFC9C0B4.toInt()
    const val FAINT = 0xFFA39B91.toInt()
    const val LINK = 0xFF7DCAED.toInt()
    const val ERROR = 0xFFFF9B8F.toInt()
    const val BAR = 0xFFF1A240.toInt()
    const val BODY = 0xFF201810.toInt()   // factorio.com's page, behind screens without a stage

    private val fonts = HashMap<Int, Typeface>()

    /** Titillium Web at 400, 600, 700 or 900. */
    fun font(context: Context, weight: Int): Typeface = fonts.getOrPut(weight) {
        context.resources.getFont(when (weight) {
            900 -> R.font.titillium_web_black
            700 -> R.font.titillium_web_bold
            600 -> R.font.titillium_web_semibold
            else -> R.font.titillium_web_regular
        })
    }
}

fun Context.dp(value: Float) = value * resources.displayMetrics.density

/** Full screen like the game: the bars come back with a swipe and go again. */
fun Activity.immersive() {
    window.insetsController?.apply {
        hide(WindowInsets.Type.systemBars())
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

fun Context.dpi(value: Int) = (value * resources.displayMetrics.density).roundToInt()

/** A frame with a fill: a panel (raised), or an inset sunk into one. */
class FramedDrawable(context: Context, private val fill: Int, private val sunken: Boolean) : Drawable() {
    private val border = context.dp(4f)
    private val depth = context.dp(6f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = RectF()
    private var shade: LinearGradient? = null

    override fun onBoundsChange(bounds: Rect) {
        inner.set(bounds.left + border, bounds.top + border, bounds.right - border, bounds.bottom - border)
        shade = if (sunken) LinearGradient(0f, inner.top, 0f, inner.top + depth, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP)
                else LinearGradient(0f, inner.top, 0f, inner.top + depth / 3, 0x12FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        paint.shader = null
        paint.color = Factorio.FRAME
        canvas.drawRect(bounds, paint)
        paint.color = fill
        canvas.drawRect(inner, paint)
        paint.shader = shade
        canvas.drawRect(inner.left, inner.top, inner.right, inner.top + if (sunken) depth else depth / 3, paint)
    }

    override fun getPadding(padding: Rect): Boolean {
        val b = border.roundToInt()
        padding.set(b, b, b, b)
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

enum class Tone(val face: Int, val light: Int, val dark: Int) {
    GREY(0xFF8E8E8E.toInt(), 0xFFE3E3E3.toInt(), 0xFF282828.toInt()),
    GREEN(0xFF5EB663.toInt(), 0xFF95DF99.toInt(), 0xFF163218.toInt()),
    ORANGE(0xFFE39C40.toInt(), 0xFFF7CC8F.toInt(), 0xFF5C3D12.toInt()),
    RED(0xFFE8605A.toInt(), 0xFFF5A29E.toInt(), 0xFF4A1514.toInt()),
}

enum class Arrow { NONE, LEFT, RIGHT }

/**
 * The game's button: a flat face with a light top edge, a dark bottom edge, shaded sides and a
 * dark glow, optionally with an arrow tip. Grey turns orange while pressed, like the game's hover;
 * the others darken. Leaves [MARGIN] dp round the face for the glow.
 */
class BevelDrawable(context: Context, tone: Tone, private val arrow: Arrow = Arrow.NONE,
                    private val width: Int = -1, private val height: Int = -1) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(MARGIN * density, 0f, 0f, 0xCC000000.toInt()) }
    private val path = Path()
    private val face = RectF()
    private var sides: LinearGradient? = null
    private var pressed = false
    var checkmark = false
    var caret = false
    var checked = false
        set(value) { field = value; invalidateSelf() }
    var tone = tone
        set(value) { field = value; invalidateSelf() }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_pressed in state
        val check = android.R.attr.state_checked in state
        if (now == pressed && check == checked) return false
        pressed = now
        checked = check
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        val m = MARGIN * density
        face.set(bounds.left + m, bounds.top + m, bounds.right - m, bounds.bottom - m)
        val tip = TIP * density
        path.reset()
        when (arrow) {
            Arrow.NONE -> path.addRect(face, Path.Direction.CW)
            Arrow.RIGHT -> {
                path.moveTo(face.left, face.top); path.lineTo(face.right - tip, face.top)
                path.lineTo(face.right, face.centerY()); path.lineTo(face.right - tip, face.bottom)
                path.lineTo(face.left, face.bottom); path.close()
            }
            Arrow.LEFT -> {
                path.moveTo(face.left + tip, face.top); path.lineTo(face.right, face.top)
                path.lineTo(face.right, face.bottom); path.lineTo(face.left + tip, face.bottom)
                path.lineTo(face.left, face.centerY()); path.close()
            }
        }
        val edge = 5 * density / face.width()
        sides = LinearGradient(face.left, 0f, face.right, 0f,
                               intArrayOf(0x88000000.toInt(), 0, 0, 0x88000000.toInt()),
                               floatArrayOf(0f, edge, 1 - edge, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val shown = if (pressed && tone == Tone.GREY) Tone.ORANGE else tone
        glow.color = shown.face
        canvas.drawPath(path, glow)
        paint.shader = null
        paint.color = if (pressed && tone != Tone.GREY) darken(shown.face) else shown.face
        canvas.drawPath(path, paint)
        canvas.save()
        canvas.clipPath(path)
        paint.shader = sides
        canvas.drawRect(face, paint)
        paint.shader = null
        val line = 2 * density
        // Pressed, the bevel turns over: dark along the top, no highlight.
        paint.color = if (pressed) shown.dark else shown.light
        canvas.drawRect(face.left, face.top, face.right, face.top + line, paint)
        paint.color = 0xCC000000.toInt()
        canvas.drawRect(face.left, face.bottom - line * 0.75f, face.right, face.bottom, paint)
        canvas.restore()
        if (caret) {
            // The drop-down's arrow, at the right end of the face.
            val w = 9 * density; val h = 5.5f * density
            val x = face.right - 14 * density; val y = face.centerY() - h / 3
            paint.color = 0xFF1E1E1E.toInt()
            canvas.drawPath(Path().apply { moveTo(x - w / 2, y); lineTo(x + w / 2, y); lineTo(x, y + h); close() }, paint)
        }
        if (checkmark && checked) {
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.2f * density
            paint.strokeCap = Paint.Cap.SQUARE
            val s = face.width()
            val check = Path().apply {
                moveTo(face.left + s * 0.22f, face.top + s * 0.52f)
                lineTo(face.left + s * 0.42f, face.top + s * 0.71f)
                lineTo(face.left + s * 0.79f, face.top + s * 0.31f)
            }
            canvas.drawPath(check, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun darken(color: Int) = Color.rgb((Color.red(color) * 0.82f).toInt(), (Color.green(color) * 0.82f).toInt(),
                                                (Color.blue(color) * 0.82f).toInt())

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val MARGIN = 3f   // dp round the face, for the glow
        const val TIP = 12f     // dp an arrow tip sticks out
    }
}

/** A Factorio button. Its height includes the glow round the face. */
open class FButton(context: Context, label: CharSequence, tone: Tone = Tone.GREY, arrow: Arrow = Arrow.NONE,
                   size: Float = 16f) : Button(context) {
    protected val bevel = BevelDrawable(context, tone, arrow)
    var tone: Tone
        get() = bevel.tone
        set(value) { bevel.tone = value }

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
        val tip = context.dpi(BevelDrawable.TIP.toInt())
        setPadding(side + if (arrow == Arrow.LEFT) tip else 0, 0, side + if (arrow == Arrow.RIGHT) tip else 0, 0)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }
}

/**
 * The game's drop-down: a grey button showing the choice; a tap opens the list under it, with
 * the current choice in orange.
 */
class Dropdown(context: Context, private val options: List<CharSequence>, private val onSelect: (Int) -> Unit) :
    FButton(context, "", size = 15f) {
    var selected = -1
        set(value) { field = value; text = options.getOrNull(value) ?: "" }

    init {
        bevel.caret = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(context.dpi(14), 0, context.dpi(30), 0)
        setOnClickListener { open() }
    }

    private fun open() {
        val list = stack(context, Factorio.PANEL, sunken = false, padding = 2, gap = 2)
        val popup = PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        options.forEachIndexed { i, option ->
            list.addView(FButton(context, option, if (i == selected) Tone.ORANGE else Tone.GREY, size = 15f).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(context.dpi(14), 0, context.dpi(14), 0)
                setOnClickListener { popup.dismiss(); selected = i; onSelect(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(40)))
        }
        // Not focusable: a focused popup would bring back the system bars.
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.showAsDropDown(this, 0, context.dpi(2))
    }
}

/** The game's checkbox: a small grey button that shows a check. */
fun checkBox(context: Context, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(context).apply {
    val size = context.dpi(30)
    buttonDrawable = BevelDrawable(context, Tone.GREY, width = size, height = size).apply { checkmark = true }
    background = null
    isChecked = checked
    setOnCheckedChangeListener { _, value -> onChange(value) }
}

/** The game's slider: a sunken track filling orange, a grey handle. 0..100. */
fun slider(context: Context, value: Int, onChange: (Int) -> Unit) = SeekBar(context).apply {
    val track = object : Drawable() {
        val paint = Paint()
        override fun draw(canvas: Canvas) {
            paint.color = Factorio.INSET_DARK
            canvas.drawRect(bounds, paint)
            paint.color = 0xAA000000.toInt()
            canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                            bounds.top + context.dp(2f), paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
    val fill = ClipDrawable(object : Drawable() {
        val paint = Paint()
        override fun draw(canvas: Canvas) {
            paint.color = Factorio.BAR
            canvas.drawRect(bounds, paint)
            paint.color = 0xFFF7CC8F.toInt()
            canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                            bounds.top + context.dp(2f), paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }, Gravity.START, ClipDrawable.HORIZONTAL)
    progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
        setId(0, android.R.id.background)
        setId(1, android.R.id.progress)
    }
    thumb = BevelDrawable(context, Tone.GREY, width = context.dpi(18), height = context.dpi(32))
    splitTrack = false
    minHeight = context.dpi(8); maxHeight = context.dpi(8)
    max = 100
    progress = value
    background = null
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) onChange(value) }
        override fun onStartTrackingTouch(bar: SeekBar) {}
        override fun onStopTrackingTouch(bar: SeekBar) {}
    })
}

/** The loading bar: a sunken track filling orange. */
class BarView(context: Context) : View(context) {
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val edge = context.dp(3f)
        paint.color = 0xFF141314.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = 0xE6000000.toInt()
        canvas.drawRect(0f, 0f, w, context.dp(2f), paint)
        val x = w * fraction
        if (x <= 0) return
        paint.color = Factorio.BAR
        canvas.drawRect(0f, 0f, x, h, paint)
        paint.color = 0xFFF9CF91.toInt()
        canvas.drawRect(0f, 0f, x, edge, paint)
        paint.color = 0xFF7A4A10.toInt()
        canvas.drawRect(0f, h - edge, x, h, paint)
    }
}

/** Text in the game's type: [weight] 400-900. */
fun label(context: Context, text: CharSequence = "", size: Float = 15f, color: Int = Factorio.TEXT, weight: Int = 400) =
    TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Factorio.font(context, weight)
        includeFontPadding = false
    }

fun heading(context: Context, text: CharSequence, size: Float = 22f) = label(context, text, size, Factorio.HEADING, 600)

/** A panel or inset as a vertical stack with [gap] dp between its children. */
fun stack(context: Context, fill: Int, sunken: Boolean, padding: Int, gap: Int) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = FramedDrawable(context, fill, sunken)
    val border = context.dpi(4)
    setPadding(border + context.dpi(padding), border + context.dpi(padding),
               border + context.dpi(padding), border + context.dpi(padding))
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = object : Drawable() {
        override fun draw(canvas: Canvas) {}
        override fun getIntrinsicHeight() = context.dpi(gap)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSPARENT
    }
}

/** The thin groove between groups of buttons. */
fun groove(context: Context) = View(context).apply {
    background = object : Drawable() {
        val paint = Paint()
        override fun draw(canvas: Canvas) {
            paint.color = Factorio.FRAME
            canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + context.dp(2f), paint)
            paint.color = 0x10FFFFFF
            canvas.drawRect(bounds.left.toFloat(), bounds.top + context.dp(2f), bounds.right.toFloat(), bounds.top + context.dp(3f), paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    minimumHeight = context.dpi(3)
}
