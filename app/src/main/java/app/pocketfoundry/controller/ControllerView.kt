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
 * The on-screen Xbox-style controller drawn over the game, in the game's own look: its dark
 * inventory slots, orange while held, with its light blue controller glyphs. It never takes
 * touches through the view system: [TouchRouter] hands it the fingers that land on it.
 *
 * Positions are fractions of the screen, sizes fractions of its height, so a layout
 * keeps its shape across resolutions. The toolbar at the top edits or hides it.
 */
@SuppressLint("ViewConstructor")
class ControllerView(context: Context) : View(context) {

    private enum class Kind { STICK, DPAD, BUTTON, TRIGGER }

    private class Control(
        val id: String,
        val kind: Kind,
        val label: String,
        val defaultX: Float,
        val defaultY: Float,
        /** Diameter (or pill height) as a fraction of the screen height. */
        val size: Float,
        val button: PadButton? = null,
        val axis: PadAxis? = null,
        val axisY: PadAxis? = null,
        val pill: Boolean = false,
    ) {
        var x = defaultX
        var y = defaultY
        var scale = 1f
        val pointers = LinkedHashSet<Int>()
        // Sticks: where the finger came down, and where it is now.
        var originX = 0f
        var originY = 0f
        var knobX = 0f
        var knobY = 0f
        var sentX = 0
        var sentY = 0
        var dpad = 0
    }

    private enum class Tool { ADJUST, HIDE, SHOW, SMALLER, BIGGER, OPACITY, RESET, DONE }

    private val controls = listOf(
        Control("LS", Kind.STICK, "L", 0.12f, 0.66f, 0.34f, axis = PadAxis.LEFT_X, axisY = PadAxis.LEFT_Y),
        Control("RS", Kind.STICK, "R", 0.715f, 0.80f, 0.28f, axis = PadAxis.RIGHT_X, axisY = PadAxis.RIGHT_Y),
        Control("DPAD", Kind.DPAD, "", 0.29f, 0.84f, 0.27f),
        Control("A", Kind.BUTTON, "A", 0.885f, 0.79f, 0.135f, PadButton.SOUTH),
        Control("B", Kind.BUTTON, "B", 0.946f, 0.655f, 0.135f, PadButton.EAST),
        Control("X", Kind.BUTTON, "X", 0.824f, 0.655f, 0.135f, PadButton.WEST),
        Control("Y", Kind.BUTTON, "Y", 0.885f, 0.52f, 0.135f, PadButton.NORTH),
        Control("LB", Kind.BUTTON, "LB", 0.075f, 0.33f, 0.10f, PadButton.LEFT_SHOULDER, pill = true),
        Control("LT", Kind.TRIGGER, "LT", 0.175f, 0.33f, 0.10f, axis = PadAxis.LEFT_TRIGGER, pill = true),
        Control("RB", Kind.BUTTON, "RB", 0.825f, 0.36f, 0.10f, PadButton.RIGHT_SHOULDER, pill = true),
        Control("RT", Kind.TRIGGER, "RT", 0.925f, 0.36f, 0.10f, axis = PadAxis.RIGHT_TRIGGER, pill = true),
        Control("VIEW", Kind.BUTTON, "", 0.44f, 0.92f, 0.10f, PadButton.BACK),
        Control("MENU", Kind.BUTTON, "", 0.56f, 0.92f, 0.10f, PadButton.START),
        Control("L3", Kind.BUTTON, "L3", 0.235f, 0.56f, 0.09f, PadButton.LEFT_STICK),
        Control("R3", Kind.BUTTON, "R3", 0.615f, 0.84f, 0.09f, PadButton.RIGHT_STICK),
    )

    private val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    private var editing = false
    private var hidden = false
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
            Kind.STICK -> {
                control.originX = x; control.originY = y
                control.knobX = x; control.knobY = y
            }
            Kind.DPAD -> updateDpad(control, x, y)
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
        if (drags.remove(id) != null) {
            dragLast.remove(id)
            return
        }
        val control = controls.firstOrNull { id in it.pointers } ?: return
        val wasFirst = control.pointers.first() == id
        control.pointers -= id
        if (control.pointers.isNotEmpty()) {
            // The resting finger takes over a stick or D-pad, centred where it is.
            if (wasFirst && (control.kind == Kind.STICK || control.kind == Kind.DPAD)) {
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
        drags.clear()
        dragLast.clear()
        controls.forEach { it.pointers.clear(); release(it) }
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
        }
    }

