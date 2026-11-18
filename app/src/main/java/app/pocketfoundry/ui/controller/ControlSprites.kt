package app.pocketfoundry.ui.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import app.pocketfoundry.core.controller.DpadDir
import app.pocketfoundry.core.controller.PadAxis
import app.pocketfoundry.core.controller.PadButton
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.mainHandler
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * One picture of the controls' rendered look (assets/controls, made in Blender): a still base, or a cap's strip of
 * frames side by side. Its measures are in its own pixels, at the scale it was loaded.
 */
class Sprite(
    val bitmap: Bitmap,
    val frames: Int,
    val frameW: Int,
    val frameH: Int,
    /** The control's centre in a frame. */
    val cx: Float,
    val cy: Float,
    /** The control's width and height: a circle's diameter, a pill's width and height. */
    val controlW: Float,
    val controlH: Float,
    /** Its pixels per pixel of the picture as rendered: measures in [extra] are in those. */
    val ratio: Float,
    val press: IntArray,
    val release: IntArray,
    val held: Int,
    val fps: Int,
    val base: String?,
    /** Its whole entry in sprites.json, for measures some sprites carry (the FPS counter's display). */
    val extra: JSONObject,
)

/**
 * The loaded sprites, and how the toolbar's pieces join (sprites.json's "toolbar", in rendered pixels). Some load the
 * first time they're asked for (ControlSprites.LATER): until then [get] says null and the caller draws what it has.
 */
class SpriteSet internal constructor(internal val sprites: HashMap<String, Sprite>, val toolbar: JSONObject?,
                                     private val later: (String) -> Unit) {
    operator fun get(name: String): Sprite? = sprites[name] ?: null.also { later(name) }
}

/**
 * The rendered sprites, decoded off the main thread at the size the screen needs: they're drawn for a 1440-pixel
 * frame, so a smaller screen keeps a smaller copy. Kept as hardware bitmaps, in the GPU's memory only (84 MB of them
 * at 1440), so the rarely used ones (LATER) wait until they're needed, and all go when the game screen does.
 */
object ControlSprites {
    private const val DIR = "controls"
    /** Loads at or above this scale keep the pictures as rendered. */
    private const val STEP = 0.05f
    /** The frame height the sprites were rendered for: the controls' sizes are fractions of it. */
    private const val DESIGN_FRAME = 1440f
    /** Loaded when first asked for: the D-pad's diagonals, the layout editor's keys, Show, the right stick's knob. */
    private val LATER = setOf("dpad-up-left", "dpad-up-right", "dpad-down-left", "dpad-down-right",
                              "tool-add", "tool-remove", "tool-smaller", "tool-bigger", "tool-opacity", "tool-reset",
                              "tool-done", "tool-show", "stick-knob-r")

    private val worker = Executors.newSingleThreadExecutor()
    // Main thread only, but for [loaded], which the worker's results reach through the main thread too.
    @Volatile private var loaded: SpriteSet? = null
    private var loadedScale = 0f
    private var wantedScale = 0f
    /** Bumped by every new load and by [release]: a result from before is thrown away. */
    private var generation = 0
    private var entries: JSONObject? = null
    private val loading = HashSet<String>()
    private var ready: () -> Unit = {}

    /** The sprites as loaded, for views that don't load them (the FPS counter); null until the controls have. */
    fun current() = loaded

    /** The sprites for a frame [frame] pixels tall, or null while they load; [ready] runs on the main thread after. */
    fun get(context: Context, frame: Float, ready: () -> Unit): SpriteSet? {
        this.ready = ready
        if (frame <= 0f) return loaded
        val scale = scaleFor(frame)
        if (scale != loadedScale && scale != wantedScale) {
            wantedScale = scale
            val app = context.applicationContext
            val made = ++generation
            worker.execute {
                val result = runCatching {
                    val json = app.assets.open("$DIR/sprites.json").bufferedReader().use { JSONObject(it.readText()) }
                    val all = json.getJSONObject("sprites")
                    val sprites = HashMap<String, Sprite>()
                    for (name in all.keys()) if (name !in LATER) sprites[name] = decode(app, all.getJSONObject(name), scale)
                    Triple(json, all, sprites)
                }.onFailure { Log.w(TAG, "controls: sprites not loaded", it) }.getOrNull()
                mainHandler.post {
                    if (made != generation || result == null) return@post result?.third?.values.orEmpty().forEach { it.bitmap.recycle() }
                    val (json, all, sprites) = result
                    loaded?.let { old -> recycleLater(old) }
                    entries = all
                    loading.clear()
                    loaded = SpriteSet(sprites, json.optJSONObject("toolbar")) { more(app, it, scale, made) }
                    loadedScale = scale
                    this.ready()
                }
            }
        }
        return loaded
    }

