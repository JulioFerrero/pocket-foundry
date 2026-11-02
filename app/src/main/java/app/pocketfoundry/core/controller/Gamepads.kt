package app.pocketfoundry.core.controller

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Vibrator
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.pocketfoundry.core.system.mainHandler
import app.pocketfoundry.ui.controller.DpadDir
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real controllers, all sharing the one virtual pad; no dead zone here, the game applies its own. A pad is in the
 * player's hands ([onInUse]) from first use until the last one is unplugged.
 */
class Gamepads(private val context: Context, private val onHome: () -> Unit,
               private val onInUse: (Boolean) -> Unit) : InputManager.InputDeviceListener {
    private val input = context.getSystemService(InputManager::class.java)
    private var inUse = false
    private val held = BooleanArray(PadButton.entries.size)
    private val sent = IntArray(PadAxis.entries.size)
    private var hat = 0

    init {
        input.registerInputDeviceListener(this, mainHandler)
        // A handheld's own controls are in hand from the start.
        if (gamepads(context).any { !it.isExternal }) used()
    }

    fun key(event: KeyEvent): Boolean {
        val device = event.device?.takeIf(::isGamepad) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onHome()
            return true
        }
        TRIGGER_KEYS[event.keyCode]?.let { trigger ->
            val analog = if (trigger == PadAxis.LEFT_TRIGGER) analogLeft(device) else analogRight(device)
            // Analog triggers also send an axis: the key only stands in when there is none.
            if (event.repeatCount == 0 && !analog) {
                setAxis(trigger, if (down) InputBridge.AXIS_MAX else 0)
                if (down) used()
            }
            return true
        }
        val button = BUTTONS[event.keyCode] ?: return false
        if (event.repeatCount > 0) return true
        held[button.ordinal] = down
        InputBridge.button(button, down)
        if (down) used()
        return true
    }

    fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val device = event.device?.takeIf(::isGamepad) ?: return false
        val rightStickOnZ = device.getMotionRange(MotionEvent.AXIS_Z) != null
        tilt(PadAxis.LEFT_X, event.getAxisValue(MotionEvent.AXIS_X))
        tilt(PadAxis.LEFT_Y, event.getAxisValue(MotionEvent.AXIS_Y))
        tilt(PadAxis.RIGHT_X, event.getAxisValue(if (rightStickOnZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX))
        tilt(PadAxis.RIGHT_Y, event.getAxisValue(if (rightStickOnZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY))
        // A trigger without an analog axis is driven by its key: a zero here would release it.
        if (analogLeft(device)) tilt(PadAxis.LEFT_TRIGGER, maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                                                                 event.getAxisValue(MotionEvent.AXIS_BRAKE)))
        if (analogRight(device)) tilt(PadAxis.RIGHT_TRIGGER, maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                                                                   event.getAxisValue(MotionEvent.AXIS_GAS)))
        setHat(DpadDir.state(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), 0.5f))
        return true
    }

    fun release() {
        for (button in PadButton.entries) {
            if (held[button.ordinal]) InputBridge.button(button, false)
        }
        held.fill(false)
        setHat(0)
        PadAxis.entries.forEach { setAxis(it, 0) }
    }

    fun close() {
        input.unregisterInputDeviceListener(this)
        release()
    }

    override fun onInputDeviceAdded(deviceId: Int) {}
    override fun onInputDeviceChanged(deviceId: Int) {}
    override fun onInputDeviceRemoved(deviceId: Int) {
        if (!inUse || gamepadConnected(context)) return
        inUse = false
        release()
        onInUse(false)
    }

    private fun used() {
        if (inUse) return
        inUse = true
        onInUse(true)
    }

    /** A stick or trigger at [value], from -1 to 1. */
    private fun tilt(axis: PadAxis, value: Float) {
        setAxis(axis, (value.coerceIn(-1f, 1f) * InputBridge.AXIS_MAX).roundToInt())
        if (abs(value) > 0.5f) used()
    }

    private fun setAxis(axis: PadAxis, value: Int) {
        if (sent[axis.ordinal] == value) return
        sent[axis.ordinal] = value
        InputBridge.axis(axis, value)
    }

    private fun setHat(bits: Int) {
        if (DpadDir.send(hat, bits)) used()
        hat = bits
    }

    companion object {
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
            // Some pads send the D-pad as keys rather than a hat.
            KeyEvent.KEYCODE_DPAD_UP to PadButton.DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to PadButton.DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to PadButton.DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to PadButton.DPAD_RIGHT,
        )
        private val TRIGGER_KEYS = mapOf(KeyEvent.KEYCODE_BUTTON_L2 to PadAxis.LEFT_TRIGGER,
                                         KeyEvent.KEYCODE_BUTTON_R2 to PadAxis.RIGHT_TRIGGER)
        // A pad may report a trigger on either of two axes.
        private fun analogLeft(device: InputDevice) = hasEither(device, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        private fun analogRight(device: InputDevice) = hasEither(device, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        private fun hasEither(device: InputDevice, a: Int, b: Int) =
            device.getMotionRange(a) != null || device.getMotionRange(b) != null
    }
}

/**
 * Needs gamepad buttons, not just joystick axes (some phones have those built in). A built-in one also needs
 * sticks: some fingerprint sensors claim gamepad buttons (uinput-fortsense, uinput-xiaomi).
 */
fun isGamepad(device: InputDevice) =
    !device.isVirtual && device.supportsSource(InputDevice.SOURCE_GAMEPAD) && (device.isExternal || hasSticks(device))

private fun hasSticks(device: InputDevice) =
    device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null &&
        device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK) != null

fun gamepads(context: Context) = devices(context).filter(::isGamepad)

fun gamepadConnected(context: Context) = gamepads(context).any()

fun gamepadVibrator(context: Context): Vibrator? = gamepads(context)
    .map { if (Build.VERSION.SDK_INT >= 31) it.vibratorManager.defaultVibrator else @Suppress("DEPRECATION") it.vibrator }
    .firstOrNull { it.hasVibrator() }

/** A keyboard connected at game start, with no gamepad, puts Factorio in keyboard-and-mouse mode (see Session). */
fun keyboardConnected(context: Context) = devices(context).any {
    !it.isVirtual && it.isExternal && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
}

private fun devices(context: Context): Sequence<InputDevice> {
    val input = context.getSystemService(InputManager::class.java)
    return input.inputDeviceIds.asSequence().mapNotNull { input.getInputDevice(it) }
}
