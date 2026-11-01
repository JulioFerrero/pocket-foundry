package app.pocketfoundry.controller

import kotlin.math.hypot

/**
 * What a control is and what it sends. [reach]: how far from its middle a touch still lands on it, in radii (pills
 * have their own, in [PadLayout]). [follows]: sticks and the D-pad follow the finger, buttons and triggers don't.
 */
sealed class Kind(val reach: Float, val follows: Boolean) {
    class Stick(val axisX: PadAxis, val axisY: PadAxis) : Kind(1.3f, true)
    object Dpad : Kind(1.15f, true)
    class Button(val button: PadButton) : Kind(1.2f, false)
    class Trigger(val axis: PadAxis) : Kind(1.2f, false)
}

// Where a control's frame sits: the top middle, by the toolbar, or a bottom corner. As parts of the room the frame
// leaves across and down the screen.
enum class Anchor(val across: Float, val down: Float) { TOP(0.5f, 0f), LEFT(0f, 1f), RIGHT(1f, 1f) }

/** In the drawn look, in place of letters: Xbox's View (two squares) and Menu (three lines). */
enum class Icon { VIEW, MENU }

/** A D-pad direction: its bit in a D-pad state, its pad button, and where it points on screen (y down). */
enum class DpadDir(val bit: Int, val button: PadButton, val dx: Float, val dy: Float) {
    UP(1, PadButton.DPAD_UP, 0f, -1f),
    DOWN(2, PadButton.DPAD_DOWN, 0f, 1f),
    LEFT(4, PadButton.DPAD_LEFT, -1f, 0f),
    RIGHT(8, PadButton.DPAD_RIGHT, 1f, 0f);

    companion object {
        /** An array, so a for loop over it allocates nothing. */
        val ALL = entries.toTypedArray()

        /** The directions ([x], [y]) points along, each where it's past [threshold]. */
        fun state(x: Float, y: Float, threshold: Float) =
            (if (y < -threshold) UP.bit else 0) or (if (y > threshold) DOWN.bit else 0) or
                (if (x < -threshold) LEFT.bit else 0) or (if (x > threshold) RIGHT.bit else 0)

        /** Sends the buttons that differ between D-pad states [old] and [new]. Returns whether one went down. */
        fun send(old: Int, new: Int): Boolean {
            val changed = old xor new
            for (dir in ALL) {
                if (changed and dir.bit != 0) InputBridge.button(dir.button, new and dir.bit != 0)
            }
            return new and changed != 0
        }
    }
}

class Control(
    /** Its key in the saved layout. */
    val id: String,
    val kind: Kind,
    val defaultX: Float,
    val defaultY: Float,
    /** Diameter (or pill height), a fraction of the frame's height. */
    val size: Float,
    /** A path under the game's controller icons, with * for the set. */
    glyph: String?,
    val pill: Boolean = false,
    val icon: Icon? = null,
    val label: String = if (icon == null) id else "",
    /** In the editor's list of controls to add. */
    val name: String = id,
) {
    val anchor = when {
        defaultY < 0.2f -> Anchor.TOP
        defaultX < 0.5f -> Anchor.LEFT
        else -> Anchor.RIGHT
    }
    // The game's own glyphs (utility-sprites.lua, controller_*): "color" idle, "black" on a pressed button's orange.
    val idleGlyph = glyph?.replace("*", "color")
    val pressedGlyph = glyph?.replace("*", "black")

    // Where the player put it, saved.
    var shown = true
    var x = defaultX
    var y = defaultY
    var scale = 1f

    /** The fingers on it, oldest first: the oldest drives a stick or the D-pad. */
    val pointers = ArrayList<Int>(2)
    // A held stick's well, where its finger landed (NaN: where the finger is next), and its knob.
    var originX = 0f
    var originY = 0f
    var knobX = 0f
    var knobY = 0f
    private var sentX = 0
    private var sentY = 0
    /** The D-pad's directions held, as [DpadDir] bits. */
    var dpad = 0

    /** Clamped, unlike a saved scale read back as it is. */
    fun scaleTo(value: Float) {
        scale = value.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun resetPlacement() {
        shown = true
        x = defaultX
        y = defaultY
        scale = 1f
    }

    /** Finger [id] lands. Returns whether that pressed something: a button, trigger or D-pad direction. */
    fun press(id: Int, touchX: Float, touchY: Float, layout: PadLayout): Boolean {
        if (id in pointers) return false   // its lift went missing
        pointers += id
        if (pointers.size > 1) return false
        return when (val k = kind) {
            is Kind.Stick -> {
                originX = touchX; originY = touchY
                knobX = touchX; knobY = touchY
                false
            }
            Kind.Dpad -> aim(touchX, touchY, layout)
            is Kind.Button -> { InputBridge.button(k.button, true); true }
            is Kind.Trigger -> { InputBridge.axis(k.axis, InputBridge.AXIS_MAX); true }
        }
    }

    /** The first finger moves. Returns whether a D-pad direction went down. */
    fun follow(touchX: Float, touchY: Float, layout: PadLayout): Boolean = when (val k = kind) {
        is Kind.Stick -> { moveKnob(k, touchX, touchY, layout.radius(this)); false }
        Kind.Dpad -> aim(touchX, touchY, layout)
        else -> false
    }

    /** Finger [id] lifts. Returns whether the control changed. */
    fun lift(id: Int): Boolean {
        val wasFirst = pointers[0] == id
        pointers -= id
        val handOver = pointers.isNotEmpty() && wasFirst && kind.follows
        if (pointers.isNotEmpty() && !handOver) return false
        // Let go, or handed over to the resting finger, recentred where it is.
        release()
        originX = Float.NaN
        return true
    }

    fun release() {
        when (val k = kind) {
            is Kind.Stick -> {
                sentX = 0; sentY = 0
                InputBridge.axis(k.axisX, 0)
                InputBridge.axis(k.axisY, 0)
            }
            Kind.Dpad -> setDpad(0)
            is Kind.Button -> InputBridge.button(k.button, false)
            is Kind.Trigger -> InputBridge.axis(k.axis, 0)
        }
    }

    /** Moves the knob to the finger, within reach [r] of the origin. Sends its offset as fractions of the reach. */
    private fun moveKnob(stick: Kind.Stick, touchX: Float, touchY: Float, r: Float) {
        if (originX.isNaN()) { originX = touchX; originY = touchY }
        var dx = touchX - originX
        var dy = touchY - originY
        val distance = hypot(dx, dy)
        if (distance > r) {
            dx *= r / distance
            dy *= r / distance
        }
        knobX = originX + dx
        knobY = originY + dy
        val ax = (dx / r * InputBridge.AXIS_MAX).toInt()
        val ay = (dy / r * InputBridge.AXIS_MAX).toInt()
        if (ax != sentX) { sentX = ax; InputBridge.axis(stick.axisX, ax) }
        if (ay != sentY) { sentY = ay; InputBridge.axis(stick.axisY, ay) }
    }

    /** Returns whether a direction went down. */
    private fun aim(touchX: Float, touchY: Float, layout: PadLayout): Boolean {
        val dx = touchX - layout.cx(this)
        val dy = touchY - layout.cy(this)
        val distance = hypot(dx, dy)
        val inMiddle = distance <= layout.radius(this) * DPAD_DEAD_ZONE
        return setDpad(if (inMiddle) 0 else DpadDir.state(dx / distance, dy / distance, SIN_22))
    }

    /** Returns whether a direction went down. */
    private fun setDpad(state: Int): Boolean {
        val pressed = DpadDir.send(dpad, state)
        dpad = state
        return pressed
    }

    private companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.8f
        /** The D-pad's middle presses nothing: this part of its radius. */
        const val DPAD_DEAD_ZONE = 0.2f
        const val SIN_22 = 0.38f   // sin 22.5°: where a straight direction meets a diagonal
    }
}

