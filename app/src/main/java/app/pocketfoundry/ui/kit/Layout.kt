package app.pocketfoundry.ui.kit

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pocketfoundry.core.system.mainHandler
import kotlin.concurrent.thread

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** A button bar's height, in dp. */
const val BUTTON_HEIGHT = 44

/** Visible, or gone: taking no room. */
var View.shown: Boolean
    get() = visibility == View.VISIBLE
    set(value) { visibility = if (value) View.VISIBLE else View.GONE }

/** Runs [work] on a thread of its own, then [onDone] with its result on the main thread. */
fun <T> background(name: String, work: () -> T, onDone: (Result<T>) -> Unit) {
    thread(name = "pocketfoundry-$name") {
        val result = runCatching(work)
        mainHandler.post { onDone(result) }
    }
}

/** A Drawable that ignores alpha and colour filters. */
abstract class PlainDrawable : Drawable() {
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The game's frames: a panel, and the dark and light insets that sit in one. */
enum class Frame(val fill: Int, val sprites: (GuiSheet) -> NineSlice) {
    PANEL(Factorio.PANEL, GuiSheet::frame),
    DEEP(Factorio.INSET_DARK, GuiSheet::deepFrame),
    SHALLOW(Factorio.INSET_LIGHT, GuiSheet::shallowFrame),
}

/** A frame's border, in dp. */
const val FRAME_BORDER = 4

private class FramedDrawable(context: Context, private val frame: Frame) : PlainDrawable() {
    private val app = context.applicationContext
    private val corner = context.dp(GuiSheet.CORNER)
    private val border = context.dp(FRAME_BORDER.toFloat())
    private val depth = context.dp(6f)
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whole = RectF()
    private val inner = RectF()
    private var shade: LinearGradient? = null

