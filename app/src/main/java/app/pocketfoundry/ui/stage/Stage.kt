package app.pocketfoundry.ui.stage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import app.pocketfoundry.core.engine.Paths
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.floor

/** The launcher's animated backdrop, behind its panels. */
@SuppressLint("ViewConstructor")
class Stage(context: Context, private val paths: Paths) : View(context), Choreographer.FrameCallback {
    enum class Scene { WELCOME, IMPORT, FACTORY }

    private class Layer(val drawing: Drawing, var dim: Float, var saturate: Float) {
        var shownAt = 0L
        var hiddenAt = -1L
        var fromDim = dim
        var fromSaturate = saturate
        var filterAt = 0L
    }

    private val drawings = HashMap<Scene, Drawing>()
    private val layers = ArrayList<Layer>()
    private var wanted: Triple<Scene, Float, Float>? = null
    private var loadingFactory = false
    private var factoryFailed = false
    private var percent = 0
    private var running = false
    private var released = false
    private var lastTick = -1L
    private val main = Handler(Looper.getMainLooper())
    private val layerPaint = Paint()
    private val matrix = ColorMatrix()
    private val brightness = ColorMatrix()

    init {
        setBackgroundColor(BACKGROUND)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** [dim] and [saturate] are factors: 1 is as drawn. */
    fun show(scene: Scene, dim: Float = 1f, saturate: Float = 1f) {
        wanted = Triple(scene, dim, saturate)
        val now = SystemClock.uptimeMillis()
        val top = layers.lastOrNull()?.takeIf { it.hiddenAt < 0 }
        val drawing = drawing(scene) ?: return            // the factory is loading: shown once it's in
        if (top?.drawing === drawing) {
            if (top.dim != dim || top.saturate != saturate) {
                top.fromDim = dimNow(top, now); top.fromSaturate = saturateNow(top, now); top.filterAt = now
                top.dim = dim; top.saturate = saturate
            }
            return
        }
        top?.hiddenAt = now
        layers.removeAll { it.drawing === drawing }
        layers += Layer(drawing, dim, saturate).apply { shownAt = now; filterAt = now }
        if (drawing.born < 0) drawing.born = now
        invalidate()
    }

    fun progress(value: Int) {
        percent = value
        (drawings[Scene.IMPORT] as? Blueprint)?.percent = value
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun resume() {
        if (running) return
        running = true
        lastTick = -1
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Final: the stage can't be used after. */
    fun release() {
        released = true
        pause()
        drawings.values.forEach { it.release() }
        drawings.clear()
        layers.clear()
    }

    private fun drawing(scene: Scene): Drawing? {
        drawings[scene]?.let { return it }
        when (scene) {
            Scene.WELCOME, Scene.IMPORT -> {
                val plan = JSONObject(context.assets.open("stage/blueprint.json").bufferedReader().use { it.readText() })
                return Blueprint(plan, welcome = scene == Scene.WELCOME).also {
                    if (scene == Scene.IMPORT) it.percent = percent
                    drawings[scene] = it
                }
            }
            Scene.FACTORY -> {
                // No sprites (no game, or a version that moved them): the blueprint will do.
                if (factoryFailed) return drawing(Scene.WELCOME)
                if (loadingFactory) return null
                loadingFactory = true
                val graphics = paths.game.resolve("data/base/graphics")
                val json = context.assets.open("stage/factory-sim.json").bufferedReader().use { it.readText() }
                thread(name = "pocketfoundry-stage") {
                    val factory = runCatching { Factory(JSONObject(json), graphics) }
                        .onFailure { Log.w(TAG, "The factory can't be drawn: $it") }.getOrNull()
                    main.post {
                        loadingFactory = false
                        if (released) {
                            factory?.release()
                            return@post
                        }
                        if (factory != null) drawings[Scene.FACTORY] = factory else factoryFailed = true
                        wanted?.let { (scene, dim, saturate) -> if (scene == Scene.FACTORY) show(scene, dim, saturate) }
                    }
                }
                return null
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        layers.removeAll { it.hiddenAt >= 0 && now - it.hiddenAt > FADE_MS }
        val changing = layers.any { now - it.shownAt < FADE_MS || it.hiddenAt >= 0 || now - it.filterAt < FADE_MS }
        val fps = if (changing) 1000 else layers.maxOfOrNull { it.drawing.fps } ?: 0
        if (fps > 0) {
            val tick = now * fps / 1000
            if (tick != lastTick) {
                lastTick = tick
                invalidate()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (layer in layers) {
            val shown = ease((now - layer.shownAt) / FADE_MS.toFloat())
            val alpha = if (layer.hiddenAt >= 0) shown * (1 - ease((now - layer.hiddenAt) / FADE_MS.toFloat())) else shown
            if (alpha <= 0f) continue
            val dim = dimNow(layer, now)
            val saturate = saturateNow(layer, now)
            val filtered = dim != 1f || saturate != 1f
            val time = (now - layer.drawing.born) / 1000.0
            if (alpha >= 1f && !filtered) {
                layer.drawing.draw(canvas, width, height, time)
                continue
            }
            layerPaint.alpha = (alpha * 255).toInt()
            layerPaint.colorFilter = if (filtered) filter(dim, saturate) else null
            canvas.saveLayer(null, layerPaint)
            layer.drawing.draw(canvas, width, height, time)
            canvas.restore()
        }
    }

    private fun dimNow(layer: Layer, now: Long) = mix(layer.fromDim, layer.dim, ease((now - layer.filterAt) / FADE_MS.toFloat()))
    private fun saturateNow(layer: Layer, now: Long) = mix(layer.fromSaturate, layer.saturate, ease((now - layer.filterAt) / FADE_MS.toFloat()))

    private fun filter(dim: Float, saturate: Float): ColorMatrixColorFilter {
        matrix.setSaturation(saturate)
        brightness.setScale(dim, dim, dim, 1f)
        matrix.postConcat(brightness)
        return ColorMatrixColorFilter(matrix)
    }

    private companion object {
        const val TAG = "PocketFoundryStage"
        const val BACKGROUND = 0xFF091726.toInt()
        const val FADE_MS = 800L
        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        /** CSS's "ease". */
        val EASE = CubicBezier(0.25f, 0.1f, 0.25f, 1f)
        fun ease(t: Float) = EASE(t.coerceIn(0f, 1f))
    }
}

abstract class Drawing {
    /** SystemClock.uptimeMillis() when first shown. */
    var born = -1L
    abstract val fps: Int
    /** [time] in seconds since [born]. */
    abstract fun draw(canvas: Canvas, width: Int, height: Int, time: Double)
    open fun release() {}

    protected fun cover(canvas: Canvas, width: Int, height: Int, w: Float, h: Float) {
        val scale = maxOf(width / w, height / h)
        canvas.translate((width - w * scale) / 2, (height - h * scale) / 2)
        canvas.scale(scale, scale)
    }
}

/** CSS's cubic-bezier() timing function. */
class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    private fun curve(t: Float, a: Float, b: Float) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * b + t * t * t
    private fun slope(t: Float, a: Float, b: Float) = 3 * (1 - t) * (1 - t) * a + 6 * (1 - t) * t * (b - a) + 3 * t * t * (1 - b)

    operator fun invoke(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var t = x
        repeat(8) {
            val d = slope(t, x1, x2)
            if (d == 0f) return@repeat
            t = (t - (curve(t, x1, x2) - x) / d).coerceIn(0f, 1f)
        }
        return curve(t, y1, y2)
    }

    companion object {
        val EASE_OUT = CubicBezier(0f, 0f, 0.58f, 1f)
        val EASE_IN_OUT = CubicBezier(0.42f, 0f, 0.58f, 1f)
    }
}

/** Unlike x % 1, wraps negative [x] into 0..1 too. */
internal fun frac(x: Double) = x - floor(x)