/** In draw order: a later control is drawn on top and wins a touch. */
fun defaultControls() = listOf(
    // Sized by use: A, then Y (inventory). X and B are smaller since gestures press them ([PointerGestures]).
    // Shoulders, triggers, stick clicks and Map/Menu mirror: x as 1 - x, same height.
    Control("LS", Kind.Stick(PadAxis.LEFT_X, PadAxis.LEFT_Y), 0.10f, 0.70f, 0.28f, "joycon/*/L-stick", label = "L", name = "L stick"),
    Control("DPAD", Kind.Dpad, 0.245f, 0.88f, 0.20f, glyph = null, label = "", name = "D-pad"),
    Control("Y", Kind.Button(PadButton.NORTH), 0.885f, 0.635f, 0.125f, "joycon/*/Y"),
    Control("A", Kind.Button(PadButton.SOUTH), 0.885f, 0.895f, 0.13f, "joycon/*/A"),
    Control("X", Kind.Button(PadButton.WEST), 0.827f, 0.765f, 0.115f, "joycon/*/X"),
    Control("B", Kind.Button(PadButton.EAST), 0.943f, 0.765f, 0.115f, "joycon/*/B"),
    Control("LB", Kind.Button(PadButton.LEFT_SHOULDER), 0.075f, 0.42f, 0.10f, "xbox/*/LB", pill = true),
    Control("LT", Kind.Trigger(PadAxis.LEFT_TRIGGER), 0.17f, 0.42f, 0.085f, "xbox/*/LT", pill = true),
    Control("RB", Kind.Button(PadButton.RIGHT_SHOULDER), 0.83f, 0.42f, 0.085f, "xbox/*/RB", pill = true),
    Control("RT", Kind.Trigger(PadAxis.RIGHT_TRIGGER), 0.925f, 0.42f, 0.10f, "xbox/*/RT", pill = true),
    Control("VIEW", Kind.Button(PadButton.BACK), 0.4715f, 0.18f, 0.085f, "xbox/*/windows", icon = Icon.VIEW, name = "Map"),
    Control("MENU", Kind.Button(PadButton.START), 0.5285f, 0.18f, 0.085f, "steam-deck/*/menu", icon = Icon.MENU, name = "Menu"),
    Control("RS", Kind.Stick(PadAxis.RIGHT_X, PadAxis.RIGHT_Y), 0.64f, 0.74f, 0.24f, "joycon/*/R-stick", label = "R", name = "R stick"),
    Control("L3", Kind.Button(PadButton.LEFT_STICK), 0.215f, 0.56f, 0.085f, "joycon/*/L-stick-press"),
    Control("R3", Kind.Button(PadButton.RIGHT_STICK), 0.785f, 0.56f, 0.085f, "joycon/*/R-stick-press"),
)
