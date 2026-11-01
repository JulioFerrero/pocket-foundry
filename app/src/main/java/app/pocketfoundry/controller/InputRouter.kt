package app.pocketfoundry.controller

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window

/**
 * The game window's input. A finger on a control goes to [ControllerView], other fingers to [PointerGestures]. Keys
 * and stick motion go to the pads first. Back opens the launcher: Termux:X11 would swallow it.
 */
class InputRouter(
    private val target: Window.Callback,
    private val controller: ControllerView,
    private val gestures: PointerGestures,
    private val keyFirst: (KeyEvent) -> Boolean,
    private val motionFirst: (MotionEvent) -> Boolean,
    private val onBack: () -> Unit,
) : Window.Callback by target {

    // Sets of pointer ids, a bit each (a HashSet would allocate): the fingers on the controller, and the others.
    private var claimed = 0
    private var forwarded = 0
    /** Where the controller is in the window. */
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
        // Termux:X11 handles a mouse as a mouse: the gestures would take it for a finger.
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return target.dispatchTouchEvent(event)
        controller.getLocationInWindow(location)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> onCancel()
            else -> return target.dispatchTouchEvent(event)
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            claimed = 0
            forwarded = 0
            gestures.cancel()   // in case the last touch's end went missing
        }
        val index = event.actionIndex
        val id = event.getPointerId(index)
        if (controller.pointerDown(id, event.getX(index) - location[0], event.getY(index) - location[1])) {
            claimed = claimed.with(id)
        } else {
            forwarded = forwarded.with(id)
            gestures.down(id, event.getX(index), event.getY(index))
        }
    }

    private fun onMove(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            if (claimed.has(id)) controller.pointerMove(id, event.getX(i) - location[0], event.getY(i) - location[1])
            else if (forwarded.has(id)) gestures.move(id, event.getX(i), event.getY(i))
        }
    }

    private fun onUp(event: MotionEvent) {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        if (claimed.has(id)) {
            claimed = claimed.without(id)
            controller.pointerUp(id)
        } else if (forwarded.has(id)) {
            gestures.up(id, event.getX(index), event.getY(index))
            forwarded = forwarded.without(id)
        }
    }

    private fun onCancel() {
        for (id in 0 until ControllerView.POINTER_IDS) {
            if (claimed.has(id)) controller.pointerUp(id)
        }
        claimed = 0
        forwarded = 0
        gestures.cancel()
    }
}

private fun Int.has(id: Int) = this and (1 shl id) != 0
private fun Int.with(id: Int) = this or (1 shl id)
private fun Int.without(id: Int) = this and (1 shl id).inv()
