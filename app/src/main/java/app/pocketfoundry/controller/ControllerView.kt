package app.pocketfoundry.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import app.pocketfoundry.InputBridge
import app.pocketfoundry.PadAxis
import app.pocketfoundry.PadButton
import app.pocketfoundry.Settings
import app.pocketfoundry.ui.Factorio
import app.pocketfoundry.ui.Tone
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/** Touches reach it from [TouchRouter], not through the view system. */
@SuppressLint("ViewConstructor")
class ControllerView(context: Context, private val touch: Boolean) : View(context) {

    private enum class Kind { STICK, DPAD, BUTTON, TRIGGER, MOVE, KEY, TOGGLE }

    private class Control(
        val id: String,
        val kind: Kind,
        val label: String,
        val defaultX: Float,
        val defaultY: Float,
        /** Diameter (or pill height), a fraction of the frame's height. */
        val size: Float,
        val button: PadButton? = null,
        val axis: PadAxis? = null,
        val axisY: PadAxis? = null,
        val pill: Boolean = false,
        val key: Int = 0,
        val name: String = label,
        val extra: Boolean = false,
        val latch: Boolean = false,
    ) {
        var shown = !extra
        var x = defaultX
        var y = defaultY
        var scale = 1f
        /** Placed from the top middle, by the toolbar, rather than a bottom corner. */
        val byToolbar = defaultY < 0.2f
        val pointers = LinkedHashSet<Int>()
        var originX = 0f
        var originY = 0f
        var knobX = 0f
        var knobY = 0f
        var sentX = 0
        var sentY = 0
        var dpad = 0
        var on = false
    }

    private enum class Tool { ADJUST, KEYS, KEYBOARD, HIDE, SHOW, ADD, REMOVE, SMALLER, BIGGER, OPACITY, RESET, DONE }

