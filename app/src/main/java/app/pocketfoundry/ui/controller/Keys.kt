package app.pocketfoundry.ui.controller

import android.view.KeyEvent
import app.pocketfoundry.core.controller.DpadDir

/**
 * Mouse & keys (Settings.controls KEYS): Factorio in its keyboard-and-mouse mode, the finger as the mouse
 * (PointerGestures) and keyboard keys on screen, in sets: the keys for one kind of screen each, one shown at a time. A
 * key can show a set ([Control.shows]): E the inventory's, M the map's. Sets are known by their key: three built in,
 * and the player's own ("set-1").
 */
object KeySets {
    /** The pad's controls: Settings.controls PAD has this one set. */
    const val PAD = "pad"
    const val GAME = "game"
    /** The inventory and every window like it (research, production): the screen behind is covered. */
    const val WINDOW = "window"
    const val MAP = "map"
    val BUILT_IN = linkedMapOf(GAME to "In game", WINDOW to "Inventory", MAP to "Map")
    /** A set the player makes. */
    const val OWN = "set-"

    /** The set after a key that shows [target], from [current]: the same key again goes back to the game's. */
    fun next(current: String, target: String) = if (target == GAME || current == target) GAME else target
}

/** For the editor's list of keys to add. */
enum class KeyGroup(val title: String) {
    WINDOWS("Windows"), BUILDING("Building"), ITEMS("Items"), EDITING("Editing"), MOVING("Moving and pointing"),
    OTHER("Other"),
    /** Keys that only bring another set: one for each set there is. */
    SETS("Sets"),
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
    STICK("M10 3a7 7 0 1 0 0 14a7 7 0 1 0 0-14M10 7a3 3 0 1 0 0 6a3 3 0 1 0 0-6"),
    /** The editor's New key. */
    PLUS("M10 4v12M4 10h12", stroke = 2.4f);

    companion object {
        /** The box its path is drawn in. */
        const val BOX = 20f
    }
}

