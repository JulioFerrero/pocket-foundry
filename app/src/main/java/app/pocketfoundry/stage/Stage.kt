package app.pocketfoundry.stage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import app.pocketfoundry.Paths
import app.pocketfoundry.TAG
import app.pocketfoundry.ui.background
import kotlin.math.floor
import kotlin.math.roundToInt

/** The launcher's animated backdrop, behind its panels. */
@SuppressLint("ViewConstructor")
class Stage(context: Context, private val paths: Paths) : View(context), Choreographer.FrameCallback {
    enum class Scene { WELCOME, IMPORT, FACTORY }

    /** A value easing from [from] to [to] in a fade begun at [at]. */
    private class Fade(var from: Float, var to: Float, var at: Long) {
        fun value(now: Long) = mix(from, to, eased(at, now))
        fun moving(now: Long) = now - at < FADE_MS

        fun retarget(target: Float, now: Long) {
            from = value(now)
            to = target
            at = now
        }
    }

    private class Layer(val drawing: Drawing, dim: Float, saturate: Float, val shownAt: Long) {
        val dim = Fade(dim, dim, shownAt)
        val saturate = Fade(saturate, saturate, shownAt)
        var hiddenAt = -1L
        val hidden get() = hiddenAt >= 0
        fun fading(now: Long) = now - shownAt < FADE_MS || hidden || dim.moving(now) || saturate.moving(now)
        fun alpha(now: Long) = eased(shownAt, now) * if (hidden) 1 - eased(hiddenAt, now) else 1f
    }

    private class Wanted(val scene: Scene, val dim: Float, val saturate: Float)

    private val welcome = Blueprint(welcome = true)
    private val importing = Blueprint(welcome = false)
    /** Null until it has loaded; the welcome blueprint if it can't be drawn. */
    private var factory: Drawing? = null
    private var loadingFactory = false
    private val layers = ArrayList<Layer>()
    private var wanted: Wanted? = null
    private var running = false
    private var released = false
    private var lastTick = -1L
    private val layerPaint = Paint()
    private val saturations = arrayOfNulls<ColorFilter>(SATURATION_STEPS)

    init {
        setBackgroundColor(BACKGROUND)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** [dim] and [saturate] are factors: 1 is as drawn. */
    fun show(scene: Scene, dim: Float = 1f, saturate: Float = 1f) {
        wanted = Wanted(scene, dim, saturate)
        val drawing = when (scene) {
            Scene.WELCOME -> welcome
            Scene.IMPORT -> importing
            Scene.FACTORY -> factory ?: return loadFactory()   // shown once it's in
        }
        val now = SystemClock.uptimeMillis()
        val top = layers.lastOrNull()?.takeIf { !it.hidden }
        if (top?.drawing === drawing) {
            if (top.dim.to != dim || top.saturate.to != saturate) {
                top.dim.retarget(dim, now)
                top.saturate.retarget(saturate, now)
            }
            return
        }
        top?.hiddenAt = now
        layers.removeAll { it.drawing === drawing }
        layers += Layer(drawing, dim, saturate, now)
        if (drawing.born < 0) drawing.born = now
        invalidate()
    }

    fun progress(value: Int) {
        importing.percent = value
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
        factory?.release()
        layers.clear()
    }

    private fun loadFactory() {
        if (loadingFactory) return
        loadingFactory = true
        background("stage", { Factory(paths.game.resolve("data/base/graphics")) }) { result ->
            val loaded = result.onFailure { Log.w(TAG, "The factory can't be drawn: $it") }.getOrNull()
            if (released) {
                loaded?.release()
                return@background
            }
            // No sprites (no game, or a version that moved them): the blueprint will do.
            factory = loaded ?: welcome
            wanted?.let { if (it.scene == Scene.FACTORY) show(it.scene, it.dim, it.saturate) }
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

    // Both run every frame, so they walk the layers by index: an iterator a frame would be garbage.
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        var fading = false
        var fps = 0
        for (n in layers.lastIndex downTo 0) {
            val layer = layers[n]
            if (layer.hidden && now - layer.hiddenAt > FADE_MS) {
                layers.removeAt(n)
                continue
            }
            if (layer.fading(now)) fading = true
            fps = maxOf(fps, layer.drawing.fps)
        }
        // At rest, a redraw only when a scene's next frame is due.
        val tick = now * fps / 1000
        if (fading || (fps > 0 && tick != lastTick)) invalidate()
        lastTick = tick
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (n in layers.indices) {
            val layer = layers[n]
            val alpha = layer.alpha(now)
            if (alpha <= 0f) continue
            val saturation = saturation(layer.saturate.value(now))
            // Fading or greying a drawing takes a layer of its own; a dimmed one at rest doesn't.
            val apart = alpha < 1f || saturation != null
            if (apart) {
                layerPaint.alpha = (alpha * 255).toInt()
                layerPaint.colorFilter = saturation
                canvas.saveLayer(null, layerPaint)
            }
            layer.drawing.draw(canvas, width, height, (now - layer.drawing.born) / 1000.0)
            // Every drawing fills the view, so black over it dims it just as scaling its colours would.
            val dim = layer.dim.value(now)
            if (dim < 1f) canvas.drawColor(((1 - dim) * 255).toInt() shl 24)
            if (apart) canvas.restore()
        }
    }

    /** A filter leaving [value] of the colour, or null for all of it. Made once per step, too fine to see in a fade. */
    private fun saturation(value: Float): ColorFilter? {
        val step = (value * SATURATION_STEPS).roundToInt().coerceAtLeast(0)
        if (step >= SATURATION_STEPS) return null
        return saturations[step] ?: ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(step / SATURATION_STEPS.toFloat()) })
            .also { saturations[step] = it }
    }

    companion object {
        /** The night blue behind the drawings, and behind the launcher until the stage is up. */
        const val BACKGROUND = 0xFF091726.toInt()
        private const val FADE_MS = 800L
        private const val SATURATION_STEPS = 32

        /** How far a fade begun at [since] has got by [now], eased, from 0 to 1. */
        private fun eased(since: Long, now: Long) = easeOut((now - since) / FADE_MS.toFloat())
    }
}

/** A scene the stage plays. Each frame is a function of the time, so a scene keeps no state between frames. */
abstract class Drawing {
    /** SystemClock.uptimeMillis() when first shown. */
    var born = -1L
    abstract val fps: Int
    /** [time] in seconds since [born]. */
    abstract fun draw(canvas: Canvas, width: Int, height: Int, time: Double)
    open fun release() {}

    /** Scales a [w] × [h] scene to fill the view, centred, cropping what spills over. */
    protected fun cover(canvas: Canvas, width: Int, height: Int, w: Float, h: Float) {
        val scale = maxOf(width / w, height / h)
        canvas.translate((width - w * scale) / 2, (height - h * scale) / 2)
        canvas.scale(scale, scale)
    }
}

/** Unlike x % 1, wraps negative [x] into 0..1 too. */
internal fun frac(x: Double) = x - floor(x)

internal fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/** 0 to 1 as [x] goes 0 to 1: quick at first, settling at the end. */
internal fun easeOut(x: Float): Float {
    val rest = 1 - x.coerceIn(0f, 1f)
    return 1 - rest * rest
}

/** 0 to 1 as [x] goes 0 to 1: slow at both ends. */
internal fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** A radial gradient stretched into an ellipse with radii [rx], [ry]. */
internal fun elliptical(cx: Float, cy: Float, rx: Float, ry: Float, colors: IntArray, stops: FloatArray?) =
    RadialGradient(cx, cy, rx, colors, stops, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, cx, cy) })
    }
