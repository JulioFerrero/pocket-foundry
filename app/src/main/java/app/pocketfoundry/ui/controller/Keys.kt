package app.pocketfoundry.ui.controller

import android.view.KeyEvent
import app.pocketfoundry.core.controller.DpadDir

/**
 * Mouse & keys (Settings.controls KEYS): Factorio in its keyboard-and-mouse mode, the finger as the mouse
 * (PointerGestures) and keyboard keys on screen. Each key set is the keys for one kind of screen; a key that opens one
 * of those screens brings its set ([Opens]).
 */
enum class KeySet(val key: String, val title: String) {
    /** The pad's controls: Settings.controls PAD has this one set. */
    PAD("pad", "Gamepad"),
    GAME("game", "In game"),
    /** The inventory and every window like it (research, production): the screen behind is covered. */
    WINDOW("window", "Inventory"),
    MAP("map", "Map");

    /** The set after a key that [opens] something: the same key again closes it. */
    fun after(opens: Opens): KeySet = when (opens) {
        Opens.GAME -> GAME
        Opens.WINDOW -> if (this == WINDOW) GAME else WINDOW
        Opens.MAP -> if (this == MAP) GAME else MAP
    }

    companion object {
        val KEYS = listOf(GAME, WINDOW, MAP)
    }
}

/** What a key opens or closes in Factorio, for the set that should show next. */
enum class Opens { GAME, WINDOW, MAP }

/** For the editor's list of keys to add. */
enum class KeyGroup(val title: String) {
    WINDOWS("Windows"), BUILDING("Building"), ITEMS("Items"), EDITING("Editing"), MOVING("Moving and pointing"),
    OTHER("Other"),
}

/**
 * The sign on what isn't a key (the drawer, the toolbar's tools in it) and in the editor: a stroked path in a 20 × 20
 * box, in SVG's path syntax. Keys show their own name: Esc, E, Shift, Ctrl+Z.
 */
enum class KeyIcon(val path: String, val stroke: Float = 2f) {
    KEYBOARD("M3.5 5h13A1.5 1.5 0 0 1 18 6.5v7a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 2 13.5v-7A1.5 1.5 0 0 1 3.5 5zM5 8h1M8 8h1M11 8h1M14 8h1M6 12h8"),
    DOTS("M4 10h.01M10 10h.01M16 10h.01", stroke = 3.4f),
    EDIT("M3 6h14M3 10h14M3 14h14M7 4.5v3M13 8.5v3M9 12.5v3"),
    HIDE("M2 10s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6zM10 7.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5M4 16L16 4"),
    LOCK("M4.5 9h11v8h-11zM7 9V6.5a3 3 0 0 1 6 0V9", stroke = 2.4f),
    /** The stick, in the editor's list: it's drawn as itself on screen. */
    STICK("M10 3a7 7 0 1 0 0 14a7 7 0 1 0 0-14M10 7a3 3 0 1 0 0 6a3 3 0 1 0 0-6");

    companion object {
        /** The box its path is drawn in. */
        const val BOX = 20f
    }
}

/**
 * Keys pressed in X, counted: two keys that both hold Ctrl (a latched Ctrl and Undo's Ctrl+Z) keep it down till the
 * last lets go. [send]: an Android key code down or up (LorieView.sendKeyEvent). The main thread's only.
 */
class KeyOut(private val send: (code: Int, down: Boolean) -> Unit) {
    private val held = LinkedHashMap<Int, Int>()

    /** In order: Ctrl before Z. */
    fun press(codes: IntArray) {
        for (code in codes) {
            val count = held[code] ?: 0
            held[code] = count + 1
            if (count == 0) send(code, true)
        }
    }

    /** In reverse: Z before Ctrl. */
    fun release(codes: IntArray) {
        for (i in codes.indices.reversed()) {
            val code = codes[i]
            val count = held[code] ?: continue
            if (count > 1) held[code] = count - 1
            else {
                held.remove(code)
                send(code, false)
            }
        }
    }

    /** Everything up: the game screen paused, or lost its fingers. */
    fun releaseAll() {
        val codes = held.keys.toList()
        held.clear()
        for (i in codes.indices.reversed()) send(codes[i], false)
    }

    fun isHeld(code: Int) = code in held
}

