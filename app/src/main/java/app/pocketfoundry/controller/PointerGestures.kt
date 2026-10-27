package app.pocketfoundry.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewConfiguration
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputStub
import com.termux.x11.input.RenderData
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * With touch controls, the fingers that land on the game rather than on a control: the screen
 * is the game's mouse, Factorio in its keyboard-and-mouse mode.
 *
 * - A tap clicks where it lands.
 * - A drag holds the left button from where it started: belts and pipes in a line, items across
 *   the inventory.
 * - A long press holds the right button: mining, for as long as the finger stays (and moves).
 *   [mining] makes every touch do that, for clearing an area.
 * - Two fingers turn the wheel: spreading them zooms in, sliding them scrolls a list. Tapped
 *   together, they right-click. In controller mode, where the game ignores the wheel for zoom,
 *   [zoom] presses its own zoom instead.
 *
 * Coordinates are the window's, as [TouchRouter] sees them.
 */
class PointerGestures private constructor(
    private val lorie: LorieView,
    private val renderData: RenderData,
    private val mining: () -> Boolean,
    private val onLongPress: () -> Unit,
    /** Notches in (positive) or out, when the wheel doesn't zoom; null: the wheel does. */
    private val zoom: ((Int) -> Unit)?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(lorie.context).scaledTouchSlop.toFloat()
    private val density = lorie.resources.displayMetrics.density
    private val location = IntArray(2)
    private val mapped = FloatArray(2)

    private val fingers = LinkedHashMap<Int, FloatArray>()   // id → x, y now
    private var downX = 0f
    private var downY = 0f
    private var held = InputStub.BUTTON_UNDEFINED
    /** Two fingers came down: the rest of this touch is theirs, until every finger lifts. */
    private var twoFinger = false
    private var twoFingerStart = 0L
    private var spanRef = 0f
    private var midStartX = 0f
    private var midStartY = 0f
    private var scrollRef = 0f
    private var mode = Mode.UNDECIDED

    private enum class Mode { UNDECIDED, PINCH, SCROLL, NONE }

    private val longPress = Runnable {
        if (fingers.size == 1 && held == InputStub.BUTTON_UNDEFINED && !twoFinger) {
            press(InputStub.BUTTON_RIGHT, downX, downY)
            onLongPress()
        }
    }

    fun down(id: Int, x: Float, y: Float) {
        fingers[id] = floatArrayOf(x, y)
        if (fingers.size == 1) {
            twoFinger = false
            mode = Mode.UNDECIDED
            downX = x
            downY = y
            if (mining()) {
                press(InputStub.BUTTON_RIGHT, x, y)
            } else {
                moveTo(x, y)   // what's under the finger shows in the game while it decides
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            return
        }
        // A drag or mining already under way keeps going; extra fingers just rest.
        if (held != InputStub.BUTTON_UNDEFINED || twoFinger || fingers.size != 2) return
        handler.removeCallbacks(longPress)
        twoFinger = true
        twoFingerStart = System.currentTimeMillis()
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
        if (held != InputStub.BUTTON_UNDEFINED) {
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
            if (mode == Mode.UNDECIDED && System.currentTimeMillis() - twoFingerStart < TAP_MS) {
                val both = fingers.values.toList()
                val a = both.first()
                val b = both.getOrElse(1) { a }
                moveTo((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                click(InputStub.BUTTON_RIGHT)
            }
            mode = Mode.NONE   // the other finger lifting does nothing more
        } else if (fingers.size == 1) {
            handler.removeCallbacks(longPress)
            if (held != InputStub.BUTTON_UNDEFINED) {
                release()
            } else {
                moveTo(x, y)
                click(InputStub.BUTTON_LEFT)
            }
        }
        fingers.remove(id)
        if (fingers.isEmpty()) twoFinger = false
    }

    /** Lets go of everything, e.g. when the game leaves the screen. */
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
                    zoom?.invoke(steps) ?: wheel(-steps)   // spreading zooms in: the wheel turned up
                    spanRef *= ZOOM_STEP.pow(steps)
                }
            }
            Mode.SCROLL -> {
                val step = SCROLL_STEP_DP * density
                val steps = ((scrollRef - midY) / step).toInt()
                if (steps != 0) {
                    wheel(steps)   // fingers up, the list moves up: scrolled down, as on a phone
                    scrollRef -= steps * step
                }
            }
            else -> {}
        }
    }

    // To the X server.

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

    private fun release() {
        if (held == InputStub.BUTTON_UNDEFINED) return
        lorie.sendMouseEvent(0f, 0f, held, false, false)
        held = InputStub.BUTTON_UNDEFINED
    }

    private fun click(button: Int) {
        lorie.sendMouseEvent(0f, 0f, button, true, false)
        lorie.sendMouseEvent(0f, 0f, button, false, false)
    }

    /** [notches] of the wheel: negative up, positive down (the X server counts 120 a notch). */
    private fun wheel(notches: Int) = lorie.sendMouseWheelEvent(0f, notches * 120f)

    companion object {
        private const val LONG_PRESS_MS = 300L
        private const val TAP_MS = 300L
        /** Spreading the fingers this much further apart is one notch of zoom. */
        private const val ZOOM_STEP = 1.18f
        private const val SCROLL_STEP_DP = 36f

        /**
         * Null if Termux:X11's input mapping can't be reached (its private fields renamed): touches
         * then reach the game the way Termux:X11 handles them.
         */
        fun attach(activity: MainActivity, mining: () -> Boolean, zoom: ((Int) -> Unit)?, onLongPress: () -> Unit): PointerGestures? = runCatching {
            // The view-to-X-screen mapping Termux:X11 keeps for its own touch handling, letterboxing included.
            val handler = MainActivity::class.java.getDeclaredField("mInputHandler").apply { isAccessible = true }.get(activity)!!
            val renderData = handler.javaClass.getDeclaredField("mRenderData").apply { isAccessible = true }.get(handler) as RenderData
            PointerGestures(activity.lorieView, renderData, mining, onLongPress, zoom)
        }.onFailure { Log.w("PocketFoundry", "Touch controls unavailable, touches go to Termux:X11", it) }.getOrNull()
    }
}
