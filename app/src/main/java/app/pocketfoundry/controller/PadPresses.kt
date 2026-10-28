package app.pocketfoundry.controller

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.pocketfoundry.InputBridge
import app.pocketfoundry.PadAxis
import app.pocketfoundry.PadButton
import kotlin.math.sign

/** With a pad, the game zooms only with RT held and the D-pad up (in) or down (out), once per notch. */
class PadPresses {
    private val handler = Handler(Looper.getMainLooper())
    private val pressedAt = HashMap<PadButton, Long>()
    private var pending = 0
    private var zooming = false

    fun hold(button: PadButton) {
        handler.removeCallbacksAndMessages(button)   // a release still waiting would end this hold
        InputBridge.button(button, true)
        pressedAt[button] = SystemClock.uptimeMillis()
    }

    fun release(button: PadButton) {
        val at = (pressedAt.remove(button) ?: 0L) + MIN_HOLD_MS
        handler.postAtTime({ InputBridge.button(button, false) }, button, maxOf(at, SystemClock.uptimeMillis()))
    }

    fun tap(button: PadButton) {
        hold(button)
        release(button)
    }

    /** [notches] in (positive) or out (negative). */
    fun zoom(notches: Int) {
        pending += notches
        if (!zooming) nextZoom()
    }

    private fun nextZoom() {
        if (pending == 0) {
            zooming = false
            return
        }
        zooming = true
        val direction = if (pending > 0) PadButton.DPAD_UP else PadButton.DPAD_DOWN
        pending -= pending.sign
        InputBridge.axis(PadAxis.RIGHT_TRIGGER, 32767)
        InputBridge.button(direction, true)
        handler.postDelayed({
            InputBridge.button(direction, false)
            InputBridge.axis(PadAxis.RIGHT_TRIGGER, 0)
            handler.postDelayed(::nextZoom, MIN_HOLD_MS - 10)
        }, MIN_HOLD_MS)
    }

    companion object {
        /** Three game ticks, so the game sees the press. */
        private const val MIN_HOLD_MS = 50L
    }
}
