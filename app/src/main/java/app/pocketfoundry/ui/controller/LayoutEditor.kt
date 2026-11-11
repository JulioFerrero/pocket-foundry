package app.pocketfoundry.ui.controller

import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.os.VibrationEffect
import app.pocketfoundry.ui.kit.GuiSheet
import kotlin.math.hypot

/** The layout editor: a finger drags a control, two pinch it, and the editor's tools change the selected one. */
class LayoutEditor(private val layout: PadLayout, private val painter: PadPainter, private val toolbar: Toolbar,
                   private val haptic: (VibrationEffect) -> Unit) {
    var active = false
    /** Showing the list of removed controls, to add one back. */
    var adding = false
        private set
    var selected: Control? = null
        private set
    private val drags = arrayOfNulls<Control>(ControllerView.POINTER_IDS)
    private val last = Array(ControllerView.POINTER_IDS) { PointF() }
    private var pinchStartDistance = 0f
    private var pinchStartScale = 1f
    private val bounds = RectF()

    fun down(id: Int, x: Float, y: Float) {
        if (adding) {
            val removed = addable()
            val s = toolbar.size
            val i = rowRects(removed).indexOfFirst { it.inset(-s * 0.06f, -s * 0.1f); it.contains(x, y) }
            if (i >= 0) return add(removed[i])
        }
        val control = layout.controlAt(x, y)
        if (control == null) {
            selected = null
            return
        }
        val other = drags.indexOfFirst { it === control }
        drags[id] = control
        last[id].set(x, y)
        if (other >= 0) {
            pinchStartDistance = hypot(x - last[other].x, y - last[other].y)
            pinchStartScale = control.scale
        } else if (selected !== control) {
            selected = control
            haptic(ControllerView.TICK)
        }
    }

    fun move(id: Int, x: Float, y: Float): Boolean {
        val control = drags[id] ?: return false
        val a = drags.indexOfFirst { it === control }
        val b = drags.indexOfLast { it === control }
        if (a != b) {
            last[id].set(x, y)
            val span = hypot(last[a].x - last[b].x, last[a].y - last[b].y)
            if (pinchStartDistance > 0f) control.scaleTo(pinchStartScale * span / pinchStartDistance)
        } else {
            layout.moveCentre(control, layout.cx(control) + x - last[id].x, layout.cy(control) + y - last[id].y)
            last[id].set(x, y)
        }
        return true
    }

    fun up(id: Int) {
        drags[id] = null
    }

    fun releaseAll() = drags.fill(null)

    fun use(tool: Tool) {
        when (tool) {
            Tool.ADD -> adding = !adding
            Tool.REMOVE -> selected?.let { remove(it) }
            Tool.SMALLER -> selected?.let { it.scaleTo(it.scale - SCALE_STEP) }
            Tool.BIGGER -> selected?.let { it.scaleTo(it.scale + SCALE_STEP) }
            Tool.OPACITY -> layout.nextOpacity()
            Tool.RESET -> {
                layout.reset()
                selected = null
                adding = false
            }
            Tool.DONE -> {
                active = false
                adding = false
                selected = null
                releaseAll()
                layout.save()
            }
            else -> {}   // the play tools are ControllerView's
        }
    }

    private fun remove(c: Control) {
        c.shown = false
        for (i in drags.indices) {
            if (drags[i] === c) drags[i] = null
        }
        selected = null
        layout.refreshShown()
    }

    private fun add(c: Control) {
        c.resetPlacement()
        layout.refreshShown()
        selected = c
        adding = false
        haptic(ControllerView.CLICK)
    }

    private fun addable() = layout.allControls.filter { !it.shown }

    private fun rowRects(controls: List<Control>): List<RectF> {
        val h = toolbar.size
        val widths = controls.map { maxOf(h * 1.1f, painter.textWidth(it.name, h * 0.42f) + h * 0.7f) }
        val gap = h * 0.12f
        val rows = ArrayList<MutableList<Int>>()
        var used = 0f
        widths.forEachIndexed { i, w ->
            if (rows.isEmpty() || used + gap + w > layout.width * 0.9f) { rows += mutableListOf(i); used = w }
            else { rows.last() += i; used += gap + w }
        }
        val rects = List(controls.size) { RectF() }
        rows.forEachIndexed { row, keys ->
            var left = layout.width / 2f - (keys.sumOf { widths[it].toDouble() }.toFloat() + gap * (keys.size - 1)) / 2f
            val top = toolbar.top + h * (1.35f + row * 1.2f)
            for (i in keys) { rects[i].set(left, top, left + widths[i], top + h); left += widths[i] + gap }
        }
        return rects
    }

    fun drawAddList(canvas: Canvas, sheet: GuiSheet?) {
        val controls = addable()
        val rects = rowRects(controls)
        if (rects.isEmpty()) return
        val s = toolbar.size
        bounds.set(rects.minOf { it.left }, rects.first().top, rects.maxOf { it.right }, rects.last().bottom)
        painter.panel(canvas, sheet, bounds, s)
        rects.forEachIndexed { i, box ->
            painter.toolKey(canvas, sheet, box, false, s)
            painter.letter(canvas, controls[i].name, box.centerX(), box.centerY(), s * 0.42f,
                           if (sheet != null) PadPainter.LIGHT else PadPainter.GLYPH)
        }
    }

    private companion object {
        const val SCALE_STEP = 0.1f
    }
}
