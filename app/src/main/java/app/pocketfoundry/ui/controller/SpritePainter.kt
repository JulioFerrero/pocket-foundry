package app.pocketfoundry.ui.controller

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.pocketfoundry.core.controller.DpadDir
import app.pocketfoundry.core.controller.PadAxis
import app.pocketfoundry.core.controller.PadButton

/**
 * Draws a control from its sprites: its base, then its cap at the frame its press has reached. A press runs the cap
 * down to the bottom and lights it; a release springs it back, or runs the press backwards if it hadn't bottomed out.
 */
class SpritePainter(private val layout: PadLayout) {
    var sprites: SpriteSet? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    /** Each moving thing's press: a control's cap, or a toolbar key's (by its [Tool]). */
    private val moves = HashMap<Any, Move>()

    /** Null without the sprites; otherwise whether [c] is still moving, so another frame should follow. */
    fun draw(canvas: Canvas, c: Control, x: Float, y: Float, r: Float, box: Boolean, now: Long): Boolean? {
        val set = sprites ?: return null
        val size = 2 * r
        return when (val kind = c.kind) {
            is Kind.Stick, Kind.KeyStick -> {
                val right = (kind as? Kind.Stick)?.axisX == PadAxis.RIGHT_X
                if (right && box) {
                    still(canvas, set["trackpad"] ?: return null, x, y, size)
                    return false
                }
                val base = set["stick-base"] ?: return null
                val knob = (if (right) set["stick-knob-r"] else null) ?: set["stick-knob"] ?: return null
                val held = c.pointers.isNotEmpty() && !c.originX.isNaN()
                still(canvas, base, if (held) c.originX else x, if (held) c.originY else y, size)
                val click = if (right) PadButton.RIGHT_STICK else PadButton.LEFT_STICK
                cap(canvas, c, knob, clicked(click), if (held) c.knobX else x, if (held) c.knobY else y, size, now)
            }
            Kind.Dpad -> {
                val base = set["dpad-base"] ?: return null
                still(canvas, base, x, y, size)
                // A diagonal still loading (ControlSprites.LATER) leans the cross towards its vertical meanwhile.
                val strip = dpadStrip(c.dpad)?.let { set[it] }
                    ?: dpadStrip(c.dpad and (DpadDir.UP.bit or DpadDir.DOWN.bit))?.let { set[it] }
                cap(canvas, c, strip ?: moves[c]?.strip?.takeUnless { it.bitmap.isRecycled } ?: set["dpad-up"] ?: return null,
                    c.dpad != 0, x, y, size, now)
            }
            // Keys are drawn by PadPainter, their labels and icons at runtime.
            is Kind.Key, is Kind.ToolKey, Kind.Drawer -> null
            else -> {
                val strip = set[STRIPS[c.id] ?: return null] ?: return null
                strip.base?.let { set[it] }?.let { still(canvas, it, x, y, size) }
                cap(canvas, c, strip, c.pointers.isNotEmpty(), x, y, size, now)
            }
        }
    }

    /** Whether the on-screen button that clicks a stick is held. */
    private fun clicked(button: PadButton): Boolean {
        val controls = layout.shownControls
        for (i in controls.indices) {
            val c = controls[i]
            if ((c.kind as? Kind.Button)?.button == button) return c.pointers.isNotEmpty()
        }
        return false
    }

    private fun still(canvas: Canvas, s: Sprite, x: Float, y: Float, size: Float) = frame(canvas, s, 0, x, y, size)

    private fun frame(canvas: Canvas, s: Sprite, index: Int, x: Float, y: Float, size: Float) {
        val k = size / s.controlH
        src.set(index * s.frameW, 0, (index + 1) * s.frameW, s.frameH)
        dst.set(x - s.cx * k, y - s.cy * k, x + (s.frameW - s.cx) * k, y + (s.frameH - s.cy) * k)
        canvas.drawBitmap(s.bitmap, src, dst, paint)
    }

    /**
     * The toolbar from its pieces, [tools] in its cells: the first cell's top-left at ([left], [top]), each [key]
     * wide. Null without the sprites; otherwise whether a key is still moving.
     */
    fun drawToolbar(canvas: Canvas, tools: List<Tool>, held: Array<Tool?>, adding: Boolean, left: Float, top: Float,
                    key: Float, now: Long): Boolean? {
        val set = sprites ?: return null
        val row = set.toolbar ?: return null
        val start = set[row.optString("left")] ?: return null
        val middle = set[row.optString("middle")] ?: return null
        val end = set[row.optString("right")] ?: return null
        // A key still loading (ControlSprites.LATER): the plain toolbar stands in until it's here.
        for (i in tools.indices) if (set[TOOL_STRIPS.getValue(tools[i])] == null) return null
        // Screen pixels per rendered pixel; the row's own origin is where its first cell starts, less its margin.
        val keySize = row.getDouble("keySize").toFloat()
        val k = key / keySize
        var x = left - (row.getDouble("firstKeyX").toFloat() - keySize / 2) * k
        val y = top + key / 2 - row.getDouble("keyY").toFloat() * k
        x = piece(canvas, start, x, y, k)
        for (i in 1 until tools.size) x = piece(canvas, middle, x, y, k)
        piece(canvas, end, x, y, k)
        var moving = false
        for (i in tools.indices) {
            val tool = tools[i]
            val strip = set[TOOL_STRIPS.getValue(tool)] ?: continue
            val down = tool in held || (tool == Tool.ADD && adding)
            if (cap(canvas, tool, strip, down, left + i * key + key / 2, top + key / 2, key, now)) moving = true
        }
        return moving
    }

