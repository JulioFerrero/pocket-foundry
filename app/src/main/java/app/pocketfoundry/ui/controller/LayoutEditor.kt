package app.pocketfoundry.ui.controller

import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.os.VibrationEffect
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.GuiSheet
import kotlin.math.hypot

/** Something over the game that the editor moves with the controls and their layout keeps: the FPS counter. */
interface Movable {
    /** Its box on screen, empty while it isn't showing. */
    fun bounds(out: RectF)

    /** Puts its top-left corner at [spot] (fractions of the screen), or in its own place for null, at [scale]. */
    fun moveTo(spot: PointF?, scale: Float)
}

/**
 * The layout editor: a finger drags a control, two pinch it, and the editor's tools change the selected one. The same
 * goes for [counter], except that a second finger pinches it from anywhere: two wouldn't fit on it.
 */
class LayoutEditor(private val layout: PadLayout, private val painter: PadPainter, private val toolbar: Toolbar,
                   private val counter: Movable, private val haptic: (VibrationEffect) -> Unit) {
    var active = false
    /** Showing the list of removed controls, to add one back. */
    var adding = false
        private set
    var selected: Control? = null
        private set
    var counterSelected = false
        private set
    private val drags = arrayOfNulls<Control>(ControllerView.POINTER_IDS)
    private val last = Array(ControllerView.POINTER_IDS) { PointF() }
    private var pinchStartDistance = 0f
    private var pinchStartScale = 1f
    private val bounds = RectF()
    private var counterFinger = NO_FINGER
    private var counterPinch = NO_FINGER
    /** Where the counter's finger is from the counter's top-left corner. */
    private val grab = PointF()
    private val counterBox = RectF()

    fun down(id: Int, x: Float, y: Float) {
        if (adding && layout.keysMode) {
            val offer = offerAt(x, y)
            if (offer != null) return add(offer)
        } else if (adding) {
            val removed = addable()
            val s = toolbar.size
            val i = rowRects(removed).indexOfFirst { it.inset(-s * 0.06f, -s * 0.1f); it.contains(x, y) }
            if (i >= 0) return add(removed[i])
        }
        if (layout.keysMode && setsDown(x, y)) return
        last[id].set(x, y)
        if (counterFinger != NO_FINGER && counterPinch == NO_FINGER) {
            counterPinch = id
            pinchStartDistance = hypot(x - last[counterFinger].x, y - last[counterFinger].y)
            pinchStartScale = layout.counterScale
            return
        }
        // The counter is drawn over the controls, so it comes first.
        counter.bounds(counterBox)
        if (counterFinger == NO_FINGER && counterBox.contains(x, y)) {
            counterFinger = id
            grab.set(x - counterBox.left, y - counterBox.top)
            if (!counterSelected) haptic(ControllerView.TICK)
            select(null, counter = true)
            return
        }
        val control = layout.drawerKeyAt(x, y) ?: layout.controlAt(x, y)
        if (control == null) {
            select(null)
            layout.closeDrawer()
            return
        }
        // The drawer opens as it's picked, for its keys to be picked; picked again, it shuts.
        if (control.kind == Kind.Drawer) {
            if (selected === control && layout.openDrawer === control) layout.closeDrawer() else layout.showDrawer(control)
        }
        val other = drags.indexOfFirst { it === control }
        drags[id] = control
        if (other >= 0) {
            pinchStartDistance = hypot(x - last[other].x, y - last[other].y)
            pinchStartScale = control.scale
        } else if (selected !== control) {
            select(control)
            haptic(ControllerView.TICK)
        }
    }

    private fun select(control: Control?, counter: Boolean = false) {
        selected = control
        counterSelected = counter
    }

    fun move(id: Int, x: Float, y: Float): Boolean {
        if (id == counterFinger || id == counterPinch) {
            last[id].set(x, y)
            if (counterPinch != NO_FINGER) {
                val span = hypot(last[counterFinger].x - last[counterPinch].x, last[counterFinger].y - last[counterPinch].y)
                if (pinchStartDistance > 0f) layout.counterScale = editorScale(pinchStartScale * span / pinchStartDistance)
            } else {
                val left = (x - grab.x).coerceIn(0f, maxOf(0f, layout.width - counterBox.width()))
                val top = (y - grab.y).coerceIn(0f, maxOf(0f, layout.height - counterBox.height()))
                layout.counter = PointF(left / layout.width, top / layout.height)
            }
            placeCounter()
            return true
        }
        val control = drags[id] ?: return false
        if (control.inDrawer) {
            // A drawer key stays in its cell till it's dragged off the panel, onto the screen.
            if (layout.drawerPanel.contains(x, y)) return false
            control.inDrawer = false
            layout.refreshShown()
            layout.moveCentre(control, x, y)
            last[id].set(x, y)
            return true
        }
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
        val dropped = drags[id]
        drags[id] = null
        if (dropped != null) intoDrawer(dropped)
        if (id != counterFinger && id != counterPinch) return
        // After a pinch the finger left drags on, from where it is on the counter's new size.
        counterFinger = if (id == counterFinger) counterPinch else counterFinger
        counterPinch = NO_FINGER
        if (counterFinger == NO_FINGER) return
        counter.bounds(counterBox)
        grab.set(last[counterFinger].x - counterBox.left, last[counterFinger].y - counterBox.top)
    }

    /** A key let go of over the open drawer's panel goes into the drawer. */
    private fun intoDrawer(c: Control) {
        if (layout.openDrawer == null || c.inDrawer || c.kind !is Kind.Key && c.kind !is Kind.ToolKey) return
        if (drags.any { it === c } || !layout.drawerPanel.contains(layout.cx(c), layout.cy(c))) return
        c.inDrawer = true
        layout.refreshShown()
    }

    fun releaseAll() {
        drags.fill(null)
        counterFinger = NO_FINGER
        counterPinch = NO_FINGER
    }

    private fun placeCounter() = counter.moveTo(layout.counter, layout.counterScale)

    /** The selected control or the counter, a step bigger or smaller. */
    private fun resize(by: Float) {
        selected?.let { it.scaleTo(it.scale + by) }
        if (!counterSelected) return
        layout.counterScale = editorScale(layout.counterScale + by)
        placeCounter()
    }

    fun use(tool: Tool) {
        when (tool) {
            Tool.ADD -> adding = !adding
            Tool.REMOVE -> selected?.takeUnless(Keys::kept)?.let { remove(it) }
            Tool.SMALLER -> resize(-SCALE_STEP)
            Tool.BIGGER -> resize(SCALE_STEP)
            Tool.OPACITY -> layout.nextOpacity()
            Tool.LATCH -> selected?.takeIf { it.kind is Kind.Key }?.let { it.latch = !it.latch }
            Tool.RESET -> {
                layout.closeDrawer()
                layout.reset()
                placeCounter()
                select(null)
                adding = false
            }
            Tool.DONE -> {
                active = false
                adding = false
                layout.closeDrawer()
                select(null)
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
        select(null)
        layout.refreshShown()
    }

    private fun add(c: Control) {
        c.resetPlacement()
        // A Mouse & keys key the set doesn't have by default comes onto the screen, or into the drawer when it's open.
        c.shown = true
        c.inDrawer = layout.openDrawer != null && (c.kind is Kind.Key || c.kind is Kind.ToolKey)
        layout.refreshShown()
        select(c)
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
        if (layout.keysMode) return drawOffers(canvas, sheet)
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

    /** Mouse & keys' add list: the keys the set hasn't, a row for each group, over the toolbar. */
    private fun placeOffers() {
        offers.clear()
        headings.clear()
        val groups = addable().filter { it.group != null }.groupBy { it.group!! }.toSortedMap()
        if (groups.isEmpty()) return addPanel.setEmpty()
        val r = offerRadius()
        val cell = r * OFFER_CELL
        val row = r * OFFER_ROW
        val pad = r * 0.6f
        val heads = groups.keys.maxOf { painter.textWidth(it.title, r * HEADING) } + r * 0.8f
        val w = heads + groups.values.maxOf { it.size } * cell + 2 * pad
        val h = groups.size * row + 2 * pad
        val left = (layout.width - w) / 2f
        val top = (toolbar.top - r * 0.5f - h).coerceAtLeast(r * 0.4f)
        addPanel.set(left, top, left + w, top + h)
        var i = 0
        for ((group, keys) in groups) {
            val y = top + pad + i++ * row + r * 1.2f
            headings += group.title to PointF(left + pad + (heads - r * 0.8f) / 2f, y)
            keys.forEachIndexed { k, c -> offers += Offer(c, left + pad + heads + (k + 0.5f) * cell, y) }
        }
    }

    private fun offerAt(x: Float, y: Float): Control? {
        placeOffers()
        val r = offerRadius()
        return offers.firstOrNull { hypot(x - it.x, y - it.y) <= r * 1.25f }?.control
    }

    private fun drawOffers(canvas: Canvas, sheet: GuiSheet?) {
        placeOffers()
        if (addPanel.isEmpty) return
        val r = offerRadius()
        painter.panel(canvas, sheet, addPanel, r * 0.5f)
        for ((title, at) in headings) painter.letter(canvas, title, at.x, at.y, r * HEADING, Factorio.HEADING)
        for (o in offers) {
            // A wide key is twice as wide as tall: smaller, it keeps to its cell.
            painter.drawKey(canvas, o.control, o.x, o.y, if (o.control.pill) r * WIDE_OFFER else r, false)
            painter.letter(canvas, o.control.name, o.x, o.y + r * 1.55f, r * 0.42f, PadPainter.LIGHT)
        }
    }

    private fun offerRadius() = layout.drawerKeyRadius() * 0.8f

    /** Mouse & keys' sets, as chips: the editor works on the one picked. */
    fun drawSets(canvas: Canvas, sheet: GuiSheet?) {
        if (!layout.keysMode) return
        placeChips()
        val s = chipHeight()
        bounds.set(chips.first().left, chips.first().top, chips.last().right, chips.last().bottom)
        painter.panel(canvas, sheet, bounds, s)
        KeySet.KEYS.forEachIndexed { i, set ->
            val on = layout.set == set
            painter.toolKey(canvas, sheet, chips[i], on, s)
            painter.letter(canvas, set.title, chips[i].centerX(), chips[i].centerY(), s * 0.42f,
                           if (on) PadPainter.INK else PadPainter.LIGHT)
        }
    }

    private fun setsDown(x: Float, y: Float): Boolean {
        placeChips()
        val i = chips.indexOfFirst { it.contains(x, y) }
        if (i < 0) return false
        layout.switchTo(KeySet.KEYS[i])
        select(null)
        haptic(ControllerView.TICK)
        return true
    }

    private fun placeChips() {
        val s = chipHeight()
        var left = layout.frame * 0.03f
        val top = layout.frame * 0.2f
        KeySet.KEYS.forEachIndexed { i, set ->
            val w = painter.textWidth(set.title, s * 0.42f) + s * 0.9f
            chips[i].set(left, top, left + w, top + s)
            left += w + s * 0.12f
        }
    }

    private fun chipHeight() = toolbar.size * 0.8f

    private class Offer(val control: Control, val x: Float, val y: Float)
    private val offers = ArrayList<Offer>()
    private val headings = ArrayList<Pair<String, PointF>>()
    private val addPanel = RectF()
    private val chips = Array(KeySet.KEYS.size) { RectF() }

    private companion object {
        const val SCALE_STEP = 0.1f
        /** An offered key's cell and row, in its radii; its group's title, the same. */
        const val OFFER_CELL = 2.9f
        const val OFFER_ROW = 3.3f
        const val HEADING = 0.55f
        const val WIDE_OFFER = 0.68f
        const val NO_FINGER = -1
    }
}
