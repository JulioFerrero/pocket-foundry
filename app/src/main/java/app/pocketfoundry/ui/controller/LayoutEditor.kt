package app.pocketfoundry.ui.controller

import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.os.VibrationEffect
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.GuiSheet
import kotlin.math.hypot


/** A group of the add list, its keys in lines. */
private typealias OfferGroup = Pair<KeyGroup, List<List<Control>>>

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
    /** Mouse & keys' windows: the key picker, a key's options, a set's name. */
    var panels: KeyPanels? = null
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
        if (control.kind is Kind.Drawer) {
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
            control.drawer = null
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
        c.drawer = layout.openDrawer?.id
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
            Tool.OPTIONS -> selected?.takeIf { it.kind is Kind.Key }?.let { panels?.options(it) }
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
        for (i in drags.indices) {
            if (drags[i] === c) drags[i] = null
        }
        select(null)
        layout.remove(c)
    }

    private fun add(c: Control) {
        adding = false
        when {
            // The list's New key: the picker, then the key the player made, as any key added.
            c.id == NEW_KEY -> return panels?.pickKey("New key") { codes -> placeOwn(layout.addOwn(codes)) } ?: Unit
            // A key that only brings a set.
            c.id.startsWith(SHOWS) -> return placeOwn(layout.addOwn(IntArray(0), SET_KEY_UNITS, c.shows))
        }
        c.resetPlacement()
        // A Mouse & keys key the set doesn't have by default comes onto the screen, or into the drawer when it's open.
        c.shown = true
        c.drawer = layout.openDrawer?.id?.takeIf { c.kind is Kind.Key || c.kind is Kind.ToolKey }
        layout.refreshShown()
        select(c)
        adding = false
        haptic(ControllerView.CLICK)
    }

    private fun placeOwn(c: Control) {
        c.drawer = layout.openDrawer?.id
        layout.refreshShown()
        select(c)
        haptic(ControllerView.CLICK)
    }

    private fun addable() = layout.allControls.filter { !it.shown }

    /** Mouse & keys' list has more than the keys the set hasn't: a new key, and a key to bring each other set. */
    private fun offered(): List<Control> {
        val more = ArrayList<Control>()
        more += Control(NEW_KEY, Kind.ToolKey(Tool.ADD), 0f, 0f, Keys.KEY, glyph = null, name = "New key",
                        keyIcon = KeyIcon.PLUS, group = KeyGroup.OTHER)
        for (set in layout.setKeys) {
            if (set != layout.set) {
                more += Control(SHOWS + set, Kind.Key, 0f, 0f, Keys.KEY, glyph = null, name = layout.title(set),
                                group = KeyGroup.SETS, defaultShows = set)
            }
        }
        return addable() + more
    }

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

    /**
     * Mouse & keys' add list: the keys the set hasn't, by group, above the toolbar; a group's keys in lines of
     * [OFFER_LINE]. In one column or two, whichever lets the keys be larger; as large as a drawer's, or as fits.
     */
    private fun placeOffers() {
        offers.clear()
        headings.clear()
        val groups = offered().filter { it.group != null }.groupBy { it.group!! }.toSortedMap()
            .map { (group, keys) -> group to keys.chunked(OFFER_LINE) }
        if (groups.isEmpty()) return addPanel.setEmpty()
        // The FPS counter is a view over this one: along the top, the list keeps below it.
        counter.bounds(counterBox)
        val margin = layout.frame * 0.02f
        val ceiling = if (!counterBox.isEmpty && counterBox.top < layout.height / 4f) counterBox.bottom + margin else margin
        val roomW = layout.width * 0.96f
        val roomH = toolbar.top - margin - ceiling
        // Sizes in a key's radius: a heading's measured at 100 radii, as text is measured truer large.
        fun heads(column: List<OfferGroup>) = column.maxOf { painter.textWidth(it.first.title, 100 * HEADING) } / 100 + 0.8f
        fun wide(column: List<OfferGroup>) = heads(column) + column.maxOf { g -> g.second.maxOf { it.size } } * OFFER_CELL
        fun tall(column: List<OfferGroup>) = column.sumOf { it.second.size }
        // Two columns of as many lines as can be.
        val lines = tall(groups)
        var split = 1
        for (i in 1 until groups.size) {
            if (maxOf(tall(groups.take(i)), lines - tall(groups.take(i))) < maxOf(tall(groups.take(split)), lines - tall(groups.take(split)))) split = i
        }
        var columns = listOf(groups)
        offerR = 0f
        for (candidate in listOfNotNull(listOf(groups), if (groups.size > 1) listOf(groups.take(split), groups.drop(split)) else null)) {
            val w = candidate.sumOf { wide(it).toDouble() }.toFloat() + (candidate.size + 1) * OFFER_PAD
            val h = candidate.maxOf(::tall) * OFFER_ROW + 2 * OFFER_PAD
            val r = minOf(layout.drawerKeyRadius() * 0.8f, roomW / w, roomH / h)
            if (r > offerR) {
                offerR = r
                columns = candidate
            }
        }
        val r = offerR
        val pad = r * OFFER_PAD
        val w = columns.sumOf { wide(it).toDouble() }.toFloat() * r + (columns.size + 1) * pad
        val h = columns.maxOf(::tall) * OFFER_ROW * r + 2 * pad
        val left = (layout.width - w) / 2f
        val top = (toolbar.top - r * 0.5f - h).coerceAtLeast(ceiling)
        addPanel.set(left, top, left + w, top + h)
        var x = left + pad
        for (column in columns) {
            val heads = heads(column) * r
            var line = 0
            for ((group, keyLines) in column) {
                headings += group.title to PointF(x + (heads - r * 0.8f) / 2f, top + pad + line * OFFER_ROW * r + r * 1.2f)
                for (keys in keyLines) {
                    val y = top + pad + line++ * OFFER_ROW * r + r * 1.2f
                    keys.forEachIndexed { k, c -> offers += Offer(c, x + heads + (k + 0.5f) * OFFER_CELL * r, y) }
                }
            }
            x += wide(column) * r + pad
        }
    }

    private fun offerAt(x: Float, y: Float): Control? {
        placeOffers()
        return offers.firstOrNull { hypot(x - it.x, y - it.y) <= offerR * 1.25f }?.control
    }

    private fun drawOffers(canvas: Canvas, sheet: GuiSheet?) {
        placeOffers()
        if (addPanel.isEmpty) return
        val r = offerR
        painter.panel(canvas, sheet, addPanel, r * 0.5f)
        for ((title, at) in headings) painter.letter(canvas, title, at.x, at.y, r * HEADING, Factorio.HEADING)
        for (o in offers) {
            // A wide key is twice as wide as tall: smaller, it keeps to its cell.
            painter.drawKey(canvas, o.control, o.x, o.y, r, false, units = 1f)
            painter.caption(canvas, o.control, o.x, o.y + r * 1.55f, r * 0.42f)
        }
    }


    /**
     * Mouse & keys' sets, as chips: the editor works on the one picked; the player's own, picked again, opens its name.
     * The last chip, +, makes a new set.
     */
    fun drawSets(canvas: Canvas, sheet: GuiSheet?) {
        if (!layout.keysMode) return
        val sets = layout.setKeys
        placeChips(sets)
        val s = chipHeight()
        bounds.set(chips.first().left, chips.first().top, chips[sets.size].right, chips.first().bottom)
        painter.panel(canvas, sheet, bounds, s)
        for (i in 0..sets.size) {
            val on = i < sets.size && layout.set == sets[i]
            painter.toolKey(canvas, sheet, chips[i], on, s)
            painter.letter(canvas, if (i < sets.size) layout.title(sets[i]) else NEW_SET, chips[i].centerX(), chips[i].centerY(),
                           s * 0.42f, if (on) PadPainter.INK else PadPainter.LIGHT)
        }
    }

    private fun setsDown(x: Float, y: Float): Boolean {
        val sets = layout.setKeys
        placeChips(sets)
        val i = (0..sets.size).firstOrNull { chips[it].contains(x, y) } ?: return false
        select(null)
        haptic(ControllerView.TICK)
        when {
            i == sets.size -> panels?.set(layout.newSet())
            sets[i] == layout.set && layout.isOwnSet(sets[i]) -> panels?.set(sets[i])
            else -> layout.switchTo(sets[i])
        }
        return true
    }

    private fun placeChips(sets: List<String>) {
        val s = chipHeight()
        var left = layout.frame * 0.03f
        val top = layout.frame * 0.2f
        while (chips.size <= sets.size) chips += RectF()
        for (i in 0..sets.size) {
            val title = if (i < sets.size) layout.title(sets[i]) else NEW_SET
            val w = painter.textWidth(title, s * 0.42f) + s * 0.9f
            chips[i].set(left, top, left + w, top + s)
            left += w + s * 0.12f
        }
    }

    private fun chipHeight() = toolbar.size * 0.8f

    private class Offer(val control: Control, val x: Float, val y: Float)
    private val offers = ArrayList<Offer>()
    private val headings = ArrayList<Pair<String, PointF>>()
    private val addPanel = RectF()
    /** The add list's keys' radius, as placed. */
    private var offerR = 0f
    private val chips = ArrayList<RectF>()

    private companion object {
        const val SCALE_STEP = 0.1f
        /** An offered key's cell and row, in its radii; its group's title, the same. */
        const val OFFER_CELL = 2.9f
        const val OFFER_ROW = 3.3f
        const val HEADING = 0.55f
        const val OFFER_PAD = 0.6f
        const val OFFER_LINE = 6
        /** The add list's own entries: New key, and a key that brings a set ("shows:" and the set's key). */
        const val NEW_KEY = "new"
        const val SHOWS = "shows:"
        const val NEW_SET = "+"
        /** A key that brings a set has its name on it: wider than a letter's. */
        const val SET_KEY_UNITS = 2f
        const val NO_FINGER = -1
    }
}
