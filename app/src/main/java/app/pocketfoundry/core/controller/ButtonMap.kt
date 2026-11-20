package app.pocketfoundry.core.controller

import android.content.Context
import android.view.KeyEvent
import app.pocketfoundry.core.system.Settings
import org.json.JSONObject

/**
 * What a controller's button does: one of the pad's buttons, a trigger, or Home (the pause menu, in a game). The game
 * names them the Xbox way. [launcherKey]: what it does in the launcher's screens, where A selects and B goes back.
 */
enum class PadTarget(val title: String, val button: PadButton? = null, val trigger: PadAxis? = null,
                     val launcherKey: Int = 0) {
    A("A", PadButton.SOUTH, launcherKey = KeyEvent.KEYCODE_DPAD_CENTER),
    B("B", PadButton.EAST, launcherKey = KeyEvent.KEYCODE_BACK),
    X("X", PadButton.WEST),
    Y("Y", PadButton.NORTH),
    LB("LB", PadButton.LEFT_SHOULDER),
    RB("RB", PadButton.RIGHT_SHOULDER),
    LT("LT", trigger = PadAxis.LEFT_TRIGGER),
    RT("RT", trigger = PadAxis.RIGHT_TRIGGER),
    L3("L3", PadButton.LEFT_STICK),
    R3("R3", PadButton.RIGHT_STICK),
    SELECT("Select", PadButton.BACK),
    START("Start", PadButton.START),
    UP("Up", PadButton.DPAD_UP, launcherKey = KeyEvent.KEYCODE_DPAD_UP),
    DOWN("Down", PadButton.DPAD_DOWN, launcherKey = KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("Left", PadButton.DPAD_LEFT, launcherKey = KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("Right", PadButton.DPAD_RIGHT, launcherKey = KeyEvent.KEYCODE_DPAD_RIGHT),
    HOME("Home"),
}

/**
 * Which of a controller's buttons does what: Android's names for the usual ones ([DEFAULTS]), then what the player
 * moved or added in Settings -> Controls -> Controller buttons, for each controller by its descriptor, so a handheld's
 * own pad and a Bluetooth one keep theirs. It covers what Factorio's own rebinding can't: buttons it never sees (C, Z,
 * paddles, numbered ones), A and B in each other's places, and the launcher's A and B.
 * [read] and [write]: the moves, as JSON ({descriptor: {key code: target}}).
 */
class ButtonMap(private val read: () -> String, private val write: (String) -> Unit) {
    constructor(context: Context) : this({ Settings(context).controllerButtons }, { Settings(context).controllerButtons = it })

    private var raw: String? = null
    private var moves = JSONObject()

    /** What [keyCode] does on the controller [descriptor]: what the player moved it to, or Android's. */
    fun target(descriptor: String, keyCode: Int): PadTarget? = moved(descriptor, keyCode) ?: DEFAULTS[keyCode]

    /** What the player moved [keyCode] to on [descriptor], if they did. */
    fun moved(descriptor: String, keyCode: Int): PadTarget? {
        val name = moves().optJSONObject(descriptor)?.optString("$keyCode").orEmpty()
        return PadTarget.entries.firstOrNull { it.name == name }
    }

    /** [keyCode] does [target] on [descriptor] from now on. */
    fun set(descriptor: String, keyCode: Int, target: PadTarget) {
        val all = JSONObject(moves().toString())
        val own = all.optJSONObject(descriptor) ?: JSONObject()
        if (DEFAULTS[keyCode] == target) own.remove("$keyCode") else own.put("$keyCode", target.name)
        if (own.length() == 0) all.remove(descriptor) else all.put(descriptor, own)
        write(all.toString())
    }

    /** [descriptor]'s buttons as Android has them. */
    fun reset(descriptor: String) {
        val all = JSONObject(moves().toString())
        all.remove(descriptor)
        write(all.toString())
    }

    /**
     * The buttons that do [target] on [descriptor]: Android's own for it that weren't moved elsewhere and that the
     * controller [has], then those moved to it.
     */
    fun keys(descriptor: String, target: PadTarget, has: (Int) -> Boolean): List<Int> {
        val own = DEFAULTS.filter { (code, it) -> it == target && moved(descriptor, code) == null && has(code) }.keys
        val added = moves().optJSONObject(descriptor)?.keys()?.asSequence().orEmpty()
            .mapNotNull { it.toIntOrNull() }.filter { moved(descriptor, it) == target }
        return own.toList() + added.sorted()
    }

    private fun moves(): JSONObject {
        val now = read()
        if (now != raw) {
            raw = now
            moves = runCatching { JSONObject(now) }.getOrDefault(JSONObject())
        }
        return moves
    }

    companion object {
        /** Android's names for a pad's buttons; some pads send the D-pad as keys rather than a hat. */
        val DEFAULTS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to PadTarget.A,
            KeyEvent.KEYCODE_BUTTON_B to PadTarget.B,
            KeyEvent.KEYCODE_BUTTON_X to PadTarget.X,
            KeyEvent.KEYCODE_BUTTON_Y to PadTarget.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to PadTarget.LB,
            KeyEvent.KEYCODE_BUTTON_R1 to PadTarget.RB,
            KeyEvent.KEYCODE_BUTTON_L2 to PadTarget.LT,
            KeyEvent.KEYCODE_BUTTON_R2 to PadTarget.RT,
            KeyEvent.KEYCODE_BUTTON_THUMBL to PadTarget.L3,
            KeyEvent.KEYCODE_BUTTON_THUMBR to PadTarget.R3,
            KeyEvent.KEYCODE_BUTTON_SELECT to PadTarget.SELECT,
            KeyEvent.KEYCODE_BUTTON_START to PadTarget.START,
            KeyEvent.KEYCODE_DPAD_UP to PadTarget.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to PadTarget.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to PadTarget.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to PadTarget.RIGHT,
            KeyEvent.KEYCODE_BUTTON_MODE to PadTarget.HOME,
        )

        private val NAMES = mapOf(
            KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1",
            KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "L stick", KeyEvent.KEYCODE_BUTTON_THUMBR to "R stick",
            KeyEvent.KEYCODE_BUTTON_SELECT to "Select", KeyEvent.KEYCODE_BUTTON_START to "Start",
            KeyEvent.KEYCODE_BUTTON_MODE to "Home",
            KeyEvent.KEYCODE_DPAD_UP to "Up", KeyEvent.KEYCODE_DPAD_DOWN to "Down",
            KeyEvent.KEYCODE_DPAD_LEFT to "Left", KeyEvent.KEYCODE_DPAD_RIGHT to "Right",
        )

        /** A button as Android names it: "C", "L1", "Button 5"; anything else by its key's name. */
        fun name(keyCode: Int): String {
            NAMES[keyCode]?.let { return it }
            if (keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_Z) {
                return "ABCXYZ"[keyCode - KeyEvent.KEYCODE_BUTTON_A].toString()
            }
            if (keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16) {
                return "Button ${keyCode - KeyEvent.KEYCODE_BUTTON_1 + 1}"
            }
            return KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase().replace('_', ' ')
                .replaceFirstChar(Char::uppercase)
        }
    }
}
