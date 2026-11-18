package app.pocketfoundry.ui.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
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
