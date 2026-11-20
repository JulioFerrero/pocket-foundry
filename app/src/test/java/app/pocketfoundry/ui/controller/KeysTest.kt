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

    @Test fun `a key brings its set and takes it away again, Esc goes back to the game's`() {
        assertEquals(KeySets.WINDOW, KeySets.next(KeySets.GAME, KeySets.WINDOW))
        assertEquals(KeySets.GAME, KeySets.next(KeySets.WINDOW, KeySets.WINDOW))
        assertEquals(KeySets.MAP, KeySets.next(KeySets.GAME, KeySets.MAP))
        assertEquals(KeySets.GAME, KeySets.next(KeySets.MAP, KeySets.MAP))
        assertEquals(KeySets.WINDOW, KeySets.next(KeySets.MAP, KeySets.WINDOW))
        assertEquals(KeySets.GAME, KeySets.next(KeySets.WINDOW, KeySets.GAME))
        assertEquals("set-2", KeySets.next(KeySets.GAME, "set-2"))
    }

    @Test fun `every key has its own id, something to send or a sign, and a name`() {
        assertEquals(Keys.catalog.size, Keys.catalog.map { it.id }.toSet().size)
        for (def in Keys.catalog) {
            if (def.kind == Kind.Key) assertTrue(def.id, def.codes.isNotEmpty())
            else assertTrue(def.id, def.icon != null || def.label.isNotEmpty())
            assertTrue(def.id, def.name.isNotEmpty())
            assertTrue(def.id, def.units in Keys.WIDTHS.toList())
        }
    }

    @Test fun `a key is named as on a keyboard, the modifiers first`() {
        assertEquals("Ctrl+Z", KeyNames.label(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z)))
        assertEquals("Ctrl+Shift+F", KeyNames.label(KeyNames.withModifiers(
            listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_F)))
        assertEquals("Esc", KeyNames.label(intArrayOf(KeyEvent.KEYCODE_ESCAPE)))
        assertEquals("F11", KeyNames.label(intArrayOf(KeyEvent.KEYCODE_F11)))
        assertEquals("Shift", KeyNames.label(KeyNames.withModifiers(listOf(KeyEvent.KEYCODE_SHIFT_LEFT), KeyEvent.KEYCODE_SHIFT_LEFT)))
        assertEquals("Ctrl+Z", Keys.controls(KeySets.GAME).first { it.id == "undo" }.label)
    }

    @Test fun `letters have the pale cap, modifiers and combinations the dark one`() {
        assertTrue(KeyNames.light(intArrayOf(KeyEvent.KEYCODE_E)))
        assertTrue(KeyNames.light(intArrayOf(KeyEvent.KEYCODE_SPACE)))
        assertTrue(KeyNames.light(intArrayOf(KeyEvent.KEYCODE_SLASH)))
        assertFalse(KeyNames.light(intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT)))
        assertFalse(KeyNames.light(intArrayOf(KeyEvent.KEYCODE_ESCAPE)))
        assertFalse(KeyNames.light(intArrayOf(KeyEvent.KEYCODE_F1)))
        assertFalse(KeyNames.light(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z)))
    }

    @Test fun `the picker's keyboard names every key it offers`() {
        val codes = KeyNames.ROWS.flatMap { it.toList() }
        assertEquals(codes.size, codes.toSet().size)
        for (code in codes) assertTrue("$code", KeyNames.name(code) != "?")
    }

    @Test fun `a width is the nearest a keycap has`() {
        assertEquals(1f, Keys.WIDTHS[Keys.widthIndex(1.1f)])
        assertEquals(2.25f, Keys.WIDTHS[Keys.widthIndex(2.3f)])
        assertEquals("6.25", Keys.widthName(Keys.WIDTHS[Keys.widthIndex(9f)]))
        assertEquals("2", Keys.widthName(2f))
    }

    @Test fun `each set shows its keys inside the frame, and the game's and the map's have the editor`() {
        for (set in KeySets.BUILT_IN.keys) {
            val controls = Keys.controls(set)
            assertTrue(set, controls.any { it.shown })
            for (c in controls.filter { it.shown && !it.inDrawer }) {
                assertTrue("$set ${c.id}", c.x in 0f..1f && c.y in 0f..1f)
            }
        }
        for (set in listOf(KeySets.GAME, KeySets.MAP, "set-1")) {
            val controls = Keys.controls(set).filter { it.shown }
            assertNotNull(controls.find { it.id == Keys.DRAWER && !it.inDrawer })
            assertTrue(controls.any { (it.kind as? Kind.ToolKey)?.tool == Tool.ADJUST && it.inDrawer })
        }
        val window = Keys.controls(KeySets.WINDOW).filter { it.shown && !it.inDrawer }.map { it.id }
        assertEquals(listOf("close", "shift", "ctrl", Keys.NUMBERS), window)
        assertTrue(Keys.controls(KeySets.GAME).filter { it.id == "shift" || it.id == "ctrl" }.all { it.latch })
    }

    @Test fun `a set's changes come back as they were saved`() {
        val before = Keys.controls(KeySets.GAME)
        before.first { it.id == "q" }.apply { x = 0.3f; y = 0.4f; scale = 1.2f; latch = true }
        before.first { it.id == "r" }.shown = false
        before.first { it.id == "p" }.shown = true
        before.first { it.id == "alt" }.drawer = null
        before.first { it.id == "q" }.drawer = Keys.NUMBERS
        before.first { it.id == "t" }.apply { codes = intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_T); units = 1.5f; shows = null }
        val mine = Keys.own("k3", intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_F), 2.25f, "set-1").apply {
            x = 0.2f; y = 0.3f; latch = true
        }
        val all = before + mine
        val json = JSONObject(LayoutJson.write(all).toString())
        val after = Keys.controls(KeySets.GAME).toMutableList()
        LayoutJson.read(json, after)()
        assertEquals(all.size, after.size)
        val back = after.first { it.id == "k3" }
        assertEquals("Ctrl+F", back.label)
        assertEquals("Ctrl+F", back.name)
        assertEquals("set-1", back.shows)
        assertEquals(2.25f, back.units)
        assertEquals("Ctrl+T", after.first { it.id == "t" }.label)
        assertEquals(null, after.first { it.id == "t" }.shows)
        for ((a, b) in all.zip(after)) {
            assertTrue(a.id, a.codes.contentEquals(b.codes))
            assertEquals(a.id, a.units, b.units)
            assertEquals(a.id, a.shows, b.shows)
            assertEquals(a.id, a.shown, b.shown)
            assertEquals(a.id, a.drawer, b.drawer)
            assertEquals(a.id, a.latch, b.latch)
            assertEquals(a.id, a.x, b.x, 0.0001f)
            assertEquals(a.id, a.y, b.y, 0.0001f)
            assertEquals(a.id, a.scale, b.scale, 0.0001f)
        }
    }

    @Test fun `the number drawer has the digits, Backspace and Enter, in the game's and the inventory's sets`() {
        for (set in listOf(KeySets.GAME, KeySets.WINDOW)) {
            val numbers = Keys.controls(set).filter { it.shown && it.drawer == Keys.NUMBERS }.map { it.label }
            assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Backspace", "0", "Enter"), numbers)
        }
    }

    @Test fun `C is the next weapon and Space shoots, as Factorio has them`() {
        val keys = Keys.controls(KeySets.GAME)
        assertEquals("Next weapon", keys.first { it.label == "C" }.name)
        assertEquals("Shoot", keys.first { it.label == "Space" }.name)
    }

    @Test fun `the keys are a thumb's size and keep off the middle third, where the game is`() {
        assertEquals(0.125f, Keys.KEY)
        for (set in KeySets.BUILT_IN.keys) {
            for (c in Keys.controls(set).filter { it.shown && !it.inDrawer && it.kind is Kind.Key }) {
                assertTrue("$set ${c.id} at ${c.x}", c.x < 0.5f || c.x > 0.64f)
            }
        }
    }

    @Test fun `an unchanged set saves nothing`() {
        assertEquals(0, LayoutJson.write(Keys.controls(KeySets.MAP)).length())
    }

    @Test fun `the pad's saved layout still reads`() {
        val saved = JSONObject("""{"opacity":0.55,"hidden":false,"controls":{"A":[0.8,0.9,1.3]},"removed":["LB"]}""")
        val controls = defaultControls().toMutableList()
        LayoutJson.read(saved, controls)()
        assertFalse(controls.first { it.id == "LB" }.shown)
        assertEquals(1.3f, controls.first { it.id == "A" }.scale, 0.0001f)
        assertTrue(controls.filter { it.id != "LB" }.all { it.shown })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a broken place is refused before anything changes`() {
        LayoutJson.read(JSONObject("""{"controls":{"q":[0.1,0.2]}}"""), Keys.controls(KeySets.GAME).toMutableList())
    }
}
