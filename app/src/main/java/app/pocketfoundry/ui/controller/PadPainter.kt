package app.pocketfoundry.ui.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import app.pocketfoundry.core.controller.DpadDir
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.GuiSheet
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.dp

/**
 * Draws the controls in the game's own GUI sprites, or in a drawn look when the game's files can't be read. Also the
 * panel and keys of the toolbar and the add list.
 */
class PadPainter(private val context: Context, private val layout: PadLayout) {
    // The toolbar draws its icons with these two too.
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Factorio.font(context, 700)
    }
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val cross = Path()
    private val bar = Path()
    private val rect = RectF()
    // Made once from 0 to 1, then stretched over each shape by [shaded].
    private val matrix = Matrix()
    private val bevelIdle = LinearGradient(0f, 0f, 0f, 1f, BEVEL_IDLE, BEVEL_STOPS, Shader.TileMode.CLAMP)
    private val bevelPressed = LinearGradient(0f, 0f, 0f, 1f, BEVEL_PRESSED, BEVEL_STOPS, Shader.TileMode.CLAMP)
    private val wellShade = LinearGradient(0f, 0f, 0f, 1f, WELL_SHADE, WELL_STOPS, Shader.TileMode.CLAMP)

    fun draw(canvas: Canvas, sheet: GuiSheet?, c: Control, selected: Boolean) {
        val x = layout.cx(c)
        val y = layout.cy(c)
        val r = layout.radius(c)
        val pressed = c.pointers.isNotEmpty()
        when {
            c.kind is Kind.Stick -> drawStick(canvas, sheet, c, x, y, r, pressed)
            c.kind == Kind.Dpad && sheet != null -> drawSpriteDpad(canvas, sheet, c, x, y, r)
            c.kind == Kind.Dpad -> drawPlainDpad(canvas, c, x, y, r)
            sheet != null -> drawSpriteButton(canvas, sheet, c, x, y, r, pressed)
            else -> drawPlainButton(canvas, c, x, y, r, pressed)
        }
        if (selected) drawSelection(canvas, sheet, c, x, y, r)
    }

    /** The panel behind the toolbar and the add list, round [box] of keys [s] tall. */
    fun panel(canvas: Canvas, sheet: GuiSheet?, box: RectF, s: Float) {
        val pad = s * if (sheet != null) 0.18f else 0.1f
        rect.set(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        if (sheet != null) return sheet.frame.draw(canvas, rect, corner(), sprite)
        fill.color = Factorio.PANEL
        canvas.drawRoundRect(rect, pad * 0.6f, pad * 0.6f, fill)
        stroke.color = Factorio.FRAME
        stroke.strokeWidth = pad * 0.8f
        canvas.drawRoundRect(rect, pad * 0.6f, pad * 0.6f, stroke)
    }

    /** A toolbar or add-list button, inset in [box]: keys are [s] tall. */
    fun toolKey(canvas: Canvas, sheet: GuiSheet?, box: RectF, held: Boolean, s: Float) {
        rect.set(box)
        rect.inset(s * 0.07f, s * 0.07f)
        if (sheet != null) spriteSlot(canvas, sheet, held)
        else bevelSlot(held, rect.top, rect.bottom) { canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, it) }
    }

    fun letter(canvas: Canvas, label: String, x: Float, y: Float, size: Float, colour: Int) {
        text.color = colour
        text.textSize = size
        canvas.drawText(label, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    fun textWidth(label: String, size: Float): Float {
        text.textSize = size
        return text.measureText(label)
    }

    /** While held, the well stays where the finger landed and the knob follows the finger. */
    private fun drawStick(canvas: Canvas, sheet: GuiSheet?, c: Control, x: Float, y: Float, r: Float, pressed: Boolean) {
        val active = pressed && !c.originX.isNaN()
        val wellX = if (active) c.originX else x
        val wellY = if (active) c.originY else y
        val knobX = if (active) c.knobX else x
        val knobY = if (active) c.knobY else y
        val knob = r * KNOB
        if (sheet == null) {
            shaded(wellShade, wellY - r, wellY + r, Factorio.FRAME, edge() * 1.6f) { canvas.drawCircle(wellX, wellY, r, it) }
            bevelSlot(pressed, knobY - knob, knobY + knob) { canvas.drawCircle(knobX, knobY, knob, it) }
            circled(canvas, c.label, knobX, knobY, knob, glyphColour(pressed))
        } else {
            // The game's dark inset. The slot sprite's bevel can't be cut round: the knob is its middle and edge.
            edgedCircle(canvas, wellX, wellY, r, Factorio.INSET_DARK)
            edgedCircle(canvas, knobX, knobY, knob, if (pressed) SLOT_PRESSED else SLOT)
            drawGlyph(canvas, sheet, c, pressed, knobX, knobY, knob * 0.62f)
        }
    }

    private fun drawPlainDpad(canvas: Canvas, c: Control, x: Float, y: Float, r: Float) {
        val arm = r * ARM
        val corner = arm * 0.3f
        cross.reset()
        bar.reset()
        cross.addRoundRect(x - arm, y - r, x + arm, y + r, corner, corner, Path.Direction.CW)
        bar.addRoundRect(x - r, y - arm, x + r, y + arm, corner, corner, Path.Direction.CW)
        cross.op(bar, Path.Op.UNION)
        bevelSlot(false, y - r, y + r) { canvas.drawPath(cross, it) }
        for (dir in DpadDir.ALL) {
            if (c.dpad and dir.bit == 0) continue
            armRect(dir, x, y, arm, arm, r + 1)
            canvas.save()
            canvas.clipRect(rect)
            bevelSlot(true, y - r, y + r) { canvas.drawPath(cross, it) }
            canvas.restore()
        }
        fill.color = DPAD_HUB
        canvas.drawCircle(x, y, arm * 0.42f, fill)
        for (dir in DpadDir.ALL) arrow(canvas, dir, x, y, (r + arm) / 2, arm * 0.34f, glyphColour(c.dpad and dir.bit != 0))
    }

    private fun drawPlainButton(canvas: Canvas, c: Control, x: Float, y: Float, r: Float, pressed: Boolean) {
        val colour = glyphColour(pressed)
        if (c.pill) {
            rect.set(x - 2 * r, y - r, x + 2 * r, y + r)
            bevelSlot(pressed, rect.top, rect.bottom) { canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, it) }
            rect.inset(r * 0.32f, r * 0.32f)
            icon.color = colour
            icon.strokeWidth = r * 0.1f
            canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, icon)
            return letter(canvas, c.label, x, y, r * 0.78f, colour)
        }
        bevelSlot(pressed, y - r, y + r) { canvas.drawCircle(x, y, r, it) }
        if (c.icon == null) return circled(canvas, c.label, x, y, r, colour)
        ring(canvas, x, y, r, colour)
        icon.strokeWidth = r * 0.09f
        if (c.icon == Icon.VIEW) {
            val s = r * 0.24f
            rect.set(x - s * 1.1f, y - s * 0.9f, x + s * 0.5f, y + s * 0.4f)
            canvas.drawRect(rect, icon)
            rect.set(x - s * 0.5f, y - s * 0.4f, x + s * 1.1f, y + s * 0.9f)
            canvas.drawRect(rect, icon)
        } else {
            val s = r * 0.28f
            for (i in -1..1) canvas.drawLine(x - s, y + i * s * 0.65f, x + s, y + i * s * 0.65f, icon)
        }
    }

    /** In the game's own GUI sprites: its dark slot button, orange while pressed, with its controller glyph. */
    private fun drawSpriteButton(canvas: Canvas, sheet: GuiSheet, c: Control, x: Float, y: Float, r: Float,
                                 pressed: Boolean) {
        rect.set(x - r, y - r, x + r, y + r)
        if (c.pill) rect.inset(-r, 0f)
        spriteSlot(canvas, sheet, pressed)
        drawGlyph(canvas, sheet, c, pressed, x, y, r * if (c.pill) 0.78f else 0.6f)
    }

    /** Four slot buttons round the game's sunken middle, dark right through. */
    private fun drawSpriteDpad(canvas: Canvas, sheet: GuiSheet, c: Control, x: Float, y: Float, r: Float) {
        val arm = r * ARM
        rect.set(x - arm, y - arm, x + arm, y + arm)
        fill.color = Factorio.INSET_DARK
        canvas.drawRect(rect, fill)
        sheet.deepFrame.draw(canvas, rect, corner(), sprite)
        for (dir in DpadDir.ALL) {
            armRect(dir, x, y, arm, arm + edge() * 2, r)
            val on = c.dpad and dir.bit != 0
            spriteSlot(canvas, sheet, on)
            arrow(canvas, dir, rect.centerX(), rect.centerY(), 0f, arm * 0.34f, glyphColour(on))
        }
    }

    private fun drawSelection(canvas: Canvas, sheet: GuiSheet?, c: Control, x: Float, y: Float, r: Float) {
        stroke.color = Tone.ORANGE.face
        stroke.strokeWidth = layout.frame * 0.004f
        if (c.pill) {
            rect.set(x - 2.2f * r, y - 1.2f * r, x + 2.2f * r, y + 1.2f * r)
            canvas.drawRoundRect(rect, 0.3f * r, 0.3f * r, stroke)
        } else if (sheet != null) {
            rect.set(x - 1.15f * r, y - 1.15f * r, x + 1.15f * r, y + 1.15f * r)
            canvas.drawRect(rect, stroke)
        } else {
            canvas.drawCircle(x, y, r * 1.12f, stroke)
        }
    }

    /** [c]'s glyph from the game, or its label if there's none, [half] its size out from (x, y). */
    private fun drawGlyph(canvas: Canvas, sheet: GuiSheet, c: Control, pressed: Boolean, x: Float, y: Float, half: Float) {
        val glyph = (if (pressed) c.pressedGlyph else c.idleGlyph)?.let { sheet.glyph(it) }
        if (glyph == null) return letter(canvas, c.label, x, y, half * 1.3f, glyphColour(pressed))
        rect.set(x - half, y - half, x + half, y + half)
        canvas.drawBitmap(glyph, null, rect, sprite)
    }

    private fun spriteSlot(canvas: Canvas, sheet: GuiSheet, pressed: Boolean) =
        sheet.slot[if (pressed) GuiSheet.PRESSED else GuiSheet.IDLE].draw(canvas, rect, minOf(rect.width(), rect.height()) / 2, sprite)

    /** A circle of [colour] inside the slot button's darkest edge. */
    private fun edgedCircle(canvas: Canvas, x: Float, y: Float, r: Float, colour: Int) {
        fill.color = colour
        canvas.drawCircle(x, y, r, fill)
        stroke.color = EDGE
        stroke.strokeWidth = edge()
        canvas.drawCircle(x, y, r - edge() / 2, stroke)
    }

    /** The drawn look's button: [shape] filled with the bevel, then edged. */
    private inline fun bevelSlot(pressed: Boolean, top: Float, bottom: Float, shape: (Paint) -> Unit) {
        if (pressed) shaded(bevelPressed, top, bottom, Tone.ORANGE.dark, edge(), shape)
        else shaded(bevelIdle, top, bottom, BEVEL_EDGE, edge(), shape)
    }

    /** [shape] filled with [gradient] stretched from [top] to [bottom], then edged. */
    private inline fun shaded(gradient: LinearGradient, top: Float, bottom: Float, edgeColour: Int, edgeWidth: Float,
                              shape: (Paint) -> Unit) {
        matrix.setScale(1f, bottom - top)
        matrix.postTranslate(0f, top)
        gradient.setLocalMatrix(matrix)
        fill.shader = gradient
        shape(fill)
        fill.shader = null
        stroke.color = edgeColour
        stroke.strokeWidth = edgeWidth
        shape(stroke)
    }

    private fun edge() = layout.frame * 0.003f
    private fun corner() = context.dp(GuiSheet.CORNER)
    private fun glyphColour(pressed: Boolean) = if (pressed) INK else GLYPH

    /** In [rect]: [dir]'s arm of a cross [arm] wide each side, from [near] to [far] out from its middle (x, y). */
    private fun armRect(dir: DpadDir, x: Float, y: Float, arm: Float, near: Float, far: Float) = when (dir) {
        DpadDir.UP -> rect.set(x - arm, y - far, x + arm, y - near)
        DpadDir.DOWN -> rect.set(x - arm, y + near, x + arm, y + far)
        DpadDir.LEFT -> rect.set(x - far, y - arm, x - near, y + arm)
        DpadDir.RIGHT -> rect.set(x + near, y - arm, x + far, y + arm)
    }

    /** [dir]'s arrow, [distance] out from (x, y): a tip, and a base [size] each side across it. */
    private fun arrow(canvas: Canvas, dir: DpadDir, x: Float, y: Float, distance: Float, size: Float, colour: Int) {
        val tip = distance + size * 0.55f
        val base = distance - size * 0.45f
        path.reset()
        path.moveTo(x + dir.dx * tip, y + dir.dy * tip)
        path.lineTo(x + dir.dx * base - dir.dy * size, y + dir.dy * base + dir.dx * size)
        path.lineTo(x + dir.dx * base + dir.dy * size, y + dir.dy * base - dir.dx * size)
        path.close()
        fill.color = colour
        canvas.drawPath(path, fill)
    }

    private fun ring(canvas: Canvas, x: Float, y: Float, r: Float, colour: Int) {
        icon.color = colour
        icon.strokeWidth = r * 0.1f
        canvas.drawCircle(x, y, r * 0.6f, icon)
    }

    private fun circled(canvas: Canvas, label: String, x: Float, y: Float, r: Float, colour: Int) {
        ring(canvas, x, y, r, colour)
        letter(canvas, label, x, y, if (label.length == 1) r * 0.78f else r * 0.56f, colour)
    }

    companion object {
        /** The blue of the game's controller glyphs (data/core/graphics/icons/controller). */
        const val GLYPH = 0xFF80CEF0.toInt()
        /** Icons on a pressed button's orange. */
        const val INK = 0xFF1E1E1E.toInt()
        /** Icons on the toolbar's dark buttons. */
        const val LIGHT = 0xFFE8E6E3.toInt()
        /** The game's slot button: its darkest edge, and its middle idle and clicked. */
        private const val EDGE = 0xFF090909.toInt()
        private const val SLOT = 0xFF2D2D2D.toInt()
        private const val SLOT_PRESSED = 0xFFDC7B00.toInt()
        // The drawn look's buttons, lit along the top.
        private val BEVEL_IDLE = intArrayOf(0xFF6B6A6B.toInt(), 0xFF6B6A6B.toInt(), 0xFF474647.toInt(), 0xFF2B2A2B.toInt())
        private val BEVEL_PRESSED = intArrayOf(Tone.ORANGE.light, Tone.ORANGE.light, Tone.ORANGE.face, 0xFFC4802C.toInt())
        private val BEVEL_STOPS = floatArrayOf(0f, 0.06f, 0.09f, 1f)
        private const val BEVEL_EDGE = 0xFF121112.toInt()
        /** The drawn look's stick well, shaded under its top lip. */
        private val WELL_SHADE = intArrayOf(0xE0000000.toInt(), 0xA0242324.toInt(), 0xA0242324.toInt())
        private val WELL_STOPS = floatArrayOf(0f, 0.14f, 1f)
        private const val DPAD_HUB = 0x66000000
        /** A stick knob's radius and a D-pad arm's half width, as parts of the control's radius. */
        private const val KNOB = 0.42f
        private const val ARM = 0.36f
    }
}
