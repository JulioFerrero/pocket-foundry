package app.pocketfoundry.stage

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Plays tools/stage/blueprint.py's simulation (assets/stage/blueprint.json). */
class Blueprint(plan: JSONObject, private val welcome: Boolean) : Drawing() {
    override val fps = 60

    @Volatile var percent = 0

    /** welcome in seconds, import in percent. */
    private class At(json: JSONObject) {
        val welcome = json.getDouble("welcome").toFloat()
        val import = json.getInt("import")
    }

    private class Belt(json: JSONObject) {
        val at = At(json)
        val item = json.getString("item")
        val y = json.getInt("y").toFloat()
        val direction = json.getInt("direction")
        val full = json.getBoolean("full")
        val actors = json.getJSONArray("actors").let { a -> IntArray(a.length()) { a.getInt(it) } }
        val visible = json.getJSONArray("visible").let { a -> Array(a.length()) { a.getString(it) } }
    }

    private class Box(json: JSONObject) {
        val at = At(json)
        val x = json.getInt("x").toFloat()
        val y = json.getInt("y").toFloat()
    }

    private class Inserter(json: JSONObject) {
        val at = At(json)
        val cx = json.getInt("cx").toFloat()
        val cy = json.getInt("cy").toFloat()
        val pick = json.getInt("pick")
        val phase = json.getInt("phase")
        val item = json.getString("item")
        val holding = json.getString("holding")
    }

    private class Wire(json: JSONObject) {
        val at = At(json)
        val path = Path().apply {
            val a = json.getJSONArray("a"); val b = json.getJSONArray("b")
            val ax = a.getInt(0).toFloat(); val ay = a.getInt(1).toFloat()
            val bx = b.getInt(0).toFloat(); val by = b.getInt(1).toFloat()
            moveTo(ax, ay)
            quadTo((ax + bx) / 2, (ay + by) / 2 + 9, bx, by)
        }
    }

    private class Drone(json: JSONObject) {
        val duration = json.getDouble("duration")
        val begin = json.getDouble("begin")
        val measure = PathMeasure(Path().apply {
            val p = json.getJSONArray("path").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            moveTo(p[0], p[1])
            cubicTo(p[2], p[3], p[4], p[5], p[6], p[7])
        }, false)
    }

    private val tick = plan.getDouble("tick")
    private val cycle = plan.getInt("cycle")
    private val period = plan.getInt("period")
    private val speed = plan.getDouble("speed")
    private val gap = plan.getInt("gap")
    private val x0 = plan.getInt("x0")
    private val x1 = plan.getInt("x1")
    private val grab = plan.getInt("grab")
    private val belts = plan.getJSONArray("belts").objects().map(::Belt)
    private val assemblers = plan.getJSONArray("assemblers").objects().map(::Box)
    private val labs = plan.getJSONArray("labs").objects().map(::Box)
    private val inserters = plan.getJSONArray("inserters").objects().map(::Inserter)
    private val poles = plan.getJSONArray("poles").objects().map(::Box)
    private val wires = plan.getJSONArray("wires").objects().map(::Wire)
    private val drones = plan.getJSONArray("drones").objects().map(::Drone)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val gearPaths = assemblers.map { gear(it.x + 24, it.y + 24, 11f, 8f, 8) }
    private val octagons = labs.map { octagon(it.x, it.y, 48f, 12f) }
    private val itemGear = gear(0f, 0f, 4.4f, 3.2f, 6)
    private val science = Path().apply { moveTo(-2.5f, -4f); lineTo(2.5f, -4f); lineTo(4f, 4f); lineTo(-4f, 4f); close() }
    private val dashes = HashMap<Pair<Float, Float>, DashPathEffect>()
    private val background = Paint().apply {
        shader = elliptical(W / 2, H / 2, 0.75f * W, 0.75f * H, intArrayOf(0xFF143352.toInt(), 0xFF091726.toInt()), null)
    }
    private val vignette = Paint().apply {
        val c = 0x050C14
        shader = elliptical(W / 2, H / 2, 0.7f * W, 0.7f * H,
            intArrayOf(c or 0x8C000000.toInt(), c or 0x8C000000.toInt(), c, c or 0x99000000.toInt()), floatArrayOf(0f, 0.35f, 0.6f, 1f))
    }

    private var time = 0.0
    private var progress = 0

