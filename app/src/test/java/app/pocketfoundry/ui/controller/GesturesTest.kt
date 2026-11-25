package app.pocketfoundry.ui.controller

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    private fun IntArray.named() = if (isEmpty()) "Nothing" else KeyNames.label(this)

    @Test fun `by default a long press right-clicks, double tap and hold left-clicks, two fingers Esc, three a middle click`() {
        val sends = GestureSends()
        assertEquals("Right click", sends[Gesture.LONG_PRESS].named())
        assertEquals("Left click", sends[Gesture.DOUBLE_TAP_HOLD].named())
        assertEquals("Esc", sends[Gesture.TWO_FINGER_TAP].named())
        assertEquals("Middle click", sends[Gesture.THREE_FINGER_TAP].named())
        assertNull(sends.write())
    }

    @Test fun `what the player changed comes back as saved, nothing included, and the rest stays default`() {
        val sends = GestureSends()
        sends[Gesture.TWO_FINGER_TAP] = IntArray(0)
        sends[Gesture.THREE_FINGER_TAP] = intArrayOf(KeyEvent.KEYCODE_Q)
        sends[Gesture.LONG_PRESS] = intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyNames.MOUSE_RIGHT)
        val json = JSONObject(sends.write().toString())
        val back = GestureSends()
        back.read(json)()
        assertEquals("Nothing", back[Gesture.TWO_FINGER_TAP].named())
        assertEquals("Q", back[Gesture.THREE_FINGER_TAP].named())
        assertEquals("Shift+Right click", back[Gesture.LONG_PRESS].named())
        assertEquals("Left click", back[Gesture.DOUBLE_TAP_HOLD].named())
    }

    @Test fun `set back to its default, a gesture saves nothing, and Reset puts them all back`() {
        val sends = GestureSends()
        sends[Gesture.LONG_PRESS] = intArrayOf(KeyEvent.KEYCODE_E)
        sends[Gesture.LONG_PRESS] = intArrayOf(KeyNames.MOUSE_RIGHT)
        assertNull(sends.write())
        sends[Gesture.THREE_FINGER_TAP] = IntArray(0)
        sends.reset()
        assertEquals("Middle click", sends[Gesture.THREE_FINGER_TAP].named())
    }

    @Test fun `a layout saved before gestures reads them as the defaults, over what was there`() {
        val sends = GestureSends()
        sends[Gesture.DOUBLE_TAP_HOLD] = IntArray(0)
        sends.read(null)()
        assertEquals("Left click", sends[Gesture.DOUBLE_TAP_HOLD].named())
        assertEquals(Gesture.entries.size, Gesture.entries.map { it.id }.toSet().size)
        assertTrue(Gesture.entries.all { it.default.isNotEmpty() })
    }
}
