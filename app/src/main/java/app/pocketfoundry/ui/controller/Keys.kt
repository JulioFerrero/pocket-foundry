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

/**
 * The sign on what isn't a key (the drawer, the toolbar's tools in it) and in the editor: a stroked path in a 20 × 20
 * box, in SVG's path syntax. Keys show their own name: Esc, E, Shift, Ctrl+Z.
 */
enum class KeyIcon(val path: String, val stroke: Float = 2f) {
    KEYBOARD("M3.5 5h13A1.5 1.5 0 0 1 18 6.5v7a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 2 13.5v-7A1.5 1.5 0 0 1 3.5 5zM5 8h1M8 8h1M11 8h1M14 8h1M6 12h8"),
    DOTS("M4 10h.01M10 10h.01M16 10h.01", stroke = 3.4f),
    EDIT("M3 6h14M3 10h14M3 14h14M7 4.5v3M13 8.5v3M9 12.5v3"),
    HIDE("M2 10s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6zM10 7.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5M4 16L16 4"),
    /** The stick, in the editor's list: it's drawn as itself on screen. */
    STICK("M10 3a7 7 0 1 0 0 14a7 7 0 1 0 0-14M10 7a3 3 0 1 0 0 6a3 3 0 1 0 0-6"),
    /** The editor's New key. */
    PLUS("M10 4v12M4 10h12", stroke = 2.4f),
    /** The arrow keys, as keyboards mark them. */
    UP("M10 15.5v-11M5.5 9L10 4.5L14.5 9", stroke = 2.2f),
    DOWN("M10 4.5v11M5.5 11L10 15.5L14.5 11", stroke = 2.2f),
    LEFT("M15.5 10h-11M9 5.5L4.5 10L9 14.5", stroke = 2.2f),
    RIGHT("M4.5 10h11M11 5.5L15.5 10L11 14.5", stroke = 2.2f),
    /** The mouse buttons: a mouse, the one button marked, the middle one its wheel. */
    MOUSE_LEFT("$MOUSE_BODY$MOUSE_SPLIT M7.5 4.8v2.4", stroke = 1.8f),
    MOUSE_MIDDLE("$MOUSE_BODY M10 4.5v2.6", stroke = 1.8f),
    MOUSE_RIGHT("$MOUSE_BODY$MOUSE_SPLIT M12.5 4.8v2.4", stroke = 1.8f);

    companion object {
        /** The box its path is drawn in. */
        const val BOX = 20f
    }
}

// A mouse, its buttons split from its body; an enum's own constants can't be used in its entries.
private const val MOUSE_BODY = "M10 2.5a5 5 0 0 1 5 5v5a5 5 0 0 1-10 0v-5a5 5 0 0 1 5-5zM5 9h10"
private const val MOUSE_SPLIT = "M10 2.5v6.5"