    override fun draw(canvas: Canvas, width: Int, height: Int, time: Double) {
        this.time = time
        progress = percent
        canvas.save()
        cover(canvas, width, height, W, H)
        canvas.drawRect(0f, 0f, W, H, background)
        grid(canvas, 16, 0.09f)
        grid(canvas, 64, 0.18f)
        for (belt in belts) drawBelt(canvas, belt)
        assemblers.forEachIndexed { k, a -> drawAssembler(canvas, a, k) }
        labs.forEachIndexed { k, l -> drawLab(canvas, l, k) }
        for (s in inserters) drawInserter(canvas, s)
        var wire = 0
        for ((i, p) in poles.withIndex()) {
            drawPole(canvas, p)
            if (i > 0 && poles[i - 1].y == p.y) drawWire(canvas, wires[wire++])
        }
        for (d in drones) drawDrone(canvas, d)
        canvas.drawRect(0f, 0f, W, H, vignette)
        canvas.restore()
    }

    private fun built(at: At): Float = if (welcome) CubicBezier.EASE_OUT(((time - at.welcome - 0.8) / 0.45).toFloat())
                                        else ((progress - at.import) / 2f).coerceIn(0f, 1f)

    private fun ghost(at: At): Float {
        if (!welcome) return 1 - built(at)
        val t = ((time - at.welcome) / 1.1).toFloat()
        return when {
            t <= 0f || t >= 1f -> 0f
            t < 0.25f -> CubicBezier.EASE_OUT(t / 0.25f)
            t < 0.75f -> 1f
            else -> 1 - CubicBezier.EASE_OUT((t - 0.75f) / 0.25f)
        }
    }

    private fun loopTick() = (floor(time / tick).toLong() % period).toInt()

    private fun drawBelt(canvas: Canvas, belt: Belt) {
        val y = belt.y
        val g = ghost(belt.at)
        if (g > 0f) {
            dashed(GHOST, g, 1f, 5f, 4f)
            canvas.drawLine(0f, y - 8, W, y - 8, stroke)
            canvas.drawLine(0f, y + 8, W, y + 8, stroke)
        }
        val b = built(belt.at)
        if (b <= 0f) return
        fill(BELT, b)
        canvas.drawRect(0f, y - 8, W, y + 8, fill)
        solid(LINE, b, 1.2f)
        canvas.drawLine(0f, y - 8, W, y - 8, stroke)
        canvas.drawLine(0f, y + 8, W, y + 8, stroke)
        solid(AMBER, b * 0.3f, 9f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(2f, 10f), (12 * (1 - frac(time / 0.4))).toFloat())
        if (belt.direction > 0) canvas.drawLine(0f, y, W, y, stroke) else canvas.drawLine(W, y, 0f, y, stroke)
        stroke.pathEffect = null
        val n = loopTick()
        val length = x1 - x0
        for (k in belt.actors.indices) {
            val actor = belt.actors[k]
            val shown = if (actor < 0) {
                if (belt.full) 1f else 0f
            } else {
                // Until its inserter is built, the item flows past.
                val by = built(inserters[actor].at)
                by * (if (belt.visible[k][n] == '1') 1f else 0f) + (1 - by) * (if (belt.full) 1f else 0f)
            }
            if (shown <= 0f) continue
            val u = (k * gap + speed * time / tick) % length
            val x = if (belt.direction > 0) x0 + u else x1 - u
            canvas.save()
            canvas.translate(x.toFloat(), y)
            drawItem(canvas, belt.item, b * shown.coerceAtMost(1f))
            canvas.restore()
        }
    }

    private fun drawItem(canvas: Canvas, item: String, alpha: Float) {
        when (item) {
            "plate" -> { fill(0xFFDDE9F7.toInt(), alpha); canvas.drawRect(-4f, -3f, 4f, 3f, fill) }
            "gear" -> {
                fill(0xFFB9C9DC.toInt(), alpha); canvas.drawPath(itemGear, fill)
                fill(0xFF0E2238.toInt(), alpha); canvas.drawCircle(0f, 0f, 1.3f, fill)
            }
            else -> { fill(AMBER, alpha); canvas.drawPath(science, fill) }
        }
    }

