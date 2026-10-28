package app.pocketfoundry.controller

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window

/** Back is taken here: Termux:X11 would otherwise swallow it. */
class TouchRouter(
    private val target: Window.Callback,
    private val controller: ControllerView,
    private val gestures: PointerGestures?,
    private val keyFirst: (KeyEvent) -> Boolean,
    private val motionFirst: (MotionEvent) -> Boolean,
    private val onBack: () -> Unit,
) : Window.Callback by target {

    private val claimed = HashSet<Int>()
    private val forwarded = HashSet<Int>()
    private var forwardedDownTime = 0L
    private val location = IntArray(2)

    override fun dispatchGenericMotionEvent(event: MotionEvent) =
        motionFirst(event) || target.dispatchGenericMotionEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyFirst(event)) return true
        // A mouse's back button stays a right click in the game.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return target.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (gestures != null && event.isFromSource(InputDevice.SOURCE_MOUSE)) return target.dispatchTouchEvent(event)
        controller.getLocationInWindow(location)
        val dx = location[0].toFloat()
        val dy = location[1].toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    claimed.clear()
                    forwarded.clear()
                    gestures?.cancel()   // in case the last touch's end went missing
                }
                val index = event.actionIndex
                val id = event.getPointerId(index)
                if (controller.pointerDown(id, event.getX(index) - dx, event.getY(index) - dy)) {
                    claimed += id
                    return true
                }
                forwarded += id
                gestures?.let { it.down(id, event.getX(index), event.getY(index)); return true }
                return forward(event, if (forwarded.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, id)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id in claimed) controller.pointerMove(id, event.getX(i) - dx, event.getY(i) - dy)
                    else if (gestures != null && id in forwarded) gestures.move(id, event.getX(i), event.getY(i))
                }
                return forwarded.isEmpty() || gestures != null || forward(event, MotionEvent.ACTION_MOVE, -1)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (claimed.remove(id)) {
                    controller.pointerUp(id)
                    return true
                }
                if (id !in forwarded) return true
                gestures?.let {
                    it.up(id, event.getX(event.actionIndex), event.getY(event.actionIndex))
                    forwarded -= id
                    return true
                }
                val handled = forward(event, if (forwarded.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id)
                forwarded -= id
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                claimed.forEach { controller.pointerUp(it) }
                claimed.clear()
                gestures?.cancel()
                val handled = forwarded.isEmpty() || gestures != null || forward(event, MotionEvent.ACTION_CANCEL, -1)
                forwarded.clear()
                return handled
            }
        }
        return target.dispatchTouchEvent(event)
    }

    private fun forward(event: MotionEvent, action: Int, actionId: Int): Boolean {
        if (action == MotionEvent.ACTION_DOWN) forwardedDownTime = event.eventTime
        if (claimed.isEmpty()) return target.dispatchTouchEvent(event)

        val indices = (0 until event.pointerCount).filter { event.getPointerId(it) in forwarded }
        val properties = Array(indices.size) { MotionEvent.PointerProperties().also { p -> event.getPointerProperties(indices[it], p) } }
        val coords = Array(indices.size) { MotionEvent.PointerCoords().also { c -> event.getPointerCoords(indices[it], c) } }
        var masked = action
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            val index = indices.indexOfFirst { event.getPointerId(it) == actionId }
            masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        val copy = MotionEvent.obtain(forwardedDownTime, event.eventTime, masked, indices.size, properties, coords,
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId,
            event.edgeFlags, event.source, event.flags)
        try {
            return target.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }
}