/** Keys by their names on a keyboard, and the keyboard the editor's picker shows. */
object KeyNames {
    val MODIFIERS = intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ALT_LEFT)

    /**
     * The mouse's buttons, among a key's codes as keys are, so Shift+Left click is Shift, then the button: below 0,
     * where Android has no key codes, and each X's button number made negative (1 left, 2 middle, 3 right).
     */
    const val MOUSE_LEFT = -1
    const val MOUSE_MIDDLE = -2
    const val MOUSE_RIGHT = -3

    fun isMouse(code: Int) = code < 0

    /** X's number for a mouse code's button. */
    fun button(code: Int) = -code

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
            MOUSE_LEFT to "Left click", MOUSE_MIDDLE to "Middle click", MOUSE_RIGHT to "Right click",
        ))
    }

    /** The keys a light cap has, as on an IBM keyboard: letters, numbers, signs and Space. Others are dark. */
    private val light: Set<Int> = names.keys.filter { code ->
        code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z || code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            names.getValue(code).length == 1 || code == KeyEvent.KEYCODE_SPACE
    }.toSet()

    /**
     * The picker's keyboard, laid out as one, a row at a time: (key, width in a keyboard's units); 0 is a gap. The main
     * block is 15 units wide, then a gap and a block of three: Home, End, Page Up and Down, Delete and the arrows. The
     * mouse's buttons are under the space bar's end, as a ThinkPad has its trackpoint's.
     */
    val KEYBOARD: List<List<Pair<Int, Float>>> = listOf(
        listOf(KeyEvent.KEYCODE_ESCAPE to 1f, 0 to 1f) + (1..4).fn() + listOf(0 to 0.5f) + (5..8).fn() +
            listOf(0 to 0.5f) + (9..12).fn() + listOf(0 to 3.5f),
        listOf(KeyEvent.KEYCODE_GRAVE to 1f) + "1234567890".keys() +
            listOf(KeyEvent.KEYCODE_MINUS to 1f, KeyEvent.KEYCODE_EQUALS to 1f, KeyEvent.KEYCODE_DEL to 2f, 0 to 1.5f,
                   KeyEvent.KEYCODE_MOVE_HOME to 1f, KeyEvent.KEYCODE_PAGE_UP to 1f),
        listOf(KeyEvent.KEYCODE_TAB to 1.5f) + "QWERTYUIOP".keys() +
            listOf(KeyEvent.KEYCODE_LEFT_BRACKET to 1f, KeyEvent.KEYCODE_RIGHT_BRACKET to 1f,
                   KeyEvent.KEYCODE_BACKSLASH to 1.5f, 0 to 0.5f, KeyEvent.KEYCODE_FORWARD_DEL to 1f,
                   KeyEvent.KEYCODE_MOVE_END to 1f, KeyEvent.KEYCODE_PAGE_DOWN to 1f),
        listOf(KeyEvent.KEYCODE_CAPS_LOCK to 1.75f) + "ASDFGHJKL".keys() +
            listOf(KeyEvent.KEYCODE_SEMICOLON to 1f, KeyEvent.KEYCODE_APOSTROPHE to 1f, KeyEvent.KEYCODE_ENTER to 2.25f),
        listOf(KeyEvent.KEYCODE_SHIFT_LEFT to 2.25f) + "ZXCVBNM".keys() +
            listOf(KeyEvent.KEYCODE_COMMA to 1f, KeyEvent.KEYCODE_PERIOD to 1f, KeyEvent.KEYCODE_SLASH to 1f,
                   0 to 4.25f, KeyEvent.KEYCODE_DPAD_UP to 1f),
        // Each width one the keycaps come in (Keys.WIDTHS).
        listOf(KeyEvent.KEYCODE_CTRL_LEFT to 1.25f, KeyEvent.KEYCODE_ALT_LEFT to 1.25f, KeyEvent.KEYCODE_SPACE to 6.25f,
               0 to 1.25f, MOUSE_LEFT to 1f, MOUSE_MIDDLE to 1f, MOUSE_RIGHT to 1f, 0 to 2.5f,
               KeyEvent.KEYCODE_DPAD_LEFT to 1f, KeyEvent.KEYCODE_DPAD_DOWN to 1f, KeyEvent.KEYCODE_DPAD_RIGHT to 1f),
    )

    /** The picker's keys, a row at a time. */
    val ROWS: List<IntArray> = KEYBOARD.map { row -> row.map { it.first }.filter { it != 0 }.toIntArray() }

    private fun IntRange.fn() = map { (KeyEvent.KEYCODE_F1 + it - 1) to 1f }
    private fun String.keys() = map { (if (it.isDigit()) KeyEvent.KEYCODE_0 + (it - '0') else KeyEvent.KEYCODE_A + (it - 'A')) to 1f }

    fun name(code: Int) = names[code] ?: "?"

    /** On a key narrower than 1.5u, as keyboards with a small Backspace write it. */
    fun legend(name: String, units: Float) = if (units < 1.5f) SHORT[name] ?: name else name

    private val SHORT = mapOf("Backspace" to "Bksp", "Delete" to "Del")

    /** An arrow key's sign, which keyboards print instead of its name; a mouse button's, a mouse. */
    fun sign(codes: IntArray): KeyIcon? = when (codes.singleOrNull()) {
        KeyEvent.KEYCODE_DPAD_UP -> KeyIcon.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyIcon.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> KeyIcon.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeyIcon.RIGHT
        MOUSE_LEFT -> KeyIcon.MOUSE_LEFT
        MOUSE_MIDDLE -> KeyIcon.MOUSE_MIDDLE
        MOUSE_RIGHT -> KeyIcon.MOUSE_RIGHT
        else -> null
    }

    /** Ctrl+Shift+Z: the modifiers first, in that order, then the key. */
    fun label(codes: IntArray): String {
        val mods = MODIFIERS.filter { it in codes }
        val rest = codes.filter { it !in MODIFIERS }
        return (mods + rest).joinToString("+", transform = ::name)
    }

    /**
     * A key's legend in two lines, as a combination is printed: the modifiers over the key ("Ctrl", "Z"). A key alone,
     * a modifier alone included, has its name as its one line.
     */
    fun legend(codes: IntArray): Pair<String, String> {
        val mods = MODIFIERS.filter { it in codes }
        val rest = codes.filter { it !in MODIFIERS }
        return if (mods.isEmpty() || rest.isEmpty()) "" to label(codes)
               else mods.joinToString("+", transform = ::name) to rest.joinToString("+", transform = ::name)
    }

    /** A key with a light cap: one letter, number, sign or Space. Modifiers, Esc, F keys and combinations are dark. */
    fun light(codes: IntArray) = codes.size == 1 && codes[0] in light

    /** [codes] with [modifiers] before them, each once. */
    fun withModifiers(modifiers: Collection<Int>, key: Int): IntArray =
        (MODIFIERS.filter { it in modifiers && it != key } + key).toIntArray()
}

