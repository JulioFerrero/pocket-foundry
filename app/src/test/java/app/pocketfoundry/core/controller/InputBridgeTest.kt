package app.pocketfoundry.core.controller

import app.pocketfoundry.core.controller.VirtualPad.AXIS_MAX
import org.junit.Assert.assertEquals
import org.junit.Test

class InputBridgeTest {
    private fun rumble(low: Int, high: Int) = byteArrayOf('R'.code.toByte(), 0, low.toByte(), high.toByte())

    @Test fun `stick values pass through`() {
        assertEquals(-32768, InputBridge.wireValue(PadAxis.LEFT_X, -32768))
        assertEquals(0, InputBridge.wireValue(PadAxis.LEFT_Y, 0))
        assertEquals(AXIS_MAX, InputBridge.wireValue(PadAxis.RIGHT_Y, AXIS_MAX))
    }

    @Test fun `stick values are clamped to 16 bits`() {
        assertEquals(AXIS_MAX, InputBridge.wireValue(PadAxis.RIGHT_X, 40000))
        assertEquals(-32768, InputBridge.wireValue(PadAxis.LEFT_Y, -40000))
    }

    @Test fun `a released trigger rests at the bottom`() {
        assertEquals(-32768, InputBridge.wireValue(PadAxis.LEFT_TRIGGER, 0))
    }

    @Test fun `a half pulled trigger sits at zero`() {
        assertEquals(0, InputBridge.wireValue(PadAxis.RIGHT_TRIGGER, 16384))
    }

    @Test fun `a fully pulled trigger is one short of the top`() {
        // AXIS_MAX * 2 - 32768: SDL reads it back as 32766 of 32767.
        assertEquals(32766, InputBridge.wireValue(PadAxis.LEFT_TRIGGER, AXIS_MAX))
    }

    @Test fun `triggers are clamped to 0 to AXIS_MAX first`() {
        assertEquals(-32768, InputBridge.wireValue(PadAxis.LEFT_TRIGGER, -5))
        assertEquals(32766, InputBridge.wireValue(PadAxis.RIGHT_TRIGGER, 99999))
    }

    @Test fun `rumble strength is the message's little-endian value`() {
        assertEquals(0x1234, InputBridge.rumbleStrength(rumble(0x34, 0x12)))
        assertEquals(0, InputBridge.rumbleStrength(rumble(0, 0)))
    }

    @Test fun `rumble strength drops the top bit`() {
        // Our SDL halves its 16-bit strength first, so the top bit is never set.
        assertEquals(AXIS_MAX, InputBridge.rumbleStrength(rumble(0xFF, 0xFF)))
        assertEquals(0, InputBridge.rumbleStrength(rumble(0, 0x80)))
    }

    @Test fun `full strength is full amplitude`() {
        assertEquals(255, InputBridge.amplitude(AXIS_MAX))
    }

    @Test fun `amplitude scales with strength`() {
        assertEquals(127, InputBridge.amplitude(16384))
        assertEquals(63, InputBridge.amplitude(8192))
    }

    @Test fun `the weakest rumble still vibrates`() {
        assertEquals(1, InputBridge.amplitude(1))
        assertEquals(1, InputBridge.amplitude(0))
    }

    @Test fun `amplitude stops at 255`() {
        assertEquals(255, InputBridge.amplitude(65535))
    }
}
