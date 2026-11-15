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
import app.pocketfoundry.ui.controller.Movable
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
    /** The number's column: two digits or "FPS", whichever is wider; it grows only while the rate has three. */
    private var column = 0f
    private val box = RectF()
    private val path = Path()
    private val tick = object : Runnable {
        override fun run() {
            rate.add(counter.frames())
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
        requestLayout()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(boxWidth, boxHeight)

    override fun bounds(out: RectF) {
        if (isShown && width > 0) out.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        else out.setEmpty()
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
        val left = if (spot == null) corner(start = true) else fit(spot.x * screen.width, screen.width - boxWidth)
        val top = if (spot == null) corner(start = false) else fit(spot.y * screen.height, screen.height - boxHeight)
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
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 6 * unitPx
        box.set(0f, 0f, w, h)
        canvas.drawRoundRect(box, 4 * unitPx, 4 * unitPx, panel)
        // The number, right-aligned so it doesn't jump as digits change, "FPS" under it.
        val text = rate.current.roundToInt().toString()
        val textRight = pad + maxOf(column, number.measureText(text))
        canvas.drawText(text, textRight, h / 2 + number.textSize * 0.2f, number)
        canvas.drawText("FPS", textRight, h - pad * 0.6f, unit)
        // The graph: the last ten seconds, from 0 to the higher of 60 and the highest rate in it.
        val history = rate.history()
        val left = textRight + pad
        val right = w - pad
        val top = pad
        val bottom = h - pad
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
        const val PANEL = 0xB0000000.toInt()
        const val GUIDE = 0x40FFFFFF
    }
}
