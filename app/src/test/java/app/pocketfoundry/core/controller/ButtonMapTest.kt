package app.pocketfoundry.core.controller

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonMapTest {
    private var saved = "{}"
    private val map = ButtonMap({ saved }, { saved = it })
    private val pad = "pad-descriptor"
    private val other = "another-pad"
    private val all: (Int) -> Boolean = { true }

    @Test fun `a controller's buttons do what Android names them, and the others nothing`() {
        assertEquals(PadTarget.A, map.target(pad, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(PadTarget.LT, map.target(pad, KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(PadTarget.HOME, map.target(pad, KeyEvent.KEYCODE_BUTTON_MODE))
        assertNull(map.target(pad, KeyEvent.KEYCODE_BUTTON_C))
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_A), map.keys(pad, PadTarget.A, all))
    }

    @Test fun `a button the game never saw does what it's set to, on that controller only`() {
        map.set(pad, KeyEvent.KEYCODE_BUTTON_C, PadTarget.LB)
        assertEquals(PadTarget.LB, map.target(pad, KeyEvent.KEYCODE_BUTTON_C))
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_C), map.keys(pad, PadTarget.LB, all))
        assertNull(map.target(other, KeyEvent.KEYCODE_BUTTON_C))
    }

    @Test fun `A and B swap`() {
        map.set(pad, KeyEvent.KEYCODE_BUTTON_B, PadTarget.A)
        map.set(pad, KeyEvent.KEYCODE_BUTTON_A, PadTarget.B)
        assertEquals(PadTarget.B, map.target(pad, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(PadTarget.A, map.target(pad, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_B), map.keys(pad, PadTarget.A, all))
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_A), map.keys(pad, PadTarget.B, all))
    }

    @Test fun `set back to what Android names it, a button keeps no move, and reset forgets them all`() {
        map.set(pad, KeyEvent.KEYCODE_BUTTON_A, PadTarget.B)
        map.set(pad, KeyEvent.KEYCODE_BUTTON_A, PadTarget.A)
        assertNull(map.moved(pad, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("{}", saved)
        map.set(pad, KeyEvent.KEYCODE_BUTTON_Z, PadTarget.RB)
        map.set(other, KeyEvent.KEYCODE_BUTTON_Z, PadTarget.RB)
        map.reset(pad)
        assertNull(map.target(pad, KeyEvent.KEYCODE_BUTTON_Z))
        assertEquals(PadTarget.RB, map.target(other, KeyEvent.KEYCODE_BUTTON_Z))
    }

    @Test fun `only the buttons a controller has are listed as its own`() {
        val noSticks: (Int) -> Boolean = { it != KeyEvent.KEYCODE_BUTTON_THUMBL }
        assertEquals(emptyList<Int>(), map.keys(pad, PadTarget.L3, noSticks))
    }

    @Test fun `what can't be read is no moves`() {
        saved = "not json"
        assertEquals(PadTarget.B, map.target(pad, KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test fun `buttons are named as Android names them`() {
        assertEquals("C", ButtonMap.name(KeyEvent.KEYCODE_BUTTON_C))
        assertEquals("Z", ButtonMap.name(KeyEvent.KEYCODE_BUTTON_Z))
        assertEquals("L1", ButtonMap.name(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals("Button 5", ButtonMap.name(KeyEvent.KEYCODE_BUTTON_5))
    }
}
