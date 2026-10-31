package app.pocketfoundry.ui.stage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Plays tools/stage/factory_sim.py's loop with the imported game's sprites, which we never ship.
 * Reads them when made, so make it off the main thread; throws if one is missing.
 */
class Factory(sim: JSONObject, graphics: File) : Drawing() {
    override val fps = FPS

    private class Belt(json: JSONObject) {
        val y = json.getDouble("y").toFloat()
        val direction = json.getInt("direction")
        val first = json.getInt("first")
        val slots = json.getJSONArray("slots").let { a -> Array(a.length()) { i -> a.getJSONArray(i).ints() } }
    }

    private class Inserter(json: JSONObject) {
        val x = json.getDouble("x").toFloat()
        val y = json.getDouble("y").toFloat()
        val south = json.getString("pickup") == "south"
        val item = json.getString("item")
        val phase = json.getInt("phase")
        val holding = json.getString("holding")
    }

    private val w = sim.getInt("W")
    private val h = sim.getInt("H")
    private val t = sim.getInt("T").toFloat()
    private val frames = sim.getInt("frames")
    private val cycle = sim.getInt("cycle")
    private val speed = sim.getInt("speed")
    private val gap = sim.getInt("gap")
    private val ground = sim.getJSONArray("ground").ints()
    private val assemblers = sim.getJSONArray("assemblers").floats()
    private val labs = sim.getJSONArray("labs").floats()
    private val poles = sim.getJSONArray("poles").let { a -> (0 until a.length()).map { a.getJSONArray(it).floats() } }
    private val asmStart = sim.getJSONArray("asmStart").ints()
    private val labStart = sim.getJSONArray("labStart").ints()
    private val robots = sim.getJSONArray("robots").let { a -> (0 until a.length()).map { a.getJSONArray(it).floats() } }
    private val inserters = sim.getJSONArray("inserters").let { a -> (0 until a.length()).map { Inserter(a.getJSONObject(it)) } }
    private val belts = sim.getJSONObject("belts").let { b -> b.keys().asSequence().associateWith { Belt(b.getJSONObject(it)) } }

    private val grass = band(graphics, "terrain/grass-1.png", 320, 256)
    private val amBase = read(graphics, "entity/assembling-machine-2/assembling-machine-2-base.png")
    private val amAnim = read(graphics, "entity/assembling-machine-2/assembling-machine-2-anim.png")
    private val amShadow = read(graphics, "entity/assembling-machine-2/assembling-machine-2-shadow.png")
    private val lab = read(graphics, "entity/lab/lab.png")
    private val labPatch = read(graphics, "entity/lab/lab-integration.png")
    private val labShadow = read(graphics, "entity/lab/lab-shadow.png")
    private val belt = band(graphics, "entity/transport-belt/transport-belt.png", 0, 256)
    private val pole = read(graphics, "entity/small-electric-pole/small-electric-pole.png")
    private val platform = read(graphics, "entity/inserter/inserter-platform.png")
    private val arm = read(graphics, "entity/inserter/inserter-hand-base.png")
    private val handOpen = read(graphics, "entity/inserter/inserter-hand-open.png")
    private val handClosed = read(graphics, "entity/inserter/inserter-hand-closed.png")
    private val robot = read(graphics, "entity/construction-robot/construction-robot.png")
    private val robotShadow = read(graphics, "entity/construction-robot/construction-robot-shadow.png")
    private val icons = mapOf(
        "plate" to read(graphics, "icons/iron-plate.png"),
        "gear" to read(graphics, "icons/iron-gear-wheel.png"),
        "science" to read(graphics, "icons/automation-science-pack.png"),
    )
    private val sheets = listOf(grass, amBase, amAnim, amShadow, lab, labPatch, labShadow, belt, pole, platform, arm,
                                handOpen, handClosed, robot, robotShadow) + icons.values

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (0.55f * 255).toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val src = Rect()
    private val dst = RectF()
    private val wires = wires()
    private var vignette: Paint? = null
    private var vignetteSize = 0L

