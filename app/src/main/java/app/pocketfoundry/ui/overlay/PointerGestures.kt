package app.pocketfoundry.ui.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewConfiguration
import app.pocketfoundry.core.bridge.PadButton
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputStub
import com.termux.x11.input.RenderData
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * Fingers on the game, in window coordinates. With [pad] (controller mode) Factorio's mouse clicks only
 * in menus, so gestures press the pad's buttons where the finger is.
 */
class PointerGestures private constructor(
    private val lorie: LorieView,
    private val renderData: RenderData,
    private val onLongPress: () -> Unit,
    private val pad: PadPresses?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val config = ViewConfiguration.get(lorie.context)
    private val slop = config.scaledTouchSlop.toFloat()
    private val doubleTapSlop = config.scaledDoubleTapSlop.toFloat()
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val density = lorie.resources.displayMetrics.density
    private val location = IntArray(2)
    private val mapped = FloatArray(2)

    private val fingers = LinkedHashMap<Int, FloatArray>()   // id → x, y
    private var downX = 0f
    private var downY = 0f
    private var held = InputStub.BUTTON_UNDEFINED
    private var heldPad: PadButton? = null
    private val holding get() = held != InputStub.BUTTON_UNDEFINED || heldPad != null
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var twoFinger = false
    private var twoFingerStart = 0L
    private var spanRef = 0f
    private var midStartX = 0f
    private var midStartY = 0f
    private var scrollRef = 0f
    private var mode = Mode.UNDECIDED

    private enum class Mode { UNDECIDED, PINCH, SCROLL, NONE }

    private val longPress = Runnable {
        if (fingers.size != 1 || holding || twoFinger) return@Runnable
        if (pad != null) {
            moveTo(downX, downY)
            holdPad(PadButton.WEST)
        } else {
            press(InputStub.BUTTON_RIGHT, downX, downY)
        }
        onLongPress()
    }

    fun down(id: Int, x: Float, y: Float) {
        fingers[id] = floatArrayOf(x, y)
        if (fingers.size == 1) {
            twoFinger = false
            mode = Mode.UNDECIDED
            downX = x
            downY = y
            val now = SystemClock.uptimeMillis()
            if (pad != null && now - lastTapAt < doubleTapMs && hypot(x - lastTapX, y - lastTapY) < doubleTapSlop) {
                lastTapAt = 0
                moveTo(x, y)
                holdPad(PadButton.SOUTH)
            } else {
                moveTo(x, y)   // the game shows what's under the finger meanwhile
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            return
        }
        if (holding || twoFinger || fingers.size != 2) return
        handler.removeCallbacks(longPress)
        twoFinger = true
        twoFingerStart = SystemClock.uptimeMillis()
        val (a, b) = fingers.values.toList()
        spanRef = hypot(a[0] - b[0], a[1] - b[1])
        midStartX = (a[0] + b[0]) / 2
        midStartY = (a[1] + b[1]) / 2
        scrollRef = midStartY
    }

    fun move(id: Int, x: Float, y: Float) {
        val finger = fingers[id] ?: return
        finger[0] = x
        finger[1] = y
        if (twoFinger) {
            if (fingers.size >= 2) twoFingerMove()
            return
        }
        if (fingers.size != 1) return
        if (holding) {
            moveTo(x, y)
        } else if (hypot(x - downX, y - downY) > slop) {
            handler.removeCallbacks(longPress)
            press(InputStub.BUTTON_LEFT, downX, downY)
            moveTo(x, y)
        }
    }

    fun up(id: Int, x: Float, y: Float) {
        fingers[id]?.let { it[0] = x; it[1] = y }
        if (twoFinger) {
            if (mode == Mode.UNDECIDED && SystemClock.uptimeMillis() - twoFingerStart < TAP_MS) {
                if (pad != null) {
                    pad.tap(PadButton.EAST)
                } else {
                    val both = fingers.values.toList()
                    val a = both.first()
                    val b = both.getOrElse(1) { a }
                    moveTo((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                    click(InputStub.BUTTON_RIGHT)
                }
            }
            mode = Mode.NONE   // so the other finger's lift doesn't tap again
        } else if (fingers.size == 1) {
            handler.removeCallbacks(longPress)
            if (holding) {
                release()
            } else {
                moveTo(x, y)
                click(InputStub.BUTTON_LEFT)
                lastTapAt = SystemClock.uptimeMillis()
                lastTapX = x
                lastTapY = y
            }
        }
        fingers.remove(id)
        if (fingers.isEmpty()) twoFinger = false
    }

    fun cancel() {
        handler.removeCallbacks(longPress)
        release()
        fingers.clear()
        twoFinger = false
    }

    private fun twoFingerMove() {
        val (a, b) = fingers.values.take(2)
        val span = hypot(a[0] - b[0], a[1] - b[1])
        val midX = (a[0] + b[0]) / 2
        val midY = (a[1] + b[1]) / 2
        if (mode == Mode.UNDECIDED) {
            val threshold = 2 * slop
            mode = when {
                abs(span - spanRef) > threshold -> Mode.PINCH
                hypot(midX - midStartX, midY - midStartY) > threshold -> Mode.SCROLL
                else -> return
            }
            spanRef = span
            scrollRef = midY
            moveTo(midX, midY)   // the map view zooms around the pointer
        }
        when (mode) {
            Mode.PINCH -> {
                val steps = (ln(span / spanRef) / ln(ZOOM_STEP)).toInt()
                if (steps != 0) {
                    pad?.zoom(steps) ?: wheel(-steps)   // spreading zooms in: wheel up
                    spanRef *= ZOOM_STEP.pow(steps)
                }
            }
            Mode.SCROLL -> {
                val step = SCROLL_STEP_DP * density
                val steps = ((scrollRef - midY) / step).toInt()
                if (steps != 0) {
                    wheel(steps)   // fingers up scroll down, as on a phone
                    scrollRef -= steps * step
                }
            }
            else -> {}
        }
    }

    private fun moveTo(x: Float, y: Float) {
        lorie.getLocationInWindow(location)
        renderData.mapScreenPoint(x - location[0], y - location[1], mapped)
        lorie.sendMouseEvent(mapped[0], mapped[1], InputStub.BUTTON_UNDEFINED, false, false)
    }

    private fun press(button: Int, x: Float, y: Float) {
        moveTo(x, y)
        lorie.sendMouseEvent(0f, 0f, button, true, false)
        held = button
    }

    private fun holdPad(button: PadButton) {
        pad?.hold(button)
        heldPad = button
    }

    private fun release() {
        if (held != InputStub.BUTTON_UNDEFINED) lorie.sendMouseEvent(0f, 0f, held, false, false)
        held = InputStub.BUTTON_UNDEFINED
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

        /** Null if Termux:X11's private fields were renamed: touches then reach the game as Termux:X11 sends them. */
        fun attach(activity: MainActivity, pad: PadPresses?, onLongPress: () -> Unit): PointerGestures? = runCatching {
            // Termux:X11's own view-to-X-screen mapping, letterboxing included.
            val handler = MainActivity::class.java.getDeclaredField("mInputHandler").apply { isAccessible = true }.get(activity)!!
            val renderData = handler.javaClass.getDeclaredField("mRenderData").apply { isAccessible = true }.get(handler) as RenderData
            PointerGestures(activity.lorieView, renderData, onLongPress, pad)
        }.onFailure { Log.w("PocketFoundry", "Touch controls unavailable, touches go to Termux:X11", it) }.getOrNull()
    }
}
