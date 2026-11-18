package app.pocketfoundry.ui.kit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Tabs down the left side of a shallow frame (Frame.SHALLOW), the open one drawn as part of it: the frame's own look,
 * cut off where the frame begins, over the frame's left edge, which it overlaps by [OVERLAP] dp and draws above. The
 * others are their names alone.
 */
@SuppressLint("ViewConstructor")
class SideTabs(context: Context, private val labels: List<String>, private val onSelect: (Int) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val tab = TAB_HEIGHT * density
    private val overlap = OVERLAP * density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Factorio.font(context, 600)
        textSize = TEXT_SIZE * resources.displayMetrics.scaledDensity
    }
    private val app = context.applicationContext
    private val corner = context.dp(GuiSheet.CORNER)
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint()
    private val face = RectF()
    var selected = 0
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Drawn after the frame beside it, without the shadow a raised view would cast.
        translationZ = 1f
        outlineProvider = null
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = labels.maxOf { text.measureText(it) } + 2 * PADDING * density + overlap
        setMeasuredDimension(resolveSize(width.toInt(), widthSpec), resolveSize((labels.size * tab).toInt(), heightSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val top = selected * tab
        val right = width.toFloat()
        // The frame's look as wide again, its right side under the frame: the first tab's top meets the frame's.
        face.set(0f, top, 2 * right, top + tab)
        canvas.save()
        canvas.clipRect(0f, top, right, top + tab)
        val sheet = GuiSprites.get(app)
        if (sheet != null) {
            Frame.SHALLOW.sprites(sheet).draw(canvas, face, corner, sprite)
        } else {
            paint.color = Frame.SHALLOW.fill
            canvas.drawRect(face, paint)
        }
        canvas.restore()
        val centre = (text.ascent() + text.descent()) / 2
        labels.forEachIndexed { i, label ->
            text.color = if (i == selected) Factorio.TEXT else Factorio.MUTED
            canvas.drawText(label, PADDING * density, i * tab + tab / 2 - centre, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val i = (event.y / tab).toInt()
            if (i in labels.indices && i != selected) {
                selected = i
                onSelect(i)
            }
        }
        return true
    }

    companion object {
        /** dp into the frame beside it: its sprites' whole border, shadow and edge, onto its fill. */
        const val OVERLAP = GuiSheet.CORNER.toInt()
        private const val TAB_HEIGHT = 46
        private const val PADDING = 16
        private const val TEXT_SIZE = 16f
    }
}
