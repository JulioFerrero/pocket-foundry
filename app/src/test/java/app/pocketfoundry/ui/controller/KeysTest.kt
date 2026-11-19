package app.pocketfoundry.ui.controller

import android.view.KeyEvent
import app.pocketfoundry.core.controller.DpadDir
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {
    private val sent = ArrayList<String>()
    private val out = KeyOut { code, down -> sent += (if (down) "+" else "-") + code }

    @Test fun `a combination presses in order and lets go in reverse`() {
        out.press(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z))
        out.release(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z))
        assertEquals(listOf("+${KeyEvent.KEYCODE_CTRL_LEFT}", "+${KeyEvent.KEYCODE_Z}",
                            "-${KeyEvent.KEYCODE_Z}", "-${KeyEvent.KEYCODE_CTRL_LEFT}"), sent)
    }

    @Test fun `a latched Ctrl stays down through Undo's own Ctrl`() {
        val ctrl = intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT)
        val undo = intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z)
        out.press(ctrl)
        out.press(undo)
        out.release(undo)
        assertTrue(out.isHeld(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(1, sent.count { it == "+${KeyEvent.KEYCODE_CTRL_LEFT}" })
        out.release(ctrl)
        assertFalse(out.isHeld(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals("-${KeyEvent.KEYCODE_CTRL_LEFT}", sent.last())
    }

    @Test fun `letting go of a key that isn't down sends nothing`() {
        out.release(intArrayOf(KeyEvent.KEYCODE_E))
        assertTrue(sent.isEmpty())
    }

    @Test fun `releasing everything lets go of every key once`() {
        out.press(intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT))
        out.press(intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT))
        out.press(intArrayOf(KeyEvent.KEYCODE_W))
        sent.clear()
        out.releaseAll()
        assertEquals(setOf("-${KeyEvent.KEYCODE_SHIFT_LEFT}", "-${KeyEvent.KEYCODE_W}"), sent.toSet())
        assertEquals(2, sent.size)
        assertFalse(out.isHeld(KeyEvent.KEYCODE_SHIFT_LEFT))
    }

    @Test fun `the stick holds nothing near its middle`() {
        assertEquals(0, Keys.stickDirections(0.2f, -0.1f, 1f))
    }

    @Test fun `the stick holds W up, W and D up and right`() {
        assertEquals(DpadDir.UP.bit, Keys.stickDirections(0.05f, -0.9f, 1f))
        assertEquals(DpadDir.UP.bit or DpadDir.RIGHT.bit, Keys.stickDirections(0.6f, -0.6f, 1f))
        assertEquals(DpadDir.LEFT.bit, Keys.stickDirections(-1f, 0.1f, 1f))
        assertEquals(KeyEvent.KEYCODE_W, Keys.stickCode(DpadDir.UP))
        assertEquals(KeyEvent.KEYCODE_D, Keys.stickCode(DpadDir.RIGHT))
    }

    @Test fun `E and M open their sets and close them again, Esc goes back to the game's`() {
        assertEquals(KeySet.WINDOW, KeySet.GAME.after(Opens.WINDOW))
        assertEquals(KeySet.GAME, KeySet.WINDOW.after(Opens.WINDOW))
        assertEquals(KeySet.MAP, KeySet.GAME.after(Opens.MAP))
        assertEquals(KeySet.GAME, KeySet.MAP.after(Opens.MAP))
        assertEquals(KeySet.WINDOW, KeySet.MAP.after(Opens.WINDOW))
        assertEquals(KeySet.GAME, KeySet.WINDOW.after(Opens.GAME))
    }

    @Test fun `every key has its own id and something to send`() {
        assertEquals(Keys.catalog.size, Keys.catalog.map { it.id }.toSet().size)
        for (def in Keys.catalog) {
            val kind = def.kind
            if (kind is Kind.Key) assertTrue(def.id, kind.codes.isNotEmpty())
            assertTrue(def.id, def.label.isNotEmpty() || def.icon != null || kind == Kind.KeyStick)
        }
    }

    @Test fun `each set shows its keys inside the frame, and the game's and the map's have the editor`() {
        for (set in KeySet.KEYS) {
            val controls = Keys.controls(set)
            assertTrue(set.name, controls.any { it.shown })
            for (c in controls.filter { it.shown && !it.inDrawer }) {
                assertTrue("${set.name} ${c.id}", c.x in 0f..1f && c.y in 0f..1f)
            }
        }
        for (set in listOf(KeySet.GAME, KeySet.MAP)) {
            val controls = Keys.controls(set).filter { it.shown }
            assertNotNull(controls.find { it.id == Keys.DRAWER && !it.inDrawer })
            assertTrue(controls.any { (it.kind as? Kind.ToolKey)?.tool == Tool.ADJUST && it.inDrawer })
        }
        val window = Keys.controls(KeySet.WINDOW).filter { it.shown }.map { it.id }
        assertEquals(listOf("close", "shift", "ctrl"), window)
        assertTrue(Keys.controls(KeySet.GAME).filter { it.id == "shift" || it.id == "ctrl" }.all { it.latch })
    }

    @Test fun `a set's changes come back as they were saved`() {
        val before = Keys.controls(KeySet.GAME)
        before.first { it.id == "q" }.apply { x = 0.3f; y = 0.4f; scale = 1.2f; latch = true }
        before.first { it.id == "r" }.shown = false
        before.first { it.id == "p" }.shown = true
        before.first { it.id == "f" }.inDrawer = false
        val json = JSONObject(LayoutJson.write(before).toString())
        val after = Keys.controls(KeySet.GAME)
        LayoutJson.read(json, after)()
        for ((a, b) in before.zip(after)) {
            assertEquals(a.id, a.shown, b.shown)
            assertEquals(a.id, a.inDrawer, b.inDrawer)
            assertEquals(a.id, a.latch, b.latch)
            assertEquals(a.id, a.x, b.x, 0.0001f)
            assertEquals(a.id, a.y, b.y, 0.0001f)
            assertEquals(a.id, a.scale, b.scale, 0.0001f)
        }
    }

    @Test fun `an unchanged set saves nothing`() {
        assertEquals(0, LayoutJson.write(Keys.controls(KeySet.MAP)).length())
    }

    @Test fun `the pad's saved layout still reads`() {
        val saved = JSONObject("""{"opacity":0.55,"hidden":false,"controls":{"A":[0.8,0.9,1.3]},"removed":["LB"]}""")
        val controls = defaultControls()
        LayoutJson.read(saved, controls)()
        assertFalse(controls.first { it.id == "LB" }.shown)
        assertEquals(1.3f, controls.first { it.id == "A" }.scale, 0.0001f)
        assertTrue(controls.filter { it.id != "LB" }.all { it.shown })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a broken place is refused before anything changes`() {
        LayoutJson.read(JSONObject("""{"controls":{"q":[0.1,0.2]}}"""), Keys.controls(KeySet.GAME))
    }
}
