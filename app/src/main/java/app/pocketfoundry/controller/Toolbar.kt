package app.pocketfoundry.controller

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import app.pocketfoundry.ui.GuiSheet

enum class Tool { ADJUST, KEYBOARD, HIDE, SHOW, ADD, REMOVE, SMALLER, BIGGER, OPACITY, RESET, DONE }

/** The tools along the top middle, and the fingers holding them. */
class Toolbar(private val layout: PadLayout, private val painter: PadPainter) {
    private val held = arrayOfNulls<Tool>(ControllerView.POINTER_IDS)
    private val box = RectF()
    private val rect = RectF()
    private val path = Path()
    private val icon = painter.icon
    private val solid = painter.fill

    /** A tool's side. */
    val size get() = layout.frame * TOOL_SIZE
    val top get() = layout.frame * TOOLBAR_TOP

    /**
     * Finger [id] takes the tool under it, if any. A tool's touch area has a margin, unless a control is there: the
     * Map button sits just below.
     */
    fun press(id: Int, tools: List<Tool>, x: Float, y: Float, controlsShown: Boolean): Boolean {
        held[id] = hit(tools, x, y, 0f)
            ?: hit(tools, x, y, size * TOUCH_MARGIN)?.takeIf { !controlsShown || layout.controlAt(x, y) == null }
        return held[id] != null
    }

    /** Returns the tool finger [id] held, if any. */
    fun lift(id: Int): Tool? = held[id].also { held[id] = null }

    fun releaseAll() = held.fill(null)

    private fun hit(tools: List<Tool>, x: Float, y: Float, margin: Float): Tool? {
        for (i in tools.indices) {
            place(i, tools.size)
            box.inset(-margin, -margin)
            if (box.contains(x, y)) return tools[i]
        }
        return null
    }

    private fun place(index: Int, count: Int) {
        val s = size
        val left = layout.width / 2f - count * s / 2f + index * s
        box.set(left, top, left + s, top + s)
    }

    /** With ADD held while [adding]. */
    fun draw(canvas: Canvas, sheet: GuiSheet?, tools: List<Tool>, adding: Boolean) {
        val s = size
        place(0, tools.size)
        box.right = box.left + s * tools.size
        painter.panel(canvas, sheet, box, s)
        for (i in tools.indices) {
            val tool = tools[i]
            place(i, tools.size)
            val down = tool in held || (tool == Tool.ADD && adding)
            painter.toolKey(canvas, sheet, box, down, s)
            icon.color = if (down) PadPainter.INK else PadPainter.LIGHT
            icon.strokeWidth = s * 0.065f
            drawIcon(canvas, tool, box.centerX(), box.centerY(), s * 0.22f)
        }
    }

    private fun drawIcon(canvas: Canvas, tool: Tool, x: Float, y: Float, s: Float) {
        when (tool) {
            Tool.ADJUST -> {
                for (i in ADJUST_KNOBS.indices) {
                    val ly = y + (i - 1) * s * 0.75f
                    canvas.drawLine(x - s, ly, x + s, ly, icon)
                    canvas.drawCircle(x + ADJUST_KNOBS[i] * s, ly, s * 0.2f, icon)
                }
            }
            Tool.KEYBOARD -> {
                rect.set(x - s * 1.15f, y - s * 0.8f, x + s * 1.15f, y + s * 0.8f)
                canvas.drawRoundRect(rect, s * 0.2f, s * 0.2f, icon)
                solid.color = icon.color
                for (row in 0..1) for (col in 0..3) {
                    canvas.drawCircle(x - s * 0.66f + col * s * 0.44f, y - s * 0.38f + row * s * 0.36f, s * 0.07f, solid)
                }
                canvas.drawLine(x - s * 0.45f, y + s * 0.42f, x + s * 0.45f, y + s * 0.42f, icon)
            }
            Tool.HIDE, Tool.SHOW -> {
                path.reset()
                path.moveTo(x - s, y)
                path.quadTo(x, y - s * 1.1f, x + s, y)
                path.quadTo(x, y + s * 1.1f, x - s, y)
                canvas.drawPath(path, icon)
                canvas.drawCircle(x, y, s * 0.28f, icon)
                if (tool == Tool.HIDE) canvas.drawLine(x - s * 0.8f, y + s * 0.8f, x + s * 0.8f, y - s * 0.8f, icon)
            }
            Tool.ADD -> {
                rect.set(x - s * 0.95f, y - s * 0.95f, x + s * 0.95f, y + s * 0.95f)
                canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, icon)
                canvas.drawLine(x - s * 0.45f, y, x + s * 0.45f, y, icon)
                canvas.drawLine(x, y - s * 0.45f, x, y + s * 0.45f, icon)
            }
            Tool.REMOVE -> {
                canvas.drawLine(x - s * 0.85f, y - s * 0.55f, x + s * 0.85f, y - s * 0.55f, icon)
                canvas.drawLine(x - s * 0.3f, y - s * 0.9f, x + s * 0.3f, y - s * 0.9f, icon)
                path.reset()
                path.moveTo(x - s * 0.6f, y - s * 0.3f)
                path.lineTo(x - s * 0.45f, y + s * 0.95f)
                path.lineTo(x + s * 0.45f, y + s * 0.95f)
                path.lineTo(x + s * 0.6f, y - s * 0.3f)
                canvas.drawPath(path, icon)
            }
            Tool.SMALLER, Tool.BIGGER -> {
                canvas.drawLine(x - s * 0.7f, y, x + s * 0.7f, y, icon)
                if (tool == Tool.BIGGER) canvas.drawLine(x, y - s * 0.7f, x, y + s * 0.7f, icon)
            }
            Tool.OPACITY -> {
                canvas.drawCircle(x, y, s * 0.8f, icon)
                rect.set(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.8f)
                solid.color = icon.color
                canvas.drawArc(rect, 90f, 180f, true, solid)
            }
            Tool.RESET -> {
                rect.set(x - s * 0.75f, y - s * 0.75f, x + s * 0.75f, y + s * 0.75f)
                canvas.drawArc(rect, -60f, 290f, false, icon)
                canvas.drawLine(x + s * 0.38f, y - s * 0.65f, x + s * 0.45f, y - s * 1.05f, icon)
                canvas.drawLine(x + s * 0.38f, y - s * 0.65f, x + s * 0.8f, y - s * 0.55f, icon)
            }
            Tool.DONE -> {
                path.reset()
                path.moveTo(x - s * 0.75f, y + s * 0.05f)
                path.lineTo(x - s * 0.2f, y + s * 0.6f)
                path.lineTo(x + s * 0.8f, y - s * 0.55f)
                canvas.drawPath(path, icon)
            }
        }
    }

    companion object {
        val EDITING = listOf(Tool.ADD, Tool.REMOVE, Tool.SMALLER, Tool.BIGGER, Tool.OPACITY, Tool.RESET, Tool.DONE)
        val PLAYING = listOf(Tool.ADJUST, Tool.KEYBOARD, Tool.HIDE)
        val HIDDEN = listOf(Tool.SHOW)
        val PAD_IN_HAND = listOf(Tool.KEYBOARD)
        val NONE = emptyList<Tool>()
        /** Of the frame's height. */
        private const val TOOL_SIZE = 0.085f
        private const val TOOLBAR_TOP = 0.025f
        /** Round each tool's touch area, as a part of its side. */
        private const val TOUCH_MARGIN = 0.15f
        /** The three sliders' knobs, from -1 (left) to 1. */
        private val ADJUST_KNOBS = floatArrayOf(-0.4f, 0.45f, -0.05f)
    }
}
