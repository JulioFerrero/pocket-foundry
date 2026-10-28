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

/**
 * The controls drawn over the game, in the game's own look: its dark inventory slots, orange
 * while held, with its light blue controller glyphs. Two sets ([touch]):
 * - touch: a stick that walks (W A S D) and a column of the game's keys, while the screen
 *   itself is the mouse ([PointerGestures]);
 * - gamepad: an Xbox-style controller through the virtual gamepad, sized by how much each button
 *   is used in Factorio, while touching the game moves its cursor: no right stick.
 * It never takes touches through the view system: [TouchRouter] hands it the fingers that land on it.
 *
 * It's laid out for a phone held sideways ([frame]): positions are fractions of that frame and sizes
 * fractions of its height, so a layout keeps its shape at any resolution, and on a squarer screen
 * (a foldable open, a tablet) it keeps a phone's. The toolbar at the top edits or hides it.
 */
@SuppressLint("ViewConstructor")
class ControllerView(context: Context, private val touch: Boolean) : View(context) {

    /** Touch controls: MOVE walks with W A S D, KEY holds a key, TOGGLE flips [mining]. */
    private enum class Kind { STICK, DPAD, BUTTON, TRIGGER, MOVE, KEY, TOGGLE }

    private class Control(
        val id: String,
        val kind: Kind,
        val label: String,
        val defaultX: Float,
        val defaultY: Float,
        /** Diameter (or pill height) as a fraction of the frame's height. */
        val size: Float,
        val button: PadButton? = null,
        val axis: PadAxis? = null,
        val axisY: PadAxis? = null,
        val pill: Boolean = false,
        /** KEY: the Android key code sent to the game. */
        val key: Int = 0,
    ) {
        var x = defaultX
        var y = defaultY
        var scale = 1f
        /** Map and menu: up by the toolbar, so placed from the top middle rather than a bottom corner. */
        val byToolbar = defaultY < 0.2f
        val pointers = LinkedHashSet<Int>()
        // Sticks: where the finger came down, and where it is now.
        var originX = 0f
        var originY = 0f
        var knobX = 0f
        var knobY = 0f
        var sentX = 0
        var sentY = 0
        var dpad = 0
        var on = false
    }

    /** KEYS opens touch controls' strip of keys; KEYBOARD, on the pad, opens the phone's keyboard straight away. */
    private enum class Tool { ADJUST, KEYS, KEYBOARD, HIDE, SHOW, SMALLER, BIGGER, OPACITY, RESET, DONE }

    /**
     * Touch controls: the keys the game asks for beyond the six buttons, in a row under the toolbar
     * (KEYS opens it). Shift and Ctrl stay down until tapped again, for shift- and ctrl-clicks;
     * the others are held while touched. Key 0 opens the phone's keyboard, for typing.
     */
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

