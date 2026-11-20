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
        if (adding) {
            val addable = addable()
            val s = toolbar.size
            val i = rowRects(addable).indexOfFirst { it.inset(-s * 0.06f, -s * 0.1f); it.contains(x, y) }
            if (i >= 0) return add(addable[i])
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

    /**
     * The add list: the controls the layout hasn't. Mouse & keys' has a new key first, then the keys the set hasn't,
     * then a key to bring each other set.
     */
    private fun addable(): List<Control> {
        val missing = layout.allControls.filter { !it.shown }
        if (!layout.keysMode) return missing
        return listOf(newKey) + missing + layout.setKeys.filter { it != layout.set }.map { set ->
            setKeys.getOrPut(set) {
                Control(SHOWS + set, Kind.Key, 0f, 0f, Keys.KEY, glyph = null, name = "", defaultShows = set,
                        defaultUnits = SET_KEY_UNITS)
            }
        }
    }

    // Made once: the sprites follow a key's press by its object.
    private val newKey = Control(NEW_KEY, Kind.ToolKey(Tool.ADD), 0f, 0f, Keys.KEY, glyph = null, label = "",
                                 name = "New key", keyIcon = KeyIcon.PLUS)
    private val setKeys = HashMap<String, Control>()

    /** In Mouse & keys' list, a key's cap: its own width up to 2u; what isn't a key, one. */
    private fun capUnits(c: Control) =
        if (c.kind is Kind.Key || c.kind is Kind.ToolKey || c.kind is Kind.Drawer) c.units.coerceIn(1f, 2f) else 1f

    /** Beside a key in Mouse & keys' list: what it does, when its cap doesn't say (Undo by Ctrl+Z; nothing by 5). */
    private fun note(c: Control) = c.name.takeIf { layout.captioned(c) }.orEmpty()

    /** A pad control's button, its name on it; a key's cap and what it does. [h]: a toolbar key's size. */
    private fun itemWidth(c: Control, h: Float): Float {
        if (!layout.keysMode) return maxOf(h * 1.1f, painter.textWidth(c.name, h * KEY_TEXT) + h * 0.7f)
        val note = note(c)
        return 2 * OFFER_CAP * h * capUnits(c) + if (note.isEmpty()) 0f else h * NOTE_GAP + painter.noteWidth(note, h * NOTE_TEXT)
    }

    private fun rowRects(controls: List<Control>): List<RectF> {
        val h = toolbar.size
        val widths = controls.map { itemWidth(it, h) }
        val gap = h * if (layout.keysMode) 0.26f else 0.12f
        val rows = ArrayList<MutableList<Int>>()
        var used = 0f
        widths.forEachIndexed { i, w ->
            if (rows.isEmpty() || used + gap + w > layout.width * 0.9f) { rows += mutableListOf(i); used = w }
            else { rows.last() += i; used += gap + w }
        }
        val rects = List(controls.size) { RectF() }
        // Out from the toolbar: under it at the top, over it at the bottom (Mouse & keys').
        val under = toolbar.top < layout.height / 2f
        rows.forEachIndexed { row, keys ->
            var left = layout.width / 2f - (keys.sumOf { widths[it].toDouble() }.toFloat() + gap * (keys.size - 1)) / 2f
            val top = if (under) toolbar.top + h * (1.35f + row * 1.2f) else toolbar.top - h * (1.35f + (rows.size - 1 - row) * 1.2f)
            for (i in keys) { rects[i].set(left, top, left + widths[i], top + h); left += widths[i] + gap }
        }
        return rects
    }

    fun drawAddList(canvas: Canvas, sheet: GuiSheet?, now: Long) {
        val controls = addable()
        val rects = rowRects(controls)
        if (rects.isEmpty()) return
        val s = toolbar.size
        bounds.set(rects.minOf { it.left }, rects.first().top, rects.maxOf { it.right }, rects.last().bottom)
        if (layout.keysMode) {
            // The keys as they'll be, on the glass.
            bounds.inset(-s * 0.3f, -s * 0.18f)
            painter.glass.draw(canvas, bounds)
            val r = OFFER_CAP * s
            rects.forEachIndexed { i, box ->
                val c = controls[i]
                val units = capUnits(c)
                painter.offer(canvas, c, box.left + r * units, box.centerY(), r, units, now)
                val note = note(c)
                if (note.isNotEmpty()) painter.note(canvas, note, box.left + 2 * r * units + s * NOTE_GAP, box.centerY(), s * NOTE_TEXT)
            }
            return
        }
        painter.panel(canvas, sheet, bounds, s)
        val ink = if (sheet != null) PadPainter.LIGHT else PadPainter.GLYPH
        rects.forEachIndexed { i, box ->
            painter.toolKey(canvas, sheet, box, false, s)
            painter.letter(canvas, controls[i].name, box.centerX(), box.centerY(), s * KEY_TEXT, ink)
        }
    }

    /**
     * Mouse & keys' sets, as chips: the editor works on the one picked; the player's own, picked again, opens its name.
     * The last chip, +, makes a new set.
     */
    fun drawSets(canvas: Canvas) {
        if (!layout.keysMode) return
        val sets = layout.setKeys
        placeChips(sets)
        val s = chipHeight()
        bounds.set(chips.first().left, chips.first().top, chips[sets.size].right, chips.first().bottom)
        bounds.inset(-s * 0.14f, -s * 0.14f)
        painter.glass.draw(canvas, bounds, s * 0.3f)
        for (i in 0..sets.size) {
            val on = i < sets.size && layout.set == sets[i]
            painter.chip(canvas, chips[i], on, s)
            painter.letter(canvas, if (i < sets.size) layout.title(sets[i]) else NEW_SET, chips[i].centerX(), chips[i].centerY(),
                           s * 0.42f, if (on) PadPainter.INK_ON_LIGHT else PadPainter.INK_ON_DARK)
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

    private val chips = ArrayList<RectF>()

    private companion object {
        const val SCALE_STEP = 0.1f
        /** The add list's words, of a button's height: a pad control's name, and what a key does beside its cap. */
        const val KEY_TEXT = 0.42f
        const val NOTE_TEXT = 0.34f
        const val NOTE_GAP = 0.1f
        /** An offered key's cap's half height, of a toolbar key's size. */
        const val OFFER_CAP = 0.46f
        /** The add list's own entries: New key, and a key that brings a set ("shows:" and the set's key). */
        const val NEW_KEY = "new"
        const val SHOWS = "shows:"
        const val NEW_SET = "+"
        /** A key that brings a set has its name on it: wider than a letter's. */
        const val SET_KEY_UNITS = 2f
        const val NO_FINGER = -1
    }
}