    /** The game screen went: its sprites go too, a moment later, once nothing draws them. */
    fun release() {
        val gone = loaded ?: return
        loaded = null
        loadedScale = 0f
        wantedScale = 0f
        generation++
        loading.clear()
        recycleLater(gone)
    }

    /** A frame already drawn may still show [set]'s pictures: they go once it's surely been replaced. */
    private fun recycleLater(set: SpriteSet) =
        mainHandler.postDelayed({ set.sprites.values.forEach { it.bitmap.recycle() } }, RECYCLE_DELAY_MS)

    /** [name], one of LATER, loaded now that it's wanted. */
    private fun more(context: Context, name: String, scale: Float, made: Int) {
        val entry = entries?.optJSONObject(name) ?: return
        if (made != generation || !loading.add(name)) return
        worker.execute {
            val sprite = runCatching { decode(context, entry, scale) }
                .onFailure { Log.w(TAG, "controls: $name not loaded", it) }.getOrNull()
            mainHandler.post {
                val set = loaded
                if (made != generation || set == null || sprite == null) return@post sprite?.bitmap?.recycle() ?: Unit
                set.sprites[name] = sprite
                ready()
            }
        }
    }

    private fun scaleFor(frame: Float): Float {
        val wanted = (frame / DESIGN_FRAME).coerceAtMost(1f)
        return (wanted / STEP).roundToInt().coerceAtLeast(1) * STEP
    }

    private fun decode(context: Context, entry: JSONObject, scale: Float): Sprite {
        val source = context.assets.open("$DIR/" + entry.getString("file")).use { BitmapFactory.decodeStream(it) }
        val frames = entry.optInt("frames", 1)
        val sourceW = if (frames > 1) entry.getInt("frameW") else entry.getInt("w")
        val sourceH = if (frames > 1) entry.getInt("frameH") else entry.getInt("h")
        val w = (sourceW * scale).roundToInt().coerceAtLeast(1)
        val h = (sourceH * scale).roundToInt().coerceAtLeast(1)
        // Each frame shrunk into its own cell, so the frames stay whole pixels wide.
        val sized = if (w == sourceW && h == sourceH) source else {
            Bitmap.createBitmap(w * frames, h, Bitmap.Config.ARGB_8888).also { out ->
                val canvas = Canvas(out)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                for (f in 0 until frames) {
                    canvas.drawBitmap(source, Rect(f * sourceW, 0, (f + 1) * sourceW, sourceH),
                                      Rect(f * w, 0, (f + 1) * w, h), paint)
                }
                source.recycle()
            }
        }
        val bitmap = sized.copy(Bitmap.Config.HARDWARE, false)?.also { sized.recycle() } ?: sized
        val ratio = w.toFloat() / sourceW
        return Sprite(
            bitmap, frames, w, h,
            entry.optDouble("cx", 0.0).toFloat() * ratio, entry.optDouble("cy", 0.0).toFloat() * ratio,
            entry.optDouble("controlW", sourceW.toDouble()).toFloat() * ratio,
            entry.optDouble("controlH", sourceH.toDouble()).toFloat() * ratio, ratio,
            entry.optJSONArray("press").ints(), entry.optJSONArray("release").ints(),
            entry.optInt("held", 0), entry.optInt("fps", 60), entry.optString("base").ifEmpty { null }, entry,
        )
    }

    private fun JSONArray?.ints() = if (this == null) IntArray(0) else IntArray(length()) { getInt(it) }

    private const val RECYCLE_DELAY_MS = 1000L
}

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
            is Kind.Stick -> {
                val right = kind.axisX == PadAxis.RIGHT_X
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
