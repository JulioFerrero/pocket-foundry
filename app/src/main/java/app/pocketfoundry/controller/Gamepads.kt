package app.pocketfoundry.controller

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import app.pocketfoundry.InputBridge
import app.pocketfoundry.PadAxis
import app.pocketfoundry.PadButton
import com.termux.x11.MainActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real controllers, USB or Bluetooth, playing through the game's virtual pad as the on-screen one does.
 * Android has already turned each into standard gamepad keys and axes (its key layout files), so an
 * Xbox pad, a DualSense or a phone clamp all arrive alike: A B X Y by position, the right stick on
 * Z and RZ. Several at once share the one virtual pad. The game applies its own dead zone.
 *
 * The controller's own home button (Xbox, PS) isn't the game's: it opens the launcher, [onHome], as Back does.
 *
 * [onInUse] says when one is played (true) and when the last is unplugged (false), for the on-screen
 * pad to step aside: on first use rather than on connecting, as a phone may list a controller nobody holds.
 */
class Gamepads private constructor(context: Context, private val onHome: () -> Unit,
                                   private val onInUse: (Boolean) -> Unit) : InputManager.InputDeviceListener {
    private val input = context.getSystemService(InputManager::class.java)
    private val held = HashSet<PadButton>()
    private val sent = IntArray(PadAxis.entries.size)
    private var hat = 0   // the D-pad when it's a hat: UP, DOWN, LEFT, RIGHT bits
    private var inUse = false

    init {
        input.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    /** A controller's button, sent to the game: true when it was one. */
    fun key(event: KeyEvent): Boolean {
        val device = event.device?.takeIf(::isGamepad) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onHome()
            return true
        }
        TRIGGER_KEYS[event.keyCode]?.let { trigger ->
            // Analog triggers send an axis as well: the key only stands in when there is none.
            if (event.repeatCount == 0 && !hasAxis(device, TRIGGER_AXES.getValue(trigger))) {
                setAxis(trigger, if (down) 32767 else 0)
                if (down) used()
            }
            return true
        }
        val button = BUTTONS[event.keyCode] ?: return false
        if (event.repeatCount > 0) return true
        if (down) held += button else held -= button
        InputBridge.button(button, down)
        if (down) used()
        return true
    }

    /** A controller's sticks, triggers and hat, sent to the game: true when it was one. */
    fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val device = event.device?.takeIf(::isGamepad) ?: return false
        val zr = device.getMotionRange(MotionEvent.AXIS_Z) != null
        val values = floatArrayOf(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.getAxisValue(if (zr) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX),
            event.getAxisValue(if (zr) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY),
            maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
            maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
        )
        PadAxis.entries.forEach { axis ->
            val value = values[axis.ordinal]
            // A trigger whose axis this pad lacks is left to its key.
            if (axis in TRIGGER_AXES && !hasAxis(device, TRIGGER_AXES.getValue(axis))) return@forEach
            setAxis(axis, (value.coerceIn(-1f, 1f) * 32767).roundToInt())
            if (abs(value) > 0.5f) used()
        }
        val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        setHat((if (y < -0.5f) UP else 0) or (if (y > 0.5f) DOWN else 0) or
               (if (x < -0.5f) LEFT else 0) or (if (x > 0.5f) RIGHT else 0))
        return true
    }

    /** Lets go of everything, e.g. when the game leaves the screen. */
    fun release() {
        held.forEach { InputBridge.button(it, false) }
        held.clear()
        setHat(0)
        PadAxis.entries.forEach { setAxis(it, 0) }
    }

    fun close() {
        input.unregisterInputDeviceListener(this)
        release()
    }

    private fun used() {
        if (inUse) return
        inUse = true
        onInUse(true)
    }

    private fun setAxis(axis: PadAxis, value: Int) {
        if (sent[axis.ordinal] == value) return
        sent[axis.ordinal] = value
        InputBridge.axis(axis, value)
    }

    private fun setHat(bits: Int) {
        if (bits == hat) return
        HAT_BUTTONS.forEach { (bit, button) ->
            if (bits and bit != hat and bit) InputBridge.button(button, bits and bit != 0)
        }
        hat = bits
        if (bits != 0) used()
    }

    override fun onInputDeviceAdded(deviceId: Int) {}
    override fun onInputDeviceChanged(deviceId: Int) {}
    override fun onInputDeviceRemoved(deviceId: Int) {
        if (!inUse || connected(input)) return
        release()
        inUse = false
        onInUse(false)
    }

    companion object {
        private const val TAG = "PocketFoundry"
        private const val UP = 1
        private const val DOWN = 2
        private const val LEFT = 4
        private const val RIGHT = 8
        private val HAT_BUTTONS = listOf(UP to PadButton.DPAD_UP, DOWN to PadButton.DPAD_DOWN,
                                         LEFT to PadButton.DPAD_LEFT, RIGHT to PadButton.DPAD_RIGHT)
        private val BUTTONS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to PadButton.SOUTH,
            KeyEvent.KEYCODE_BUTTON_B to PadButton.EAST,
            KeyEvent.KEYCODE_BUTTON_X to PadButton.WEST,
            KeyEvent.KEYCODE_BUTTON_Y to PadButton.NORTH,
            KeyEvent.KEYCODE_BUTTON_L1 to PadButton.LEFT_SHOULDER,
            KeyEvent.KEYCODE_BUTTON_R1 to PadButton.RIGHT_SHOULDER,
            KeyEvent.KEYCODE_BUTTON_THUMBL to PadButton.LEFT_STICK,
            KeyEvent.KEYCODE_BUTTON_THUMBR to PadButton.RIGHT_STICK,
            KeyEvent.KEYCODE_BUTTON_SELECT to PadButton.BACK,
            KeyEvent.KEYCODE_BUTTON_START to PadButton.START,
            // Pads whose D-pad is keys rather than a hat.
            KeyEvent.KEYCODE_DPAD_UP to PadButton.DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to PadButton.DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to PadButton.DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to PadButton.DPAD_RIGHT,
        )
        private val TRIGGER_KEYS = mapOf(KeyEvent.KEYCODE_BUTTON_L2 to PadAxis.LEFT_TRIGGER,
                                         KeyEvent.KEYCODE_BUTTON_R2 to PadAxis.RIGHT_TRIGGER)
        /** Each trigger's analog axes, either of which a pad may use. */
        private val TRIGGER_AXES = mapOf(
            PadAxis.LEFT_TRIGGER to intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
            PadAxis.RIGHT_TRIGGER to intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
        )

        private fun hasAxis(device: InputDevice, axes: IntArray) = axes.any { device.getMotionRange(it) != null }

        /** A controller: gamepad buttons, not just a joystick axis (a phone may have one of those built in). */
        fun isGamepad(device: InputDevice?) =
            device != null && !device.isVirtual && device.supportsSource(InputDevice.SOURCE_GAMEPAD)

        private fun connected(input: InputManager) = input.inputDeviceIds.any { isGamepad(input.getInputDevice(it)) }
        fun connected(context: Context) = connected(context.getSystemService(InputManager::class.java))

        /** The motor of the first controller that has one, for the game's rumble. */
        fun vibrator(context: Context): Vibrator? {
            val input = context.getSystemService(InputManager::class.java)
            return input.inputDeviceIds.asSequence()
                .mapNotNull { input.getInputDevice(it) }
                .filter(::isGamepad)
                .map { if (Build.VERSION.SDK_INT >= 31) it.vibratorManager.defaultVibrator else @Suppress("DEPRECATION") it.vibrator }
                .firstOrNull { it.hasVibrator() }
        }

        /**
         * Termux:X11 hands every key to the X server before the window sees it (LorieView's pre-IME dispatch,
         * through the activity's key listener): a controller's buttons go to the virtual pad first. If that
         * listener can't be reached (renamed), [TouchRouter] still offers them the keys that get past it.
         */
        fun attach(activity: MainActivity, onHome: () -> Unit, onInUse: (Boolean) -> Unit): Gamepads {
            val pads = Gamepads(activity, onHome, onInUse)
            runCatching {
                val field = MainActivity::class.java.getDeclaredField("mLorieKeyListener").apply { isAccessible = true }
                val keys = field.get(activity) as View.OnKeyListener
                val ours = View.OnKeyListener { view, code, event -> pads.key(event) || keys.onKey(view, code, event) }
                field.set(activity, ours)
                activity.lorieView.setOnKeyListener(ours)
            }.onFailure { Log.w(TAG, "Controller buttons may reach Termux:X11 as keys", it) }
            return pads
        }
    }
}