/**
 * Keys pressed in X, counted: two keys that both hold Ctrl (a latched Ctrl and Undo's Ctrl+Z) keep it down till the
 * last lets go. [send]: an Android key code down or up (LorieView.sendKeyEvent), or a mouse button's
 * ([KeyNames.isMouse], LorieView.sendMouseEvent). The main thread's only.
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

    /** The mouse buttons keys hold down now: a finger on the game moves the cursor with them (PointerGestures). */
    fun heldButtons(): IntArray = held.keys.filter(KeyNames::isMouse).toIntArray()
}

/** The keys there are, and where each set has them by default. */
object Keys {
    /** Where a key sits in a set: a position in the layout's frame, or in a drawer (its id). */
    class Place(val x: Float, val y: Float, val drawer: String? = null)

    class Def(
        val id: String,
        val kind: Kind,
        /** Under the key in a drawer and the editor's list. */
        val name: String,
        /** What it sends: Android key codes, pressed in order, so Ctrl+Z is Ctrl then Z. */
        val codes: IntArray = IntArray(0),
        /** For what isn't a key (a drawer, the tools, the stick): its sign, or else its [label]. A key shows its name. */
        val icon: KeyIcon? = null,
        val label: String = "",
        /** Its width, in a keyboard's units: 1 for a letter, 1.5 for Ctrl. */
        val units: Float = 1f,
        /** Height (a key's, 1u) or diameter (the stick's), a fraction of the frame's height. */
        val size: Float = KEY,
        val latch: Boolean = false,
        /** The set it brings: the inventory's for E. */
        val shows: String? = null,
        val places: Map<String, Place> = emptyMap(),
    )

    private fun key(id: String, name: String, vararg codes: Int, units: Float = 1f,
                    latch: Boolean = false, shows: String? = null, places: Map<String, Place> = emptyMap()) =
        Def(id, Kind.Key, name, codes, units = units, latch = latch, shows = shows, places = places)

    private fun inDrawer(drawer: String, vararg sets: String) = sets.associateWith { Place(0f, 0f, drawer) }
    private fun at(x: Float, y: Float, vararg sets: String) = sets.associateWith { Place(x, y) }

    /** For combinators, trains, any number Factorio asks for. */
    private fun digit(n: Int) = key("$n", "$n", KeyEvent.KEYCODE_0 + n,
                                    places = inDrawer(NUMBERS, KeySets.GAME, KeySets.WINDOW))

    /**
     * Where the keys are, for the thumbs of a phone held sideways (docs/playing.md): the middle third is the game's and
     * the hardest to reach, so the keys keep to the sides and off Factorio's own panels (the minimap at the top right,
     * the shortcut bar and the quickbar along the bottom, the weapons at the bottom left). The right thumb has E, F, Q
     * and R left of the minimap, R nearest; the left, the stick and Shift and Ctrl beside it; the keys pressed least
     * are along the top, left of the middle. In fractions of the frame (3168 × 1440 on a vivo X200 Ultra).
     */
    private const val OWN_SETS = "*"
    private const val ROW_Y = 0.0799f
    private const val LEFT_X = 0.2273f
    private const val RIGHT_X = 0.6992f
    private const val RIGHTMOST_X = 0.7592f
    private const val UPPER_Y = 0.611f
    private const val LOWER_Y = 0.7465f
    /** The inventory's keys, along the bottom left, where its window leaves room. */
    private const val BOTTOM_Y = 0.9278f

