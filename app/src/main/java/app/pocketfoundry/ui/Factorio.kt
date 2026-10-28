package app.pocketfoundry.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
import app.pocketfoundry.Paths
import app.pocketfoundry.R
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.roundToInt

object Factorio {
    const val PANEL = 0xFF313031.toInt()
    const val FRAME = 0xFF2E2623.toInt()
    const val INSET_DARK = 0xFF242324.toInt()
    const val INSET_LIGHT = 0xFF414040.toInt()
    const val HEADING = 0xFFFFE6C0.toInt()
    const val TEXT = Color.WHITE
    const val MUTED = 0xFFC9C0B4.toInt()
    const val FAINT = 0xFFA39B91.toInt()
    const val LINK = 0xFF7DCAED.toInt()
    const val ERROR = 0xFFFF9B8F.toInt()
    const val BAR = 0xFFF1A240.toInt()
    const val BODY = 0xFF201810.toInt()

    private val fonts = HashMap<Int, Typeface>()

    /** Titillium Web: the game's Lilittium is Titillium with Cyrillic added. */
    fun font(context: Context, weight: Int): Typeface = fonts.getOrPut(weight) {
        context.resources.getFont(when (weight) {
            900 -> R.font.titillium_web_black
            700 -> R.font.titillium_web_bold
            600 -> R.font.titillium_web_semibold
            else -> R.font.titillium_web_regular
        })
    }
}

/** Sprite places are from data/core/prototypes/style.lua (Factorio 2.x). Main thread only. */
object GuiSprites {
    private var sheet: GuiSheet? = null
    private var read = false

    fun get(context: Context): GuiSheet? {
        if (!read) {
            read = true
            sheet = runCatching { GuiSheet(File(Paths(context).game, "data/core/graphics")) }
                .onFailure { if (it !is FileNotFoundException) Log.w("PocketFoundry", "Factorio's GUI sprites unusable, drawing the look instead", it) }
                .getOrNull()
        }
        return sheet
    }

    fun reload() {
        read = false
        sheet = null
    }
}

/** Each piece is cut out so scaling can't bleed in its neighbours. */
class GuiSheet(dir: File) {
    private val png = BitmapFactory.decodeFile(File(dir, "gui-new.png").path) ?: throw FileNotFoundException("gui-new.png")

    init {
        check(png.width == 597 && png.height == 1220) { "gui-new.png is ${png.width}×${png.height}, not the layout read here" }
    }

    // A panel is the style "frame"; insets are inside_deep_frame and inside_shallow_frame (one border, two fills).
    val frame = nine(0, 0)
    val deepFrame = nine(17, 0, centerX = 42)
    val shallowFrame = nine(17, 0, centerX = 76)

    // button, green_button and red_button: idle, clicked, disabled, hovered. Orange is button's selected look.
    private val buttons = mapOf(
        Tone.GREY to listOf(nine(0, 17), nine(51, 17), nine(17, 17), nine(34, 17)),
        Tone.GREEN to listOf(nine(68, 17), nine(119, 17), nine(85, 17), nine(102, 17)),
        Tone.ORANGE to listOf(nine(225, 17), nine(352, 17), nine(17, 17), nine(369, 17)),
        Tone.RED to listOf(nine(136, 17), nine(187, 17), nine(153, 17), nine(170, 17)),
    )

    // back_button and confirm_button (arrow_back, arrow_forward): the arrow end from a 48 × 64 tile of the
    // arrow tileset (its left half points left, its right half right), the other end from the button's tile.
    // The tileset's states are idle 0, disabled 1, hovered 2 (orange, on grey), clicked 3.
    private val arrows = Tone.entries.associateWith { tone ->
        val (row, tile) = when (tone) {
            Tone.GREEN -> 296 to 68
            Tone.RED -> 360 to 136
            else -> 232 to 0
        }
        listOf(if (tone == Tone.ORANGE) 2 else 0, 3, 1, 2).map { i ->
            val x = i * 96
            val end = tile + i * 17
            ArrowButton(piece(x, row, 24, 64), piece(x + 24, row, 24, 64), piece(x + 24, row, 1, 64),
                        listOf(piece(end, 17, 8, 8), piece(end, 25, 8, 1), piece(end, 26, 8, 8)),
                        listOf(piece(end + 9, 17, 8, 8), piece(end + 9, 25, 8, 1), piece(end + 9, 26, 8, 8)))
        }
    }

