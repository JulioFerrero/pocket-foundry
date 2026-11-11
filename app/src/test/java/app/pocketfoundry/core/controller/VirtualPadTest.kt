package app.pocketfoundry.core.controller

import app.pocketfoundry.core.controller.VirtualPad.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VirtualPadTest {
    @Before fun rest() = Source.entries.forEach(VirtualPad::release)

    @Test fun `a button stays down while any source holds it`() {
        VirtualPad.button(Source.GAMEPAD, PadButton.SOUTH, true)
        VirtualPad.button(Source.SCREEN, PadButton.SOUTH, true)
        VirtualPad.button(Source.SCREEN, PadButton.SOUTH, false)
        assertTrue(VirtualPad.isDown(PadButton.SOUTH))
        VirtualPad.button(Source.GAMEPAD, PadButton.SOUTH, false)
        assertFalse(VirtualPad.isDown(PadButton.SOUTH))
    }

    @Test fun `releasing a source leaves the others' presses`() {
        VirtualPad.button(Source.GAMEPAD, PadButton.EAST, true)
        VirtualPad.axis(Source.GAMEPAD, PadAxis.RIGHT_TRIGGER, VirtualPad.AXIS_MAX)
        VirtualPad.button(Source.SCREEN, PadButton.NORTH, true)
        VirtualPad.release(Source.SCREEN)
        assertTrue(VirtualPad.isDown(PadButton.EAST))
        assertEquals(VirtualPad.AXIS_MAX, VirtualPad.valueOf(PadAxis.RIGHT_TRIGGER))
        assertFalse(VirtualPad.isDown(PadButton.NORTH))
    }

    @Test fun `an axis goes as far as the source pushing it furthest`() {
        VirtualPad.axis(Source.SCREEN, PadAxis.LEFT_X, 1000)
        VirtualPad.axis(Source.GAMEPAD, PadAxis.LEFT_X, -20000)
        assertEquals(-20000, VirtualPad.valueOf(PadAxis.LEFT_X))
        VirtualPad.axis(Source.GAMEPAD, PadAxis.LEFT_X, 0)
        assertEquals(1000, VirtualPad.valueOf(PadAxis.LEFT_X))
    }

    @Test fun `a fraction scales to the axis and stops at full tilt`() {
        VirtualPad.axis(Source.GAMEPAD, PadAxis.RIGHT_Y, 0.5f)
        assertEquals(16384, VirtualPad.valueOf(PadAxis.RIGHT_Y))
        VirtualPad.axis(Source.GAMEPAD, PadAxis.RIGHT_Y, -3f)
        assertEquals(-VirtualPad.AXIS_MAX, VirtualPad.valueOf(PadAxis.RIGHT_Y))
    }

    @Test fun `the D-pad says when a direction went down`() {
        assertTrue(VirtualPad.dpad(Source.SCREEN, 0, DpadDir.UP.bit))
        assertTrue(VirtualPad.isDown(PadButton.DPAD_UP))
        assertFalse(VirtualPad.dpad(Source.SCREEN, DpadDir.UP.bit, 0))
        assertFalse(VirtualPad.isDown(PadButton.DPAD_UP))
    }
}