    private fun updateStick(control: Control, x: Float, y: Float) {
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
        val ax = (dx / r * 32767).toInt()
        val ay = (dy / r * 32767).toInt()
        if (ax != control.sentX) { control.sentX = ax; InputBridge.axis(control.axis!!, ax) }
        if (ay != control.sentY) { control.sentY = ay; InputBridge.axis(control.axisY!!, ay) }
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
            control.x = (control.x + (x - lx) / width).coerceIn(0f, 1f)
            control.y = (control.y + (y - ly) / height).coerceIn(0f, 1f)
            dragLast[id] = x to y
        }
        invalidate()
    }

    private fun useTool(tool: Tool) {
        haptic(VibrationEffect.EFFECT_CLICK)
        when (tool) {
            Tool.ADJUST -> { releaseAll(); editing = true }
            Tool.HIDE -> { releaseAll(); hidden = true; save() }
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

    // Geometry.

    private fun cx(c: Control) = c.x * width
    private fun cy(c: Control) = c.y * height
    private fun radius(c: Control) = c.size * c.scale * height / 2

    private fun controlAt(x: Float, y: Float): Control? = controls.lastOrNull { c ->
        val r = radius(c)
        if (c.pill) {
            x in cx(c) - 2.2f * r..cx(c) + 2.2f * r && y in cy(c) - 1.25f * r..cy(c) + 1.25f * r
        } else {
            val reach = when (c.kind) { Kind.STICK -> 1.3f; Kind.DPAD -> 1.15f; else -> 1.2f }
            hypot(x - cx(c), y - cy(c)) <= r * reach
        }
    }

    private fun tools(): List<Tool> = when {
        editing -> listOf(Tool.SMALLER, Tool.BIGGER, Tool.OPACITY, Tool.RESET, Tool.DONE)
        hidden -> listOf(Tool.SHOW)
        else -> listOf(Tool.ADJUST, Tool.HIDE)
    }

    private fun toolSize() = height * 0.085f

    private fun toolRect(index: Int, count: Int, out: RectF): RectF {
        val s = toolSize()
        val left = width / 2f - count * s / 2f + index * s
        out.set(left, height * 0.025f, left + s, height * 0.025f + s)
        return out
    }

    private fun toolAt(x: Float, y: Float): Tool? {
        val list = tools()
        list.forEachIndexed { i, tool ->
            toolRect(i, list.size, rect)
            rect.inset(-toolSize() * 0.15f, -toolSize() * 0.15f)
            if (rect.contains(x, y)) return tool
        }
        return null
    }

    // Drawing.

    override fun onDraw(canvas: Canvas) {
        if (editing) canvas.drawColor(0x55000000)
        if (!hidden) controls.forEach { draw(canvas, it) }
        drawToolbar(canvas)
    }

    private fun edge() = height * 0.003f

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
            stroke.strokeWidth = height * 0.004f
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
            val held = tool in toolPointers.values
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
        val saved = prefs.getString("layout", null) ?: return
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
        prefs.edit().putString("layout", json.toString()).apply()
    }

    companion object {
        /** The controls' opacity, as saved with the layout. */
        fun opacity(context: Context): Float =
            context.getSharedPreferences("controller", Context.MODE_PRIVATE).getString("layout", null)
                ?.let { runCatching { JSONObject(it).optDouble("opacity", DEFAULT_OPACITY.toDouble()).toFloat() }.getOrNull() }
                ?: DEFAULT_OPACITY

        fun setOpacity(context: Context, value: Float) {
            val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)
            val json = prefs.getString("layout", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            prefs.edit().putString("layout", json.put("opacity", value.toDouble()).toString()).apply()
        }

        private const val UP = 1
        private const val DOWN = 2
        private const val LEFT = 4
        private const val RIGHT = 8
        private val DIRECTIONS = listOf(UP to 0, DOWN to 1, LEFT to 2, RIGHT to 3)
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
    }
}
