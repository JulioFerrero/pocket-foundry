package app.pocketfoundry.controller

import android.os.Handler
import android.os.Looper
import app.pocketfoundry.InputBridge
import app.pocketfoundry.PadAxis
import app.pocketfoundry.PadButton
import kotlin.math.sign

/**
 * Pinching in controller mode, where Factorio ignores the mouse wheel: its own zoom, RT held with
 * the D-pad up (in) or down (out), pressed on the virtual gamepad once per notch, one after another.
 */
class PadZoom {
    private val handler = Handler(Looper.getMainLooper())
    private var pending = 0   // notches still to press: positive in, negative out
    private var pressing = false

    /** [notches] in (positive) or out (negative). */
    fun zoom(notches: Int) {
        pending += notches
        if (!pressing) next()
    }

    private fun next() {
        if (pending == 0) {
            pressing = false
            return
        }
        pressing = true
        val direction = if (pending > 0) PadButton.DPAD_UP else PadButton.DPAD_DOWN
        pending -= pending.sign
        InputBridge.axis(PadAxis.RIGHT_TRIGGER, 32767)
        InputBridge.button(direction, true)
        // A few game ticks each way, so the game sees every press.
        handler.postDelayed({
            InputBridge.button(direction, false)
            InputBridge.axis(PadAxis.RIGHT_TRIGGER, 0)
            handler.postDelayed(::next, GAP_MS)
        }, HOLD_MS)
    }

    companion object {
        private const val HOLD_MS = 50L
        private const val GAP_MS = 40L
    }
}