/** Keys by their names on a keyboard, and the keyboard the editor's picker shows. */
object KeyNames {
    val MODIFIERS = intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ALT_LEFT)

    private val names: Map<Int, String> = buildMap {
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, ('A' + i).toString())
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, i.toString())
        for (i in 1..12) put(KeyEvent.KEYCODE_F1 + i - 1, "F$i")
        putAll(mapOf(
            KeyEvent.KEYCODE_ESCAPE to "Esc", KeyEvent.KEYCODE_TAB to "Tab", KeyEvent.KEYCODE_SPACE to "Space",
            KeyEvent.KEYCODE_ENTER to "Enter", KeyEvent.KEYCODE_DEL to "Backspace", KeyEvent.KEYCODE_FORWARD_DEL to "Delete",
            KeyEvent.KEYCODE_INSERT to "Insert", KeyEvent.KEYCODE_MOVE_HOME to "Home", KeyEvent.KEYCODE_MOVE_END to "End",
            KeyEvent.KEYCODE_PAGE_UP to "PgUp", KeyEvent.KEYCODE_PAGE_DOWN to "PgDn", KeyEvent.KEYCODE_DPAD_UP to "Up",
            KeyEvent.KEYCODE_DPAD_DOWN to "Down", KeyEvent.KEYCODE_DPAD_LEFT to "Left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "Right", KeyEvent.KEYCODE_GRAVE to "`", KeyEvent.KEYCODE_MINUS to "-",
            KeyEvent.KEYCODE_EQUALS to "=", KeyEvent.KEYCODE_LEFT_BRACKET to "[", KeyEvent.KEYCODE_RIGHT_BRACKET to "]",
            KeyEvent.KEYCODE_BACKSLASH to "\\", KeyEvent.KEYCODE_SEMICOLON to ";", KeyEvent.KEYCODE_APOSTROPHE to "'",
            KeyEvent.KEYCODE_COMMA to ",", KeyEvent.KEYCODE_PERIOD to ".", KeyEvent.KEYCODE_SLASH to "/",
            KeyEvent.KEYCODE_CAPS_LOCK to "Caps", KeyEvent.KEYCODE_SHIFT_LEFT to "Shift",
            KeyEvent.KEYCODE_CTRL_LEFT to "Ctrl", KeyEvent.KEYCODE_ALT_LEFT to "Alt",
        ))
    }

    /** The keys a light cap has, as on an IBM keyboard: letters, numbers, signs and Space. Others are dark. */
    private val light: Set<Int> = names.keys.filter { code ->
        code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z || code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            names.getValue(code).length == 1 || code == KeyEvent.KEYCODE_SPACE
    }.toSet()

    /** The picker's keyboard, a row at a time. */
    val ROWS: List<IntArray> = listOf(
        intArrayOf(KeyEvent.KEYCODE_ESCAPE) + IntArray(12) { KeyEvent.KEYCODE_F1 + it },
        intArrayOf(KeyEvent.KEYCODE_GRAVE) + intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0).map { KeyEvent.KEYCODE_0 + it } +
            intArrayOf(KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_DEL),
        intArrayOf(KeyEvent.KEYCODE_TAB) + "QWERTYUIOP".letters() +
            intArrayOf(KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH),
        intArrayOf(KeyEvent.KEYCODE_CAPS_LOCK) + "ASDFGHJKL".letters() +
            intArrayOf(KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_ENTER),
        intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT) + "ZXCVBNM".letters() +
            intArrayOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_DPAD_UP),
        // Insert left out: Factorio has nothing on it, and the row is full.
        intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
                   KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                   KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT),
    )

    private fun String.letters() = IntArray(length) { KeyEvent.KEYCODE_A + (this[it] - 'A') }

    fun name(code: Int) = names[code] ?: "?"

    /** Ctrl+Shift+Z: the modifiers first, in that order, then the key. */
    fun label(codes: IntArray): String {
        val mods = MODIFIERS.filter { it in codes }
        val rest = codes.filter { it !in MODIFIERS }
        return (mods + rest).joinToString("+", transform = ::name)
    }

    /** A key with a light cap: one letter, number, sign or Space. Modifiers, Esc, F keys and combinations are dark. */
    fun light(codes: IntArray) = codes.size == 1 && codes[0] in light

    /** [codes] with [modifiers] before them, each once. */
    fun withModifiers(modifiers: Collection<Int>, key: Int): IntArray =
        (MODIFIERS.filter { it in modifiers && it != key } + key).toIntArray()
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
        /** Under the key in the drawer and the editor's list. */
        val name: String,
        /** What it sends: Android key codes, pressed in order, so Ctrl+Z is Ctrl then Z. */
        val codes: IntArray = IntArray(0),
        /** For what isn't a key (the drawer, the tools, the stick): its sign. A key shows its name. */
        val icon: KeyIcon? = null,
        /** Its width, in a keyboard's units: 1 for a letter, 2.25 for Shift. */
        val units: Float = 1f,
        /** Height (a key's, 1u) or diameter (the stick's), a fraction of the frame's height. */
        val size: Float = KEY,
        val latch: Boolean = false,
        /** The set it brings: the inventory's for E. */
        val shows: String? = null,
        val places: Map<String, Place> = emptyMap(),
    )

    private fun key(id: String, group: KeyGroup, name: String, vararg codes: Int, units: Float = 1f,
                    latch: Boolean = false, shows: String? = null, places: Map<String, Place> = emptyMap()) =
        Def(id, Kind.Key, group, name, codes, units = units, latch = latch, shows = shows, places = places)

    private fun drawer(vararg sets: String) = sets.associateWith { Place(0f, 0f, drawer = true) }
    private fun top(x: Float) = mapOf(KeySets.GAME to Place(x, TOP_Y), KeySets.MAP to Place(x, TOP_Y),
                                      OWN_SETS to Place(x, TOP_Y))

    private const val OWN_SETS = "*"

    val catalog: List<Def> = listOf(
        key("esc", KeyGroup.WINDOWS, "Menu", KeyEvent.KEYCODE_ESCAPE, shows = KeySets.GAME,
            places = top(0.4053f) - OWN_SETS),
        key("e", KeyGroup.WINDOWS, "Inventory", KeyEvent.KEYCODE_E, shows = KeySets.WINDOW, places = top(0.4527f) - OWN_SETS),
        key("m", KeyGroup.WINDOWS, "Map", KeyEvent.KEYCODE_M, shows = KeySets.MAP, places = top(0.5f) - OWN_SETS),
        key("t", KeyGroup.WINDOWS, "Research", KeyEvent.KEYCODE_T, shows = KeySets.WINDOW, places = top(0.5473f) - OWN_SETS),
        key("p", KeyGroup.WINDOWS, "Production", KeyEvent.KEYCODE_P, shows = KeySets.WINDOW),
        // The inventory's way out: E again.
        key("close", KeyGroup.WINDOWS, "Close", KeyEvent.KEYCODE_E, shows = KeySets.WINDOW,
            places = mapOf(KeySets.WINDOW to Place(0.1973f, 0.9424f))),
        key("q", KeyGroup.BUILDING, "Pipette", KeyEvent.KEYCODE_Q, places = mapOf(KeySets.GAME to Place(0.682f, 0.639f))),
        key("r", KeyGroup.BUILDING, "Rotate", KeyEvent.KEYCODE_R, places = mapOf(KeySets.GAME to Place(0.729f, 0.639f))),
        key("shift", KeyGroup.BUILDING, "Shift", KeyEvent.KEYCODE_SHIFT_LEFT, units = 2.25f, latch = true,
            places = mapOf(KeySets.GAME to Place(0.640f, 0.755f), KeySets.WINDOW to Place(0.0535f, 0.9424f))),
        key("ctrl", KeyGroup.BUILDING, "Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, units = 1.5f, latch = true,
            places = mapOf(KeySets.GAME to Place(0.7256f, 0.755f), KeySets.WINDOW to Place(0.139f, 0.9424f))),
        key("alt", KeyGroup.BUILDING, "Details", KeyEvent.KEYCODE_ALT_LEFT, places = drawer(KeySets.GAME, KeySets.MAP)),
        key("f", KeyGroup.ITEMS, "Pick up", KeyEvent.KEYCODE_F, places = drawer(KeySets.GAME)),
        key("z", KeyGroup.ITEMS, "Drop", KeyEvent.KEYCODE_Z, places = drawer(KeySets.GAME)),
        key("c", KeyGroup.ITEMS, "Shoot", KeyEvent.KEYCODE_C, places = drawer(KeySets.GAME)),
        key("undo", KeyGroup.EDITING, "Undo", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z,
            places = drawer(KeySets.GAME, KeySets.MAP)),
        key("redo", KeyGroup.EDITING, "Redo", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Y),
        key("cut", KeyGroup.EDITING, "Cut", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_X),
        key("copy", KeyGroup.EDITING, "Copy", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C,
            places = drawer(KeySets.GAME, KeySets.MAP)),
        key("paste", KeyGroup.EDITING, "Paste", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V,
            places = drawer(KeySets.GAME, KeySets.MAP)),
        Def("stick", Kind.KeyStick, KeyGroup.MOVING, "Stick", icon = KeyIcon.STICK, size = STICK,
            places = listOf(KeySets.GAME, KeySets.MAP, OWN_SETS).associateWith { Place(0.0852f, 0.652f) }),
        key("console", KeyGroup.OTHER, "Console", KeyEvent.KEYCODE_GRAVE),
        Def("keyboard", Kind.ToolKey(Tool.KEYBOARD), KeyGroup.OTHER, "Keyboard", icon = KeyIcon.KEYBOARD,
            places = drawer(KeySets.GAME, KeySets.MAP, OWN_SETS)),
        Def("edit", Kind.ToolKey(Tool.ADJUST), KeyGroup.OTHER, "Edit keys", icon = KeyIcon.EDIT,
            places = drawer(KeySets.GAME, KeySets.MAP, OWN_SETS)),
        Def("hide", Kind.ToolKey(Tool.HIDE), KeyGroup.OTHER, "Hide keys", icon = KeyIcon.HIDE,
            places = drawer(KeySets.GAME, KeySets.MAP, OWN_SETS)),
        Def(DRAWER, Kind.Drawer, KeyGroup.OTHER, "More", icon = KeyIcon.DOTS, places = top(0.5947f)),
    )

    /**
     * Every key, for [set]: those the set doesn't have are there unshown, for the editor to add. A set the player
     * makes starts with the stick and the drawer, to move and to edit it; PadLayout.newSet adds the way back.
     */
    fun controls(set: String): List<Control> = catalog.map { def ->
        val place = def.places[set] ?: def.places[OWN_SETS].takeIf { set.startsWith(KeySets.OWN) }
        Control(def.id, def.kind, place?.takeUnless { it.drawer }?.x ?: NEW_X,
                place?.takeUnless { it.drawer }?.y ?: NEW_Y, def.size, glyph = null, name = def.name,
                keyIcon = def.icon, defaultShown = place != null, defaultInDrawer = place?.drawer == true,
                defaultLatch = def.latch, group = def.group, defaultCodes = def.codes, defaultShows = def.shows,
                defaultUnits = def.units)
    }

    /** A key the player made: [codes] it sends, at the middle of the screen. */
    fun own(id: String, codes: IntArray, units: Float = 1f, shows: String? = null) =
        Control(id, Kind.Key, NEW_X, NEW_Y, KEY, glyph = null, name = KeyNames.label(codes), group = KeyGroup.OTHER,
                defaultCodes = codes, defaultShows = shows, defaultUnits = units, own = true)

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

    /** A keyboard's widths, in its units: what a key can be (the rendered caps, kc-pearl-1.25). */
    val WIDTHS = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.75f, 6.25f)

    fun widthName(units: Float) = if (units % 1f == 0f) units.toInt().toString() else units.toString()

    /** The width of [WIDTHS] nearest [units]. */
    fun widthIndex(units: Float): Int {
        var best = 0
        for (i in WIDTHS.indices) if (kotlin.math.abs(WIDTHS[i] - units) < kotlin.math.abs(WIDTHS[best] - units)) best = i
        return best
    }

    const val DRAWER = "more"
    /** Can't be removed: the drawer holds the way into the editor, and the editor's tools are in it. */
    fun kept(c: Control) = c.id == DRAWER || c.kind is Kind.ToolKey

    /** A key's height (1u), of the frame's height: the rendered caps' (art/controls/controls.py UNIT), a little larger. */
    const val KEY = 0.096f
    private const val STICK = 0.27f
    /** The top row's middle, of the frame's height; and where a set's own way back sits on it (2u, Esc's left edge). */
    const val TOP_Y = 0.0576f
    const val BACK_X = 0.427f
    /** Where the editor puts a key it adds: the middle of the screen. */
    private const val NEW_X = 0.5f
    private const val NEW_Y = 0.5f
    /** The knob moves this part of its reach before a key goes down. */
    private const val STICK_DEAD_ZONE = 0.3f
    private const val SIN_22 = 0.38f
}
