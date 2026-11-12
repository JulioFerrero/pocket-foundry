package app.pocketfoundry.ui.controller

import android.os.SystemClock
import android.view.ViewConfiguration
import app.pocketfoundry.core.controller.PadAxis
import app.pocketfoundry.core.controller.PadButton
import app.pocketfoundry.core.controller.VirtualPad
import app.pocketfoundry.core.controller.VirtualPad.Source.GESTURES
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.mainHandler
import app.pocketfoundry.ui.kit.dp
import com.termux.x11.MainActivity
import com.termux.x11.input.InputStub
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign

/**
 * Fingers on the game, in window coordinates. Tap: left click. Drag: left button held. Long press: right button held,
 * X with [padMode]. Two fingers: tap right-clicks (B with [padMode]), pinch zooms, drag scrolls. With [padMode],
 * double tap and hold holds A. [padMode] is controller mode, where Factorio's mouse clicks only in menus.
 *
 * A trackpad finger (Settings.touch TRACKPAD, read at each first finger, or one that lands on the BOX) moves the cursor
 * by how far it goes, like a laptop's trackpad, and what it does happens at the cursor, which the game shows; a drag
 * holds no button.
 */
class PointerGestures(activity: MainActivity, padMode: Boolean, private val onLongPress: () -> Unit) {
    private val settings = Settings(activity)
    private val lorie = activity.lorieView
    /** Termux:X11's own view-to-X-screen mapping, letterboxing included. */
    private val renderData = activity.mInputHandler.mRenderData
    private val pad = if (padMode) PadPresses() else null
    private val config = ViewConfiguration.get(lorie.context)
    private val slop = config.scaledTouchSlop.toFloat()
    private val doubleTapSlop = config.scaledDoubleTapSlop.toFloat()
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val scrollStep = lorie.context.dp(SCROLL_STEP_DP)
    private val location = IntArray(2)
    private val mapped = FloatArray(2)

    /** The fingers down, oldest first: the two oldest pinch and scroll. [xs] and [ys] say where each is, by id. */
    private val fingers = ArrayList<Int>(ControllerView.POINTER_IDS)
    private val xs = FloatArray(ControllerView.POINTER_IDS)
    private val ys = FloatArray(ControllerView.POINTER_IDS)
    private var downX = 0f
    private var downY = 0f
    private var heldMouse = InputStub.BUTTON_UNDEFINED
    private var heldPad: PadButton? = null
    private val holding get() = heldMouse != InputStub.BUTTON_UNDEFINED || heldPad != null
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var mode = Mode.ONE
    private var twoFingerStart = 0L
    private var spanRef = 0f
    private var midStartX = 0f
    private var midStartY = 0f
    private var scrollRef = 0f

    private var trackpad = false
    private var speed = 1f
    /** The trackpad's cursor, in window coordinates (-1: not placed yet), and where it was when the finger went down. */
    private var cursorX = -1f
    private var cursorY = -1f
    private var cursorDownX = 0f
    private var cursorDownY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    /** Where a finger acts: under it, or at the trackpad's cursor. */
    private val aimX get() = if (trackpad) cursorX else downX
    private val aimY get() = if (trackpad) cursorY else downY

    private enum class Mode { ONE, UNDECIDED, PINCH, SCROLL, LIFTED }

    private val longPress = Runnable {
        if (fingers.size != 1 || holding || mode != Mode.ONE) return@Runnable
        if (pad != null) {
            moveTo(aimX, aimY)
            holdPad(PadButton.WEST)
        } else {
            press(InputStub.BUTTON_RIGHT, aimX, aimY)
        }
        onLongPress()
    }

    /** [box]: on the trackpad box. */
    fun down(id: Int, x: Float, y: Float, box: Boolean = false) {
        fingers += id
        xs[id] = x
        ys[id] = y
        if (fingers.size == 1) firstFingerDown(x, y, box)
        else if (fingers.size == 2 && !holding && mode == Mode.ONE) secondFingerDown()
    }

    fun move(id: Int, x: Float, y: Float) {
        if (id !in fingers) return
        xs[id] = x
        ys[id] = y
        if (mode != Mode.ONE) {
            if (fingers.size >= 2) twoFingerMove()
            return
        }
        if (fingers.size != 1) return
        if (trackpad) {
            trackpadMove(x, y)
        } else if (holding) {
            moveTo(x, y)
        } else if (hypot(x - downX, y - downY) > slop) {
            mainHandler.removeCallbacks(longPress)
            press(InputStub.BUTTON_LEFT, downX, downY)
            moveTo(x, y)
        }
    }