    override fun onBoundsChange(bounds: Rect) {
        whole.set(bounds)
        inner.set(bounds.left + border, bounds.top + border, bounds.right - border, bounds.bottom - border)
        if (frame != Frame.PANEL) shade = LinearGradient(0f, inner.top, 0f, inner.top + depth, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val sheet = GuiSprites.get(app)
        if (sheet != null) return frame.sprites(sheet).draw(canvas, whole, corner, sprite)
        paint.shader = null
        paint.color = Factorio.FRAME
        canvas.drawRect(bounds, paint)
        paint.color = frame.fill
        canvas.drawRect(inner, paint)
        // A panel's top edge catches the light; an inset's is in shadow.
        if (frame == Frame.PANEL) {
            paint.color = 0x12FFFFFF
            canvas.drawRect(inner.left, inner.top, inner.right, inner.top + depth / 3, paint)
        } else {
            paint.shader = shade
            canvas.drawRect(inner.left, inner.top, inner.right, inner.top + depth, paint)
        }
    }
}

/** A size in dp to pixels; MATCH, WRAP and 0 stay as they are. */
private fun Context.px(dp: Int) = if (dp > 0) dpi(dp) else dp

/**
 * Adds [view] with its size and margins in dp. The size defaults to Android's: full width in a column, wrapped in a row.
 * It takes every argument FrameLayout.add does, so in a row inside a frame a call never falls through to the frame.
 */
fun <V : View> LinearLayout.add(view: V, width: Int = if (orientation == LinearLayout.VERTICAL) MATCH else WRAP, height: Int = WRAP,
                                gravity: Int = -1, weight: Float = 0f, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): V {
    val params = LinearLayout.LayoutParams(context.px(width), context.px(height), weight)
    params.gravity = gravity
    params.setMargins(context.dpi(start), context.dpi(top), context.dpi(end), context.dpi(bottom))
    return view.also { addView(it, params) }
}

/** Adds [view] with its size and margins in dp, filling the frame unless told otherwise. */
fun <V : View> FrameLayout.add(view: V, width: Int = MATCH, height: Int = MATCH, gravity: Int = -1,
                               start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): V {
    val params = FrameLayout.LayoutParams(context.px(width), context.px(height), gravity)
    params.setMargins(context.dpi(start), context.dpi(top), context.dpi(end), context.dpi(bottom))
    return view.also { addView(it, params) }
}

/** Takes the room the other items leave. */
fun LinearLayout.spacer() = add(View(context), 0, 0, weight = 1f)

/** Items side by side, centred vertically unless [gravity] says otherwise. */
fun row(context: Context, gravity: Int = Gravity.CENTER_VERTICAL, build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
    this.gravity = gravity
    build()
}

/** Items one under another, [gap] dp apart. */
fun column(context: Context, gap: Int = 0, build: LinearLayout.() -> Unit = {}) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply { setSize(0, context.dpi(gap)) }
    build()
}

/** Padding in dp: [horizontal] on both sides, [vertical] above and below. */
fun View.pad(horizontal: Int, vertical: Int = horizontal) =
    setPadding(context.dpi(horizontal), context.dpi(vertical), context.dpi(horizontal), context.dpi(vertical))

/** A column in one of the game's frames, with [padding] dp inside the frame's border. */
fun stack(context: Context, frame: Frame, padding: Int = 0, gap: Int = 0, build: LinearLayout.() -> Unit = {}) = column(context, gap) {
    background = FramedDrawable(context, frame)
    val inset = context.dpi(FRAME_BORDER) + context.dpi(padding)
    setPadding(inset, inset, inset, inset)
    build()
}

/** A window in the game's look, with [title] on top. */
fun panel(context: Context, title: View, build: LinearLayout.() -> Unit = {}) = stack(context, Frame.PANEL, padding = 8, gap = 10) {
    add(title)
    build()
}

fun panel(context: Context, title: CharSequence, build: LinearLayout.() -> Unit = {}) = panel(context, heading(context, title), build)

/** A red [back] button on the left and [actions] on the right, 4 dp apart. A bar spaced otherwise adds its own. */
fun buttonBar(context: Context, back: CharSequence, onBack: () -> Unit, vararg actions: View) = row(context) {
    add(button(context, back, Tone.RED, Arrow.LEFT, onClick = onBack), height = BUTTON_HEIGHT)
    spacer()
    actions.forEachIndexed { i, action -> add(action, height = BUTTON_HEIGHT, start = if (i > 0) 4 else 0) }
}

/** The game's sunken line: the frame colour, over a faint lit edge 1 dp high. */
fun groove(context: Context) = View(context).apply {
    background = LayerDrawable(arrayOf(ColorDrawable(0x10FFFFFF), ColorDrawable(Factorio.FRAME))).apply {
        setLayerInsetBottom(1, context.dpi(1))
    }
}

/** The game's progress bar. */
class BarView(context: Context) : View(context) {
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val edge = context.dp(3f)
        paint.color = 0xFF141314.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = 0xE6000000.toInt()   // the track's shaded top
        canvas.drawRect(0f, 0f, w, context.dp(2f), paint)
        val x = w * fraction
        if (x <= 0) return
        paint.color = Factorio.BAR
        canvas.drawRect(0f, 0f, x, h, paint)
        paint.color = 0xFFF9CF91.toInt()   // lit top edge
        canvas.drawRect(0f, 0f, x, edge, paint)
        paint.color = 0xFF7A4A10.toInt()   // dark bottom edge
        canvas.drawRect(0f, h - edge, x, h, paint)
    }
}

fun label(context: Context, text: CharSequence = "", size: Float = 15f, color: Int = Factorio.TEXT, weight: Int = 400) =
    TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Factorio.font(context, weight)
        includeFontPadding = false
    }

fun heading(context: Context, text: CharSequence, size: Float = 22f) = label(context, text, size, Factorio.HEADING, 600)

/** Underlined text that opens [url] in the browser; lit like a button while focused. */
fun link(context: Context, text: CharSequence, url: String, size: Float = 14f) = label(context, text, size, Factorio.LINK).apply {
    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Tone.GREY.glow, Factorio.LINK)))
    setOnClickListener { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** [text], then [note] smaller and in [color]: a resolution's "Default", a driver's version. */
fun withNote(text: CharSequence, note: CharSequence, color: Int = 0xFF3A3A3A.toInt(), sizeSp: Int = 13, gap: String = "  "): CharSequence =
    SpannableStringBuilder(text).apply {
        val start = length
        append(gap).append(note)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(AbsoluteSizeSpan(sizeSp, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

/** A scroll bar that stays while there's more to scroll to, in a gap of its own beside the content. */
fun ScrollView.keepScrollBar() {
    isScrollbarFadingEnabled = false
    scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
    setPadding(paddingLeft, paddingTop, context.dpi(10), paddingBottom)
    scrollBarSize = context.dpi(4)
    verticalScrollbarThumbDrawable = GradientDrawable().apply {
        setColor(Factorio.FAINT)
        cornerRadius = context.dpi(2).toFloat()
    }
}

/** As tall as its content up to [maxPx], then it scrolls. */
@SuppressLint("ViewConstructor")
class MaxHeightScrollView(context: Context, private val maxPx: Int) : ScrollView(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
}
