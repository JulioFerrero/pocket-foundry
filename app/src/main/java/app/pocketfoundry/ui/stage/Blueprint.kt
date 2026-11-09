package app.pocketfoundry.ui.stage

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pocket Foundry's own drawing, from no game art: six assemblers turn a belt of plates into a belt of gears, above six
 * labs fed science from a belt running west, while two drones fly over. Welcome builds it piece by piece in under
 * five seconds; the import builds it as its [percent] grows.
 */
class Blueprint(private val welcome: Boolean) : Drawing() {
    override val fps = 60
    var percent = 0

    private enum class Item { PLATE, GEAR, SCIENCE }

    /** Inserters take items off a [full] belt where their hands meet it, or put them on an empty one. */
    private class Belt(val item: Item, val y: Float, val east: Boolean, val full: Boolean, val step: Int) {
        val edges = floatArrayOf(0f, y - 8, W, y - 8, 0f, y + 8, W, y + 8)
        /** Of the [PER_SWING] items that pass while an inserter swings once, the inserter that works each, if any. */
        val workers = arrayOfNulls<Inserter>(PER_SWING)
    }

    /**
     * Its hand rests [reach] below it over the pickup (above, if negative) and swings round to drop on the other side.
     * It works item [slot] of every [PER_SWING] on its belt, and swings in time with the belt to meet each of them.
     */
    private class Inserter(val x: Float, val y: Float, val reach: Float, val belt: Belt, slot: Int, val step: Int) {
        /** How far along the belt its hand is, from where the items come in. */
        val hand = if (belt.east) x - X0 else X1 - x
        /** Where it is in its swing when the belt starts: it grabs at 0, or drops at [DROP], as its item passes. */
        val phase = (GAP * slot - hand) / SWING_LENGTH + if (belt.full) 0f else DROP

        init { belt.workers[slot] = this }
    }

    /** Flies a cubic curve ([path]: start, two controls, end) at an even speed, [seconds] a trip, from [begin]. */
    private class Drone(path: FloatArray, val seconds: Double, val begin: Double) {
        val measure = PathMeasure(Path().apply {
            moveTo(path[0], path[1])
            cubicTo(path[2], path[3], path[4], path[5], path[6], path[7])
        }, false)
    }

