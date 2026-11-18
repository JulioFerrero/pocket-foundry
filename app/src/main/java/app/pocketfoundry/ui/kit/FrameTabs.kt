package app.pocketfoundry.ui.kit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Tabs along a [frame]'s left side ([vertical]) or its top, the open one drawn as part of it: the frame's own look,
 * cut off where the frame begins, over the frame's edge, which it overlaps by [OVERLAP] dp and draws above. The others
 * are their names alone.
 */
@SuppressLint("ViewConstructor")
class FrameTabs(context: Context, private val labels: List<String>, private val frame: Frame,
                private val vertical: Boolean, private val onSelect: (Int) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val tab = TAB_HEIGHT * density
    private val pad = PADDING * density
    private val overlap = OVERLAP * density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Factorio.font(context, 600)
        textSize = TEXT_SIZE * resources.displayMetrics.scaledDensity
    }
    /** Along the top, each tab as wide as its name. */
    private val widths = labels.map { text.measureText(it) + 2 * pad }
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
        val width = if (vertical) widths.max() + overlap else widths.sum()
        val height = if (vertical) labels.size * tab else tab + overlap
        setMeasuredDimension(resolveSize(width.toInt(), widthSpec), resolveSize(height.toInt(), heightSpec))
    }

    private fun start(i: Int) = if (vertical) i * tab else widths.take(i).sum()

    override fun onDraw(canvas: Canvas) {
        val from = start(selected)
        val to = from + if (vertical) tab else widths[selected]
        // The frame's look twice as long again, the far side under the frame: the first tab's edge meets the frame's.
        if (vertical) face.set(0f, from, 2f * width, to) else face.set(from, 0f, to, 2f * height)
        canvas.save()
        if (vertical) canvas.clipRect(0f, from, width.toFloat(), to) else canvas.clipRect(from, 0f, to, height.toFloat())
        val sheet = GuiSprites.get(app)
        if (sheet != null) {
            frame.sprites(sheet).draw(canvas, face, corner, sprite)
        } else {
            paint.color = frame.fill
            canvas.drawRect(face, paint)
        }
        canvas.restore()
        val centre = (text.ascent() + text.descent()) / 2
        labels.forEachIndexed { i, label ->
            text.color = if (i == selected) Factorio.TEXT else Factorio.MUTED
            if (vertical) canvas.drawText(label, pad, start(i) + tab / 2 - centre, text)
            else canvas.drawText(label, start(i) + pad, (height - overlap) / 2 - centre + overlap / 4, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val at = if (vertical) event.y else event.x
            val i = labels.indices.lastOrNull { start(it) <= at } ?: return true
            if (at < start(i) + (if (vertical) tab else widths[i]) && i != selected) {
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