    fun up(id: Int, x: Float, y: Float) {
        xs[id] = x
        ys[id] = y
        if (mode != Mode.ONE) twoFingerUp() else if (fingers.size == 1) oneFingerUp(x, y)
        fingers -= id
        if (fingers.isEmpty()) mode = Mode.ONE
    }

    fun cancel() {
        mainHandler.removeCallbacks(longPress)
        release()
        pad?.cancel()
        fingers.clear()
        mode = Mode.ONE
    }

    private fun firstFingerDown(x: Float, y: Float, box: Boolean) {
        downX = x
        downY = y
        trackpad = box || settings.touch == Settings.TRACKPAD
        speed = settings.trackpadSpeed / 100f
        if (trackpad) trackpadDown(x, y) else moveTo(x, y)   // the game shows what's under the finger
        val now = SystemClock.uptimeMillis()
        if (pad != null && now - lastTapAt < doubleTapMs && hypot(x - lastTapX, y - lastTapY) < doubleTapSlop) {
            lastTapAt = 0
            holdPad(PadButton.SOUTH)
        } else {
            mainHandler.postDelayed(longPress, LONG_PRESS_MS)
        }
    }

    private fun secondFingerDown() {
        mainHandler.removeCallbacks(longPress)
        mode = Mode.UNDECIDED
        twoFingerStart = SystemClock.uptimeMillis()
        spanRef = span()
        midStartX = midX()
        midStartY = midY()
    }

    private fun oneFingerUp(x: Float, y: Float) {
        mainHandler.removeCallbacks(longPress)
        if (holding) {
            release()
        } else if (trackpad) {
            if (dragged) return
            moveCursor(cursorDownX, cursorDownY)   // a tap's own wobble doesn't move it
            click(InputStub.BUTTON_LEFT)
            lastTapAt = SystemClock.uptimeMillis()
            lastTapX = x
            lastTapY = y
        } else {
            moveTo(x, y)
            click(InputStub.BUTTON_LEFT)
            lastTapAt = SystemClock.uptimeMillis()
            lastTapX = x
            lastTapY = y
        }
    }

    private fun twoFingerUp() {
        if (mode == Mode.UNDECIDED && SystemClock.uptimeMillis() - twoFingerStart < TAP_MS) {
            if (pad != null) {
                pad.tap(PadButton.EAST)
            } else {
                if (!trackpad) moveTo(midX(), midY())
                click(InputStub.BUTTON_RIGHT)
            }
        }
        mode = Mode.LIFTED   // so the other finger's lift doesn't tap again
    }

    private fun twoFingerMove() {
        val span = span()
        val midX = midX()
        val midY = midY()
        if (mode == Mode.UNDECIDED) {
            val threshold = 2 * slop
            mode = when {
                abs(span - spanRef) > threshold -> Mode.PINCH
                hypot(midX - midStartX, midY - midStartY) > threshold -> Mode.SCROLL
                else -> return
            }
            spanRef = span
            scrollRef = midY
            if (!trackpad) moveTo(midX, midY)   // the map view zooms around the pointer
        }
        when (mode) {
            Mode.PINCH -> {
                val steps = (ln(span / spanRef) / ln(ZOOM_STEP)).toInt()
                if (steps != 0) {
                    if (pad != null) pad.zoom(steps) else wheel(-steps)   // spreading zooms in: wheel up
                    spanRef *= ZOOM_STEP.pow(steps)
                }
            }
            Mode.SCROLL -> {
                val steps = ((scrollRef - midY) / scrollStep).toInt()
                if (steps != 0) {
                    wheel(steps)   // fingers up scroll down, as on a phone
                    scrollRef -= steps * scrollStep
                }
            }
            else -> {}
        }
    }

    private fun trackpadDown(x: Float, y: Float) {
        lastX = x
        lastY = y
        dragged = false
        if (cursorX < 0) {
            lorie.getLocationInWindow(location)
            moveCursor(location[0] + lorie.width / 2f, location[1] + lorie.height / 2f)
        }
        cursorDownX = cursorX
        cursorDownY = cursorY
    }

    /** Small movements count too, for aiming: a tap puts the cursor back where it was. */
    private fun trackpadMove(x: Float, y: Float) {
        if (!dragged && hypot(x - downX, y - downY) > slop) {
            dragged = true
            mainHandler.removeCallbacks(longPress)
        }
        moveCursor(cursorX + (x - lastX) * speed, cursorY + (y - lastY) * speed)
        lastX = x
        lastY = y
    }