    /** Key 0 opens the phone's keyboard. */
    private class StripKey(val label: String, val key: Int, val latch: Boolean = false) {
        var down = false
    }
    private val strip = listOf(
        StripKey("Tab", KeyEvent.KEYCODE_TAB),
        StripKey("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, latch = true),
        StripKey("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, latch = true),
        StripKey("Alt", KeyEvent.KEYCODE_ALT_LEFT),
        StripKey("F", KeyEvent.KEYCODE_F),
        StripKey("Z", KeyEvent.KEYCODE_Z),
        StripKey("T", KeyEvent.KEYCODE_T),
        StripKey("Space", KeyEvent.KEYCODE_SPACE),
        StripKey("abc", 0),
    )
    private var stripOpen = false
    private val stripPointers = HashMap<Int, StripKey>()

    private val all = if (touch) listOf(
        Control("MOVE", Kind.MOVE, "", 0.085f, 0.70f, 0.28f, name = "Walk"),
        // Factorio's default keys, on the right edge, clear of the quickbar.
        Control("INVENTORY", Kind.KEY, "", 0.955f, 0.30f, 0.115f, key = KeyEvent.KEYCODE_E, name = "Inventory"),
        Control("ROTATE", Kind.KEY, "", 0.955f, 0.425f, 0.115f, key = KeyEvent.KEYCODE_R, name = "Rotate"),
        Control("PIPETTE", Kind.KEY, "", 0.955f, 0.55f, 0.115f, key = KeyEvent.KEYCODE_Q, name = "Pipette"),
        Control("MINE", Kind.TOGGLE, "", 0.955f, 0.675f, 0.115f, name = "Mine"),
        Control("MAP", Kind.KEY, "", 0.955f, 0.80f, 0.115f, key = KeyEvent.KEYCODE_M, name = "Map"),
        Control("MENU", Kind.KEY, "", 0.955f, 0.925f, 0.115f, key = KeyEvent.KEYCODE_ESCAPE, name = "Menu"),
        Control("TAB", Kind.KEY, "Tab", 0.885f, 0.30f, 0.115f, key = KeyEvent.KEYCODE_TAB, extra = true),
        Control("SHIFT", Kind.KEY, "Shift", 0.885f, 0.425f, 0.115f, key = KeyEvent.KEYCODE_SHIFT_LEFT, extra = true, latch = true),
        Control("CTRL", Kind.KEY, "Ctrl", 0.885f, 0.55f, 0.115f, key = KeyEvent.KEYCODE_CTRL_LEFT, extra = true, latch = true),
        Control("ALT", Kind.KEY, "Alt", 0.885f, 0.675f, 0.115f, key = KeyEvent.KEYCODE_ALT_LEFT, extra = true),
        Control("F", Kind.KEY, "F", 0.885f, 0.80f, 0.115f, key = KeyEvent.KEYCODE_F, extra = true),
        Control("Z", Kind.KEY, "Z", 0.885f, 0.925f, 0.115f, key = KeyEvent.KEYCODE_Z, extra = true),
        Control("T", Kind.KEY, "T", 0.815f, 0.80f, 0.115f, key = KeyEvent.KEYCODE_T, extra = true),
        Control("SPACE", Kind.KEY, "Space", 0.815f, 0.925f, 0.115f, key = KeyEvent.KEYCODE_SPACE, extra = true),
    ) else listOf(
        // Sized by use in Factorio: gestures press A, X and B ([PointerGestures]), so Y, the inventory, is largest.
        Control("LS", Kind.STICK, "L", 0.10f, 0.70f, 0.28f, axis = PadAxis.LEFT_X, axisY = PadAxis.LEFT_Y, name = "L stick"),
        Control("DPAD", Kind.DPAD, "", 0.245f, 0.88f, 0.20f, name = "D-pad"),
        Control("Y", Kind.BUTTON, "Y", 0.885f, 0.635f, 0.125f, PadButton.NORTH),
        Control("A", Kind.BUTTON, "A", 0.885f, 0.895f, 0.13f, PadButton.SOUTH),
        Control("X", Kind.BUTTON, "X", 0.827f, 0.765f, 0.115f, PadButton.WEST),
        Control("B", Kind.BUTTON, "B", 0.943f, 0.765f, 0.115f, PadButton.EAST),
        Control("LB", Kind.BUTTON, "LB", 0.075f, 0.42f, 0.10f, PadButton.LEFT_SHOULDER, pill = true),
        Control("LT", Kind.TRIGGER, "LT", 0.17f, 0.42f, 0.085f, axis = PadAxis.LEFT_TRIGGER, pill = true),
        Control("RB", Kind.BUTTON, "RB", 0.83f, 0.47f, 0.085f, PadButton.RIGHT_SHOULDER, pill = true),
        Control("RT", Kind.TRIGGER, "RT", 0.925f, 0.47f, 0.10f, axis = PadAxis.RIGHT_TRIGGER, pill = true),
        Control("VIEW", Kind.BUTTON, "", 0.4715f, 0.18f, 0.085f, PadButton.BACK, name = "Map"),
        Control("MENU", Kind.BUTTON, "", 0.5285f, 0.18f, 0.085f, PadButton.START, name = "Menu"),
        Control("RS", Kind.STICK, "R", 0.64f, 0.74f, 0.24f, axis = PadAxis.RIGHT_X, axisY = PadAxis.RIGHT_Y,
                name = "R stick", extra = true),
        Control("L3", Kind.BUTTON, "L3", 0.215f, 0.56f, 0.085f, PadButton.LEFT_STICK, extra = true),
        Control("R3", Kind.BUTTON, "R3", 0.77f, 0.58f, 0.085f, PadButton.RIGHT_STICK, extra = true),
    )
    private var controls = all.filter { it.shown }
    private var adding = false

    private val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)
    private val layoutKey = if (touch) "layout-touch" else "layout-edges"
    /** An older layout that saved every position: only its opacity and hiding carry over. */
    private val oldLayoutKey = if (touch) null else "layout-hybrid"
    private val vibrator = context.getSystemService(Vibrator::class.java)

    /** (Android key code, down). */
    var keys: (Int, Boolean) -> Unit = { _, _ -> }

    var onKeyboard: () -> Unit = {}

    val mining get() = controls.any { it.kind == Kind.TOGGLE && it.on }

    private var editing = false
    private var hidden = false

    /** Touch controls stay, as their game ignores the pad. */
    var padInHand = false
        set(value) {
            if (touch || field == value) return
            field = value
            handsChanged()
        }

    var keyboardInHand = false
        set(value) {
            if (!touch || field == value) return
            field = value
            handsChanged()
        }

    private val stepAside get() = (padInHand || keyboardInHand) && !editing

    private fun handsChanged() {
        if (stepAside) {
            releaseAll()
            controls.forEach { if (it.kind == Kind.TOGGLE) it.on = false }   // else touches keep mining with the pickaxe hidden
        }
        invalidate()
    }
    private var opacity = DEFAULT_OPACITY

    private val toolPointers = HashMap<Int, Tool>()

    private val drags = HashMap<Int, Control>()
    private val dragLast = HashMap<Int, Pair<Float, Float>>()
    private var selected: Control? = null
    private var pinchStartDistance = 0f
    private var pinchStartScale = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Factorio.font(context, 700)
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val cross = Path()
    private val bar = Path()
    private val rect = RectF()

    init {
        load()
        controls = all.filter { it.shown }
        alpha = opacity
    }

    fun reload() {
        load()
        controls = all.filter { it.shown }
        alpha = opacity
        invalidate()
    }

    fun startEditing() {
        releaseAll()
        editing = true
        invalidate()
    }

    // Coordinates are the view's.

    fun pointerDown(id: Int, x: Float, y: Float): Boolean {
        toolAt(x, y)?.let { toolPointers[id] = it; invalidate(); return true }
        if (stepAside) return false
        if (stripOpen && !editing) stripAt(x, y)?.let { pressStrip(id, it); return true }
        if (hidden) return false

        if (editing && adding) {
            val extra = addable()
            val i = rowAt(extra.map { it.name }, x, y)
            if (i >= 0) { add(extra[i]); return true }
        }
        val control = controlAt(x, y)
        if (editing) {
            if (control != null) {
                val pinching = drags.values.firstOrNull { it === control }
                drags[id] = control
                dragLast[id] = x to y
                if (pinching != null) {
                    val other = drags.entries.first { it.value === control && it.key != id }.key
                    val (ox, oy) = dragLast.getValue(other)
                    pinchStartDistance = hypot(x - ox, y - oy)
                    pinchStartScale = control.scale
                } else if (selected !== control) {
                    selected = control
                    haptic(VibrationEffect.EFFECT_TICK)
                }
            } else {
                selected = null
            }
            invalidate()
            return true
        }

        control ?: return false
        val first = control.pointers.isEmpty()
        control.pointers += id
        if (!first) return true
        when (control.kind) {
            Kind.BUTTON -> {
                InputBridge.button(control.button!!, true)
                haptic(VibrationEffect.EFFECT_CLICK)
            }
            Kind.TRIGGER -> {
                InputBridge.axis(control.axis!!, 32767)
                haptic(VibrationEffect.EFFECT_CLICK)
            }
            Kind.STICK, Kind.MOVE -> {
                control.originX = x; control.originY = y
                control.knobX = x; control.knobY = y
            }
            Kind.DPAD -> updateDpad(control, x, y)
            Kind.KEY -> {
                if (control.latch) control.on = !control.on
                keys(control.key, !control.latch || control.on)
                haptic(VibrationEffect.EFFECT_CLICK)
            }
            Kind.TOGGLE -> {
                control.on = !control.on
                haptic(VibrationEffect.EFFECT_CLICK)
            }
        }
        invalidate()
        return true
    }

    fun pointerMove(id: Int, x: Float, y: Float) {
        if (editing) {
            moveInEditor(id, x, y)
            return
        }
        val control = controls.firstOrNull { it.pointers.firstOrNull() == id } ?: return
        when (control.kind) {
            Kind.STICK -> updateStick(control, x, y)
            Kind.MOVE -> updateMove(control, x, y)
            Kind.DPAD -> updateDpad(control, x, y)
            else -> return
        }
        invalidate()
    }

    fun pointerUp(id: Int) {
        toolPointers.remove(id)?.let { tool ->
            useTool(tool)
            return
        }
        stripPointers.remove(id)?.let { key ->
            when {
                key.key == 0 -> onKeyboard()
                !key.latch && key.down && key !in stripPointers.values -> { keys(key.key, false); key.down = false }
            }
            invalidate()
            return
        }
        if (drags.remove(id) != null) {
            dragLast.remove(id)
            return
        }
        val control = controls.firstOrNull { id in it.pointers } ?: return
        val wasFirst = control.pointers.first() == id
        control.pointers -= id
        if (control.pointers.isNotEmpty()) {
            // The resting finger takes over, recentred where it is.
            if (wasFirst && (control.kind == Kind.STICK || control.kind == Kind.MOVE || control.kind == Kind.DPAD)) {
                release(control)
                control.originX = Float.NaN
                invalidate()
            }
            return
        }
        release(control)
        invalidate()
    }

    fun releaseAll() {
        toolPointers.clear()
        stripPointers.clear()
        strip.forEach { if (it.down) { keys(it.key, false); it.down = false } }
        drags.clear()
        dragLast.clear()
        controls.forEach { c ->
            // A key goes up only if it was down; pad controls are simply zeroed.
            val held = c.pointers.isNotEmpty()
            c.pointers.clear()
            if (held || c.kind != Kind.KEY) release(c)
            if (c.latch && c.on) { keys(c.key, false); c.on = false }
        }
        invalidate()
    }

    private fun release(control: Control) {
        when (control.kind) {
            Kind.BUTTON -> InputBridge.button(control.button!!, false)
            Kind.TRIGGER -> InputBridge.axis(control.axis!!, 0)
            Kind.STICK -> {
                control.sentX = 0; control.sentY = 0
                InputBridge.axis(control.axis!!, 0)
                InputBridge.axis(control.axisY!!, 0)
            }
            Kind.DPAD -> setDpad(control, 0)
            Kind.MOVE -> setWalk(control, 0)
            Kind.KEY -> if (!control.latch) keys(control.key, false)
            Kind.TOGGLE -> {}
        }
    }

    /** Returns the knob's offset as fractions of the reach. */
    private fun knob(control: Control, x: Float, y: Float): Pair<Float, Float> {
        if (control.originX.isNaN()) { control.originX = x; control.originY = y }
        val r = radius(control)
        var dx = x - control.originX
        var dy = y - control.originY
        val distance = hypot(dx, dy)
        if (distance > r) {
            dx *= r / distance
            dy *= r / distance
        }
        control.knobX = control.originX + dx
        control.knobY = control.originY + dy
        return dx / r to dy / r
    }

    private fun updateStick(control: Control, x: Float, y: Float) {
        val (fx, fy) = knob(control, x, y)
        val ax = (fx * 32767).toInt()
        val ay = (fy * 32767).toInt()
        if (ax != control.sentX) { control.sentX = ax; InputBridge.axis(control.axis!!, ax) }
        if (ay != control.sentY) { control.sentY = ay; InputBridge.axis(control.axisY!!, ay) }
    }

    private fun updateMove(control: Control, x: Float, y: Float) {
        val (fx, fy) = knob(control, x, y)
        val distance = hypot(fx, fy)
        var state = 0
        if (distance > WALK_DEADZONE) {
            if (fy / distance < -SIN_22) state = state or UP
            if (fy / distance > SIN_22) state = state or DOWN
            if (fx / distance < -SIN_22) state = state or LEFT
            if (fx / distance > SIN_22) state = state or RIGHT
        }
        setWalk(control, state)
    }

    private fun setWalk(control: Control, state: Int) {
        val changed = control.dpad xor state
        if (changed == 0) return
        for ((bit, key) in WALK_KEYS) {
            if (changed and bit != 0) keys(key, state and bit != 0)
        }
        control.dpad = state
    }

    private fun updateDpad(control: Control, x: Float, y: Float) {
        val dx = x - cx(control)
        val dy = y - cy(control)
        val distance = hypot(dx, dy)
        var state = 0
        if (distance > radius(control) * 0.2f) {
            if (dy / distance < -SIN_22) state = state or UP
            if (dy / distance > SIN_22) state = state or DOWN
            if (dx / distance < -SIN_22) state = state or LEFT
            if (dx / distance > SIN_22) state = state or RIGHT
        }
        setDpad(control, state)
    }

    private fun setDpad(control: Control, state: Int) {
        val changed = control.dpad xor state
        if (changed == 0) return
        for ((bit, button) in DPAD_BUTTONS) {
            if (changed and bit != 0) InputBridge.button(button, state and bit != 0)
        }
        if (state and changed != 0) haptic(VibrationEffect.EFFECT_TICK)
        control.dpad = state
    }

    private fun moveInEditor(id: Int, x: Float, y: Float) {
        val control = drags[id] ?: return
        val fingers = drags.filterValues { it === control }.keys
        if (fingers.size >= 2) {
            dragLast[id] = x to y
            val (ax, ay) = dragLast.getValue(fingers.first())
            val (bx, by) = dragLast.getValue(fingers.elementAt(1))
            if (pinchStartDistance > 0f) {
                control.scale = (pinchStartScale * hypot(ax - bx, ay - by) / pinchStartDistance).coerceIn(MIN_SCALE, MAX_SCALE)
            }
        } else {
            val (lx, ly) = dragLast.getValue(id)
            control.x = (onScreen(cx(control) + x - lx, halfWidth(control), width) - frameLeft(control)) / frameWidth()
            control.y = (onScreen(cy(control) + y - ly, radius(control), height) - frameTop(control)) / frame()
            dragLast[id] = x to y
        }
        invalidate()
    }

    private fun useTool(tool: Tool) {
        haptic(VibrationEffect.EFFECT_CLICK)
        when (tool) {
            Tool.ADJUST -> { releaseAll(); stripOpen = false; adding = false; editing = true }
            Tool.KEYS -> {
                if (stripOpen) strip.forEach { if (it.down) { keys(it.key, false); it.down = false } }
                stripOpen = !stripOpen
            }
            Tool.KEYBOARD -> onKeyboard()
            Tool.HIDE -> { releaseAll(); stripOpen = false; hidden = true; save() }
            Tool.SHOW -> { hidden = false; save() }
            Tool.ADD -> adding = !adding
            Tool.REMOVE -> selected?.let { c ->
                c.shown = false
                drags.entries.removeAll { it.value === c }
                selected = null
                controls = all.filter { it.shown }
            }
            Tool.SMALLER -> selected?.let { it.scale = (it.scale - 0.1f).coerceIn(MIN_SCALE, MAX_SCALE) }
            Tool.BIGGER -> selected?.let { it.scale = (it.scale + 0.1f).coerceIn(MIN_SCALE, MAX_SCALE) }
            Tool.OPACITY -> {
                opacity = OPACITIES[(OPACITIES.indexOfFirst { it >= opacity - 0.01f } + 1) % OPACITIES.size]
                alpha = opacity
            }
            Tool.RESET -> {
                all.forEach { it.shown = !it.extra; it.x = it.defaultX; it.y = it.defaultY; it.scale = 1f }
                controls = all.filter { it.shown }
                selected = null
                adding = false
                opacity = DEFAULT_OPACITY
                alpha = opacity
            }
            Tool.DONE -> { editing = false; adding = false; selected = null; drags.clear(); dragLast.clear(); save() }
        }
        invalidate()
    }

    private fun addable() = all.filter { !it.shown }

    private fun add(c: Control) {
        c.shown = true
        c.on = false
        c.x = c.defaultX; c.y = c.defaultY; c.scale = 1f
        controls = all.filter { it.shown }
        selected = c
        adding = false
        haptic(VibrationEffect.EFFECT_CLICK)
        invalidate()
    }

    /** Height of the phone-shaped frame the layout is drawn in; sizes are fractions of it. */
    private fun frame() = minOf(height.toFloat(), width / FRAME_ASPECT, FRAME_MAX_DP * resources.displayMetrics.density)
    private fun frameWidth() = frame() * FRAME_ASPECT

    private fun frameLeft(c: Control) = when {
        c.byToolbar -> (width - frameWidth()) / 2
        c.defaultX < 0.5f -> 0f
        else -> width - frameWidth()
    }
    private fun frameTop(c: Control) = if (c.byToolbar) 0f else height - frame()

    // Kept on screen: a layout made on another screen (a foldable, open or shut) may reach past this one.
    private fun cx(c: Control) = onScreen(frameLeft(c) + c.x * frameWidth(), halfWidth(c), width)
    private fun cy(c: Control) = onScreen(frameTop(c) + c.y * frame(), radius(c), height)
    private fun radius(c: Control) = c.size * c.scale * frame() / 2
    private fun halfWidth(c: Control) = if (c.pill) 2 * radius(c) else radius(c)
    private fun onScreen(at: Float, half: Float, size: Int) = at.coerceIn(minOf(half, size / 2f), maxOf(size - half, size / 2f))

    private fun controlAt(x: Float, y: Float): Control? = controls.lastOrNull { c ->
        val r = radius(c)
        if (c.pill) {
            x in cx(c) - 2.2f * r..cx(c) + 2.2f * r && y in cy(c) - 1.25f * r..cy(c) + 1.25f * r
        } else {
            // The walking stick only takes a thumb inside its well: the game's windows reach close to it.
            val reach = when (c.kind) { Kind.STICK -> 1.3f; Kind.MOVE -> 1f; Kind.DPAD -> 1.15f; else -> 1.2f }
            hypot(x - cx(c), y - cy(c)) <= r * reach
        }
    }

    private fun tools(): List<Tool> = when {
        editing -> listOf(Tool.ADD, Tool.REMOVE, Tool.SMALLER, Tool.BIGGER, Tool.OPACITY, Tool.RESET, Tool.DONE)
        // A real controller still needs the phone's keyboard, for typing names.
        keyboardInHand -> emptyList()
        padInHand -> listOf(Tool.KEYBOARD)
        hidden -> listOf(Tool.SHOW)
        touch -> listOf(Tool.ADJUST, Tool.KEYS, Tool.HIDE)
        else -> listOf(Tool.ADJUST, Tool.KEYBOARD, Tool.HIDE)
    }

    private fun toolSize() = frame() * 0.085f

    private fun toolRect(index: Int, count: Int, out: RectF): RectF {
        val s = toolSize()
        val left = width / 2f - count * s / 2f + index * s
        out.set(left, frame() * 0.025f, left + s, frame() * 0.025f + s)
        return out
    }

    /** With a margin round each tool that yields to a control there: the map button sits close. */
    private fun toolAt(x: Float, y: Float): Tool? {
        tool(x, y, 0f)?.let { return it }
        return tool(x, y, toolSize() * 0.15f)?.takeIf { stepAside || hidden || controlAt(x, y) == null }
    }

    private fun tool(x: Float, y: Float, margin: Float): Tool? {
        val list = tools()
        list.forEachIndexed { i, tool ->
            toolRect(i, list.size, rect)
            rect.inset(-margin, -margin)
            if (rect.contains(x, y)) return tool
        }
        return null
    }

    private fun pressStrip(id: Int, key: StripKey) {
        stripPointers[id] = key
        haptic(VibrationEffect.EFFECT_CLICK)
        when {
            key.key == 0 -> {}   // the keyboard opens when the finger lifts
            key.latch -> { key.down = !key.down; keys(key.key, key.down) }
            !key.down -> { key.down = true; keys(key.key, true) }
        }
        invalidate()
    }

    private fun stripAt(x: Float, y: Float): StripKey? = strip.getOrNull(rowAt(strip.map { it.label }, x, y))

    private fun rowRects(labels: List<String>): List<RectF> {
        val h = toolSize()
        text.textSize = h * 0.42f
        val widths = labels.map { maxOf(h * 1.1f, text.measureText(it) + h * 0.7f) }
        val gap = h * 0.12f
        val rows = ArrayList<MutableList<Int>>()
        var used = 0f
        widths.forEachIndexed { i, w ->
            if (rows.isEmpty() || used + gap + w > width * 0.9f) { rows += mutableListOf(i); used = w }
            else { rows.last() += i; used += gap + w }
        }
        val rects = List(labels.size) { RectF() }
        rows.forEachIndexed { row, keys ->
            var left = width / 2f - (keys.sumOf { widths[it].toDouble() }.toFloat() + gap * (keys.size - 1)) / 2f
            val top = frame() * 0.025f + h * (1.35f + row * 1.2f)
            for (i in keys) { rects[i].set(left, top, left + widths[i], top + h); left += widths[i] + gap }
        }
        return rects
    }

    private fun rowAt(labels: List<String>, x: Float, y: Float): Int {
        val margin = toolSize()
        return rowRects(labels).indexOfFirst { it.inset(-margin * 0.06f, -margin * 0.1f); it.contains(x, y) }
    }

    override fun onDraw(canvas: Canvas) {
        if (stepAside) {
            if (tools().isNotEmpty()) drawToolbar(canvas)
            return
        }
        if (editing) canvas.drawColor(0x55000000)
        if (!hidden) controls.forEach { draw(canvas, it) }
        drawToolbar(canvas)
        if (stripOpen && !editing) drawRow(canvas, strip.map { it.label }) { strip[it].down || strip[it] in stripPointers.values }
        if (editing && adding) drawRow(canvas, addable().map { it.name }) { false }
    }

    private fun drawRow(canvas: Canvas, labels: List<String>, lit: (Int) -> Boolean) {
        val rects = rowRects(labels)
        if (rects.isEmpty()) return
        val s = toolSize()
        val pad = s * 0.1f
        rect.set(rects.minOf { it.left } - pad, rects.first().top - pad, rects.maxOf { it.right } + pad, rects.last().bottom + pad)
        fill.color = Factorio.PANEL
        canvas.drawRoundRect(rect, pad * 0.6f, pad * 0.6f, fill)
        stroke.color = Factorio.FRAME
        stroke.strokeWidth = pad * 0.8f
        canvas.drawRoundRect(rect, pad * 0.6f, pad * 0.6f, stroke)
        rects.forEachIndexed { i, key ->
            key.inset(s * 0.07f, s * 0.07f)
            slot(lit(i), key.top, key.bottom) { canvas.drawRoundRect(key, s * 0.05f, s * 0.05f, it) }
            letter(canvas, labels[i], key.centerX(), key.centerY(), s * 0.42f, if (lit(i)) 0xFF1E1E1E.toInt() else GLYPH)
        }
    }

    private fun edge() = frame() * 0.003f

    private fun slot(pressed: Boolean, top: Float, bottom: Float, shape: (Paint) -> Unit) {
        fill.shader = LinearGradient(0f, top, 0f, bottom, if (pressed) PRESSED else IDLE, BEVEL, Shader.TileMode.CLAMP)
        shape(fill)
        fill.shader = null
        stroke.strokeWidth = edge()
        stroke.color = if (pressed) 0xFF5C3D12.toInt() else 0xFF121112.toInt()
        shape(stroke)
    }

    private fun well(canvas: Canvas, x: Float, y: Float, r: Float) {
        fill.shader = LinearGradient(0f, y - r, 0f, y + r, intArrayOf(0xE0000000.toInt(), 0xA0242324.toInt(), 0xA0242324.toInt()),
                                     floatArrayOf(0f, 0.14f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, fill)
        fill.shader = null
        stroke.strokeWidth = edge() * 1.6f
        stroke.color = Factorio.FRAME
        canvas.drawCircle(x, y, r, stroke)
    }

    private fun glyph(pressed: Boolean) = if (pressed) 0xFF1E1E1E.toInt() else GLYPH

    private fun letter(canvas: Canvas, label: String, x: Float, y: Float, size: Float, colour: Int) {
        text.color = colour
        text.textSize = size
        canvas.drawText(label, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    private fun circled(canvas: Canvas, label: String, x: Float, y: Float, r: Float, colour: Int) {
        icon.color = colour
        icon.strokeWidth = r * 0.1f
        canvas.drawCircle(x, y, r * 0.6f, icon)
        letter(canvas, label, x, y, if (label.length == 1) r * 0.78f else r * 0.56f, colour)
    }

    private fun draw(canvas: Canvas, c: Control) {
        val r = radius(c)
        val x = cx(c)
        val y = cy(c)
        val pressed = c.pointers.isNotEmpty()
        when (c.kind) {
            Kind.MOVE -> {
                val active = pressed && !c.originX.isNaN()
                val wx = if (active) c.originX else x
                val wy = if (active) c.originY else y
                well(canvas, wx, wy, r)
                for ((bit, dir) in DIRECTIONS) arrow(canvas, dir, wx, wy, r * 0.76f, r * 0.08f, if (c.dpad and bit != 0) Tone.ORANGE.face else GLYPH)
                val kx = if (active) c.knobX else x
                val ky = if (active) c.knobY else y
                val knob = r * 0.36f
                slot(pressed, ky - knob, ky + knob) { canvas.drawCircle(kx, ky, knob, it) }
            }
            Kind.KEY, Kind.TOGGLE -> {
                val lit = pressed || c.on
                slot(lit, y - r, y + r) { canvas.drawCircle(x, y, r, it) }
                icon.color = glyph(lit)
                icon.strokeWidth = r * 0.09f
                if (c.label.isEmpty()) drawKeyIcon(canvas, c.id, x, y, r * 0.42f)
                else letter(canvas, c.label, x, y, if (c.label.length == 1) r * 0.78f else r * 0.46f, glyph(lit))
            }
            Kind.STICK -> {
                val active = pressed && !c.originX.isNaN()
                well(canvas, if (active) c.originX else x, if (active) c.originY else y, r)
                val kx = if (active) c.knobX else x
                val ky = if (active) c.knobY else y
                val knob = r * 0.42f
                slot(pressed, ky - knob, ky + knob) { canvas.drawCircle(kx, ky, knob, it) }
                circled(canvas, c.label, kx, ky, knob, glyph(pressed))
            }
            Kind.DPAD -> {
                val arm = r * 0.36f
                val corner = arm * 0.3f
                cross.reset()
                bar.reset()
                cross.addRoundRect(x - arm, y - r, x + arm, y + r, corner, corner, Path.Direction.CW)
                bar.addRoundRect(x - r, y - arm, x + r, y + arm, corner, corner, Path.Direction.CW)
                cross.op(bar, Path.Op.UNION)
                slot(false, y - r, y + r) { canvas.drawPath(cross, it) }
                for ((bit, dir) in DIRECTIONS) {
                    if (c.dpad and bit == 0) continue
                    armRect(dir, x, y, r, arm)
                    canvas.save()
                    canvas.clipRect(rect)
                    slot(true, y - r, y + r) { canvas.drawPath(cross, it) }
                    canvas.restore()
                }
                fill.color = 0x66000000
                canvas.drawCircle(x, y, arm * 0.42f, fill)
                for ((bit, dir) in DIRECTIONS) arrow(canvas, dir, x, y, (r + arm) / 2, arm * 0.34f, glyph(c.dpad and bit != 0))
            }
            else -> {
                val colour = glyph(pressed)
                if (c.pill) {
                    rect.set(x - 2 * r, y - r, x + 2 * r, y + r)
                    slot(pressed, rect.top, rect.bottom) { canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, it) }
                    val inset = r * 0.32f
                    rect.set(x - 2 * r + inset, y - r + inset, x + 2 * r - inset, y + r - inset)
                    icon.color = colour
                    icon.strokeWidth = r * 0.1f
                    canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, icon)
                    letter(canvas, c.label, x, y, r * 0.78f, colour)
                } else {
                    slot(pressed, y - r, y + r) { canvas.drawCircle(x, y, r, it) }
                    when (c.id) {
                        "VIEW" -> { ring(canvas, x, y, r, colour); drawViewIcon(canvas, x, y, r) }
                        "MENU" -> { ring(canvas, x, y, r, colour); drawMenuIcon(canvas, x, y, r) }
                        else -> circled(canvas, c.label, x, y, r, colour)
                    }
                }
            }
        }
        if (editing && c === selected) {
            stroke.color = Tone.ORANGE.face
            stroke.strokeWidth = frame() * 0.004f
            if (c.pill) {
                rect.set(x - 2.2f * r, y - 1.2f * r, x + 2.2f * r, y + 1.2f * r)
                canvas.drawRoundRect(rect, 0.3f * r, 0.3f * r, stroke)
            } else {
                canvas.drawCircle(x, y, r * 1.12f, stroke)
            }
        }
    }

    /** [dir]: 0 up, 1 down, 2 left, 3 right. */
    private fun armRect(dir: Int, x: Float, y: Float, r: Float, arm: Float) = when (dir) {
        0 -> rect.set(x - arm, y - r - 1, x + arm, y - arm)
        1 -> rect.set(x - arm, y + arm, x + arm, y + r + 1)
        2 -> rect.set(x - r - 1, y - arm, x - arm, y + arm)
        else -> rect.set(x + arm, y - arm, x + r + 1, y + arm)
    }

    private fun arrow(canvas: Canvas, dir: Int, x: Float, y: Float, distance: Float, size: Float, colour: Int) {
        val (dx, dy) = when (dir) { 0 -> 0f to -1f; 1 -> 0f to 1f; 2 -> -1f to 0f; else -> 1f to 0f }
        val tipX = x + dx * (distance + size * 0.55f)
        val tipY = y + dy * (distance + size * 0.55f)
        val baseX = x + dx * (distance - size * 0.45f)
        val baseY = y + dy * (distance - size * 0.45f)
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(baseX - dy * size, baseY + dx * size)
        path.lineTo(baseX + dy * size, baseY - dx * size)
        path.close()
        fill.color = colour
        canvas.drawPath(path, fill)
    }

    /** [s] is half the drawing's width; uses [icon] as the caller set it. */
    private fun drawKeyIcon(canvas: Canvas, id: String, x: Float, y: Float, s: Float) {
        when (id) {
            "INVENTORY" -> {
                val q = s * 0.82f
                val gap = s * 0.3f
                for (i in 0..1) for (j in 0..1) {
                    val left = x - q - gap / 2 + i * (q + gap)
                    val top = y - q - gap / 2 + j * (q + gap)
                    rect.set(left, top, left + q, top + q)
                    canvas.drawRoundRect(rect, q * 0.18f, q * 0.18f, icon)
                }
            }
            "ROTATE" -> {
                rect.set(x - s * 0.85f, y - s * 0.85f, x + s * 0.85f, y + s * 0.85f)
                canvas.drawArc(rect, -60f, 290f, false, icon)
                canvas.drawLine(x + s * 0.43f, y - s * 0.74f, x + s * 0.5f, y - s * 1.2f, icon)
                canvas.drawLine(x + s * 0.43f, y - s * 0.74f, x + s * 0.9f, y - s * 0.62f, icon)
            }
            "PIPETTE" -> {
                canvas.save()
                canvas.rotate(45f, x, y)
                rect.set(x - s * 0.32f, y - s * 1.15f, x + s * 0.32f, y - s * 0.5f)
                canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, icon)
                canvas.drawLine(x - s * 0.5f, y - s * 0.42f, x + s * 0.5f, y - s * 0.42f, icon)
                path.reset()
                path.moveTo(x - s * 0.22f, y - s * 0.42f)
                path.lineTo(x - s * 0.22f, y + s * 0.6f)
                path.lineTo(x, y + s * 1.1f)
                path.lineTo(x + s * 0.22f, y + s * 0.6f)
                path.lineTo(x + s * 0.22f, y - s * 0.42f)
                canvas.drawPath(path, icon)
                canvas.restore()
            }
            "MINE" -> {
                canvas.save()
                canvas.rotate(45f, x, y)
                canvas.drawLine(x, y - s * 0.8f, x, y + s * 1.1f, icon)
                path.reset()
                path.moveTo(x - s * 1.05f, y - s * 0.3f)
                path.quadTo(x, y - s * 1.35f, x + s * 1.05f, y - s * 0.3f)
                canvas.drawPath(path, icon)
                canvas.restore()
            }
            "MAP" -> {
                path.reset()
                path.moveTo(x - s, y - s * 0.7f)
                path.lineTo(x - s * 0.33f, y - s * 0.95f)
                path.lineTo(x + s * 0.33f, y - s * 0.7f)
                path.lineTo(x + s, y - s * 0.95f)
                path.lineTo(x + s, y + s * 0.7f)
                path.lineTo(x + s * 0.33f, y + s * 0.95f)
                path.lineTo(x - s * 0.33f, y + s * 0.7f)
                path.lineTo(x - s, y + s * 0.95f)
                path.close()
                canvas.drawPath(path, icon)
                canvas.drawLine(x - s * 0.33f, y - s * 0.95f, x - s * 0.33f, y + s * 0.7f, icon)
                canvas.drawLine(x + s * 0.33f, y - s * 0.7f, x + s * 0.33f, y + s * 0.95f, icon)
            }
            "MENU" -> for (dy in listOf(-s * 0.6f, 0f, s * 0.6f)) canvas.drawLine(x - s * 0.85f, y + dy, x + s * 0.85f, y + dy, icon)
        }
    }

    private fun ring(canvas: Canvas, x: Float, y: Float, r: Float, colour: Int) {
        icon.color = colour
        icon.strokeWidth = r * 0.1f
        canvas.drawCircle(x, y, r * 0.6f, icon)
    }

    private fun drawViewIcon(canvas: Canvas, x: Float, y: Float, r: Float) {
        icon.strokeWidth = r * 0.09f
        val s = r * 0.24f
        rect.set(x - s * 1.1f, y - s * 0.9f, x + s * 0.5f, y + s * 0.4f)
        canvas.drawRect(rect, icon)
        rect.set(x - s * 0.5f, y - s * 0.4f, x + s * 1.1f, y + s * 0.9f)
        canvas.drawRect(rect, icon)
    }

    private fun drawMenuIcon(canvas: Canvas, x: Float, y: Float, r: Float) {
        icon.strokeWidth = r * 0.09f
        val s = r * 0.28f
        for (dy in listOf(-s * 0.65f, 0f, s * 0.65f)) canvas.drawLine(x - s, y + dy, x + s, y + dy, icon)
    }

    private fun drawToolbar(canvas: Canvas) {
        val list = tools()
        val s = toolSize()
        toolRect(0, list.size, rect)
        val pad = s * 0.1f
        rect.set(rect.left - pad, rect.top - pad, rect.left + s * list.size + pad, rect.top + s + pad)
        fill.color = Factorio.PANEL
        canvas.drawRoundRect(rect, pad * 0.6f, pad * 0.6f, fill)
        stroke.color = Factorio.FRAME
        stroke.strokeWidth = pad * 0.8f
        canvas.drawRoundRect(rect, pad * 0.6f, pad * 0.6f, stroke)
        list.forEachIndexed { i, tool ->
            toolRect(i, list.size, rect)
            rect.inset(s * 0.07f, s * 0.07f)
            val held = tool in toolPointers.values || (tool == Tool.KEYS && stripOpen) || (tool == Tool.ADD && adding)
            slot(held, rect.top, rect.bottom) { canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, it) }
            icon.color = if (held) 0xFF1E1E1E.toInt() else 0xFFE8E6E3.toInt()
            icon.strokeWidth = s * 0.065f
            drawTool(canvas, tool, rect.centerX(), rect.centerY(), s * 0.22f)
        }
    }