    // checkbox: idle, clicked, disabled, hovered.
    val checkbox = listOf(piece(0, 132, 28, 28), piece(84, 132, 28, 28), piece(28, 132, 28, 28), piece(56, 132, 28, 28))
    val check = piece(112, 132, 28, 28)

    /** dropdown.png's largest mip size. */
    val caret = BitmapFactory.decodeFile(File(dir, "icons/mip/dropdown.png").path)
        ?.let { Bitmap.createBitmap(it, 0, 0, minOf(32, it.width), minOf(32, it.height)) }

    fun button(tone: Tone, state: Int) = buttons.getValue(tone)[state]
    fun arrowButton(tone: Tone, state: Int) = arrows.getValue(tone)[state]

    private fun piece(x: Int, y: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(png, x, y, w, h)

    /** A 17-pixel tile cut 8 | 1 | 8 both ways. */
    private fun nine(x: Int, y: Int, centerX: Int = x + 8, centerY: Int = y + 8) = NineSlice(List(9) { i ->
        val col = i % 3
        val row = i / 3
        if (i == 4) piece(centerX, centerY, 1, 1)
        else piece(x + CUTS[col], y + CUTS[row], if (col == 1) 1 else 8, if (row == 1) 1 else 8)
    })

    init {
        png.recycle()
    }

    companion object {
        const val IDLE = 0
        const val PRESSED = 1
        const val DISABLED = 2
        const val HOVERED = 3
        /** A sprite pixel is a dp: the game's 100% scale. */
        const val CORNER = 8f
        /** The arrow end's width, per height of the face. */
        const val ARROW_TIP = 24f / 64
        private val CUTS = intArrayOf(0, 8, 9)
    }
}

class NineSlice(private val parts: List<Bitmap>) {
    private val cell = RectF()