    val catalog: List<Def> = listOf(
        key("esc", "Menu", KeyEvent.KEYCODE_ESCAPE, shows = KeySets.GAME,
            places = at(0.2399f, ROW_Y, KeySets.GAME, KeySets.MAP)),
        key("e", "Inventory", KeyEvent.KEYCODE_E, shows = KeySets.WINDOW,
            places = at(RIGHT_X, UPPER_Y, KeySets.GAME, KeySets.MAP)),
        key("m", "Map", KeyEvent.KEYCODE_M, shows = KeySets.MAP,
            places = at(0.2999f, ROW_Y, KeySets.GAME, KeySets.MAP)),
        key("t", "Research", KeyEvent.KEYCODE_T, shows = KeySets.WINDOW,
            places = at(0.3598f, ROW_Y, KeySets.GAME, KeySets.MAP)),
        key("p", "Production", KeyEvent.KEYCODE_P, shows = KeySets.WINDOW),
        key("l", "Logistics", KeyEvent.KEYCODE_L, shows = KeySets.WINDOW),
        key("o", "Trains", KeyEvent.KEYCODE_O, shows = KeySets.WINDOW),
        key("b", "Blueprints", KeyEvent.KEYCODE_B, shows = KeySets.WINDOW),
        // The inventory's way out: E again.
        key("close", "Close", KeyEvent.KEYCODE_E, shows = KeySets.WINDOW,
            places = at(0.2109f, BOTTOM_Y, KeySets.WINDOW)),
        key("q", "Pipette", KeyEvent.KEYCODE_Q, places = at(RIGHT_X, LOWER_Y, KeySets.GAME)),
        key("r", "Rotate", KeyEvent.KEYCODE_R, places = at(RIGHTMOST_X, LOWER_Y, KeySets.GAME)),
        key("shift", "Shift", KeyEvent.KEYCODE_SHIFT_LEFT, units = 1.5f, latch = true,
            places = at(LEFT_X, LOWER_Y, KeySets.GAME) + at(0.047f, BOTTOM_Y, KeySets.WINDOW)),
        key("ctrl", "Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, units = 1.5f, latch = true,
            places = at(LEFT_X, UPPER_Y, KeySets.GAME) + at(0.136f, BOTTOM_Y, KeySets.WINDOW)),
        key("alt", "Details", KeyEvent.KEYCODE_ALT_LEFT,
            places = inDrawer(DRAWER, KeySets.GAME, KeySets.MAP)),
        key("h", "Flip across", KeyEvent.KEYCODE_H),
        key("v", "Flip down", KeyEvent.KEYCODE_V),
        key("f", "Pick up", KeyEvent.KEYCODE_F, places = at(RIGHTMOST_X, UPPER_Y, KeySets.GAME)),
        key("z", "Drop", KeyEvent.KEYCODE_Z, places = inDrawer(DRAWER, KeySets.GAME)),
        key("x", "Quickbar row", KeyEvent.KEYCODE_X, places = inDrawer(DRAWER, KeySets.GAME)),
        key("undo", "Undo", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z,
            places = inDrawer(DRAWER, KeySets.GAME, KeySets.MAP)),
        key("redo", "Redo", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Y),
        key("cut", "Cut", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_X),
        key("copy", "Copy", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C,
            places = inDrawer(DRAWER, KeySets.GAME, KeySets.MAP)),
        key("paste", "Paste", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V,
            places = inDrawer(DRAWER, KeySets.GAME, KeySets.MAP)),
        key("space", "Shoot", KeyEvent.KEYCODE_SPACE, units = 2f,
            places = inDrawer(DRAWER, KeySets.GAME)),
        key("c", "Next weapon", KeyEvent.KEYCODE_C),
        Def("stick", Kind.KeyStick, "Stick", icon = KeyIcon.STICK, size = STICK,
            places = at(0.095f, 0.66f, KeySets.GAME, KeySets.MAP, OWN_SETS)),
        // The pad's box, as large: over E and F, its right edge at F's, for the right thumb.
        Def("trackpad", Kind.Trackpad, "Trackpad", units = Kind.Trackpad.HALF_WIDTH, size = TRACKPAD,
            places = at(0.6921f, 0.41f, KeySets.GAME)),
    ) + (1..9).map(::digit) + listOf(
        key("backspace", "Backspace", KeyEvent.KEYCODE_DEL,
            places = inDrawer(NUMBERS, KeySets.GAME, KeySets.WINDOW)),
        digit(0),
        // What ends a number typed; and in and out of a vehicle.
        key("enter", "Enter", KeyEvent.KEYCODE_ENTER,
            places = inDrawer(NUMBERS, KeySets.GAME, KeySets.WINDOW)),
        key("console", "Console", KeyEvent.KEYCODE_GRAVE),
        // A laptop's buttons, for the trackpad: held, the cursor moves with them down, so Left drags a line of belts.
        key("mouse-left", "Left click", KeyNames.MOUSE_LEFT),
        key("mouse-right", "Right click", KeyNames.MOUSE_RIGHT),
        key("mouse-middle", "Middle click", KeyNames.MOUSE_MIDDLE),
        Def("keyboard", Kind.ToolKey(Tool.KEYBOARD), "Keyboard", icon = KeyIcon.KEYBOARD,
            places = inDrawer(DRAWER, KeySets.GAME, KeySets.MAP, OWN_SETS)),
        Def("edit", Kind.ToolKey(Tool.ADJUST), "Edit keys", icon = KeyIcon.EDIT,
            places = inDrawer(DRAWER, KeySets.GAME, KeySets.MAP, OWN_SETS)),
        Def("hide", Kind.ToolKey(Tool.HIDE), "Hide keys", icon = KeyIcon.HIDE,
            places = inDrawer(DRAWER, KeySets.GAME, KeySets.MAP, OWN_SETS)),
        Def(DRAWER, Kind.Drawer(), "More", icon = KeyIcon.DOTS,
            places = at(0.4198f, ROW_Y, KeySets.GAME, KeySets.MAP, OWN_SETS)),
        // A phone's numpad: 1 2 3, 4 5 6, 7 8 9, then Backspace, 0 and Enter.
        Def(NUMBERS, Kind.Drawer(columns = 3), "Numbers", label = "123",
            places = at(0.4797f, ROW_Y, KeySets.GAME) + at(0.2715f, BOTTOM_Y, KeySets.WINDOW)),
    )