/** The keys there are, and where each set has them by default. */
object Keys {
    /** Where a key sits in a set: a position in the layout's frame, or in the drawer. */
    class Place(val x: Float, val y: Float, val drawer: Boolean = false)

    class Def(
        val id: String,
        val kind: Kind,
        val group: KeyGroup,
        /** On the key: its name on a keyboard (Esc, E, Shift, Ctrl+Z); empty for what has an icon instead. */
        val label: String,
        val icon: KeyIcon?,
        /** Under the key in the drawer and the editor's list. */
        val name: String,
        val wide: Boolean,
        /** Diameter, or a wide key's height, a fraction of the frame's height. */
        val size: Float,
        val latch: Boolean = false,
        val places: Map<KeySet, Place> = emptyMap(),
    )

    private fun key(id: String, group: KeyGroup, label: String, icon: KeyIcon?, name: String, vararg codes: Int,
                    opens: Opens? = null, wide: Boolean = false, latch: Boolean = false,
                    places: Map<KeySet, Place> = emptyMap()) =
        Def(id, Kind.Key(codes, opens), group, label, icon, name, wide, if (wide) WIDE else ROUND, latch, places)

    private fun drawer(vararg sets: KeySet) = sets.associateWith { Place(0f, 0f, drawer = true) }
    private fun top(x: Float) = mapOf(KeySet.GAME to Place(x, TOP_Y), KeySet.MAP to Place(x, TOP_Y))

    val catalog: List<Def> = listOf(
        key("esc", KeyGroup.WINDOWS, "Esc", null, "Menu", KeyEvent.KEYCODE_ESCAPE, opens = Opens.GAME,
            wide = true, places = top(0.341f)),
        key("e", KeyGroup.WINDOWS, "E", null, "Inventory", KeyEvent.KEYCODE_E, opens = Opens.WINDOW,
            wide = true, places = top(0.4205f)),
        key("m", KeyGroup.WINDOWS, "M", null, "Map", KeyEvent.KEYCODE_M, opens = Opens.MAP, wide = true,
            places = top(0.5f)),
        key("t", KeyGroup.WINDOWS, "T", null, "Research", KeyEvent.KEYCODE_T, opens = Opens.WINDOW,
            wide = true, places = top(0.5795f)),
        key("p", KeyGroup.WINDOWS, "P", null, "Production", KeyEvent.KEYCODE_P, opens = Opens.WINDOW,
            wide = true),
        // The inventory's way out: E again.
        key("close", KeyGroup.WINDOWS, "E", null, "Close", KeyEvent.KEYCODE_E, opens = Opens.WINDOW,
            places = mapOf(KeySet.WINDOW to Place(0.216f, 0.932f))),
        key("q", KeyGroup.BUILDING, "Q", null, "Pipette", KeyEvent.KEYCODE_Q,
            places = mapOf(KeySet.GAME to Place(0.680f, 0.639f))),
        key("r", KeyGroup.BUILDING, "R", null, "Rotate", KeyEvent.KEYCODE_R,
            places = mapOf(KeySet.GAME to Place(0.742f, 0.639f))),
        key("shift", KeyGroup.BUILDING, "Shift", null, "Shift", KeyEvent.KEYCODE_SHIFT_LEFT, wide = true,
            latch = true, places = mapOf(KeySet.GAME to Place(0.640f, 0.764f), KeySet.WINDOW to Place(0.05f, 0.932f))),
        key("ctrl", KeyGroup.BUILDING, "Ctrl", null, "Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, wide = true, latch = true,
            places = mapOf(KeySet.GAME to Place(0.731f, 0.764f), KeySet.WINDOW to Place(0.141f, 0.932f))),
        key("alt", KeyGroup.BUILDING, "Alt", null, "Details", KeyEvent.KEYCODE_ALT_LEFT,
            places = drawer(KeySet.GAME, KeySet.MAP)),
        key("f", KeyGroup.ITEMS, "F", null, "Pick up", KeyEvent.KEYCODE_F, places = drawer(KeySet.GAME)),
        key("z", KeyGroup.ITEMS, "Z", null, "Drop", KeyEvent.KEYCODE_Z, places = drawer(KeySet.GAME)),
        key("c", KeyGroup.ITEMS, "C", null, "Shoot", KeyEvent.KEYCODE_C, places = drawer(KeySet.GAME)),
        key("undo", KeyGroup.EDITING, "Ctrl+Z", null, "Undo", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z,
            places = drawer(KeySet.GAME, KeySet.MAP)),
        key("redo", KeyGroup.EDITING, "Ctrl+Y", null, "Redo", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Y),
        key("cut", KeyGroup.EDITING, "Ctrl+X", null, "Cut", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_X),
        key("copy", KeyGroup.EDITING, "Ctrl+C", null, "Copy", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C,
            places = drawer(KeySet.GAME, KeySet.MAP)),
        key("paste", KeyGroup.EDITING, "Ctrl+V", null, "Paste", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V,
            places = drawer(KeySet.GAME, KeySet.MAP)),
        Def("stick", Kind.KeyStick, KeyGroup.MOVING, "", KeyIcon.STICK, "Stick", wide = false, size = STICK,
            places = mapOf(KeySet.GAME to Place(0.0852f, 0.652f), KeySet.MAP to Place(0.0852f, 0.652f))),
        key("console", KeyGroup.OTHER, "`", null, "Console", KeyEvent.KEYCODE_GRAVE),
        Def("keyboard", Kind.ToolKey(Tool.KEYBOARD), KeyGroup.OTHER, "", KeyIcon.KEYBOARD, "Keyboard", wide = false,
            size = ROUND, places = drawer(KeySet.GAME, KeySet.MAP)),
        Def("edit", Kind.ToolKey(Tool.ADJUST), KeyGroup.OTHER, "", KeyIcon.EDIT, "Edit keys", wide = false,
            size = ROUND, places = drawer(KeySet.GAME, KeySet.MAP)),
        Def("hide", Kind.ToolKey(Tool.HIDE), KeyGroup.OTHER, "", KeyIcon.HIDE, "Hide keys", wide = false,
            size = ROUND, places = drawer(KeySet.GAME, KeySet.MAP)),
        Def(DRAWER, Kind.Drawer, KeyGroup.OTHER, "", KeyIcon.DOTS, "More", wide = true, size = WIDE,
            places = top(0.659f)),
    )