    private val plateBelt = Belt(Item.PLATE, 108f, east = true, full = true, step = 0)
    private val gearBelt = Belt(Item.GEAR, 12f, east = true, full = false, step = 1)
    private val scienceBelt = Belt(Item.SCIENCE, 292f, east = false, full = true, step = 2)
    private val belts = arrayOf(gearBelt, plateBelt, scienceBelt)
    // In each column: plates into the assembler, gears out of it onto the belt above, science into the lab. The
    // science belt comes in from the right, so its slots count the columns from there.
    private val inserters =
        Array(COLUMNS) { Inserter(columnX(it) + 24, 92f, 16f, plateBelt, SLOTS[it], assemblerStep(it) + 1) } +
        Array(COLUMNS) { Inserter(columnX(it) + 24, 28f, 16f, gearBelt, SLOTS[it], assemblerStep(it) + 2) } +
        Array(COLUMNS) { Inserter(columnX(it) + 24, 308f, -16f, scienceBelt, SLOTS[COLUMNS - 1 - it], labStep(it) + 1) }
    private val drones = arrayOf(
        Drone(floatArrayOf(-40f, 210f, 220f, 30f, 520f, 390f, 920f, 170f), seconds = 15.0, begin = -2.0),
        Drone(floatArrayOf(920f, 340f, 640f, 190f, 300f, 430f, -40f, 70f), seconds = 19.0, begin = -9.0),
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pole = RectF()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val machine = RectF(0f, 0f, MACHINE, MACHINE)
    private val inserterBase = RectF(-6f, -6f, 6f, 6f)
    private val machineGear = gear(MACHINE / 2, MACHINE / 2, 11f, 8f, 8)
    private val itemGear = gear(0f, 0f, 4.4f, 3.2f, 6)
    private val lab = octagon(MACHINE, 12f)
    private val sciencePack = Path().apply { moveTo(-2.5f, -4f); lineTo(2.5f, -4f); lineTo(4f, 4f); lineTo(-4f, 4f); close() }
    private val wire = Path().apply { moveTo(0f, 0f); quadTo(COLUMN_GAP / 2, 9f, COLUMN_GAP, 0f) }
    private val arrows = dashes(2f, 10f)
    private val beltGhost = dashes(5f, 4f)
    private val machineGhost = dashes(4f, 3f)
    private val gearGhost = dashes(3f, 3f)
    private val inserterGhost = dashes(3f, 2f)
    private val poleGhost = dashes(2f, 2f)
    private val background = Paint().apply {
        shader = elliptical(W / 2, H / 2, 0.75f * W, 0.75f * H, intArrayOf(0xFF143352.toInt(), Stage.BACKGROUND), null)
    }
    private val vignette = Paint().apply {
        val c = 0x050C14
        shader = elliptical(W / 2, H / 2, 0.7f * W, 0.7f * H,
            intArrayOf(c or 0x8C000000.toInt(), c or 0x8C000000.toInt(), c, c or 0x99000000.toInt()), floatArrayOf(0f, 0.35f, 0.6f, 1f))
    }

    private var time = 0.0

    override fun draw(canvas: Canvas, width: Int, height: Int, time: Double) {
        this.time = time
        canvas.save()
        cover(canvas, width, height, W, H)
        canvas.drawRect(0f, 0f, W, H, background)
        grid(canvas, 16, 0.09f)
        grid(canvas, 64, 0.18f)
        for (belt in belts) drawBelt(canvas, belt)
        for (i in 0 until COLUMNS) drawAssembler(canvas, i)
        for (i in 0 until COLUMNS) drawLab(canvas, i)
        for (s in inserters) drawInserter(canvas, s)
        for (i in 0 until COLUMNS - 1) {
            drawPole(canvas, poleX(i), TOP_POLES, topPoleStep(i))
            drawPole(canvas, poleX(i), BOTTOM_POLES, bottomPoleStep(i))
        }
        for (i in 0 until COLUMNS - 2) {
            drawWire(canvas, poleX(i), TOP_POLES, maxOf(topPoleStep(i), topPoleStep(i + 1)))
            drawWire(canvas, poleX(i), BOTTOM_POLES, maxOf(bottomPoleStep(i), bottomPoleStep(i + 1)))
        }
        for (d in drones) drawDrone(canvas, d)
        canvas.drawRect(0f, 0f, W, H, vignette)
        canvas.restore()
    }

    // Welcome starts a step every 0.1 s: its ghost fades in and out over 1.1 s, and 0.8 s in, the piece builds over
    // 0.45 s. The import builds a piece every 2 percent, its ghost showing until then.
    private fun welcomeAt(step: Int) = 0.2 + 0.1 * step

    private fun built(step: Int): Float =
        if (welcome) easeOut(((time - welcomeAt(step) - 0.8) / 0.45).toFloat())
        else ((percent - (2 + 2 * step)) / 2f).coerceIn(0f, 1f)

    private fun ghost(step: Int, dashes: PathEffect): Paint? {
        val t = ((time - welcomeAt(step)) / 1.1).toFloat()
        val alpha = when {
            !welcome -> 1 - built(step)
            t <= 0f || t >= 1f -> 0f
            t < 0.25f -> easeOut(t / 0.25f)
            t < 0.75f -> 1f
            else -> 1 - easeOut((t - 0.75f) / 0.25f)
        }
        return if (alpha > 0f) stroke(0x8C8CBEFF.toInt(), alpha, 1f, dashes) else null
    }

    private fun drawBelt(canvas: Canvas, belt: Belt) {
        ghost(belt.step, beltGhost)?.let { canvas.drawLines(belt.edges, it) }
        val b = built(belt.step)
        if (b <= 0f) return
        canvas.drawRect(0f, belt.y - 8, W, belt.y + 8, fill(0xD9142840.toInt(), b))
        canvas.drawLines(belt.edges, stroke(LINE, b, 1.2f))
        val shift = (ARROW_GAP * frac(SPEED * time / ARROW_GAP)).toFloat()
        canvas.save()
        canvas.translate(if (belt.east) shift else -shift, 0f)
        canvas.drawLine(-ARROW_GAP, belt.y, W + ARROW_GAP, belt.y, stroke(AMBER, b * 0.3f, 9f, arrows))
        canvas.restore()
        for (k in 0 until LENGTH / GAP) {
            val along = ((k * GAP + SPEED * time) % LENGTH).toFloat()
            val shown = shown(belt, belt.workers[k % PER_SWING], along)
            if (shown <= 0f) continue
            canvas.save()
            canvas.translate(if (belt.east) X0 + along else X1 - along, belt.y)
            drawItem(canvas, belt.item, b * shown)
            canvas.restore()
        }
    }

    private fun shown(belt: Belt, worker: Inserter?, along: Float): Float {
        val passing = if (belt.full) 1f else 0f
        if (worker == null) return passing
        val there = if (belt.full) along < worker.hand else along >= worker.hand
        // Until its inserter is built, the item rides on by.
        return mix(passing, if (there) 1f else 0f, built(worker.step))
    }

    private fun drawItem(canvas: Canvas, item: Item, alpha: Float) {
        when (item) {
            Item.PLATE -> canvas.drawRect(-4f, -3f, 4f, 3f, fill(0xFFDDE9F7.toInt(), alpha))
            Item.GEAR -> {
                canvas.drawPath(itemGear, fill(0xFFB9C9DC.toInt(), alpha))
                canvas.drawCircle(0f, 0f, 1.3f, fill(0xFF0E2238.toInt(), alpha))
            }
            Item.SCIENCE -> canvas.drawPath(sciencePack, fill(AMBER, alpha))
        }
    }

    private fun drawAssembler(canvas: Canvas, i: Int) {
        val step = assemblerStep(i)
        canvas.save()
        canvas.translate(columnX(i), ASSEMBLER_Y)
        ghost(step, machineGhost)?.let { canvas.drawRect(machine, it) }
        ghost(step, gearGhost)?.let { canvas.drawPath(machineGear, it) }
        val b = built(step)
        if (b > 0f) {
            canvas.drawRect(machine, fill(BODY, b))
            canvas.drawRect(machine, stroke(LINE, b, 1.5f))
            canvas.drawRect(5f, 5f, 43f, 43f, stroke(LINE, b * 0.4f, 1f))
            canvas.drawRect(39f, 5f, 44f, 10f, fill(AMBER, b * pulse(time + i * 0.9, 2.4, 0.3f, 1f)))
            canvas.rotate((360 * frac((time + i * 0.7) / 6)).toFloat(), MACHINE / 2, MACHINE / 2)
            canvas.drawPath(machineGear, stroke(LINE, b, 1.4f))
            canvas.drawCircle(MACHINE / 2, MACHINE / 2, 3f, fill(LINE, b))
        }
        canvas.restore()
    }

    private fun drawLab(canvas: Canvas, i: Int) {
        val step = labStep(i)
        canvas.save()
        canvas.translate(columnX(i), LAB_Y)
        ghost(step, machineGhost)?.let { canvas.drawPath(lab, it) }
        val b = built(step)
        if (b > 0f) {
            canvas.drawPath(lab, fill(BODY, b))
            canvas.drawPath(lab, stroke(LINE, b, 1.5f))
            canvas.drawCircle(MACHINE / 2, MACHINE / 2, 13f, stroke(LINE, b * 0.5f, 1f))
            canvas.drawCircle(MACHINE / 2, MACHINE / 2, 7f, fill(0xFF7DCAED.toInt(), b * pulse(time + i * 0.8, 3.0, 0.35f, 0.95f)))
        }
        canvas.restore()
    }

    private fun drawInserter(canvas: Canvas, s: Inserter) {
        canvas.save()
        canvas.translate(s.x, s.y)
        ghost(s.step, inserterGhost)?.let { canvas.drawRect(inserterBase, it) }
        val b = built(s.step)
        if (b > 0f) {
            canvas.drawRect(inserterBase, fill(0x266EA0E6, b))
            canvas.drawRect(inserterBase, stroke(LINE, b, 1f))
            val c = frac(SPEED * time / SWING_LENGTH + s.phase).toFloat()
            canvas.save()
            canvas.rotate(angle(c))
            canvas.drawLine(0f, 0f, 0f, s.reach, stroke(AMBER, b, 2.5f, cap = Paint.Cap.ROUND))
            canvas.drawLine(-4f, s.reach, 4f, s.reach, stroke(AMBER, b, 2f))
            if (c < DROP) {
                canvas.translate(0f, s.reach)
                drawItem(canvas, s.belt.item, b)
            }
            canvas.restore()
            canvas.drawCircle(0f, 0f, 2.5f, fill(LINE, b))
        }
        canvas.restore()
    }

    /** Degrees, [c] of the way through a swing: 0 over the pickup, 180 over the drop. */
    private fun angle(c: Float) = when {
        c < 0.12f -> 0f
        c < 0.45f -> 180 * smooth((c - 0.12f) / 0.33f)
        c < 0.62f -> 180f
        c < 0.95f -> 180 * (1 - smooth((c - 0.62f) / 0.33f))
        else -> 0f
    }

    private fun drawPole(canvas: Canvas, x: Float, y: Float, step: Int) {
        pole.set(x - 4, y - 4, x + 4, y + 4)
        ghost(step, poleGhost)?.let { canvas.drawRect(pole, it) }
        val b = built(step)
        if (b <= 0f) return
        canvas.drawRect(pole, fill(0x336EA0E6, b))
        canvas.drawRect(pole, stroke(LINE, b, 1f))
        val cross = stroke(LINE, b, 0.8f)
        canvas.drawLine(pole.left, pole.top, pole.right, pole.bottom, cross)
        canvas.drawLine(pole.right, pole.top, pole.left, pole.bottom, cross)
    }

    private fun drawWire(canvas: Canvas, x: Float, y: Float, step: Int) {
        val b = built(step)
        if (b <= 0f) return
        canvas.save()
        canvas.translate(x, y)
        canvas.drawPath(wire, stroke(AMBER, b * 0.55f, 1f))
        canvas.restore()
    }

    private fun drawDrone(canvas: Canvas, d: Drone) {
        d.measure.getPosTan(frac((time - d.begin) / d.seconds).toFloat() * d.measure.length, pos, tan)
        val angle = (atan2(tan[1], tan[0]) * 180 / PI).toFloat()
        canvas.save()
        canvas.translate(pos[0] + 16, pos[1] + 22)
        canvas.rotate(angle)
        canvas.drawOval(-8f, -5f, 8f, 5f, fill(0x47000000, 1f))   // its shadow
        canvas.restore()
        canvas.save()
        canvas.translate(pos[0], pos[1])
        canvas.rotate(angle)
        canvas.drawRoundRect(-6f, -4.5f, 6f, 4.5f, 2f, 2f, fill(LINE, 1f))
        val rotor = stroke(LINE, 1f, 1f)
        canvas.drawCircle(-6f, -6f, 3.2f, rotor)
        canvas.drawCircle(6f, -6f, 3.2f, rotor)
        canvas.drawCircle(-6f, 6f, 3.2f, rotor)
        canvas.drawCircle(6f, 6f, 3.2f, rotor)
        canvas.drawCircle(6f, 0f, 1.6f, fill(AMBER, 1f))
        canvas.restore()
    }

    /** While loops: `step` would allocate. */
    private fun grid(canvas: Canvas, step: Int, alpha: Float) {
        val line = stroke(0xFF78AAE6.toInt(), alpha, 0.5f)
        var x = 0
        while (x < W) {
            canvas.drawLine(x + 0.25f, 0f, x + 0.25f, H, line)
            x += step
        }
        var y = 0
        while (y < H) {
            canvas.drawLine(0f, y + 0.25f, W, y + 0.25f, line)
            y += step
        }
    }

    private fun fill(color: Int, alpha: Float) = fillPaint.also { it.color = withAlpha(color, alpha) }

    private fun stroke(color: Int, alpha: Float, width: Float, dashes: PathEffect? = null, cap: Paint.Cap = Paint.Cap.BUTT) =
        strokePaint.also {
            it.color = withAlpha(color, alpha)
            it.strokeWidth = width
            it.pathEffect = dashes
            it.strokeCap = cap
        }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0xFFFFFF)