    /** A toolbar piece at its rendered size times [k], its top-left at ([x], [y]); returns where the next starts. */
    private fun piece(canvas: Canvas, s: Sprite, x: Float, y: Float, k: Float): Float {
        val w = s.frameW / s.ratio * k
        src.set(0, 0, s.frameW, s.frameH)
        dst.set(x, y, x + w, y + s.frameH / s.ratio * k)
        canvas.drawBitmap(s.bitmap, src, dst, paint)
        return x + w
    }

    private fun cap(canvas: Canvas, key: Any, s: Sprite, down: Boolean, x: Float, y: Float, size: Float,
                    now: Long): Boolean {
        val move = moves.getOrPut(key) { Move() }
        if (down) {
            if (!move.down) move.start(Phase.PRESS, 0, now)
            move.strip = s
        } else if (move.down) {
            val at = move.pressIndex(now)
            if (move.phase == Phase.PRESS && at < TRAVEL) move.start(Phase.UNPRESS, at, now)
            else move.start(Phase.RELEASE, 0, now)
        }
        move.down = down
        // A press begun on sprites since let go (another size, or the game screen gone and back) goes on with these.
        val strip = move.strip?.takeUnless { it.bitmap.isRecycled } ?: s
        val steps = ((now - move.since) * strip.fps / 1000).toInt()
        var index = 0
        var moving = true
        when (move.phase) {
            Phase.PRESS -> {
                val i = move.from + steps
                if (i >= strip.press.lastIndex) { move.phase = Phase.HELD; index = strip.held; moving = false }
                else index = strip.press[i]
            }
            Phase.UNPRESS -> {
                val i = move.from - steps
                if (i <= 0) { move.phase = Phase.REST; moving = false } else index = strip.press[i]
            }
            Phase.RELEASE -> {
                if (steps >= strip.release.size) { move.phase = Phase.REST; moving = false }
                else index = strip.release[steps]
            }
            Phase.HELD -> { index = strip.held; moving = false }
            Phase.REST -> moving = false
        }
        frame(canvas, strip, index, x, y, size)
        return moving
    }

    private enum class Phase { REST, PRESS, HELD, UNPRESS, RELEASE }

    private class Move {
        var down = false
        var strip: Sprite? = null
        var phase = Phase.REST
        var from = 0
        var since = 0L

        fun start(phase: Phase, from: Int, now: Long) {
            this.phase = phase
            this.from = from
            since = now
        }

        /** How far into the press the cap is: the last press frame once it has bottomed out. */
        fun pressIndex(now: Long): Int {
            val strip = strip ?: return 0
            return if (phase == Phase.PRESS) (from + ((now - since) * strip.fps / 1000).toInt()).coerceAtMost(strip.press.lastIndex)
            else strip.press.lastIndex
        }
    }

    private fun dpadStrip(bits: Int): String? {
        val up = bits and DpadDir.UP.bit != 0
        val down = bits and DpadDir.DOWN.bit != 0
        val left = bits and DpadDir.LEFT.bit != 0
        val right = bits and DpadDir.RIGHT.bit != 0
        return when {
            up && left -> "dpad-up-left"
            up && right -> "dpad-up-right"
            down && left -> "dpad-down-left"
            down && right -> "dpad-down-right"
            up -> "dpad-up"
            down -> "dpad-down"
            left -> "dpad-left"
            right -> "dpad-right"
            else -> null
        }
    }

    private companion object {
        /** The press frames that travel down; let go before the bottom and the press runs back up. */
        const val TRAVEL = 4
        val STRIPS = mapOf(
            "A" to "a", "B" to "b", "X" to "x", "Y" to "y", "LB" to "lb", "RB" to "rb", "LT" to "lt", "RT" to "rt",
            "VIEW" to "view", "MENU" to "menu", "L3" to "l3", "R3" to "r3",
        )
        val TOOL_STRIPS = Tool.entries.associateWith { "tool-" + it.name.lowercase() }
    }
}