    /**
     * Every key, for [set]: those the set doesn't have are there unshown, for the editor to add. A set the player
     * makes starts with the stick and the drawer, to move and to edit it; PadLayout.newSet adds the way back. The
     * trackpad is in its place only with [trackpad] (Settings.touch BOX).
     */
    fun controls(set: String, trackpad: Boolean = false): List<Control> = catalog.map { def ->
        val place = (def.places[set] ?: def.places[OWN_SETS].takeIf { set.startsWith(KeySets.OWN) })
            ?.takeIf { trackpad || def.kind != Kind.Trackpad }
        Control(def.id, def.kind, place?.takeIf { it.drawer == null }?.x ?: NEW_X,
                place?.takeIf { it.drawer == null }?.y ?: NEW_Y, def.size, glyph = null, label = def.label, name = def.name,
                keyIcon = def.icon, defaultShown = place != null, defaultDrawer = place?.drawer,
                defaultLatch = def.latch, defaultCodes = def.codes, defaultShows = def.shows,
                defaultUnits = def.units)
    }

    /** A key the player made: [codes] it sends, at the middle of the screen. */
    fun own(id: String, codes: IntArray, units: Float = 1f, shows: String? = null) =
        Control(id, Kind.Key, NEW_X, NEW_Y, KEY, glyph = null, name = KeyNames.label(codes),
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
    /** The drawer of numbers, Backspace and Enter. */
    const val NUMBERS = "123"
    /** Can't be removed: the drawer holds the way into the editor, and the editor's tools are in it. */
    fun kept(c: Control) = c.id == DRAWER || c.kind is Kind.ToolKey

    /**
     * A key's height (1u), of the frame's height: 48 dp on a phone (1440 px, 384 dp tall sideways), what Android and
     * Apple ask of a thumb's target; the rendered caps' (art/controls/controls.py UNIT).
     */
    const val KEY = 0.125f
    private const val STICK = 0.27f
    /** The trackpad's height: the pad's right stick's, which its box stands in for. */
    private const val TRACKPAD = 0.24f
    /** Where a set the player makes has its way back: on the top row, from Esc's left edge (2u). */
    const val TOP_Y = ROW_Y
    const val BACK_X = 0.2683f
    /** Where the editor puts a key it adds: the middle of the screen. */
    private const val NEW_X = 0.5f
    private const val NEW_Y = 0.5f
    /** The knob moves this part of its reach before a key goes down. */
    private const val STICK_DEAD_ZONE = 0.3f
    private const val SIN_22 = 0.38f
}