    private fun moveCursor(x: Float, y: Float) {
        lorie.getLocationInWindow(location)
        cursorX = x.coerceIn(location[0].toFloat(), (location[0] + lorie.width - 1).toFloat())
        cursorY = y.coerceIn(location[1].toFloat(), (location[1] + lorie.height - 1).toFloat())
        moveTo(cursorX, cursorY)
    }

    private fun span() = hypot(xs[fingers[0]] - xs[fingers[1]], ys[fingers[0]] - ys[fingers[1]])
    private fun midX() = (xs[fingers[0]] + xs[fingers[1]]) / 2
    private fun midY() = (ys[fingers[0]] + ys[fingers[1]]) / 2

    private fun moveTo(x: Float, y: Float) {
        cursorX = x   // a trackpad finger goes on from here
        cursorY = y
        lorie.getLocationInWindow(location)
        renderData.mapScreenPoint(x - location[0], y - location[1], mapped)
        lorie.sendMouseEvent(mapped[0], mapped[1], InputStub.BUTTON_UNDEFINED, false, false)
    }

    private fun press(button: Int, x: Float, y: Float) {
        moveTo(x, y)
        lorie.sendMouseEvent(0f, 0f, button, true, false)
        heldMouse = button
    }

    private fun holdPad(button: PadButton) {
        pad?.hold(button)
        heldPad = button
    }

    private fun release() {
        if (heldMouse != InputStub.BUTTON_UNDEFINED) lorie.sendMouseEvent(0f, 0f, heldMouse, false, false)
        heldMouse = InputStub.BUTTON_UNDEFINED
        heldPad?.let { pad?.release(it) }
        heldPad = null
    }

    private fun click(button: Int) {
        lorie.sendMouseEvent(0f, 0f, button, true, false)
        lorie.sendMouseEvent(0f, 0f, button, false, false)
    }

    /** Negative up, positive down; the X server counts 120 a notch. */
    private fun wheel(notches: Int) = lorie.sendMouseWheelEvent(0f, notches * 120f)

    companion object {
        private const val LONG_PRESS_MS = 300L
        private const val TAP_MS = 300L
        /** Span ratio per notch of zoom. */
        private const val ZOOM_STEP = 1.18f
        private const val SCROLL_STEP_DP = 36f
    }
}

/**
 * Pad presses for [PointerGestures], each held at least [MIN_HOLD_MS] so the game sees it. The game zooms only with
 * RT held and D-pad up (in) or down (out), one press per notch.
 */
private class PadPresses {
    private val pressedAt = LongArray(PadButton.entries.size)
    /** Made once, so a release allocates nothing. */
    private val releases = PadButton.entries.map { Runnable { VirtualPad.button(GESTURES, it, false) } }
    private var pending = 0
    private var zooming = false
    private var notch = PadButton.DPAD_UP
    private val endNotch = Runnable {
        VirtualPad.button(GESTURES, notch, false)
        VirtualPad.axis(GESTURES, PadAxis.RIGHT_TRIGGER, 0)
        mainHandler.postDelayed(nextNotch, ZOOM_GAP_MS)
    }
    private val nextNotch = Runnable(::nextZoom)

    fun hold(button: PadButton) {
        mainHandler.removeCallbacks(releases[button.ordinal])
        VirtualPad.button(GESTURES, button, true)
        pressedAt[button.ordinal] = SystemClock.uptimeMillis()
    }

    fun release(button: PadButton) {
        val at = pressedAt[button.ordinal] + MIN_HOLD_MS
        mainHandler.postAtTime(releases[button.ordinal], maxOf(at, SystemClock.uptimeMillis()))
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

    fun cancel() {
        releases.forEach(mainHandler::removeCallbacks)
        mainHandler.removeCallbacks(endNotch)
        mainHandler.removeCallbacks(nextNotch)
        pending = 0
        zooming = false
        VirtualPad.release(GESTURES)
    }

    private fun nextZoom() {
        zooming = pending != 0
        if (!zooming) return
        notch = if (pending > 0) PadButton.DPAD_UP else PadButton.DPAD_DOWN
        pending -= pending.sign
        VirtualPad.axis(GESTURES, PadAxis.RIGHT_TRIGGER, VirtualPad.AXIS_MAX)
        VirtualPad.button(GESTURES, notch, true)
        mainHandler.postDelayed(endNotch, MIN_HOLD_MS)
    }

    companion object {
        /** Three game ticks, so the game sees the press. */
        private const val MIN_HOLD_MS = 50L
        /** Between notches: over two ticks, so the game sees D-pad and RT up before the next press. */
        private const val ZOOM_GAP_MS = 40L
    }
}
