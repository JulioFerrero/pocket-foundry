package app.pocketfoundry.controller

import android.content.Context
import app.pocketfoundry.floats
import app.pocketfoundry.strings
import app.pocketfoundry.ui.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** The controls and where they sit on this screen: the layout the player saved, in a phone-shaped frame. */
class PadLayout(private val context: Context) {
    private val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

    val allControls = defaultControls()
    var shownControls = allControls
        private set
    var opacity = DEFAULT_OPACITY
        private set
    var hidden = false

    var width = 0
        private set
    var height = 0
        private set
    /** Height of the phone-shaped frame the layout is drawn in; sizes are fractions of it. */
    var frame = 0f
        private set
    private val frameWidth get() = frame * FRAME_ASPECT

    fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        frame = minOf(height.toFloat(), width / FRAME_ASPECT, context.dp(FRAME_MAX_DP))
    }

    fun refreshShown() {
        shownControls = allControls.filter { it.shown }
    }

    // Kept on screen: a layout made on another screen (a foldable, open or shut) may reach past this one.
    fun cx(c: Control) = onScreen(frameLeft(c) + c.x * frameWidth, halfWidth(c), width)
    fun cy(c: Control) = onScreen(frameTop(c) + c.y * frame, radius(c), height)
    fun radius(c: Control) = c.size * c.scale * frame / 2
    private fun halfWidth(c: Control) = if (c.pill) 2 * radius(c) else radius(c)
    private fun frameLeft(c: Control) = (width - frameWidth) * c.anchor.across
    private fun frameTop(c: Control) = (height - frame) * c.anchor.down
    private fun onScreen(at: Float, half: Float, size: Int) = at.coerceIn(minOf(half, size / 2f), maxOf(size - half, size / 2f))

    /** Puts [c]'s middle at (x, y), kept on screen: the inverse of [cx] and [cy]. */
    fun moveCentre(c: Control, x: Float, y: Float) {
        c.x = (onScreen(x, halfWidth(c), width) - frameLeft(c)) / frameWidth
        c.y = (onScreen(y, radius(c), height) - frameTop(c)) / frame
    }

    /** The topmost shown control under (x, y). */
    fun controlAt(x: Float, y: Float): Control? {
        for (i in shownControls.lastIndex downTo 0) {
            val c = shownControls[i]
            val r = radius(c)
            val dx = abs(x - cx(c))
            val dy = abs(y - cy(c))
            val hit = if (c.pill) dx <= PILL_REACH_X * r && dy <= PILL_REACH_Y * r else hypot(dx, dy) <= r * c.kind.reach
            if (hit) return c
        }
        return null
    }

    /** Every control shown where it started, at the default opacity. */
    fun reset() {
        allControls.forEach { it.resetPlacement() }
        refreshShown()
        opacity = DEFAULT_OPACITY
    }

    fun nextOpacity() {
        val i = OPACITIES.indexOfFirst { it >= opacity - 0.01f }
        opacity = OPACITIES[(i + 1) % OPACITIES.size]
    }

    fun load() {
        val saved = prefs.getString(LAYOUT_KEY, null)
        val json = saved ?: prefs.getString(OLD_LAYOUT_KEY, null)
        if (json != null) runCatching { read(JSONObject(json), placements = saved != null) }
        refreshShown()
    }

    private fun read(json: JSONObject, placements: Boolean) {
        opacity = json.optDouble("opacity", DEFAULT_OPACITY.toDouble()).toFloat()
        hidden = json.optBoolean("hidden", false)
        if (!placements) return
        val removed = json.optJSONArray("removed")?.strings().orEmpty()
        val placed = json.getJSONObject("controls")
        for (c in allControls) {
            c.shown = c.id !in removed
            placed.optJSONArray(c.id)?.floats()?.let { (x, y, scale) ->
                c.x = x
                c.y = y
                c.scale = scale
            }
        }
    }

    /** Only what the player changed, so the rest follow the defaults when those change. */
    fun save() {
        val placed = JSONObject()
        allControls.filter { it.x != it.defaultX || it.y != it.defaultY || it.scale != 1f }
            .forEach { placed.put(it.id, JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.scale.toDouble()))) }
        val json = JSONObject().put("opacity", opacity.toDouble()).put("hidden", hidden).put("controls", placed)
            .put("removed", JSONArray(allControls.filter { !it.shown }.map { it.id }))
        prefs.edit().putString(LAYOUT_KEY, json.toString()).apply()
    }

    private companion object {
        const val LAYOUT_KEY = "layout-edges"
        /** An older layout that saved every position: only its opacity and hiding carry over. */
        const val OLD_LAYOUT_KEY = "layout-hybrid"
        const val DEFAULT_OPACITY = 0.8f
        val OPACITIES = floatArrayOf(0.35f, 0.55f, 0.8f, 1f)
        /** A pill's touch area, in radii out from its middle. */
        const val PILL_REACH_X = 2.2f
        const val PILL_REACH_Y = 1.25f
        /** The phone the layout was made on, sideways: 845 × 384 dp. */
        const val FRAME_ASPECT = 2.2f
        /** About a phone's height sideways, so a tablet's controls are a phone's size. */
        const val FRAME_MAX_DP = 480f
    }
}
