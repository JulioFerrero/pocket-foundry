package app.pocketfoundry.ui.stage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A small working factory in the imported game's own sprites, which we never ship: seven assemblers turn the iron
 * plates on the belt below them into gears on the belt above, four labs take science packs off a belt running west,
 * and construction robots fly over. Reads the sprites when made, so make it off the main thread; throws if one is
 * missing.
 */
class Factory(private val graphics: File) : Drawing() {
    override val fps = 30

    /** Inserters take items off a [full] belt where their hands meet it, or put them on an empty one. */
    private class Belt(val y: Float, val east: Boolean, val full: Boolean, val icon: Bitmap) {
        /** Of the [PER_SWING] items that pass while an inserter swings once, the inserter that works each, if any. */
        val workers = arrayOfNulls<Inserter>(PER_SWING)
    }

    /**
     * Its hand rests over the pickup, the tile [south] of it (or north), and swings over to drop on the other side.
     * It works item [slot] of every [PER_SWING] on its belt, and swings in time with the belt to meet each of them.
     */
    private class Inserter(val x: Float, val y: Float, val south: Boolean, val belt: Belt, slot: Int) {
        /** How far along the belt its hand is, in px from where the items come in. */
        val hand = if (belt.east) x * TILE + PIVOT_X else W - (x * TILE + PIVOT_X)
        /** Frames into its swing at frame 0: so it grabs ([GRAB]) or drops ([DROP]) just as its item passes. */
        val phase = (GAP * slot - hand) / SPEED + if (belt.full) GRAB else DROP

        init { belt.workers[slot] = this }
    }

    /** [direction] is one of the sheet's 16, clockwise from north; [y] in tiles; [phase], of the loop, where it starts. */
    private class Robot(val direction: Int, val y: Float, val phase: Float)

    /** Frames [w] × [h] px, [perRow] to a row, drawn moved by ([shiftX], [shiftY]) in util.by_pixel units. */
    private class Sprite(val sheet: Bitmap, val w: Int, val h: Int, val perRow: Int = 1, val shiftX: Float = 0f, val shiftY: Float = 0f)

    /** Every bitmap read, for release(). */
    private val sheets = ArrayList<Bitmap>()

    // Frame sizes, frames per row and shifts are the game's own, from its entity prototypes. The grass is its 16
    // variants of a 4-tile square.
    private val grass = Sprite(loadBand("terrain/grass-1.png", 320, 256), 256, 256, 16)
    private val amBase = Sprite(load("entity/assembling-machine-2/assembling-machine-2-base.png"), 200, 188, shiftY = 2.5f)
    private val amAnim = Sprite(load("entity/assembling-machine-2/assembling-machine-2-anim.png"), 148, 158, 8, 1.5f, -15.5f)
    private val amShadow = Sprite(load("entity/assembling-machine-2/assembling-machine-2-shadow.png"), 54, 160,
                                  shiftX = 45.5f, shiftY = 0.5f)
    private val lab = Sprite(load("entity/lab/lab.png"), 194, 174, 11, shiftY = 1.5f)
    private val labPatch = Sprite(load("entity/lab/lab-integration.png"), 242, 162, shiftY = 15.5f)
    private val labShadow = Sprite(load("entity/lab/lab-shadow.png"), 242, 136, shiftX = 13f, shiftY = 11f)
    private val beltAnim = Sprite(loadBand("entity/transport-belt/transport-belt.png", 0, 256), 128, 128, 16)
    private val pole = Sprite(load("entity/small-electric-pole/small-electric-pole.png"), 72, 220, shiftY = -21f)
    private val platform = Sprite(load("entity/inserter/inserter-platform.png"), 105, 79, 4, 1.5f, 6.5f)
    private val arm = load("entity/inserter/inserter-hand-base.png")
    private val handOpen = load("entity/inserter/inserter-hand-open.png")
    private val handClosed = load("entity/inserter/inserter-hand-closed.png")
    private val robot = Sprite(load("entity/construction-robot/construction-robot.png"), 66, 76, 16, shiftY = -4.5f)
    private val robotShadow = Sprite(load("entity/construction-robot/construction-robot-shadow.png"), 104, 49, 16, 33.5f, 18.75f)