    private fun drawTool(canvas: Canvas, tool: Tool, x: Float, y: Float, s: Float) {
        when (tool) {
            Tool.ADJUST -> {
                for ((i, knob) in listOf(-0.4f, 0.45f, -0.05f).withIndex()) {
                    val ly = y + (i - 1) * s * 0.75f
                    canvas.drawLine(x - s, ly, x + s, ly, icon)
                    canvas.drawCircle(x + knob * s, ly, s * 0.2f, icon)
                }
            }
            Tool.KEYS, Tool.KEYBOARD -> {
                rect.set(x - s * 1.15f, y - s * 0.8f, x + s * 1.15f, y + s * 0.8f)
                canvas.drawRoundRect(rect, s * 0.2f, s * 0.2f, icon)
                fill.color = icon.color
                for (row in 0..1) for (col in 0..3) {
                    canvas.drawCircle(x - s * 0.66f + col * s * 0.44f, y - s * 0.38f + row * s * 0.36f, s * 0.07f, fill)
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
            Tool.SMALLER -> canvas.drawLine(x - s * 0.7f, y, x + s * 0.7f, y, icon)
            Tool.BIGGER -> {
                canvas.drawLine(x - s * 0.7f, y, x + s * 0.7f, y, icon)
                canvas.drawLine(x, y - s * 0.7f, x, y + s * 0.7f, icon)
            }
            Tool.OPACITY -> {
                canvas.drawCircle(x, y, s * 0.8f, icon)
                rect.set(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.8f)
                fill.color = icon.color
                canvas.drawArc(rect, 90f, 180f, true, fill)
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

    private fun haptic(effect: Int) {
        if (!Settings.vibration(context)) return
        vibrator?.vibrate(VibrationEffect.createPredefined(effect))
    }

    private fun load() {
        val saved = prefs.getString(layoutKey, null)
        val carried = saved ?: oldLayoutKey?.let { prefs.getString(it, null) } ?: return
        runCatching {
            val json = JSONObject(carried)
            opacity = json.optDouble("opacity", DEFAULT_OPACITY.toDouble()).toFloat()
            hidden = json.optBoolean("hidden", false)
            if (saved == null) return
            val removed = json.optJSONArray("removed")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            val added = json.optJSONArray("added")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            all.forEach { it.shown = if (it.extra) it.id in added else it.id !in removed }
            val placed = json.getJSONObject("controls")
            all.forEach { c ->
                placed.optJSONArray(c.id)?.let {
                    c.x = it.getDouble(0).toFloat()
                    c.y = it.getDouble(1).toFloat()
                    c.scale = it.getDouble(2).toFloat()
                }
            }
        }
    }

    /** Only what the player changed, so the rest follow the defaults when those change. */
    private fun save() {
        val placed = JSONObject()
        all.filter { it.x != it.defaultX || it.y != it.defaultY || it.scale != 1f }
            .forEach { placed.put(it.id, JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.scale.toDouble()))) }
        val json = JSONObject().put("opacity", opacity.toDouble()).put("hidden", hidden).put("controls", placed)
            .put("removed", JSONArray(all.filter { !it.extra && !it.shown }.map { it.id }))
            .put("added", JSONArray(all.filter { it.extra && it.shown }.map { it.id }))
        prefs.edit().putString(layoutKey, json.toString()).apply()
    }

    companion object {
        private const val UP = 1
        private const val DOWN = 2
        private const val LEFT = 4
        private const val RIGHT = 8
        private val DIRECTIONS = listOf(UP to 0, DOWN to 1, LEFT to 2, RIGHT to 3)
        private val WALK_KEYS = listOf(UP to KeyEvent.KEYCODE_W, DOWN to KeyEvent.KEYCODE_S,
                                       LEFT to KeyEvent.KEYCODE_A, RIGHT to KeyEvent.KEYCODE_D)
        /** A fraction of the stick's reach. */
        private const val WALK_DEADZONE = 0.3f
        private val DPAD_BUTTONS = listOf(UP to PadButton.DPAD_UP, DOWN to PadButton.DPAD_DOWN,
                                  LEFT to PadButton.DPAD_LEFT, RIGHT to PadButton.DPAD_RIGHT)
        /** The blue of the game's controller glyphs (data/core/graphics/icons/controller). */
        private const val GLYPH = 0xFF80CEF0.toInt()
        private val IDLE = intArrayOf(0xFF6B6A6B.toInt(), 0xFF6B6A6B.toInt(), 0xFF474647.toInt(), 0xFF2B2A2B.toInt())
        private val PRESSED = intArrayOf(0xFFF7CC8F.toInt(), 0xFFF7CC8F.toInt(), 0xFFE39C40.toInt(), 0xFFC4802C.toInt())
        private val BEVEL = floatArrayOf(0f, 0.06f, 0.09f, 1f)
        private const val SIN_22 = 0.38f   // sin 22.5°: where a straight direction meets a diagonal
        private const val DEFAULT_OPACITY = 0.8f
        private val OPACITIES = floatArrayOf(0.35f, 0.55f, 0.8f, 1f)
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 1.8f
        /** The phone the layout was made on, sideways: 845 × 384 dp. */
        private const val FRAME_ASPECT = 2.2f
        /** About a phone's height sideways, so a tablet's controls are a phone's size. */
        private const val FRAME_MAX_DP = 480f
    }
}