    fun draw(canvas: Canvas, r: RectF, corner: Float, paint: Paint) {
        val c = minOf(corner, r.width() / 2, r.height() / 2)
        val xs = floatArrayOf(r.left, r.left + c, r.right - c, r.right)
        val ys = floatArrayOf(r.top, r.top + c, r.bottom - c, r.bottom)
        for (i in 0 until 9) {
            cell.set(xs[i % 3], ys[i / 3], xs[i % 3 + 1], ys[i / 3 + 1])
            canvas.drawBitmap(parts[i], null, cell, paint)
        }
    }
}

class ArrowButton(private val pointLeft: Bitmap, private val pointRight: Bitmap, private val middle: Bitmap,
                  private val endLeft: List<Bitmap>, private val endRight: List<Bitmap>) {
    private val cell = RectF()

    fun draw(canvas: Canvas, face: RectF, left: Boolean, paint: Paint) {
        val tip = face.height() * GuiSheet.ARROW_TIP
        // The end at the arrow's scale, so their bevels meet.
        val c = face.height() * 8 / 64
        if (left) {
            put(canvas, pointLeft, face.left, face.top, face.left + tip, face.bottom, paint)
            put(canvas, middle, face.left + tip, face.top, face.right - c, face.bottom, paint)
            end(canvas, endRight, face.right - c, face, c, paint)
        } else {
            end(canvas, endLeft, face.left, face, c, paint)
            put(canvas, middle, face.left + c, face.top, face.right - tip, face.bottom, paint)
            put(canvas, pointRight, face.right - tip, face.top, face.right, face.bottom, paint)
        }
    }

    private fun end(canvas: Canvas, pieces: List<Bitmap>, x: Float, face: RectF, c: Float, paint: Paint) {
        put(canvas, pieces[0], x, face.top, x + c, face.top + c, paint)
        put(canvas, pieces[1], x, face.top + c, x + c, face.bottom - c, paint)
        put(canvas, pieces[2], x, face.bottom - c, x + c, face.bottom, paint)
    }

    private fun put(canvas: Canvas, bitmap: Bitmap, l: Float, t: Float, r: Float, b: Float, paint: Paint) {
        cell.set(l, t, r, b)
        canvas.drawBitmap(bitmap, null, cell, paint)
    }
}

fun Context.dp(value: Float) = value * resources.displayMetrics.density

fun Activity.immersive() {
    window.insetsController?.apply {
        hide(WindowInsets.Type.systemBars())
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

fun Context.dpi(value: Int) = (value * resources.displayMetrics.density).roundToInt()

class FramedDrawable(context: Context, private val fill: Int, private val sunken: Boolean) : Drawable() {
    private val app = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val whole = RectF()
    private val border = context.dp(4f)
    private val depth = context.dp(6f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = RectF()
    private var shade: LinearGradient? = null

    override fun onBoundsChange(bounds: Rect) {
        whole.set(bounds)
        inner.set(bounds.left + border, bounds.top + border, bounds.right - border, bounds.bottom - border)
        shade = if (sunken) LinearGradient(0f, inner.top, 0f, inner.top + depth, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP)
                else LinearGradient(0f, inner.top, 0f, inner.top + depth / 3, 0x12FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        GuiSprites.get(app)?.let { sheet ->
            val frame = if (!sunken) sheet.frame else if (fill == Factorio.INSET_DARK) sheet.deepFrame else sheet.shallowFrame
            frame.draw(canvas, whole, GuiSheet.CORNER * density, sprite)
            return
        }
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
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

enum class Tone(val face: Int, val light: Int, val dark: Int, val glow: Int) {
    GREY(0xFF8E8E8E.toInt(), 0xFFE3E3E3.toInt(), 0xFF282828.toInt(), 0xFFE1B16A.toInt()),
    GREEN(0xFF5EB663.toInt(), 0xFF95DF99.toInt(), 0xFF163218.toInt(), 0xFF87D88B.toInt()),
    ORANGE(0xFFE39C40.toInt(), 0xFFF7CC8F.toInt(), 0xFF5C3D12.toInt(), 0xFFE1B16A.toInt()),
    RED(0xFFE8605A.toInt(), 0xFFF5A29E.toInt(), 0xFF4A1514.toInt(), 0xFFFE5A5A.toInt()),
}

enum class Arrow { NONE, LEFT, RIGHT }

class BevelDrawable(context: Context, tone: Tone, private val arrow: Arrow = Arrow.NONE,
                    private val width: Int = -1, private val height: Int = -1) : Drawable() {
    private val app = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var pathTip = -1f
    private val box = RectF()
    private val face = RectF()
    private val caretBox = RectF()
    private var sides: LinearGradient? = null
    private var pressed = false
    private var hovered = false
    private var enabled = true
    var checkmark = false
    var caret = false
    var checked = false
        set(value) { field = value; invalidateSelf() }
    var tone = tone
        set(value) { field = value; invalidateSelf() }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_pressed in state
        val hover = android.R.attr.state_focused in state || android.R.attr.state_hovered in state
        val check = android.R.attr.state_checked in state
        val on = android.R.attr.state_enabled in state
        if (now == pressed && hover == hovered && check == checked && on == enabled) return false
        pressed = now
        hovered = hover
        checked = check
        enabled = on
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        val m = MARGIN * density
        box.set(bounds)
        face.set(bounds.left + m, bounds.top + m, bounds.right - m, bounds.bottom - m)
        caretBox.set(face.right - 22 * density, face.centerY() - 8 * density, face.right - 6 * density, face.centerY() + 8 * density)
        pathTip = -1f
        val edge = 5 * density / face.width()
        sides = LinearGradient(face.left, 0f, face.right, 0f,
                               intArrayOf(0x88000000.toInt(), 0, 0, 0x88000000.toInt()),
                               floatArrayOf(0f, edge, 1 - edge, 1f), Shader.TileMode.CLAMP)
    }

    private fun outline(tip: Float): Path {
        if (tip == pathTip) return path
        pathTip = tip
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
        return path
    }

    override fun draw(canvas: Canvas) {
        GuiSprites.get(app)?.let {
            drawSprites(canvas, it)
            return
        }
        val path = outline(TIP * density)
        val lit = hovered && !pressed && enabled
        val shown = if ((pressed || lit) && tone == Tone.GREY) Tone.ORANGE else tone
        glow(shown.face, lit)
        canvas.drawPath(path, glow)
        paint.shader = null
        paint.color = when {
            pressed && tone != Tone.GREY -> darken(shown.face)
            lit && tone != Tone.GREY -> shown.light
            else -> shown.face
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
        paint.color = 0xCC000000.toInt()
        canvas.drawRect(face.left, face.bottom - line * 0.75f, face.right, face.bottom, paint)
        canvas.restore()
        if (caret) {
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

    private fun drawSprites(canvas: Canvas, sheet: GuiSheet) {
        val state = when {
            !enabled -> GuiSheet.DISABLED
            pressed -> GuiSheet.PRESSED
            hovered -> GuiSheet.HOVERED
            else -> GuiSheet.IDLE
        }
        if (checkmark) {
            canvas.drawBitmap(sheet.checkbox[state], null, box, sprite)
            if (checked) canvas.drawBitmap(sheet.check, null, box, sprite)
            return
        }
        glow(Color.BLACK, state == GuiSheet.HOVERED)
        canvas.drawPath(outline(if (arrow == Arrow.NONE) 0f else face.height() * GuiSheet.ARROW_TIP), glow)
        if (arrow == Arrow.NONE) sheet.button(tone, state).draw(canvas, face, GuiSheet.CORNER * density, sprite)
        else sheet.arrowButton(tone, state).draw(canvas, face, arrow == Arrow.LEFT, sprite)
        if (caret) sheet.caret?.let { canvas.drawBitmap(it, null, caretBox, sprite) }
    }

    private fun glow(fill: Int, lit: Boolean) {
        glow.color = fill
        glow.setShadowLayer(MARGIN * density, 0f, 0f, if (lit) tone.glow else 0xCC000000.toInt())
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

/** Its height includes the glow round the face. */
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
        val sprites = GuiSprites.get(context) != null
        alpha = if (enabled || sprites) 1f else 0.45f
        setTextColor(if (enabled || !sprites) Color.BLACK else 0xFFB3B3B3.toInt())
    }
}

class Dropdown(context: Context, private val options: List<CharSequence>, private val onSelect: (Int) -> Unit) :
    FButton(context, "", size = 15f) {
    var selected = -1
        set(value) { field = value; text = options.getOrNull(value) ?: "" }
    private var popup: PopupWindow? = null
    private val items = ArrayList<FButton>()
    private var highlight = -1

    init {
        bevel.caret = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(context.dpi(14), 0, context.dpi(30), 0)
        setOnClickListener { open() }
    }

    private fun open() {
        val list = stack(context, Factorio.PANEL, sunken = false, padding = 2, gap = 2)
        val popup = PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        items.clear()
        options.forEachIndexed { i, option ->
            items += FButton(context, option, if (i == selected) Tone.ORANGE else Tone.GREY, size = 15f).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(context.dpi(14), 0, context.dpi(14), 0)
                setOnClickListener { popup.dismiss(); selected = i; onSelect(i) }
            }
            list.addView(items.last(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(40)))
        }
        // Not focusable: a focused popup brings back the system bars, so keys stay with this button.
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.showAsDropDown(this, 0, context.dpi(2))
        this.popup = popup
        highlight(if (isFocused) selected.coerceAtLeast(0) else -1)
    }

    private fun highlight(index: Int) {
        highlight = index
        items.forEachIndexed { i, item -> item.isHovered = i == index }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val open = popup?.takeIf { it.isShowing } ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> highlight((highlight - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN -> highlight((highlight + 1).coerceAtMost(items.lastIndex))
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {}   // the list stays open
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A ->
                items.getOrNull(highlight)?.performClick() ?: open.dismiss()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> open.dismiss()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}

fun checkBox(context: Context, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(context).apply {
    val size = context.dpi(30)
    buttonDrawable = BevelDrawable(context, Tone.GREY, width = size, height = size).apply { checkmark = true }
    background = null
    isChecked = checked
    setOnCheckedChangeListener { _, value -> onChange(value) }
}

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

fun label(context: Context, text: CharSequence = "", size: Float = 15f, color: Int = Factorio.TEXT, weight: Int = 400) =
    TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Factorio.font(context, weight)
        includeFontPadding = false
    }

fun heading(context: Context, text: CharSequence, size: Float = 22f) = label(context, text, size, Factorio.HEADING, 600)

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