    // The layout, in tiles, north to south: the gear belt (row 0.5), the assemblers (3.5) with an inserter above and
    // below each, the plate belt (6.5), the science belt (8.5), and the labs (11.5) with an inserter above each.
    private val gearBelt = Belt(0.5f, east = true, full = false, load("icons/iron-gear-wheel.png"))
    private val plateBelt = Belt(6.5f, east = true, full = true, load("icons/iron-plate.png"))
    private val scienceBelt = Belt(8.5f, east = false, full = true, load("icons/automation-science-pack.png"))
    private val belts = arrayOf(gearBelt, plateBelt, scienceBelt)
    private val gearInserters = Array(ASSEMBLERS.size) { Inserter(ASSEMBLERS[it], 1.5f, south = true, gearBelt, GEAR_SLOTS[it]) }
    private val plateInserters = Array(ASSEMBLERS.size) { Inserter(ASSEMBLERS[it], 5.5f, south = true, plateBelt, PLATE_SLOTS[it]) }
    private val scienceInserters = Array(LABS.size) { Inserter(LABS[it], 9.5f, south = false, scienceBelt, SCIENCE_SLOTS[it]) }
    private val inserters = gearInserters + plateInserters + scienceInserters
    private val robots = arrayOf(Robot(WEST, 4.8f, 0f), Robot(WEST, 9.4f, 0.55f), Robot(EAST, 7.6f, 0.3f))

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (0.55f * 255).toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val src = Rect()
    private val dst = RectF()
    private val wires = wires()
    private val vignette = Paint()
    private var vignetteWidth = 0
    private var vignetteHeight = 0