    override fun draw(canvas: Canvas, width: Int, height: Int, time: Double) {
        canvas.drawColor(0xFF201810.toInt())
        canvas.save()
        cover(canvas, width, height, w.toFloat(), h.toFloat())
        drawFrame(canvas, (floor(time * FPS).toLong() % frames).toInt())
        canvas.restore()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette(width, height))
    }

    private fun drawFrame(c: Canvas, f: Int) {
        var i = 0
        for (gy in 0 until h step 256) for (gx in 0 until w step 256) {
            draw(c, grass, ground[i++] * 256, 0, 256, 256, gx.toFloat(), gy.toFloat(), 256f, 256f, paint)
        }
        for (x in assemblers) put(c, amShadow, 0, 0, 54, 160, x, 3.5f, 45.5f, 0.5f, shade)
        for (x in labs) put(c, labShadow, 0, 0, 242, 136, x, 11.5f, 13f, 11f, shade)
        for ((row, name) in listOf(0 to "gear", 0 to "plate", 1 to "science")) {
            val belt = belts.getValue(name)
            val index = row * 16 + (2 * f) % 16
            for (tx in -1 until 29) frame(c, this.belt, 128, 128, 16, index, tx + 0.5f, belt.y)
            for (k in belt.first until belt.first + belt.slots.size) {
                val px = beltX(belt, k, f)
                if (px > -40 && px < w + 40 && present(belt, k, f)) {
                    icon(c, name, px / t, belt.y + if (Math.floorMod(k, 2) != 0) 0.25f else -0.25f)
                }
            }
        }
        // Back to front, inserter arms above, as the game layers them.
        val objects = ArrayList<Pair<Float, () -> Unit>>()
        assemblers.forEachIndexed { k, x ->
            objects += 3.5f to {
                put(c, amBase, 0, 0, 200, 188, x, 3.5f, 0f, 2.5f)
                frame(c, amAnim, 148, 158, 8, (asmStart[k] + f * 2 / 3) % 64, x, 3.5f, 1.5f, -15.5f)
            }
        }
        labs.forEachIndexed { k, x ->
            objects += 11.5f to {
                put(c, labPatch, 0, 0, 242, 162, x, 11.5f, 0f, 15.5f)
                frame(c, lab, 194, 174, 11, (labStart[k] + f * 33 / 96) % 33, x, 11.5f, 0f, 1.5f)
            }
        }
        for (s in inserters) objects += s.y to { frame(c, platform, 105, 79, 4, if (s.south) 0 else 2, s.x, s.y, 1.5f, 6.5f) }
        for ((x, y) in poles) objects += y to { put(c, pole, 0, 0, 72, 220, x, y, 0f, -21f) }
        objects.sortedBy { it.first }.forEach { it.second() }
        for (s in inserters) {
            val swing = swingOf(f, s.phase)
            val holding = s.holding[f] == '1'
            val dropDir = if (s.south) -1 else 1
            val angle = swing * PI
            val reach = (1.2f - 0.2f * swing) * t
            val pivotX = s.x * t + 2; val pivotY = s.y * t - 8
            val handX = (pivotX + sin(angle) * reach * 0.9).toFloat()
            val handY = (s.y * t + dropDir * cos(angle) * reach).toFloat()
            val elbowX = (pivotX + handX) / 2; val elbowY = (pivotY + handY) / 2 - 0.42f * t
            segment(c, arm, pivotX, pivotY, elbowX, elbowY, 16f)
            segment(c, if (holding) handClosed else handOpen, handX, handY, elbowX, elbowY, 36f)
            if (holding) icon(c, s.item, handX / t, handY / t, 26)
        }
        for (wire in wires) {
            for ((colour, ox, oy) in WIRE_COLOURS) {
                line.color = colour
                c.save(); c.translate(ox, oy); c.drawPath(wire, line); c.restore()
            }
        }
        for ((direction, y, phase) in robots) {
            val span = w + 6 * t
            val travel = ((f.toFloat() / frames + phase) % 1) * span
            val x = if (direction.toInt() == 12) w + 3 * t - travel else travel - 3 * t
            val bob = (3 * sin(2 * PI * (f / 48.0 + phase))).toFloat()
            frame(c, robotShadow, 104, 49, 16, direction.toInt(), x / t, y, 33.5f, 18.75f, shade)
            put(c, robot, direction.toInt() * 66, 76, 66, 76, x / t, y - 0.9f + bob / t, 0f, -4.5f)
        }
    }

    private fun present(belt: Belt, k: Int, f: Int): Boolean {
        val s = belt.slots.getOrNull(k - belt.first) ?: return false
        var v = s[0] == 1
        var i = 1
        while (i < s.size && s[i] <= f) { v = !v; i++ }
        return v
    }

    private fun beltX(belt: Belt, k: Int, f: Int): Float {
        val d = (k * gap + speed * f).toFloat()
        return if (belt.direction > 0) d else w - d
    }

    private fun swingOf(f: Int, phase: Int): Float {
        val c = (f + phase) % cycle
        return when {
            c < 20 -> 1 - smooth(c / 20f)
            c < 24 -> 0f
            c < 44 -> smooth((c - 24) / 20f)
            else -> 1f
        }
    }

    // Centred on tile (cx, cy), shifted in Factorio's util.by_pixel units.

    private fun put(c: Canvas, sheet: Bitmap, sx: Int, sy: Int, sw: Int, sh: Int, cx: Float, cy: Float,
                    shiftX: Float = 0f, shiftY: Float = 0f, p: Paint = paint, dw: Int = sw, dh: Int = sh) {
        val x = floor(cx * t + shiftX * 2 - dw / 2f + 0.5f)
        val y = floor(cy * t + shiftY * 2 - dh / 2f + 0.5f)
        draw(c, sheet, sx, sy, sw, sh, x, y, dw.toFloat(), dh.toFloat(), p)
    }

    private fun frame(c: Canvas, sheet: Bitmap, fw: Int, fh: Int, line: Int, index: Int, cx: Float, cy: Float,
                      shiftX: Float = 0f, shiftY: Float = 0f, p: Paint = paint) =
        put(c, sheet, (index % line) * fw, (index / line) * fh, fw, fh, cx, cy, shiftX, shiftY, p)

    private fun draw(c: Canvas, sheet: Bitmap, sx: Int, sy: Int, sw: Int, sh: Int, x: Float, y: Float, dw: Float, dh: Float, p: Paint) {
        src.set(sx, sy, sx + sw, sy + sh)
        dst.set(x, y, x + dw, y + dh)
        c.drawBitmap(sheet, src, dst, p)
    }

    /** Icon sheets hold the 64 px icon, then its 32 px mipmap. */
    private fun icon(c: Canvas, name: String, cx: Float, cy: Float, size: Int = 32) {
        val sheet = icons.getValue(name)
        if (size == 32) put(c, sheet, 64, 0, 32, 32, cx, cy) else put(c, sheet, 0, 0, 64, 64, cx, cy, dw = size, dh = size)
    }

    /** b is the sprite's bottom, a its top; in pixels, not tiles. */
    private fun segment(c: Canvas, sheet: Bitmap, ax: Float, ay: Float, bx: Float, by: Float, width: Float) {
        val dx = ax - bx; val dy = ay - by
        val length = maxOf(4f, hypot(dx, dy))
        c.save()
        c.translate((ax + bx) / 2, (ay + by) / 2)
        c.rotate((atan2(dx, -dy) * 180 / PI).toFloat())
        dst.set(-width / 2, -length / 2, width / 2, length / 2)
        c.drawBitmap(sheet, null, dst, paint)
        c.restore()
    }

    private fun wires(): List<Path> {
        val top = poles.filter { it[1] == 3.5f }
        val rows = listOf(listOf(floatArrayOf(-1.5f, 3.5f)) + top + listOf(floatArrayOf(29.5f, 3.5f)), poles.filter { it[1] > 10 })
        return rows.flatMap { row ->
            val tops = row.map { (x, y) -> x * t to y * t - 114 }
            (1 until tops.size).map { k ->
                val (ax, ay) = tops[k - 1]; val (bx, by) = tops[k]
                Path().apply {
                    for (s in 0..40) {
                        val u = s / 40f
                        val x = ax + 14 + (bx - ax - 28) * u
                        val y = ay + (by - ay) * u + 120 * u * (1 - u)
                        if (s == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
            }
        }
    }

    private fun vignette(width: Int, height: Int): Paint {
        val size = width.toLong() shl 32 or height.toLong()
        if (size != vignetteSize || vignette == null) {
            vignetteSize = size
            // CSS's farthest-corner ellipse from 60% 40%: 0.6 × √2 of each side.
            val rx = 0.6f * width * SQRT2; val ry = 0.6f * height * SQRT2
            val shade = 0x201810
            vignette = Paint().apply {
                shader = RadialGradient(0.6f * width, 0.4f * height, rx, intArrayOf(shade, shade, shade or 0xB3000000.toInt()),
                                        floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, 0.6f * width, 0.4f * height) })
                }
            }
        }
        return vignette!!
    }

    override fun release() = sheets.forEach { it.recycle() }

    private companion object {
        const val FPS = 30
        const val SQRT2 = 1.4142135f
        val WIRE_COLOURS = listOf(Triple(0x4F1E1810, 44f, 150f), Triple(0xFF965C34.toInt(), 0f, 0f))

        fun smooth(x: Float) = x * x * (3 - 2 * x)
        fun JSONArray.ints() = IntArray(length()) { getInt(it) }
        fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }

        fun read(graphics: File, path: String): Bitmap =
            BitmapFactory.decodeFile(File(graphics, path).path, BitmapFactory.Options().apply { inScaled = false })
                ?: throw FileNotFoundException(path)

        fun band(graphics: File, path: String, top: Int, height: Int): Bitmap {
            val file = File(graphics, path)
            if (!file.isFile) throw FileNotFoundException(path)
            val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path)
                          else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)
            return try {
                decoder.decodeRegion(Rect(0, top, decoder.width, top + height), null) ?: throw FileNotFoundException(path)
            } finally {
                decoder.recycle()
            }
        }
    }
}