    private val controls = if (touch) listOf(
        Control("MOVE", Kind.MOVE, "", 0.085f, 0.70f, 0.28f),
        // Factorio's default keys. On the right edge, clear of the quickbar and the left thumb.
        Control("INVENTORY", Kind.KEY, "", 0.955f, 0.30f, 0.115f, key = KeyEvent.KEYCODE_E),
        Control("ROTATE", Kind.KEY, "", 0.955f, 0.425f, 0.115f, key = KeyEvent.KEYCODE_R),
        Control("PIPETTE", Kind.KEY, "", 0.955f, 0.55f, 0.115f, key = KeyEvent.KEYCODE_Q),
        Control("MINE", Kind.TOGGLE, "", 0.955f, 0.675f, 0.115f),
        Control("MAP", Kind.KEY, "", 0.955f, 0.80f, 0.115f, key = KeyEvent.KEYCODE_M),
        Control("MENU", Kind.KEY, "", 0.955f, 0.925f, 0.115f, key = KeyEvent.KEYCODE_ESCAPE),
    ) else listOf(
        // Sized by use (Factorio's default bindings), with the gestures doing the most common presses
        // where the finger is (double tap A, long press X, two-finger tap B: PointerGestures). Y opens
        // the inventory and only a button does that, so it's the largest; A, X and B stay as a smaller
        // Xbox diamond the game's hints point at. RT is the modifier most combinations need, LB holds
        // the quick panel; LT and RB (shoot) less. The D-pad rotates, picks up and shows info. Map and
        // menu sit up by the toolbar, leaving the bottom to the game. Touch is the cursor, so no right
        // stick, L3 or R3. The right hand's buttons stay left of the game's minimap and info panel,
        // which fill the right edge in controller mode.
        Control("LS", Kind.STICK, "L", 0.10f, 0.70f, 0.28f, axis = PadAxis.LEFT_X, axisY = PadAxis.LEFT_Y),
        Control("DPAD", Kind.DPAD, "", 0.245f, 0.88f, 0.20f),
        Control("Y", Kind.BUTTON, "Y", 0.715f, 0.635f, 0.125f, PadButton.NORTH),
        Control("A", Kind.BUTTON, "A", 0.715f, 0.895f, 0.13f, PadButton.SOUTH),
        Control("X", Kind.BUTTON, "X", 0.657f, 0.765f, 0.115f, PadButton.WEST),
        Control("B", Kind.BUTTON, "B", 0.773f, 0.765f, 0.115f, PadButton.EAST),
        Control("LB", Kind.BUTTON, "LB", 0.075f, 0.42f, 0.10f, PadButton.LEFT_SHOULDER, pill = true),
        Control("LT", Kind.TRIGGER, "LT", 0.17f, 0.42f, 0.085f, axis = PadAxis.LEFT_TRIGGER, pill = true),
        Control("RB", Kind.BUTTON, "RB", 0.655f, 0.47f, 0.085f, PadButton.RIGHT_SHOULDER, pill = true),
        Control("RT", Kind.TRIGGER, "RT", 0.75f, 0.47f, 0.10f, axis = PadAxis.RIGHT_TRIGGER, pill = true),
        Control("VIEW", Kind.BUTTON, "", 0.585f, 0.075f, 0.085f, PadButton.BACK),
        Control("MENU", Kind.BUTTON, "", 0.645f, 0.075f, 0.085f, PadButton.START),
    )

