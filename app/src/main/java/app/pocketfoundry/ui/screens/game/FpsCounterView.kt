package app.pocketfoundry.ui.screens.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.widget.FrameLayout
import app.pocketfoundry.core.game.FrameCounter
import app.pocketfoundry.core.game.FrameRate
import app.pocketfoundry.ui.controller.Glass
import app.pocketfoundry.ui.controller.SpriteSet
import app.pocketfoundry.ui.controller.Movable
import app.pocketfoundry.ui.controller.Sprite
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.Tone
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The FPS counter (Settings): the game's frames a second and a graph of the last ten seconds. It reads the frames our
 * SDL counts (FrameCounter), so every driver shows the same, and the app draws it over the game: the same size at any
 * resolution, clear of rounded corners and cutouts that hid one drawn into the game's corner. The layout editor moves
 * it.
 */
@SuppressLint("ViewConstructor")
class FpsCounterView(context: Context, private val counter: FrameCounter) : View(context), Movable {
    private val rate = FrameRate()
    private val density = resources.displayMetrics.density
    /** The game's border, in pixels: its own corner clears it. */
    var border = 0
    private var spot: PointF? = null
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Factorio.TEXT
        typeface = Factorio.font(context, 700)
        textAlign = Paint.Align.RIGHT
    }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Factorio.MUTED
        typeface = Factorio.font(context, 600)
        textAlign = Paint.Align.RIGHT
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Tone.GREEN.light
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val guide = Paint().apply { color = GUIDE }
    // Set by [sizeTo]: drawn at its size rather than stretched, so the text stays sharp.
    private var scale = 0f
    /** A dp at this size. */
    private var unitPx = 0f
    private var boxWidth = 0
    private var boxHeight = 0
    // Room round the box for the rendered panel's shadow, which falls to the lower right.
    private var padLeft = 0
    private var padTop = 0
    private var padRight = 0
    private var padBottom = 0
    private val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private val graph = RectF()
    /** The number's column: two digits or "FPS", whichever is wider; it grows only while the rate has three. */
    private var column = 0f
    private val box = RectF()
    private val path = Path()
    /** Mouse & keys' look: the counter on smoked glass, as its keys' panels are, not the rendered panel. */
    var glass: Glass? = null
        set(value) {
            field = value
            pad()
            requestLayout()
            invalidate()
        }
    /** The controls' rendered sprites, while they're loaded: the panel's look, else a plain one. */
    var sprites: SpriteSet? = null
        set(value) {
            field = value
            invalidate()
        }
    private val tick = object : Runnable {
        override fun run() {
            rate.add(counter.sample(), System.nanoTime())
            invalidate()
            postDelayed(this, FrameRate.TICK_MS)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        sizeTo(1f)
    }

    private fun sizeTo(scale: Float) {
        if (scale == this.scale) return
        this.scale = scale
        unitPx = density * scale
        number.textSize = 17 * unitPx
        unit.textSize = 10 * unitPx
        line.strokeWidth = 1.5f * unitPx
        guide.strokeWidth = unitPx
        column = maxOf(number.measureText("00"), unit.measureText("FPS"))
        boxWidth = (WIDTH_DP * unitPx).roundToInt()
        boxHeight = (HEIGHT_DP * unitPx).roundToInt()
        pad()
        requestLayout()
        invalidate()
    }

    /** Room round the box for its shadow: the rendered panel's, or the glass's. */
    private fun pad() {
        val glass = glass
        if (glass != null) {
            padLeft = ceil(glass.shadowLeft).toInt()
            padTop = ceil(glass.shadowTop).toInt()
            padRight = ceil(glass.shadowRight).toInt()
            padBottom = ceil(glass.shadowBottom).toInt()
            return
        }
        padLeft = (EDGE_DP * unitPx).roundToInt()
        padTop = padLeft
        padRight = (SHADOW_RIGHT_DP * unitPx).roundToInt()
        padBottom = (SHADOW_BOTTOM_DP * unitPx).roundToInt()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(boxWidth + padLeft + padRight, boxHeight + padTop + padBottom)

    /** The box itself, without the room for its shadow. */
    override fun bounds(out: RectF) {
        if (isShown && width > 0) {
            out.set(left.toFloat() + padLeft, top.toFloat() + padTop, 0f, 0f)
            out.right = out.left + boxWidth
            out.bottom = out.top + boxHeight
        } else out.setEmpty()
    }

    override fun moveTo(spot: PointF?, scale: Float) {
        this.spot = spot
        sizeTo(scale)
        place()
    }

    /** Where the layout editor put it, kept on screen; otherwise in its own corner. */
    fun place() {
        val params = layoutParams as? FrameLayout.LayoutParams ?: return
        val screen = parent as? View ?: return
        val spot = spot
        // The spot and the corner are the box's top-left: the view starts its shadow room before it.
        val wide = boxWidth + padLeft + padRight
        val tall = boxHeight + padTop + padBottom
        val boxLeft = if (spot == null) corner(start = true).toFloat() else spot.x * screen.width
        val boxTop = if (spot == null) corner(start = false).toFloat() else spot.y * screen.height
        val left = fit(boxLeft - padLeft, screen.width - wide)
        val top = fit(boxTop - padTop, screen.height - tall)
        if (params.leftMargin == left && params.topMargin == top) return
        params.leftMargin = left
        params.topMargin = top
        layoutParams = params
    }

    private fun fit(at: Float, most: Int) = at.roundToInt().coerceIn(0, maxOf(0, most))

    /**
     * Its own place, below the screen's top-left corner: past its rounded edge, a cutout there, and the game's border.
     * [start] for the distance from the left edge, otherwise from the top.
     */
    private fun corner(start: Boolean): Int {
        val insets = rootWindowInsets
        val radius = if (Build.VERSION.SDK_INT >= 31)
            insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0 else 0
        // A box at d from both edges clears a corner of radius r once d >= r(1 - 1/sqrt 2).
        val clear = ceil(radius * 0.3).toInt()
        val cutout = insets?.displayCutout?.let { if (start) it.safeInsetLeft else it.safeInsetTop } ?: 0
        return maxOf(clear, cutout, border) + (MARGIN_DP * density).roundToInt()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(padLeft.toFloat(), padTop.toFloat())
        val rendered = if (glass == null) sprites?.get("fps-panel") else null
        if (rendered != null) drawRendered(canvas, rendered, sprites?.get("fps-glass")) else drawPlain(canvas)
        canvas.restore()
    }

    /** The rendered panel, its display lit with the number and graph at the places its entry gives. */
    private fun drawRendered(canvas: Canvas, panel: Sprite, glass: Sprite?) {
        val w = boxWidth.toFloat()
        val h = boxHeight.toFloat()
        val k = h / panel.controlH
        box.set(w / 2 - panel.cx * k, h / 2 - panel.cy * k, 0f, 0f)
        box.right = box.left + panel.frameW * k
        box.bottom = box.top + panel.frameH * k
        canvas.drawBitmap(panel.bitmap, null, box, bitmap)
        // Measures in the picture as rendered, to this view's pixels.
        val q = panel.ratio * k
        val text = panel.extra.getJSONObject("text")
        val screen = panel.extra.getJSONObject("display")
        val area = panel.extra.getJSONObject("graph")
        number.textSize = text.getDouble("numberSize").toFloat() * q
        unit.textSize = text.getDouble("unitSize").toFloat() * q
        // As the plain counter: the number's column as wide as two digits or "FPS", from the display's left edge,
        // right-aligned in it so it doesn't jump as digits change; the graph right after it.
        val inset = (area.getDouble("y") - screen.getDouble("y")).toFloat() * q
        val displayLeft = box.left + screen.getDouble("x").toFloat() * q
        val displayRight = displayLeft + screen.getDouble("w").toFloat() * q
        val digits = rate.current.roundToInt().toString()
        val right = displayLeft + inset + maxOf(number.measureText("00"), unit.measureText("FPS"), number.measureText(digits))
        canvas.drawText(digits, right, box.top + text.getDouble("numberBaseline").toFloat() * q, number)
        canvas.drawText("FPS", right, box.top + text.getDouble("unitBaseline").toFloat() * q, unit)
        graph.set(right + inset, box.top + area.getDouble("y").toFloat() * q, displayRight - inset, 0f)
        graph.bottom = graph.top + area.getDouble("h").toFloat() * q
        drawGraph(canvas, graph.left, graph.top, graph.right, graph.bottom)
        if (glass != null) canvas.drawBitmap(glass.bitmap, null, box, bitmap)
    }

    private fun drawPlain(canvas: Canvas) {
        val w = boxWidth.toFloat()
        val h = boxHeight.toFloat()
        val pad = 6 * unitPx
        number.textSize = 17 * unitPx
        unit.textSize = 10 * unitPx
        box.set(0f, 0f, w, h)
        glass?.draw(canvas, box, GLASS_RADIUS_DP * unitPx) ?: canvas.drawRoundRect(box, 4 * unitPx, 4 * unitPx, panel)
        // The number, right-aligned so it doesn't jump as digits change, "FPS" under it.
        val text = rate.current.roundToInt().toString()
        val textRight = pad + maxOf(column, number.measureText(text))
        canvas.drawText(text, textRight, h / 2 + number.textSize * 0.2f, number)
        canvas.drawText("FPS", textRight, h - pad * 0.6f, unit)
        drawGraph(canvas, textRight + pad, pad, w - pad, h - pad)
    }

    /** The last ten seconds, from 0 to the higher of 60 and the highest rate in them, over a baseline. */
    private fun drawGraph(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val history = rate.history()
        canvas.drawLine(left, bottom, right, bottom, guide)
        if (history.size < 2) return
        val ceiling = maxOf(60f, history.max())
        val step = (right - left) / (FrameRate.SAMPLES - 1)
        // Newest at the right edge; a graph that hasn't filled yet starts partway.
        val start = right - step * (history.size - 1)
        path.rewind()
        history.forEachIndexed { i, fps ->
            val x = start + i * step
            val y = bottom - (bottom - top) * (fps / ceiling).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
    }

    private companion object {
        const val WIDTH_DP = 132
        const val HEIGHT_DP = 36
        const val MARGIN_DP = 6
        /** Round the box: a hair at the top left, the rendered panel's shadow at the right and bottom. */
        const val EDGE_DP = 1
        const val SHADOW_RIGHT_DP = 5
        const val SHADOW_BOTTOM_DP = 7
        const val PANEL = 0xB0000000.toInt()
        const val GLASS_RADIUS_DP = 9
        const val GUIDE = 0x40FFFFFF
    }
}
