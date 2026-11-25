package app.pocketfoundry.ui.controller

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mouse & keys' gestures on the game, besides a finger's own tap, drag and pinch: each sends keys or mouse buttons
 * (KeyNames), as a key does, or nothing. The long press and double tap and hold keep them down while the finger stays,
 * the cursor going with it; the taps press and let go (PointerGestures). Set in the editor's Touch panel
 * (KeyPanels.touch). [id]: its key in the saved layout.
 */
enum class Gesture(val id: String, val title: String, val default: IntArray) {
    LONG_PRESS("longPress", "Long press", intArrayOf(KeyNames.MOUSE_RIGHT)),
    DOUBLE_TAP_HOLD("doubleTapHold", "Double tap and hold", intArrayOf(KeyNames.MOUSE_LEFT)),
    TWO_FINGER_TAP("twoFingerTap", "Two-finger tap", intArrayOf(KeyEvent.KEYCODE_ESCAPE)),
    THREE_FINGER_TAP("threeFingerTap", "Three-finger tap", intArrayOf(KeyNames.MOUSE_MIDDLE)),
}

/** What each [Gesture] sends: its default, unless the player changed it. Empty: nothing. */
class GestureSends {
    private val changed = HashMap<Gesture, IntArray>()

    operator fun get(gesture: Gesture): IntArray = changed[gesture] ?: gesture.default

    operator fun set(gesture: Gesture, codes: IntArray) {
        if (codes.contentEquals(gesture.default)) changed.remove(gesture) else changed[gesture] = codes
    }

    fun reset() = changed.clear()

    /** Only what the player changed; null when nothing is. */
    fun write(): JSONObject? {
        if (changed.isEmpty()) return null
        val json = JSONObject()
        for ((gesture, codes) in changed) json.put(gesture.id, JSONArray(codes.toList()))
        return json
    }

    /** Reads [json] whole, or throws; the returned step puts it in place of what's here. */
    fun read(json: JSONObject?): () -> Unit {
        val read = Gesture.entries.mapNotNull { g ->
            json?.optJSONArray(g.id)?.let { a -> g to IntArray(a.length()) { a.getInt(it) } }
        }
        return {
            changed.clear()
            for ((gesture, codes) in read) this[gesture] = codes
        }
    }
}

/** Where PointerGestures' gestures go in Mouse & keys: what each sends, and the keys that send it (ControllerView). */
interface GestureOut {
    fun sends(gesture: Gesture): IntArray
    fun press(codes: IntArray)
    fun release(codes: IntArray)
    /** Pressed and let go, and then what a key that sends them does: Esc brings back the game's keys. */
    fun tap(codes: IntArray)
    /** The mouse buttons keys hold down now (KeyOut.heldButtons). */
    fun heldButtons(): IntArray
}