    private fun pulse(t: Double, seconds: Double, low: Float, high: Float): Float {
        val p = frac(t / seconds).toFloat()
        return mix(low, high, if (p < 0.5f) smooth(p * 2) else 1 - smooth((p - 0.5f) * 2))
    }

    private companion object {
        const val W = 880f
        const val H = 400f
        const val LINE = 0xFFCFE3FF.toInt()
        const val AMBER = 0xFFF1A240.toInt()
        const val BODY = 0x1A6EA0E6   // a machine's; inserters and poles have the same blue, stronger

        // Belts run off both edges, X0 to X1, at a yellow belt's 1.875 tiles a second with 16 px tiles.
        const val X0 = -40f
        const val X1 = 920f
        const val LENGTH = 960
        const val SPEED = 30.0         // px a second
        const val GAP = 12             // px between items
        const val ARROW_GAP = 12f
        const val PER_SWING = 8        // items that pass while an inserter swings once, there and back
        const val SWING_LENGTH = 96f   // px of belt that passes meanwhile: GAP × PER_SWING, so 3.2 s
        const val DROP = 0.47f         // of a swing, when the hand lets go over the drop

        // Each column is an assembler over a lab, with a pole between each two.
        const val COLUMNS = 6
        const val COLUMN_GAP = 136f
        const val MACHINE = 48f        // an assembler's or a lab's side
        const val ASSEMBLER_Y = 36f
        const val LAB_Y = 316f
        const val TOP_POLES = 60f
        const val BOTTOM_POLES = 340f

        // Which of the 8 items passing per swing each column's inserter works, counting columns from where the belt
        // comes in: all different, so no two reach for the same item, and set so each arm swings a little after the
        // one upstream, rippling down the belt.
        val SLOTS = intArrayOf(0, 3, 6, 1, 4, 7)

        fun columnX(i: Int) = 56 + COLUMN_GAP * i
        fun poleX(i: Int) = columnX(i) + 92

        // The build, a step at a time: the belts (steps 0-2); then column by column each assembler, its two inserters
        // and the pole to its right (3-25); then the labs from the right, each with its inserter and pole (27-44).
        fun assemblerStep(i: Int) = 3 + 4 * i
        fun topPoleStep(i: Int) = assemblerStep(i) + 3
        fun labStep(i: Int) = 27 + 3 * (COLUMNS - 1 - i)
        fun bottomPoleStep(i: Int) = labStep(i) + 2

        fun dashes(on: Float, off: Float) = DashPathEffect(floatArrayOf(on, off), 0f)

        fun gear(cx: Float, cy: Float, outer: Float, inner: Float, teeth: Int) = Path().apply {
            for (k in 0 until teeth * 4) {
                val a = 2 * PI * k / (teeth * 4)
                val r = if (k % 4 == 1 || k % 4 == 2) outer else inner
                val x = (cx + r * cos(a)).toFloat()
                val y = (cy + r * sin(a)).toFloat()
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

        fun octagon(s: Float, cut: Float) = Path().apply {
            moveTo(cut, 0f); lineTo(s - cut, 0f); lineTo(s, cut); lineTo(s, s - cut)
            lineTo(s - cut, s); lineTo(cut, s); lineTo(0f, s - cut); lineTo(0f, cut); close()
        }
    }
}