    private val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)
    // The pad's layout was redone for touch as the cursor and its gestures: a new key, so an older one doesn't place it.
    private val layoutKey = if (touch) "layout-touch" else "layout-hybrid"
    private val vibrator = context.getSystemService(Vibrator::class.java)

    /** Sends a key to the game (Android key code, down); set by [OnScreenController]. */
    var keys: (Int, Boolean) -> Unit = { _, _ -> }

    /** Shows or hides the phone's keyboard; set by [OnScreenController]. */
    var onKeyboard: () -> Unit = {}

    /** The pickaxe is on: touches on the game mine instead of clicking. */
    val mining get() = controls.any { it.kind == Kind.TOGGLE && it.on }

    private var editing = false
    private var hidden = false

    /**
     * A real controller is being played ([Gamepads]): the drawn pad steps aside until it's unplugged, leaving
     * only the toolbar's keyboard; the layout editor still shows it. Touch controls stay, as their game ignores the pad.
     */
    var padInHand = false
        set(value) {
            if (touch || field == value) return
            field = value
            handsChanged()
        }

    /** A real keyboard is connected ([Keyboards]): touch controls' stick and keys step aside the same way. */
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
            controls.forEach { if (it.kind == Kind.TOGGLE) it.on = false }   // the pickaxe off: touches click again
        }
        invalidate()
    }
    private var opacity = DEFAULT_OPACITY

    // Toolbar taps act when the finger lifts on the same tool.
    private val toolPointers = HashMap<Int, Tool>()

    // Edit mode: which control each finger moves, and pinch-to-resize.
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
        alpha = opacity
    }

    /** Picks up what Settings changed while the game was away. */
    fun reload() {
        load()
        alpha = opacity
        invalidate()
    }

    /** Opens the layout editor, as ADJUST does. */
    fun startEditing() {
        releaseAll()
        editing = true
        invalidate()
    }

    // Touch, from TouchRouter (view coordinates).

    fun pointerDown(id: Int, x: Float, y: Float): Boolean {
        toolAt(x, y)?.let { toolPointers[id] = it; invalidate(); return true }
        if (stepAside) return false
        if (stripOpen && !editing) stripAt(x, y)?.let { pressStrip(id, it); return true }
        if (hidden) return false

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
            return true   // nothing reaches the game while editing
        }

        control ?: return false
        val first = control.pointers.isEmpty()
        control.pointers += id
        if (!first) return true   // a second finger on the same control just rests there
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
                keys(control.key, true)
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
            // The resting finger takes over a stick or D-pad, centred where it is.
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

    /** Lets go of everything, e.g. when the game leaves the screen. */
    fun releaseAll() {
        toolPointers.clear()
        stripPointers.clear()
        strip.forEach { if (it.down) { keys(it.key, false); it.down = false } }
        drags.clear()
        dragLast.clear()
        controls.forEach { c ->
            // A key goes up only if it was down; the pad's buttons and sticks are simply zeroed.
            val held = c.pointers.isNotEmpty()
            c.pointers.clear()
            if (held || c.kind != Kind.KEY) release(c)
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
            Kind.KEY -> keys(control.key, false)
            Kind.TOGGLE -> {}   // stays as it was left
        }
    }

    /** Moves a stick's knob after the finger, within its reach; returns its offset as fractions of the reach. */
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

    /** The walking stick: eight directions as W A S D, as the game walks with keys. */
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
        // Eight directions; the middle is neutral.
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
            Tool.ADJUST -> { releaseAll(); stripOpen = false; editing = true }
            Tool.KEYS -> {
                if (stripOpen) strip.forEach { if (it.down) { keys(it.key, false); it.down = false } }
                stripOpen = !stripOpen
            }
            Tool.KEYBOARD -> onKeyboard()
            Tool.HIDE -> { releaseAll(); stripOpen = false; hidden = true; save() }
            Tool.SHOW -> { hidden = false; save() }
            Tool.SMALLER -> selected?.let { it.scale = (it.scale - 0.1f).coerceIn(MIN_SCALE, MAX_SCALE) }
            Tool.BIGGER -> selected?.let { it.scale = (it.scale + 0.1f).coerceIn(MIN_SCALE, MAX_SCALE) }
            Tool.OPACITY -> {
                opacity = OPACITIES[(OPACITIES.indexOfFirst { it >= opacity - 0.01f } + 1) % OPACITIES.size]
                alpha = opacity
            }
            Tool.RESET -> {
                controls.forEach { it.x = it.defaultX; it.y = it.defaultY; it.scale = 1f }
                opacity = DEFAULT_OPACITY
                alpha = opacity
            }
            Tool.DONE -> { editing = false; selected = null; drags.clear(); dragLast.clear(); save() }
        }
        invalidate()
    }

    // Geometry. The layout is drawn in a frame shaped like the phone it was made on: the screen's height, or
    // less where the screen is squarer than that phone or taller than a phone. Then it sits at the bottom, its
    // left hand's controls in the left corner and its right hand's in the right, map and menu by the toolbar.

    /** The frame's height: the unit all sizes are fractions of. */
    private fun frame() = minOf(height.toFloat(), width / FRAME_ASPECT, FRAME_MAX_DP * resources.displayMetrics.density)
    private fun frameWidth() = frame() * FRAME_ASPECT

    /** Where a control's fractions are measured from. */
    private fun frameLeft(c: Control) = when {
        c.byToolbar -> (width - frameWidth()) / 2
        c.defaultX < 0.5f -> 0f
        else -> width - frameWidth()
    }
    private fun frameTop(c: Control) = if (c.byToolbar) 0f else height - frame()

    // Kept whole on screen, also when a layout made on another screen (a foldable, open or shut) reaches past this one.
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
            // The walking stick only takes a thumb inside its well: the game's windows can reach close to it.
            val reach = when (c.kind) { Kind.STICK -> 1.3f; Kind.MOVE -> 1f; Kind.DPAD -> 1.15f; else -> 1.2f }
            hypot(x - cx(c), y - cy(c)) <= r * reach
        }
    }

    private fun tools(): List<Tool> = when {
        editing -> listOf(Tool.SMALLER, Tool.BIGGER, Tool.OPACITY, Tool.RESET, Tool.DONE)
        // A real keyboard needs nothing on screen; a real controller only the phone's keyboard, for typing names.
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

    /** The tool under the finger, with a margin round each that yields to a control there (the map button sits close). */
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

    /** The strip's keys side by side under the toolbar, each as wide as its name needs. */
    private fun stripRect(index: Int, out: RectF): RectF {
        val h = toolSize()
        text.textSize = h * 0.42f
        val widths = strip.map { maxOf(h * 1.1f, text.measureText(it.label) + h * 0.7f) }
        val gap = h * 0.12f
        var left = width / 2f - (widths.sum() + gap * (strip.size - 1)) / 2f
        for (i in 0 until index) left += widths[i] + gap
        val top = frame() * 0.025f + h * 1.35f
        out.set(left, top, left + widths[index], top + h)
        return out
    }

    private fun stripAt(x: Float, y: Float): StripKey? {
        strip.indices.forEach { i ->
            stripRect(i, rect)
            rect.inset(-toolSize() * 0.06f, -toolSize() * 0.15f)
            if (rect.contains(x, y)) return strip[i]
        }
        return null
    }

    // Drawing.

    override fun onDraw(canvas: Canvas) {
        if (stepAside) {
            if (tools().isNotEmpty()) drawToolbar(canvas)
            return
        }
        if (editing) canvas.drawColor(0x55000000)
        if (!hidden) controls.forEach { draw(canvas, it) }
        drawToolbar(canvas)
        if (stripOpen && !editing) drawStrip(canvas)
    }

    private fun drawStrip(canvas: Canvas) {
        val s = toolSize()
        val pad = s * 0.1f
        val first = RectF(stripRect(0, rect))
        stripRect(strip.size - 1, rect)
        rect.set(first.left - pad, first.top - pad, rect.right + pad, rect.bottom + pad)
        fill.color = Factorio.PANEL
        canvas.drawRoundRect(rect, pad * 0.6f, pad * 0.6f, fill)
        stroke.color = Factorio.FRAME
        stroke.strokeWidth = pad * 0.8f
        canvas.drawRoundRect(rect, pad * 0.6f, pad * 0.6f, stroke)
        strip.forEachIndexed { i, key ->
            stripRect(i, rect)
            rect.inset(s * 0.07f, s * 0.07f)
            val lit = key.down || key in stripPointers.values
            slot(lit, rect.top, rect.bottom) { canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, it) }
            letter(canvas, key.label, rect.centerX(), rect.centerY(), s * 0.42f, if (lit) 0xFF1E1E1E.toInt() else GLYPH)
        }
    }

    private fun edge() = frame() * 0.003f

    /** One of the game's slots over [top]..[bottom], outlined by [shape]: a light top edge, orange while held. */
    private fun slot(pressed: Boolean, top: Float, bottom: Float, shape: (Paint) -> Unit) {
        fill.shader = LinearGradient(0f, top, 0f, bottom, if (pressed) PRESSED else IDLE, BEVEL, Shader.TileMode.CLAMP)
        shape(fill)
        fill.shader = null
        stroke.strokeWidth = edge()
        stroke.color = if (pressed) 0xFF5C3D12.toInt() else 0xFF121112.toInt()
        shape(stroke)
    }

    /** A stick's reach: a well sunk into the screen. */
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

    /** The game's face-button glyph: a letter in a ring. */
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
                // A quiet well with four arrows; the knob follows the thumb.
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
                drawKeyIcon(canvas, c.id, x, y, r * 0.42f)
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
                // One cross-shaped slot; a held arm turns orange.
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
                    // Shoulders and triggers: the name in a box, as the game draws them, inset evenly.
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

    /** The part of the D-pad's cross that is the arm towards [dir] (0 up, 1 down, 2 left, 3 right). */
    private fun armRect(dir: Int, x: Float, y: Float, r: Float, arm: Float) = when (dir) {
        0 -> rect.set(x - arm, y - r - 1, x + arm, y - arm)
        1 -> rect.set(x - arm, y + arm, x + arm, y + r + 1)
        2 -> rect.set(x - r - 1, y - arm, x - arm, y + arm)
        else -> rect.set(x + arm, y - arm, x + r + 1, y + arm)
    }

    /** A D-pad arrowhead [distance] from the middle, pointing out. */
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

    /** The touch keys' icons, [s] the half-width of the drawing, in [icon]'s colour. */
    private fun drawKeyIcon(canvas: Canvas, id: String, x: Float, y: Float, s: Float) {
        when (id) {
            "INVENTORY" -> {
                // Four inventory slots.
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
                // An eyedropper, leaning right.
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
                // A pickaxe.
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
                // A folded map.
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

    /** The toolbar: a small panel of the game's, with slot buttons. */
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
            val held = tool in toolPointers.values || (tool == Tool.KEYS && stripOpen)
            slot(held, rect.top, rect.bottom) { canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, it) }
            icon.color = if (held) 0xFF1E1E1E.toInt() else 0xFFE8E6E3.toInt()
            icon.strokeWidth = s * 0.065f
            drawTool(canvas, tool, rect.centerX(), rect.centerY(), s * 0.22f)
        }
    }

    private fun drawTool(canvas: Canvas, tool: Tool, x: Float, y: Float, s: Float) {
        when (tool) {
            Tool.ADJUST -> {
                // Three sliders.
                for ((i, knob) in listOf(-0.4f, 0.45f, -0.05f).withIndex()) {
                    val ly = y + (i - 1) * s * 0.75f
                    canvas.drawLine(x - s, ly, x + s, ly, icon)
                    canvas.drawCircle(x + knob * s, ly, s * 0.2f, icon)
                }
            }
            Tool.KEYS, Tool.KEYBOARD -> {
                // A keyboard: three rows of keys and a space bar.
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

    // The saved layout.

    private fun load() {
        val saved = prefs.getString(layoutKey, null) ?: return
        runCatching {
            val json = JSONObject(saved)
            opacity = json.optDouble("opacity", DEFAULT_OPACITY.toDouble()).toFloat()
            hidden = json.optBoolean("hidden", false)
            val placed = json.getJSONObject("controls")
            controls.forEach { c ->
                placed.optJSONArray(c.id)?.let {
                    c.x = it.getDouble(0).toFloat()
                    c.y = it.getDouble(1).toFloat()
                    c.scale = it.getDouble(2).toFloat()
                }
            }
        }
    }

    private fun save() {
        val placed = JSONObject()
        controls.forEach { placed.put(it.id, JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.scale.toDouble()))) }
        val json = JSONObject().put("opacity", opacity.toDouble()).put("hidden", hidden).put("controls", placed)
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
        /** How far the walking stick must lean before the character moves. */
        private const val WALK_DEADZONE = 0.3f
        private val DPAD_BUTTONS = listOf(UP to PadButton.DPAD_UP, DOWN to PadButton.DPAD_DOWN,
                                  LEFT to PadButton.DPAD_LEFT, RIGHT to PadButton.DPAD_RIGHT)
        /** The game's controller glyphs are this blue (data/core/graphics/icons/controller). */
        private const val GLYPH = 0xFF80CEF0.toInt()
        // A slot's face, top to bottom: the light edge, then the face darkening downwards.
        private val IDLE = intArrayOf(0xFF6B6A6B.toInt(), 0xFF6B6A6B.toInt(), 0xFF474647.toInt(), 0xFF2B2A2B.toInt())
        private val PRESSED = intArrayOf(0xFFF7CC8F.toInt(), 0xFFF7CC8F.toInt(), 0xFFE39C40.toInt(), 0xFFC4802C.toInt())
        private val BEVEL = floatArrayOf(0f, 0.06f, 0.09f, 1f)
        private const val SIN_22 = 0.38f   // sin 22.5°: the edge between a straight and a diagonal direction
        private const val DEFAULT_OPACITY = 0.8f
        private val OPACITIES = floatArrayOf(0.35f, 0.55f, 0.8f, 1f)
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 1.8f
        /** The phone the layout was made on, held sideways: 845 × 384 dp. Most phones are 2 to 2.4. */
        private const val FRAME_ASPECT = 2.2f
        /** The tallest the frame gets: a phone's height sideways, so a tablet's controls are a phone's size. */
        private const val FRAME_MAX_DP = 480f
    }
}