    private fun drawAssembler(canvas: Canvas, a: Box, k: Int) {
        val cx = a.x + 24; val cy = a.y + 24
        val g = ghost(a.at)
        if (g > 0f) {
            dashed(GHOST, g, 1f, 4f, 3f); canvas.drawRect(a.x, a.y, a.x + 48, a.y + 48, stroke)
            dashed(GHOST, g, 1f, 3f, 3f); canvas.drawPath(gearPaths[k], stroke)
        }
        val b = built(a.at)
        if (b <= 0f) return
        fill(BODY, b); canvas.drawRect(a.x, a.y, a.x + 48, a.y + 48, fill)
        solid(LINE, b, 1.5f); canvas.drawRect(a.x, a.y, a.x + 48, a.y + 48, stroke)
        solid(LINE, b * 0.4f, 1f); canvas.drawRect(a.x + 5, a.y + 5, a.x + 43, a.y + 43, stroke)
        canvas.save()
        canvas.rotate((360 * frac((time + k * 0.7) / 6)).toFloat(), cx, cy)
        solid(LINE, b, 1.4f); canvas.drawPath(gearPaths[k], stroke)
        fill(LINE, b); canvas.drawCircle(cx, cy, 3f, fill)
        canvas.restore()
        fill(AMBER, b * pulse(time + k * 0.9, 2.4, 0.3f, 1f))
        canvas.drawRect(a.x + 39, a.y + 5, a.x + 44, a.y + 10, fill)
    }

    private fun drawLab(canvas: Canvas, l: Box, k: Int) {
        val cx = l.x + 24; val cy = l.y + 24
        val g = ghost(l.at)
        if (g > 0f) { dashed(GHOST, g, 1f, 4f, 3f); canvas.drawPath(octagons[k], stroke) }
        val b = built(l.at)
        if (b <= 0f) return
        fill(BODY, b); canvas.drawPath(octagons[k], fill)
        solid(LINE, b, 1.5f); canvas.drawPath(octagons[k], stroke)
        solid(LINE, b * 0.5f, 1f); canvas.drawCircle(cx, cy, 13f, stroke)
        fill(CYAN, b * pulse(time + k * 0.8, 3.0, 0.35f, 0.95f)); canvas.drawCircle(cx, cy, 7f, fill)
    }

    private fun drawInserter(canvas: Canvas, s: Inserter) {
        val cx = s.cx; val cy = s.cy
        val end = cy + 16 * s.pick
        val g = ghost(s.at)
        if (g > 0f) { dashed(GHOST, g, 1f, 3f, 2f); canvas.drawRect(cx - 6, cy - 6, cx + 6, cy + 6, stroke) }
        val b = built(s.at)
        if (b <= 0f) return
        fill(0x266EA0E6, b); canvas.drawRect(cx - 6, cy - 6, cx + 6, cy + 6, fill)
        solid(LINE, b, 1f); canvas.drawRect(cx - 6, cy - 6, cx + 6, cy + 6, stroke)
        canvas.save()
        canvas.rotate(swing(s), cx, cy)
        solid(AMBER, b, 2.5f)
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, cy, cx, end, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        solid(AMBER, b, 2f)
        canvas.drawLine(cx - 4, end, cx + 4, end, stroke)
        if (s.holding[loopTick()] == '1') {
            canvas.translate(cx, end)
            drawItem(canvas, s.item, b)
        }
        canvas.restore()
        fill(LINE, b); canvas.drawCircle(cx, cy, 2.5f, fill)
    }

    private fun swing(s: Inserter): Float {
        val begin = (s.phase + grab - cycle) * tick
        val c = frac((time - begin) / (cycle * tick)).toFloat()
        return when {
            c < 0.12f -> 0f
            c < 0.45f -> 180 * SWING((c - 0.12f) / 0.33f)
            c < 0.62f -> 180f
            c < 0.95f -> 180 * (1 - SWING((c - 0.62f) / 0.33f))
            else -> 0f
        }
    }

    private fun drawPole(canvas: Canvas, p: Box) {
        val g = ghost(p.at)
        if (g > 0f) { dashed(GHOST, g, 1f, 2f, 2f); canvas.drawRect(p.x - 4, p.y - 4, p.x + 4, p.y + 4, stroke) }
        val b = built(p.at)
        if (b <= 0f) return
        fill(0x336EA0E6, b); canvas.drawRect(p.x - 4, p.y - 4, p.x + 4, p.y + 4, fill)
        solid(LINE, b, 1f); canvas.drawRect(p.x - 4, p.y - 4, p.x + 4, p.y + 4, stroke)
        solid(LINE, b, 0.8f)
        canvas.drawLine(p.x - 4, p.y - 4, p.x + 4, p.y + 4, stroke)
        canvas.drawLine(p.x + 4, p.y - 4, p.x - 4, p.y + 4, stroke)
    }