    /** Every key, for [set]: those the set doesn't have are there unshown, for the editor to add. */
    fun controls(set: KeySet): List<Control> = catalog.map { def ->
        val place = def.places[set]
        Control(def.id, def.kind, place?.takeUnless { it.drawer }?.x ?: NEW_X,
                place?.takeUnless { it.drawer }?.y ?: NEW_Y, def.size, glyph = null, pill = def.wide,
                label = def.label, name = def.name, keyIcon = def.icon, defaultShown = place != null,
                defaultInDrawer = place?.drawer == true, defaultLatch = def.latch, group = def.group)
    }

    /** The keys the stick holds, as [DpadDir] bits, for its knob [dx], [dy] from the middle, [r] its reach. */
    fun stickDirections(dx: Float, dy: Float, r: Float): Int {
        val distance = kotlin.math.hypot(dx, dy)
        if (distance <= r * STICK_DEAD_ZONE) return 0
        return DpadDir.state(dx / distance, dy / distance, SIN_22)
    }

    /** W, A, S and D for [DpadDir] bits. */
    fun stickCode(dir: DpadDir) = when (dir) {
        DpadDir.UP -> KeyEvent.KEYCODE_W
        DpadDir.DOWN -> KeyEvent.KEYCODE_S
        DpadDir.LEFT -> KeyEvent.KEYCODE_A
        DpadDir.RIGHT -> KeyEvent.KEYCODE_D
    }

    const val DRAWER = "more"
    /** Can't be removed: the drawer holds the way into the editor, and the editor's tools are in it. */
    fun kept(c: Control) = c.id == DRAWER || c.kind is Kind.ToolKey

    private const val ROUND = 0.115f
    private const val WIDE = 0.085f
    private const val STICK = 0.27f
    private const val TOP_Y = 0.064f
    /** Where the editor puts a key it adds: the middle of the screen. */
    private const val NEW_X = 0.5f
    private const val NEW_Y = 0.5f
    /** The knob moves this part of its reach before a key goes down. */
    private const val STICK_DEAD_ZONE = 0.3f
    private const val SIN_22 = 0.38f
}
