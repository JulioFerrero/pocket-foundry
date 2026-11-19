package app.pocketfoundry.ui.controller

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.floats
import app.pocketfoundry.ui.kit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The controls and where they sit on this screen: the layout the player saved, in a phone-shaped frame. The pad's
 * controls are one set; Mouse & keys' ([keysMode]) are three, one shown at a time ([set], [KeySet]).
 */
class PadLayout(private val context: Context, val keysMode: Boolean = false) {
    private val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

    private val sets: Map<KeySet, List<Control>> =
        if (keysMode) KeySet.KEYS.associateWith { Keys.controls(it) } else mapOf(KeySet.PAD to defaultControls())
    var set = if (keysMode) KeySet.GAME else KeySet.PAD
        private set
    /** The set's controls, shown or not. */
    val allControls get() = sets.getValue(set)
    var shownControls = emptyList<Control>()
        private set
    /** The set's keys in its drawer, in the order of the catalog. */
    var drawerKeys = emptyList<Control>()
        private set
    /** The drawer whose panel is open. */
    var openDrawer: Control? = null
        private set
    /** Where Mouse & keys' keys go (GameScreen); none in the pad's layout. */
    var keyOut: KeyOut? = null
    /** The latched keys held now, by id: one set's Shift and another's are the same key. */
    private val latched = HashSet<String>()
    /** An open drawer's panel; [drawerCentre] places its keys in it. */
    val drawerPanel = RectF()
    var opacity = DEFAULT_OPACITY
        private set
    var hidden = false
    /** Where the player put the FPS counter's top-left corner, as fractions of the screen; null for its own corner. */
    var counter: PointF? = null
    var counterScale = 1f

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
        placeDrawer()
    }

    fun refreshShown() {
        shownControls = allControls.filter { it.shown && !it.inDrawer }
        drawerKeys = allControls.filter { it.shown && it.inDrawer }
        if (openDrawer?.let { it !in shownControls } == true) openDrawer = null
        placeDrawer()
    }

    /** The keys for another screen: the inventory's, the map's. */
    fun switchTo(target: KeySet) {
        if (target == set || target !in sets) return
        set = target
        openDrawer = null
        refreshShown()
    }

    fun toggleDrawer(drawer: Control) {
        openDrawer = if (openDrawer === drawer) null else drawer
        placeDrawer()
    }

    fun closeDrawer() {
        openDrawer = null
    }

    fun isLatched(c: Control) = c.latch && c.id in latched

    /** Returns whether [c] is held now. */
    fun toggleLatched(c: Control): Boolean = if (latched.remove(c.id)) false else latched.add(c.id)

    /** Every key up, latched ones too: the game screen paused, or a finger went missing. */
    fun releaseKeys() {
        latched.clear()
        keyOut?.releaseAll()
    }

    /** The drawer key under ([x], [y]), when its panel is open. */
    fun drawerKeyAt(x: Float, y: Float): Control? {
        if (openDrawer == null) return null
        val r = drawerKeyRadius()
        for (i in drawerKeys.indices) {
            drawerCentre(i, point)
            if (hypot(x - point.x, y - point.y) <= r * DRAWER_REACH) return drawerKeys[i]
        }
        return null
    }

    fun drawerKeyRadius() = frame * DRAWER_KEY / 2

    /** The middle of the drawer's [index]th key. */
    fun drawerCentre(index: Int, out: PointF) {
        val r = drawerKeyRadius()
        val columns = drawerColumns()
        val pad = r * DRAWER_PAD
        out.set(drawerPanel.left + pad + (index % columns + 0.5f) * r * DRAWER_CELL_WIDTH,
                drawerPanel.top + pad + (index / columns) * r * DRAWER_CELL_HEIGHT + r)
    }

    private fun drawerColumns() = drawerKeys.size.coerceIn(1, DRAWER_COLUMNS)

    /** Under the drawer when it's in the top half, over it otherwise; its right edge at the drawer's. */
    private fun placeDrawer() {
        val drawer = openDrawer ?: return
        val r = drawerKeyRadius()
        val columns = drawerColumns()
        val rows = ceil(drawerKeys.size / columns.toFloat()).toInt().coerceAtLeast(1)
        val pad = r * DRAWER_PAD
        val w = columns * r * DRAWER_CELL_WIDTH + 2 * pad
        val h = rows * r * DRAWER_CELL_HEIGHT + 2 * pad
        val left = (cx(drawer) + halfWidth(drawer) - w).coerceIn(0f, maxOf(0f, width - w))
        val gap = r * 0.3f
        val top = if (cy(drawer) < height / 2f) cy(drawer) + radius(drawer) + gap else cy(drawer) - radius(drawer) - gap - h
        drawerPanel.set(left, top, left + w, top + h)
    }

    // Kept on screen: a layout made on another screen (a foldable, open or shut) may reach past this one.
    fun cx(c: Control) = onScreen(frameLeft(c) + c.x * frameWidth, halfWidth(c), width)
    fun cy(c: Control) = onScreen(frameTop(c) + c.y * frame, radius(c), height)
    fun radius(c: Control) = c.size * c.scale * frame / 2
    fun halfWidth(c: Control) = if (c.pill) 2 * radius(c) else radius(c)
    private fun frameLeft(c: Control) = (width - frameWidth) * c.anchor.across
    private fun frameTop(c: Control) = (height - frame) * c.anchor.down
    private fun onScreen(at: Float, half: Float, size: Int) = at.coerceIn(minOf(half, size / 2f), maxOf(size - half, size / 2f))

    /** The inverse of [cx] and [cy]. */
    fun moveCentre(c: Control, x: Float, y: Float) {
        c.x = (onScreen(x, halfWidth(c), width) - frameLeft(c)) / frameWidth
        c.y = (onScreen(y, radius(c), height) - frameTop(c)) / frame
    }

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

    /** The set shown, as it comes. */
    fun reset() {
        allControls.forEach { it.resetPlacement() }
        refreshShown()
        opacity = DEFAULT_OPACITY
        counter = null
        counterScale = 1f
    }

    fun nextOpacity() {
        val i = OPACITIES.indexOfFirst { it >= opacity - 0.01f }
        opacity = OPACITIES[(i + 1) % OPACITIES.size]
    }

    /** A layout that can't be read is left as it was, whole: the defaults on a first load. */
    fun load() {
        if (!keysMode) moveOldLayout()
        val saved = prefs.getString(if (keysMode) KEYS_KEY else LAYOUT_KEY, null) ?: return refreshShown()
        try {
            val json = JSONObject(saved)
            // Read whole before any of it is used.
            val apply = if (keysMode) {
                val each = json.optJSONObject("sets") ?: JSONObject()
                val reads = sets.map { (set, controls) -> each.optJSONObject(set.key)?.let { LayoutJson.read(it, controls) } }
                ({ reads.forEach { it?.invoke() } })
            } else LayoutJson.read(json, allControls)
            opacity = json.optDouble("opacity", DEFAULT_OPACITY.toDouble()).toFloat()
            hidden = json.optBoolean("hidden", false)
            counter = json.optJSONArray("counter")?.floats()?.takeIf { it.size == 2 }?.let { PointF(it[0], it[1]) }
            counterScale = json.optDouble("counterScale", 1.0).toFloat()
            apply()
        } catch (e: Exception) {
            Log.w(TAG, "The saved controller layout is unreadable: $saved", e)
        }
        refreshShown()
    }

    /** From the older layout, which saved every position, only its opacity and hiding carry over. */
    private fun moveOldLayout() {
        val old = prefs.getString(OLD_LAYOUT_KEY, null) ?: return
        if (!prefs.contains(LAYOUT_KEY)) try {
            val json = JSONObject(old)
            val carried = JSONObject().put("opacity", json.optDouble("opacity", DEFAULT_OPACITY.toDouble()))
                .put("hidden", json.optBoolean("hidden", false)).put("controls", JSONObject())
            prefs.edit().putString(LAYOUT_KEY, carried.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "The old controller layout is unreadable: $old", e)
        }
        prefs.edit().remove(OLD_LAYOUT_KEY).apply()
    }

    /** Only what the player changed, so the rest follow the defaults when those change. */
    fun save() {
        val json = if (keysMode) {
            val each = JSONObject()
            for ((set, controls) in sets) LayoutJson.write(controls).takeIf { it.length() > 0 }?.let { each.put(set.key, it) }
            JSONObject().put("sets", each)
        } else LayoutJson.write(allControls).apply { if (!has("controls")) put("controls", JSONObject()) }
        json.put("opacity", opacity.toDouble()).put("hidden", hidden)
        counter?.let { json.put("counter", JSONArray(listOf(it.x.toDouble(), it.y.toDouble()))) }
        if (counterScale != 1f) json.put("counterScale", counterScale.toDouble())
        prefs.edit().putString(if (keysMode) KEYS_KEY else LAYOUT_KEY, json.toString()).apply()
    }

    private companion object {
        const val LAYOUT_KEY = "layout-edges"
        const val KEYS_KEY = "layout-keys"
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
        /** A drawer key's diameter, of the frame's height; its cell, in its radii, room for its name under it. */
        const val DRAWER_KEY = 0.1f
        const val DRAWER_CELL_WIDTH = 2.9f
        const val DRAWER_CELL_HEIGHT = 3.1f
        const val DRAWER_PAD = 0.5f
        const val DRAWER_COLUMNS = 5
        const val DRAWER_REACH = 1.2f
    }
    private val point = PointF()
}
