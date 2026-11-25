package app.pocketfoundry.ui.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import app.pocketfoundry.core.controller.PadAxis
import app.pocketfoundry.core.controller.DpadDir
import app.pocketfoundry.ui.kit.Factorio
import app.pocketfoundry.ui.kit.GuiSheet
import app.pocketfoundry.ui.kit.Tone
import app.pocketfoundry.ui.kit.dp
import androidx.core.graphics.PathParser

/**
 * Draws the controls in their rendered look ([SpritePainter]); while it loads, in the game's own GUI sprites, or in a
 * drawn look when the game's files can't be read. Also the panel and keys of the toolbar and the add list.
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
    /** What an add list's key does, beside it. */
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Factorio.font(context, 600)
        color = NOTE
    }
    /** Mouse & keys' surfaces. */
    val glass = Glass(context)
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val point = PointF()
    private val signs = HashMap<KeyIcon, Path>()
    private val cross = Path()
    private val bar = Path()
    private val rect = RectF()
    // Made once from 0 to 1, then stretched over each shape by [shaded].
    private val matrix = Matrix()
    private val bevelIdle = LinearGradient(0f, 0f, 0f, 1f, BEVEL_IDLE, BEVEL_STOPS, Shader.TileMode.CLAMP)
    private val bevelPressed = LinearGradient(0f, 0f, 0f, 1f, BEVEL_PRESSED, BEVEL_STOPS, Shader.TileMode.CLAMP)
    private val wellShade = LinearGradient(0f, 0f, 0f, 1f, WELL_SHADE, WELL_STOPS, Shader.TileMode.CLAMP)
    private val chipShade = LinearGradient(0f, 0f, 0f, 1f, CHIP_TOP, CHIP_BOTTOM, Shader.TileMode.CLAMP)

    /** The right stick is a trackpad box (Settings.touch BOX). */
    var box = false

    /** The rendered look; until its pictures load, the controls are drawn as before. */
    val sprites = SpritePainter(layout)

    /** Returns whether [c] is still moving, so another frame should follow. */
    fun draw(canvas: Canvas, sheet: GuiSheet?, c: Control, selected: Boolean, now: Long): Boolean =
        drawAt(canvas, sheet, c, layout.cx(c), layout.cy(c), layout.radius(c), selected, now)

    /** The open drawer's panel, its keys and their names under them. */
    fun drawDrawer(canvas: Canvas, sheet: GuiSheet?, selected: Control?, now: Long): Boolean {
        if (layout.openDrawer == null) return false
        val r = layout.drawerKeyRadius()
        if (!layout.keypad) glass.draw(canvas, layout.drawerPanel)
        val keys = layout.drawerKeys
        var moving = false
        for (i in keys.indices) {
            layout.drawerCentre(i, point)
            // A drawer's cells are one key wide, whatever a key's width on screen.
            if (drawAt(canvas, sheet, keys[i], point.x, point.y, r, keys[i] === selected, now, units = 1f)) moving = true
            caption(canvas, keys[i], point.x, point.y + r * 1.55f, r * 0.42f)
        }
        return moving
    }

    /** What a key in a drawer does, under it, when that says more than the key (PadLayout.captioned): lifted off the glass. */
    private fun caption(canvas: Canvas, c: Control, x: Float, y: Float, size: Float) {
        if (layout.captioned(c)) shadowedLetter(canvas, c.name, x, y, size)
    }

    /** Pale words with a shadow under them: they read on glass or on the game. */
    fun shadowedLetter(canvas: Canvas, label: String, x: Float, y: Float, size: Float) {
        text.setShadowLayer(px(2f), px(2f), px(3f), CAPTION_SHADOW)
        letter(canvas, label, x, y, size, INK_ON_DARK)
        text.clearShadowLayer()
    }

    /**
     * [c] in the add list: its cap [r] high and [units] wide ([c]'s own, at most 2), its legend on it; a stick or a
     * trackpad, a dark cap with its sign. Null till the sprites load.
     */
    fun offer(canvas: Canvas, c: Control, x: Float, y: Float, r: Float, units: Float, now: Long): Boolean? {
        if (c.kind is Kind.Key || c.kind is Kind.ToolKey || c.kind is Kind.Drawer) {
            if (sprites.sprites == null) return null
            return drawAt(canvas, null, c, x, y, r, false, now, units)
        }
        if (!sprites.plainCap(canvas, light = false, units = 1f, x = x, y = y, size = 2 * r)) return null
        c.keyIcon?.let { keyIcon(canvas, it, x, y, r * CAP_TOP * 1.2f, INK_ON_DARK) }
        return false
    }

    /** A set's chip in the editor, [s] high, on the glass: the set shown lit orange, as a held key. */
    fun chip(canvas: Canvas, box: RectF, on: Boolean, s: Float) {
        val radius = s * 0.2f
        if (!on) {
            fill.color = CHIP
            canvas.drawRoundRect(box, radius, radius, fill)
            return
        }
        matrix.setScale(1f, box.height())
        matrix.postTranslate(0f, box.top)
        chipShade.setLocalMatrix(matrix)
        // Opaque: a paint's alpha thins its shader too.
        fill.color = 0xFF000000.toInt()
        fill.shader = chipShade
        canvas.drawRoundRect(box, radius, radius, fill)
        fill.shader = null
        // Light along its top edge.
        canvas.save()
        canvas.clipRect(box.left, box.top, box.right, box.top + px(2f))
        fill.color = CHIP_LIT
        canvas.drawRoundRect(box, radius, radius, fill)
        canvas.restore()
    }

    /** What an add list's key does, from ([left], [y]): beside its cap. */
    fun note(canvas: Canvas, words: String, left: Float, y: Float, size: Float) {
        note.textSize = size
        canvas.drawText(words, left, y - (note.descent() + note.ascent()) / 2, note)
    }

    fun noteWidth(words: String, size: Float): Float {
        note.textSize = size
        return note.measureText(words)
    }

    private fun drawAt(canvas: Canvas, sheet: GuiSheet?, c: Control, x: Float, y: Float, r: Float, selected: Boolean,
                       now: Long, units: Float = c.units, held: Boolean = false): Boolean {
        val pressed = held || c.pointers.isNotEmpty() || layout.isLatched(c) || (c.kind is Kind.Drawer && layout.openDrawer === c)
        if (c.kind is Kind.Key || c.kind is Kind.ToolKey || c.kind is Kind.Drawer) {
            // The rendered keycap, its name written on its top, which is smaller than the key: its walls lean in. Or
            // the drawn one.
            val moving = sprites.draw(canvas, c, x, y, r, box, now, units, held)
            val w: Float
            val h: Float
            val ink: Int
            if (moving != null) {
                w = r * (units - CAP_WALLS)
                h = r * CAP_TOP
                ink = if (pressed || c.light) INK_ON_LIGHT else INK_ON_DARK
                keyLabel(canvas, c, x, y + if (pressed) r * PRESS_DROP else 0f, w, h, ink, units)
            } else {
                w = units * r * 0.85f
                h = r * 0.75f
                ink = glyphColour(pressed)
                drawKey(canvas, c, x, y, r, pressed, units)
            }
            val top = y - h + if (pressed && moving != null) r * PRESS_DROP else 0f
            if (c.kind == Kind.Key && c.latch) led(canvas, x + w, top, h, layout.isLatched(c))
            if (c.kind == Kind.Key && c.longPressMs > 0) clock(canvas, x - w, top, h, ink)
            if (selected) drawSelection(canvas, c, x, y, r)
            return moving == true
        }
        val moving = sprites.draw(canvas, c, x, y, r, box, now)
        if (moving != null) {
            if (selected) drawSelection(canvas, c, x, y, r)
            return moving
        }
        when {
            c.kind == Kind.Trackpad || box && (c.kind as? Kind.Stick)?.axisX == PadAxis.RIGHT_X -> drawTrackpadBox(canvas, x, y, r)
            c.kind is Kind.Stick || c.kind == Kind.KeyStick -> drawStick(canvas, sheet, c, x, y, r, pressed)
            c.kind == Kind.Dpad && sheet != null -> drawSpriteDpad(canvas, sheet, c, x, y, r)
            c.kind == Kind.Dpad -> drawPlainDpad(canvas, c, x, y, r)
            sheet != null -> drawSpriteButton(canvas, sheet, c, x, y, r, pressed)
            else -> drawPlainButton(canvas, c, x, y, r, pressed)
        }
        if (selected) drawSelection(canvas, c, x, y, r)
        return false
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

    /**
     * [c] drawn as it is on screen, at ([x], [y]), [r] its radius, [units] wide, [held] or not: the editor's windows show
     * the key they edit, and the picker its keyboard.
     */
    fun preview(canvas: Canvas, c: Control, x: Float, y: Float, r: Float, now: Long, units: Float = c.units,
                held: Boolean = false) = drawAt(canvas, null, c, x, y, r, false, now, units, held)

    /**
     * A key that stays held has a light, as Caps Lock has, at its top's corner ([right], [top]), its top [h] half high:
     * dark till the key is held, then lit.
     */
    private fun led(canvas: Canvas, right: Float, top: Float, h: Float, lit: Boolean) {
        val w = h * 0.5f
        val tall = h * 0.2f
        val inset = h * 0.26f
        rect.set(right - inset - w, top + inset, right - inset, top + inset + tall)
        fill.color = if (lit) LED_ON else LED_OFF
        canvas.drawRoundRect(rect, tall / 2, tall / 2, fill)
        if (lit) {
            // Its window's rim.
            stroke.color = LED_RIM
            stroke.strokeWidth = px(2f)
            canvas.drawRoundRect(rect, tall / 2, tall / 2, stroke)
        } else {
            // Light catching the hole's lower edge.
            fill.color = LED_EDGE
            canvas.drawRect(rect.left + tall / 2, rect.bottom, rect.right - tall / 2, rect.bottom + px(1f), fill)
        }
    }

    /**
     * A key that goes down only on a long press has a clock at its top's other corner ([left], [top]), its top [h] half
     * high, in its legend's [colour].
     */
    private fun clock(canvas: Canvas, left: Float, top: Float, h: Float, colour: Int) {
        val r = h * 0.17f
        val inset = h * 0.26f
        val cx = left + inset + r
        val cy = top + inset + r
        icon.color = colour
        icon.strokeWidth = r * 0.32f
        canvas.drawCircle(cx, cy, r, icon)
        canvas.drawLine(cx, cy, cx, cy - r * 0.6f, icon)
        canvas.drawLine(cx, cy, cx + r * 0.45f, cy, icon)
    }

    /** A keyboard key in the drawn look: its cap the control's whole size, its name or sign on it (Keys). */
    fun drawKey(canvas: Canvas, c: Control, x: Float, y: Float, r: Float, pressed: Boolean, units: Float = c.units) {
        rect.set(x - units * r, y - r, x + units * r, y + r)
        bevelSlot(pressed, rect.top, rect.bottom) { canvas.drawRoundRect(rect, r * 0.25f, r * 0.25f, it) }
        keyLabel(canvas, c, x, y, units * r * 0.85f, r * 0.75f, glyphColour(pressed), units)
    }

    /**
     * A key's legend on a cap top [w] by [h] from its middle ([x], [y]), as a keyboard prints it: a letter large, a word
     * (Esc, Shift, F11) smaller, a combination its modifiers small over its key; each smaller only to fit. What isn't a
     * key shows its sign; a key that only brings a set, the set's name.
     */
    private fun keyLabel(canvas: Canvas, c: Control, x: Float, y: Float, w: Float, h: Float, colour: Int, units: Float) {
        val sign = c.keyIcon ?: if (c.kind == Kind.Key) KeyNames.sign(c.codes) else null
        if (sign != null) return keyIcon(canvas, sign, x, y, h * 1.2f, colour)
        val main = when {
            c.kind != Kind.Key -> c.label
            c.codes.isEmpty() -> layout.label(c)
            else -> KeyNames.legend(c.legendMain, units)
        }
        if (main.isEmpty()) return
        val room = 2 * w * LABEL_WIDTH
        if (c.legendTop.isEmpty()) return letter(canvas, main, x, y, fitted(main, legendSize(main, h), room), colour)
        letter(canvas, c.legendTop, x, y - h * 0.42f, fitted(c.legendTop, h * MODIFIER_SIZE, room), colour)
        letter(canvas, main, x, y + h * 0.36f, fitted(main, legendSize(main, h) * 0.8f, room), colour)
    }

    private fun legendSize(text: String, h: Float) = h * if (text.length == 1) LETTER_SIZE else WORD_SIZE

    /** At most [size], or as fits [room]: measured large, as at 1 px Android rounds each letter's width. */
    private fun fitted(text: String, size: Float, room: Float) = minOf(size, room * 100 / textWidth(text, 100f))

    /** [sign] [size] wide, its middle at ([x], [y]). */
    private fun keyIcon(canvas: Canvas, sign: KeyIcon, x: Float, y: Float, size: Float, colour: Int) {
        val shape = signs.getOrPut(sign) { PathParser.createPathFromPathData(sign.path) }
        icon.color = colour
        icon.strokeWidth = sign.stroke
        canvas.save()
        canvas.translate(x - size / 2, y - size / 2)
        canvas.scale(size / KeyIcon.BOX, size / KeyIcon.BOX)
        canvas.drawPath(shape, icon)
        canvas.restore()
    }

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
        val glyph = c.icon ?: return circled(canvas, c.label, x, y, r, colour)
        ring(canvas, x, y, r, colour)
        icon.strokeWidth = r * 0.09f
        when (glyph) {
            Icon.VIEW -> {
                val s = r * 0.24f
                rect.set(x - s * 1.1f, y - s * 0.9f, x + s * 0.5f, y + s * 0.4f)
                canvas.drawRect(rect, icon)
                rect.set(x - s * 0.5f, y - s * 0.4f, x + s * 1.1f, y + s * 0.9f)
                canvas.drawRect(rect, icon)
            }
            Icon.MENU -> {
                val s = r * 0.28f
                for (i in -1..1) canvas.drawLine(x - s, y + i * s * 0.65f, x + s, y + i * s * 0.65f, icon)
            }
        }
    }

    private fun drawSpriteButton(canvas: Canvas, sheet: GuiSheet, c: Control, x: Float, y: Float, r: Float,
                                 pressed: Boolean) {
        rect.set(x - r, y - r, x + r, y + r)
        if (c.pill) rect.inset(-r, 0f)
        spriteSlot(canvas, sheet, pressed)
        drawGlyph(canvas, sheet, c, pressed, x, y, r * if (c.pill) 0.78f else 0.6f)
    }

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

    /** The selection round [box]: the FPS counter's, in the editor. */
    fun drawSelection(canvas: Canvas, box: RectF) {
        rect.set(box)
        rect.inset(-layout.frame * 0.008f, -layout.frame * 0.008f)
        brackets(canvas)
    }

    private fun drawSelection(canvas: Canvas, c: Control, x: Float, y: Float, r: Float) {
        val more = px(20f)
        when {
            c.kind is Kind.Key || c.kind is Kind.ToolKey || c.kind is Kind.Drawer || c.kind == Kind.Trackpad ->
                rect.set(x - (c.units + 0.15f) * r, y - 1.15f * r, x + (c.units + 0.15f) * r, y + 1.15f * r)
            c.pill -> rect.set(x - 2 * r - more, y - r - more, x + 2 * r + more, y + r + more)
            else -> rect.set(x - r - more, y - r - more, x + r + more, y + r + more)
        }
        brackets(canvas)
    }

    /** Four corners round [rect], as Factorio marks what the cursor is on: orange, on a dark line that keeps it seen. */
    private fun brackets(canvas: Canvas) {
        val leg = layout.frame * 0.03f
        path.reset()
        for ((cx, cy, dx, dy) in listOf(floatArrayOf(rect.left, rect.top, 1f, 1f), floatArrayOf(rect.right, rect.top, -1f, 1f),
                                         floatArrayOf(rect.left, rect.bottom, 1f, -1f), floatArrayOf(rect.right, rect.bottom, -1f, -1f))) {
            path.moveTo(cx + dx * leg, cy)
            path.lineTo(cx, cy)
            path.lineTo(cx, cy + dy * leg)
        }
        val width = layout.frame * 0.0055f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.color = BRACKET_UNDER
        stroke.strokeWidth = width + px(6f)
        canvas.drawPath(path, stroke)
        stroke.color = BRACKET
        stroke.strokeWidth = width
        canvas.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    private fun drawGlyph(canvas: Canvas, sheet: GuiSheet, c: Control, pressed: Boolean, x: Float, y: Float, half: Float) {
        val glyph = (if (pressed) c.pressedGlyph else c.idleGlyph)?.let { sheet.glyph(it) }
        if (glyph == null) return letter(canvas, c.label, x, y, half * 1.3f, glyphColour(pressed))
        rect.set(x - half, y - half, x + half, y + half)
        canvas.drawBitmap(glyph, null, rect, sprite)
    }

    private fun spriteSlot(canvas: Canvas, sheet: GuiSheet, pressed: Boolean) =
        sheet.slot[if (pressed) GuiSheet.PRESSED else GuiSheet.IDLE].draw(canvas, rect, minOf(rect.width(), rect.height()) / 2, sprite)

    /** As tall as the stick's well and as wide as the box it touches (Kind.Trackpad.HALF_WIDTH), rounded like a laptop's trackpad. */
    private fun drawTrackpadBox(canvas: Canvas, x: Float, y: Float, r: Float) {
        rect.set(x - r * Kind.Trackpad.HALF_WIDTH, y - r, x + r * Kind.Trackpad.HALF_WIDTH, y + r)
        fill.color = Factorio.INSET_DARK
        canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, fill)
        stroke.color = EDGE
        stroke.strokeWidth = edge() * 1.6f
        canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, stroke)
    }

    private fun edgedCircle(canvas: Canvas, x: Float, y: Float, r: Float, colour: Int) {
        fill.color = colour
        canvas.drawCircle(x, y, r, fill)
        stroke.color = EDGE
        stroke.strokeWidth = edge()
        canvas.drawCircle(x, y, r - edge() / 2, stroke)
    }

    private inline fun bevelSlot(pressed: Boolean, top: Float, bottom: Float, shape: (Paint) -> Unit) {
        if (pressed) shaded(bevelPressed, top, bottom, Tone.ORANGE.dark, edge(), shape)
        else shaded(bevelIdle, top, bottom, BEVEL_EDGE, edge(), shape)
    }

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
    /** [n] pixels of a 1440 pixel tall screen, on this one. */
    private fun px(n: Float) = n * layout.frame / 1440f
    private fun corner() = context.dp(GuiSheet.CORNER)
    private fun glyphColour(pressed: Boolean) = if (pressed) INK else GLYPH

    private fun armRect(dir: DpadDir, x: Float, y: Float, arm: Float, near: Float, far: Float) = when (dir) {
        DpadDir.UP -> rect.set(x - arm, y - far, x + arm, y - near)
        DpadDir.DOWN -> rect.set(x - arm, y + near, x + arm, y + far)
        DpadDir.LEFT -> rect.set(x - far, y - arm, x - near, y + arm)
        DpadDir.RIGHT -> rect.set(x + near, y - arm, x + far, y + arm)
    }

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
        /**
         * A rendered keycap's top, in the key's radii: half its height, and what its walls take off each half width
         * (controls.py's keycap, measured on its render).
         */
        private const val CAP_TOP = 0.57f
        private const val CAP_WALLS = 0.43f
        /** A key's legend, in its top's half heights: a letter, a word, a combination's modifiers. */
        private const val LETTER_SIZE = 1.25f
        private const val WORD_SIZE = 1.05f
        private const val MODIFIER_SIZE = 0.62f
        /** A pale cap's legend is dark, a dark cap's pale; on the lit orange of a held key, dark. */
        const val INK_ON_LIGHT = 0xFF2A2926.toInt()
        const val INK_ON_DARK = 0xFFEDEBE6.toInt()
        /** A set's chip: on the glass, a breath of white; the set shown, orange lit along its top. */
        private const val CHIP = 0x10FFFFFF
        private const val CHIP_TOP = 0xFFF3AA42.toInt()
        private const val CHIP_BOTTOM = 0xFFE08C22.toInt()
        private const val CHIP_LIT = 0xFFFFE2AA.toInt()
        /** A key's light: off, with the edge of its hole; on, with its rim. */
        private const val LED_OFF = 0xFF141312.toInt()
        private const val LED_EDGE = 0x21FFFFFF
        private const val LED_ON = 0xFFFFF4D6.toInt()
        private const val LED_RIM = 0x967A440A.toInt()
        private const val CAPTION_SHADOW = 0x96000000.toInt()
        /** What an add list's key does: pale, a little through. */
        private const val NOTE = 0xD6EDEBE6.toInt()
        /** The selection's orange, the held keys', and the dark it's drawn on. */
        private const val BRACKET = 0xFFE9982F.toInt()
        private const val BRACKET_UNDER = 0xAB14100A.toInt()
        /** A key's name takes at most this part of its face's width. */
        private const val LABEL_WIDTH = 0.82f
        /** How far a pressed cap's sign goes down with it, in radii. */
        private const val PRESS_DROP = 0.05f
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
        private val BEVEL_IDLE = intArrayOf(0xFF6B6A6B.toInt(), 0xFF6B6A6B.toInt(), 0xFF474647.toInt(), 0xFF2B2A2B.toInt())
        private val BEVEL_PRESSED = intArrayOf(Tone.ORANGE.light, Tone.ORANGE.light, Tone.ORANGE.face, 0xFFC4802C.toInt())
        private val BEVEL_STOPS = floatArrayOf(0f, 0.06f, 0.09f, 1f)
        private const val BEVEL_EDGE = 0xFF121112.toInt()
        private val WELL_SHADE = intArrayOf(0xE0000000.toInt(), 0xA0242324.toInt(), 0xA0242324.toInt())
        private val WELL_STOPS = floatArrayOf(0f, 0.14f, 1f)
        private const val DPAD_HUB = 0x66000000
        /** A stick knob's radius and a D-pad arm's half width, as parts of the control's radius. */
        private const val KNOB = 0.42f
        private const val ARM = 0.36f
    }
}
