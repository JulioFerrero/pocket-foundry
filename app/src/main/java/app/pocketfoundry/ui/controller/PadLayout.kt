package app.pocketfoundry.ui.controller

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import app.pocketfoundry.core.system.Settings
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
 * controls are one set; Mouse & keys' ([keysMode]) are three and the player's own, one shown at a time ([set],
 * KeySets).
 */
class PadLayout(private val context: Context, val keysMode: Boolean = false) {
    private val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

    /** Each set's controls, by its key, in the editor's order: the built-in sets, then the player's. */
    private val sets = LinkedHashMap<String, MutableList<Control>>()
    /** The names of the sets the player made. */
    private val ownTitles = LinkedHashMap<String, String>()
    var set = if (keysMode) KeySets.GAME else KeySets.PAD
        private set
    /** The set's controls, shown or not. */
    val allControls: List<Control> get() = sets.getValue(set)
    val setKeys get() = sets.keys.toList()

    init {
        if (keysMode) KeySets.BUILT_IN.keys.forEach { sets[it] = keys(it) }
        else sets[KeySets.PAD] = defaultControls().toMutableList()
    }
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
    /** What Mouse & keys' gestures send, the same in every set (the editor's Touch). */
    val gestures = GestureSends()
    /** The latched keys held now, by id: one set's Shift and another's are the same key. */
    private val latched = HashSet<String>()
    /** An open drawer's panel; [drawerCentre] places its keys in it. */
    val drawerPanel = RectF()
    /** Mouse & keys' keycaps are solid: see-through, the game's ground shows on them. The pad's are faded. */
    private val defaultOpacity = if (keysMode) 1f else DEFAULT_OPACITY
    var opacity = defaultOpacity
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
        if (openDrawer?.let { it !in shownControls } == true) openDrawer = null
        drawerKeys = openDrawer?.let { open -> allControls.filter { it.shown && it.drawer == open.id } }.orEmpty()
        placeDrawer()
    }

    /** [set]'s keys, the trackpad among them when Settings.touch is BOX, as the pad's right stick is a box then. */
    private fun keys(set: String) = Keys.controls(set, trackpad = Settings(context).touch == Settings.BOX).toMutableList()

    /** The keys for another screen: the inventory's, the map's. */
    fun switchTo(target: String) {
        if (target == set || target !in sets) return
        set = target
        openDrawer = null
        refreshShown()
        onSwitch?.invoke(target)
    }

    /** The set shown changed: to [switchTo]'s target. */
    var onSwitch: ((String) -> Unit)? = null

    fun toggleDrawer(drawer: Control) = if (openDrawer === drawer) closeDrawer() else showDrawer(drawer)

    fun showDrawer(drawer: Control) {
        openDrawer = drawer
        refreshShown()
    }

    fun closeDrawer() {
        openDrawer = null
        drawerKeys = emptyList()
    }

    fun title(set: String) = KeySets.BUILT_IN[set] ?: ownTitles[set] ?: set

    fun isOwnSet(set: String) = set in ownTitles

    /** What's written on [c]: its name; a key that only brings a set, that set's. */
    fun label(c: Control) = if (c.kind == Kind.Key && c.codes.isEmpty()) c.shows?.let(::title).orEmpty() else c.label

    /**
     * Whether what [c] does says more than what's on it: Undo for Ctrl+Z, a tool's name, Left click for its mouse;
     * not 5's "5".
     */
    fun captioned(c: Control) =
        c.name.isNotEmpty() && (c.name != label(c) || c.kind == Kind.Key && KeyNames.sign(c.codes) != null)

    /**
     * An open drawer whose keys all say what they do (the numbers) is a keypad: no panel and no names under its keys,
     * which sit as close as a keyboard's. The others are a panel, each key's name under it.
     */
    val keypad get() = drawerKeys.none(::captioned)

    /** A set of the player's, "Set 4" till it's named, shown at once. Returns its key. */
    fun newSet(): String {
        val n = (ownTitles.keys.mapNotNull { it.removePrefix(KeySets.OWN).toIntOrNull() }.maxOrNull() ?: 0) + 1
        val key = KeySets.OWN + n
        ownTitles[key] = "Set ${KeySets.BUILT_IN.size + n}"
        sets[key] = keys(key)
        switchTo(key)
        // Its way back: a key that only brings the game's keys. Esc would open Factorio's menu too.
        addOwn(IntArray(0), 2f, KeySets.GAME).apply {
            x = Keys.BACK_X
            y = Keys.TOP_Y
        }
        return key
    }

    fun rename(set: String, title: String) {
        if (set in ownTitles && title.isNotBlank()) ownTitles[set] = title.trim()
    }

    /** One of the player's sets gone, and the keys that brought it with it: those that only did, or bring nothing. */
    fun removeSet(gone: String) {
        if (gone !in ownTitles) return
        if (set == gone) switchTo(KeySets.GAME)
        sets.remove(gone)
        ownTitles.remove(gone)
        for (controls in sets.values) {
            controls.removeAll { it.own && it.codes.isEmpty() && it.shows == gone }
            controls.filter { it.shows == gone }.forEach { it.shows = null }
        }
        refreshShown()
    }

    /** A key the player made, at the middle of the screen of the set shown. */
    fun addOwn(codes: IntArray, units: Float = 1f, shows: String? = null): Control {
        val n = sets.values.flatten().mapNotNull { if (it.own) it.id.removePrefix(OWN_KEY).toIntOrNull() else null }
            .maxOrNull() ?: 0
        val key = Keys.own(OWN_KEY + (n + 1), codes, units, shows)
        sets.getValue(set) += key
        refreshShown()
        return key
    }

    /** A key the player made goes for good; the others are only hidden, for Add to bring back. */
    fun remove(c: Control) {
        if (c.own) sets.getValue(set).remove(c) else c.shown = false
        refreshShown()
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
        val pad = r * drawerPad()
        out.set(drawerPanel.left + pad + (index % columns + 0.5f) * r * cellWidth(),
                // A panel's key at its cell's top, its name under it; a keypad's in its cell's middle.
                drawerPanel.top + pad + (index / columns) * r * cellHeight() + if (keypad) r * KEYPAD_PITCH / 2 else r)
    }

    // In a key's radii.
    private fun cellWidth() = if (keypad) KEYPAD_PITCH else DRAWER_CELL_WIDTH
    private fun cellHeight() = if (keypad) KEYPAD_PITCH else DRAWER_CELL_HEIGHT
    private fun drawerPad() = if (keypad) 0f else DRAWER_PAD

    private fun drawerColumns() = drawerKeys.size.coerceIn(1, (openDrawer?.kind as? Kind.Drawer)?.columns ?: 1)

    /** Under the drawer when it's in the top half, over it otherwise; its right edge at the drawer's. */
    private fun placeDrawer() {
        val drawer = openDrawer ?: return
        val r = drawerKeyRadius()
        val columns = drawerColumns()
        val rows = ceil(drawerKeys.size / columns.toFloat()).toInt().coerceAtLeast(1)
        val pad = r * drawerPad()
        val w = columns * r * cellWidth() + 2 * pad
        val h = rows * r * cellHeight() + 2 * pad
        val left = (cx(drawer) + halfWidth(drawer) - w).coerceIn(0f, maxOf(0f, width - w))
        val gap = r * 0.3f
        val top = if (cy(drawer) < height / 2f) cy(drawer) + radius(drawer) + gap else cy(drawer) - radius(drawer) - gap - h
        drawerPanel.set(left, top, left + w, top + h)
    }

    // Kept on screen: a layout made on another screen (a foldable, open or shut) may reach past this one.
    fun cx(c: Control) = onScreen(frameLeft(c) + c.x * frameWidth, halfWidth(c), width)
    fun cy(c: Control) = onScreen(frameTop(c) + c.y * frame, radius(c), height)
    fun radius(c: Control) = c.size * c.scale * frame / 2
    fun halfWidth(c: Control) = radius(c) * c.units
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
            val hit = if (c.units > 1.01f) dx <= (c.units + PILL_REACH_MORE) * r && dy <= PILL_REACH_Y * r
                      else hypot(dx, dy) <= r * c.kind.reach
            if (hit) return c
        }
        return null
    }

    /** The set shown, as it comes: the keys the player made in it go. */
    fun reset() {
        sets.getValue(set).removeAll { it.own }
        allControls.forEach { it.resetPlacement() }
        refreshShown()
        opacity = defaultOpacity
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
                val own = json.optJSONArray("own sets") ?: JSONArray()
                val titles = (0 until own.length()).map { own.getJSONObject(it) }
                    .associate { it.getString("key") to it.getString("title") }
                require(titles.keys.all { it.startsWith(KeySets.OWN) }) { "own sets: ${titles.keys}" }
                val lists = sets.keys.associateWith { sets.getValue(it) } +
                    titles.keys.associateWith(::keys)
                val reads = lists.map { (key, controls) -> each.optJSONObject(key)?.let { LayoutJson.read(it, controls) } }
                val gestureRead = gestures.read(json.optJSONObject("gestures"))
                ({
                    for ((key, title) in titles) {
                        ownTitles[key] = title
                        sets[key] = lists.getValue(key)
                    }
                    reads.forEach { it?.invoke() }
                    gestureRead()
                })
            } else LayoutJson.read(json, sets.getValue(KeySets.PAD))
            opacity = json.optDouble("opacity", defaultOpacity.toDouble()).toFloat()
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
            for ((key, controls) in sets) LayoutJson.write(controls).takeIf { it.length() > 0 }?.let { each.put(key, it) }
            val own = JSONArray(ownTitles.map { (key, title) -> JSONObject().put("key", key).put("title", title) })
            JSONObject().put("sets", each).apply {
                if (own.length() > 0) put("own sets", own)
                gestures.write()?.let { put("gestures", it) }
            }
        } else LayoutJson.write(allControls).apply { if (!has("controls")) put("controls", JSONObject()) }
        json.put("opacity", opacity.toDouble()).put("hidden", hidden)
        counter?.let { json.put("counter", JSONArray(listOf(it.x.toDouble(), it.y.toDouble()))) }
        if (counterScale != 1f) json.put("counterScale", counterScale.toDouble())
        prefs.edit().putString(if (keysMode) KEYS_KEY else LAYOUT_KEY, json.toString()).apply()
    }

    private companion object {
        const val LAYOUT_KEY = "layout-edges"
        const val KEYS_KEY = "layout-keys"
        /** The ids of the keys the player makes: k1, k2. */
        const val OWN_KEY = "k"
        const val OLD_LAYOUT_KEY = "layout-hybrid"
        const val DEFAULT_OPACITY = 0.8f
        /** The opacity tool's steps: down to none, the controls unseen but working. */
        val OPACITIES = floatArrayOf(0f, 0.1f, 0.35f, 0.55f, 0.8f, 1f)
        /** A pill's or a wide key's touch area, in radii out from its middle: past its ends, and up and down. */
        const val PILL_REACH_MORE = 0.2f
        const val PILL_REACH_Y = 1.25f
        /** The phone the layout was made on, sideways: 845 × 384 dp. */
        const val FRAME_ASPECT = 2.2f
        /** About a phone's height sideways, so a tablet's controls are a phone's size. */
        const val FRAME_MAX_DP = 480f
        /** A drawer key's diameter, of the frame's height; its cell, in its radii, room for its name under it. */
        const val DRAWER_KEY = 0.115f
        const val DRAWER_CELL_WIDTH = 2.9f
        const val DRAWER_CELL_HEIGHT = 3.1f
        const val DRAWER_PAD = 0.5f
        /** A keypad's keys, centre to centre, in their radii: a keyboard's gap between them, as on screen (Keys). */
        const val KEYPAD_PITCH = 2.12f
        const val DRAWER_REACH = 1.2f
    }
    private val point = PointF()
}