    // Back to front, row by row from the north, as the game layers them; the inserter arms, wires and robots above.
    override fun draw(canvas: Canvas, width: Int, height: Int, time: Double) {
        val f = (floor(time * fps).toLong() % LOOP).toInt()
        canvas.drawColor(GROUND)
        canvas.save()
        cover(canvas, width, height, W.toFloat(), H.toFloat())
        for (i in GRASS.indices) put(canvas, grass, GRASS[i], i % GRASS_ACROSS * 4 + 2f, i / GRASS_ACROSS * 4 + 2f)
        for (x in ASSEMBLERS) put(canvas, amShadow, 0, x, ASSEMBLER_Y, shade)
        for (x in LABS) put(canvas, labShadow, 0, x, LAB_Y, shade)
        for (belt in belts) drawBelt(canvas, belt, f)
        for (s in gearInserters) drawPlatform(canvas, s)
        // Each machine starts its animation at a different frame, so they don't work in step.
        for (k in ASSEMBLERS.indices) {
            put(canvas, amBase, 0, ASSEMBLERS[k], ASSEMBLER_Y)
            put(canvas, amAnim, (k * 23 + f * 2 / 3) % 64, ASSEMBLERS[k], ASSEMBLER_Y)
        }
        for (x in TOP_POLES) put(canvas, pole, 0, x, ASSEMBLER_Y)
        for (s in plateInserters) drawPlatform(canvas, s)
        for (s in scienceInserters) drawPlatform(canvas, s)
        for (x in BOTTOM_POLES) put(canvas, pole, 0, x, BOTTOM_POLE_Y)
        for (k in LABS.indices) {
            put(canvas, labPatch, 0, LABS[k], LAB_Y)
            put(canvas, lab, (k * 13 + f * 33 / 96) % 33, LABS[k], LAB_Y)
        }
        for (s in inserters) drawArm(canvas, s, f)
        drawWires(canvas)
        for (r in robots) drawRobot(canvas, r, f)
        canvas.restore()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette(width, height))
    }

    private fun drawBelt(c: Canvas, belt: Belt, f: Int) {
        // transport-belt.png's first row runs east, its second west; ours moves 2 of its frames each frame.
        val frame = (if (belt.east) 0 else 16) + 2 * f % 16
        for (tx in -1..(W / TILE).toInt() + 1) put(c, beltAnim, frame, tx + 0.5f, belt.y)
        val travel = SPEED * f
        for (k in Math.floorDiv(-MARGIN - travel, GAP)..Math.floorDiv(W + MARGIN - travel, GAP)) {
            val along = k * GAP + travel
            if (!carries(belt, belt.workers[Math.floorMod(k, PER_SWING)], along)) continue
            val x = if (belt.east) along else W - along
            val lane = if (Math.floorMod(k, 2) != 0) 0.25f else -0.25f   // items alternate between the two lanes
            icon(c, belt.icon, x / TILE, belt.y + lane)
        }
    }

    /** Whether there's an item [along] the belt: a full belt loses it at its inserter's hand, an empty one gains it there. */
    private fun carries(belt: Belt, worker: Inserter?, along: Int): Boolean {
        if (worker == null) return belt.full
        return if (belt.full) along < worker.hand else along >= worker.hand
    }

    private fun drawPlatform(c: Canvas, s: Inserter) = put(c, platform, if (s.south) PLATFORM_SOUTH else PLATFORM_NORTH, s.x, s.y)

    private fun drawArm(c: Canvas, s: Inserter, f: Int) {
        val at = CYCLE * frac((f + s.phase) / CYCLE.toDouble()).toFloat()
        val holding = at >= GRAB || at < DROP
        val swing = towardPickup(at)
        val angle = swing * PI
        val reach = (1.2f - 0.2f * swing) * TILE
        val pivotX = s.x * TILE + PIVOT_X
        val pivotY = s.y * TILE - 8
        val handX = (pivotX + sin(angle) * reach * 0.9).toFloat()
        val handY = (s.y * TILE + (if (s.south) -1 else 1) * cos(angle) * reach).toFloat()
        val elbowX = (pivotX + handX) / 2
        val elbowY = (pivotY + handY) / 2 - 0.42f * TILE
        segment(c, arm, pivotX, pivotY, elbowX, elbowY, 16f)
        segment(c, if (holding) handClosed else handOpen, handX, handY, elbowX, elbowY, 36f)
        if (holding) icon(c, s.belt.icon, handX / TILE, handY / TILE, 26)
    }

    /** 1 over the pickup, 0 over the drop, [at] frames into a swing: out with the item, a rest, back, and a wait. */
    private fun towardPickup(at: Float) = when {
        at < DROP -> 1 - smooth(at / SWING)
        at < GRAB - SWING -> 0f
        at < GRAB -> smooth((at - (GRAB - SWING)) / SWING)
        else -> 1f
    }

    private fun drawWires(c: Canvas) {
        for (wire in wires) {
            line.color = 0x4F1E1810   // its shadow, first
            c.save()
            c.translate(44f, 150f)
            c.drawPath(wire, line)
            c.restore()
            line.color = 0xFF965C34.toInt()
            c.drawPath(wire, line)
        }
    }

    /** Across the screen and a bit beyond, once a loop, bobbing as it flies. */
    private fun drawRobot(c: Canvas, r: Robot, f: Int) {
        val travel = ((f.toFloat() / LOOP + r.phase) % 1) * (W + 6 * TILE)
        val x = if (r.direction == WEST) W + 3 * TILE - travel else travel - 3 * TILE
        val bob = (3 * sin(2 * PI * (f / 48.0 + r.phase))).toFloat()
        put(c, robotShadow, r.direction, x / TILE, r.y, shade)
        put(c, robot, 16 + r.direction, x / TILE, r.y - 0.9f + bob / TILE)   // the sheet's second row: in flight
    }

    /** Draws [s]'s [frame] centred on tile (cx, cy). */
    private fun put(c: Canvas, s: Sprite, frame: Int, cx: Float, cy: Float, p: Paint = paint) =
        blit(c, s.sheet, frame % s.perRow * s.w, frame / s.perRow * s.h, s.w, s.h,
             start(cx, s.shiftX, s.w), start(cy, s.shiftY, s.h), s.w.toFloat(), s.h.toFloat(), p)

    /** Icon sheets hold the 64 px icon, then its 32 px mipmap. */
    private fun icon(c: Canvas, sheet: Bitmap, cx: Float, cy: Float, size: Int = 32) {
        val x = start(cx, 0f, size)
        val y = start(cy, 0f, size)
        if (size == 32) blit(c, sheet, 64, 0, 32, 32, x, y, 32f, 32f, paint)
        else blit(c, sheet, 0, 0, 64, 64, x, y, size.toFloat(), size.toFloat(), paint)
    }

    /** Where a [size]-px sprite starts when centred on tile [at], moved by [shift] in util.by_pixel units: 1/32 tile. */
    private fun start(at: Float, shift: Float, size: Int) = floor(at * TILE + shift * TILE / 32 - size / 2f + 0.5f)

    private fun blit(c: Canvas, sheet: Bitmap, sx: Int, sy: Int, sw: Int, sh: Int, x: Float, y: Float, dw: Float, dh: Float, p: Paint) {
        src.set(sx, sy, sx + sw, sy + sh)
        dst.set(x, y, x + dw, y + dh)
        c.drawBitmap(sheet, src, dst, p)
    }

    /** In pixels, not tiles. */
    private fun segment(c: Canvas, sheet: Bitmap, topX: Float, topY: Float, bottomX: Float, bottomY: Float, width: Float) {
        val dx = topX - bottomX
        val dy = topY - bottomY
        val length = maxOf(4f, hypot(dx, dy))
        c.save()
        c.translate((topX + bottomX) / 2, (topY + bottomY) / 2)
        c.rotate((atan2(dx, -dy) * 180 / PI).toFloat())
        dst.set(-width / 2, -length / 2, width / 2, length / 2)
        c.drawBitmap(sheet, null, dst, paint)
        c.restore()
    }

    /** Copper wires sagging from pole top to pole top. The top row runs on to poles out of sight past both edges. */
    private fun wires(): Array<Path> {
        val top = floatArrayOf(-1.5f) + TOP_POLES + floatArrayOf(29.5f)
        return Array(top.size - 1) { wire(top[it], top[it + 1], ASSEMBLER_Y) } +
            Array(BOTTOM_POLES.size - 1) { wire(BOTTOM_POLES[it], BOTTOM_POLES[it + 1], BOTTOM_POLE_Y) }
    }

    private fun wire(fromX: Float, toX: Float, y: Float) = Path().apply {
        val top = y * TILE - 114
        moveTo(fromX * TILE + 14, top)
        quadTo((fromX + toX) / 2 * TILE, top + 60, toX * TILE - 14, top)   // sagging 30 px in the middle
    }

    private fun vignette(width: Int, height: Int): Paint {
        if (width != vignetteWidth || height != vignetteHeight) {
            vignetteWidth = width
            vignetteHeight = height
            // Centred at 60% 40% and reaching the farthest corner: radii 0.6 × √2 of each side.
            val clear = GROUND and 0xFFFFFF
            vignette.shader = elliptical(0.6f * width, 0.4f * height, 0.6f * width * SQRT2, 0.6f * height * SQRT2,
                                         intArrayOf(clear, clear, clear or 0xB3000000.toInt()), floatArrayOf(0f, 0.4f, 1f))
        }
        return vignette
    }

    private fun load(path: String) = kept(BitmapFactory.decodeFile(File(graphics, path).path), path)

    /** Only rows [top] to [top] + [height] of the sheet. */
    private fun loadBand(path: String, top: Int, height: Int): Bitmap {
        @Suppress("DEPRECATION")   // its replacement is Android 12's
        val decoder = BitmapRegionDecoder.newInstance(File(graphics, path).path, false)
        try {
            return kept(decoder.decodeRegion(Rect(0, top, decoder.width, top + height), null), path)
        } finally {
            decoder.recycle()
        }
    }

    private fun kept(bitmap: Bitmap?, path: String) = (bitmap ?: throw FileNotFoundException(path)).also { sheets += it }

    override fun release() = sheets.forEach { it.recycle() }

    private companion object {
        const val W = 1760              // px: 27.5 tiles by 12.5, the sprites at 1:1
        const val H = 800
        const val TILE = 64f
        const val LOOP = 288            // frames before it all repeats: whole loops of the belts, swings and machines
        const val SPEED = 4             // px a frame: a yellow belt's 1.875 tiles a second at 30 fps
        const val GAP = 16              // px between items, alternating lanes: 2 per lane per tile
        const val MARGIN = 40           // px past the edges where items are still drawn
        const val PER_SWING = 12        // items that pass while an inserter swings once: SPEED × CYCLE / GAP
        const val CYCLE = 48f           // frames an inserter's swing takes, there and back
        const val GRAB = 44f            // frames into it when the hand closes, over the pickup
        const val DROP = 20f            // and when it opens, over the drop
        const val SWING = 20f           // frames from one side to the other
        const val PIVOT_X = 2f          // px from an inserter's centre to its arm's pivot
        const val PLATFORM_SOUTH = 0    // inserter-platform.png's frame for a pickup to the south
        const val PLATFORM_NORTH = 2
        const val EAST = 4              // of a robot sheet's 16 directions
        const val WEST = 12
        const val ASSEMBLER_Y = 3.5f
        const val LAB_Y = 11.5f
        const val BOTTOM_POLE_Y = 11.2f
        const val SQRT2 = 1.4142135f
        const val GROUND = 0xFF201810.toInt()

        val ASSEMBLERS = floatArrayOf(3.5f, 7.5f, 11.5f, 15.5f, 19.5f, 23.5f, 27.5f)
        val LABS = floatArrayOf(11.5f, 15.5f, 19.5f, 23.5f)
        val TOP_POLES = FloatArray(ASSEMBLERS.size - 1) { ASSEMBLERS[it + 1] - 2 }   // between the assemblers
        val BOTTOM_POLES = FloatArray(LABS.size - 1) { LABS[it] + 2 }               // between the labs
        // Which of the 12 items passing per swing each inserter works: all different on a belt, so no two reach for
        // the same item, and mixed so the arms don't swing in step.
        val GEAR_SLOTS = intArrayOf(7, 5, 4, 3, 2, 0, 11)
        val PLATE_SLOTS = intArrayOf(0, 7, 3, 10, 5, 1, 8)
        val SCIENCE_SLOTS = intArrayOf(3, 8, 0, 6)
        const val GRASS_ACROSS = 7
        /** grass-1.png's variant for each 4-tile square of ground, [GRASS_ACROSS] to a row. */
        val GRASS = intArrayOf(14, 14, 14, 6, 5, 15, 5, 3, 14, 9, 4, 2, 1, 12, 14, 5, 0, 2, 1, 1, 6, 7, 0, 14, 10, 14, 6, 7)
    }
}