    private fun drawWire(canvas: Canvas, w: Wire) {
        val b = built(w.at)
        if (b <= 0f) return
        solid(AMBER, b * 0.55f, 1f)
        canvas.drawPath(w.path, stroke)
    }

    private fun drawDrone(canvas: Canvas, d: Drone) {
        d.measure.getPosTan(frac((time - d.begin) / d.duration).toFloat() * d.measure.length, pos, tan)
        val angle = (atan2(tan[1], tan[0]) * 180 / PI).toFloat()
        canvas.save()
        canvas.translate(pos[0] + 16, pos[1] + 22)
        canvas.rotate(angle)
        fill(0x47000000, 1f)
        rect.set(-8f, -5f, 8f, 5f); canvas.drawOval(rect, fill)
        canvas.restore()
        canvas.save()
        canvas.translate(pos[0], pos[1])
        canvas.rotate(angle)
        fill(LINE, 1f)
        rect.set(-6f, -4.5f, 6f, 4.5f); canvas.drawRoundRect(rect, 2f, 2f, fill)
        solid(LINE, 1f, 1f)
        for (dx in intArrayOf(-6, 6)) for (dy in intArrayOf(-6, 6)) canvas.drawCircle(dx.toFloat(), dy.toFloat(), 3.2f, stroke)
        fill(AMBER, 1f); canvas.drawCircle(6f, 0f, 1.6f, fill)
        canvas.restore()
    }

    /** Lines half a unit wide inside each cell's top and left edges, as the SVG pattern clips them. */
    private fun grid(canvas: Canvas, step: Int, alpha: Float) {
        solid(GRID, alpha, 0.5f)
        var x = 0
        while (x < W) { canvas.drawLine(x + 0.25f, 0f, x + 0.25f, H, stroke); x += step }
        var y = 0
        while (y < H) { canvas.drawLine(0f, y + 0.25f, W, y + 0.25f, stroke); y += step }
    }

    private fun fill(color: Int, alpha: Float) {
        fill.color = withAlpha(color, alpha)
    }

    private fun solid(color: Int, alpha: Float, width: Float) {
        stroke.color = withAlpha(color, alpha)
        stroke.strokeWidth = width
        stroke.pathEffect = null
    }

    private fun dashed(color: Int, alpha: Float, width: Float, on: Float, off: Float) {
        solid(color, alpha, width)
        stroke.pathEffect = dashes.getOrPut(on to off) { DashPathEffect(floatArrayOf(on, off), 0f) }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0xFFFFFF)

    private fun pulse(t: Double, seconds: Double, low: Float, high: Float): Float {
        val p = frac(t / seconds).toFloat()
        val up = if (p < 0.5f) CubicBezier.EASE_IN_OUT(p * 2) else 1 - CubicBezier.EASE_IN_OUT((p - 0.5f) * 2)
        return low + (high - low) * up
    }

    private companion object {
        const val W = 880f
        const val H = 400f
        const val LINE = 0xFFCFE3FF.toInt()
        const val GHOST = 0x8C8CBEFF.toInt()
        const val AMBER = 0xFFF1A240.toInt()
        const val CYAN = 0xFF7DCAED.toInt()
        const val BELT = 0xD9142840.toInt()
        const val BODY = 0x1A6EA0E6
        const val GRID = 0xFF78AAE6.toInt()
        val SWING = CubicBezier(0.45f, 0f, 0.55f, 1f)

        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

        fun gear(cx: Float, cy: Float, outer: Float, inner: Float, teeth: Int) = Path().apply {
            for (k in 0 until teeth * 4) {
                val a = 2 * PI * k / (teeth * 4)
                val r = if (k % 4 == 1 || k % 4 == 2) outer else inner
                val x = (cx + r * cos(a)).toFloat(); val y = (cy + r * sin(a)).toFloat()
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

        fun octagon(x: Float, y: Float, s: Float, cut: Float) = Path().apply {
            moveTo(x + cut, y); lineTo(x + s - cut, y); lineTo(x + s, y + cut); lineTo(x + s, y + s - cut)
            lineTo(x + s - cut, y + s); lineTo(x + cut, y + s); lineTo(x, y + s - cut); lineTo(x, y + cut); close()
        }

        /** An ellipse, as SVG's objectBoundingBox radial gradient is on a wide box. */
        fun elliptical(cx: Float, cy: Float, rx: Float, ry: Float, colors: IntArray, stops: FloatArray?) =
            RadialGradient(cx, cy, rx, colors, stops, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, cx, cy) })
            }
    }
}
