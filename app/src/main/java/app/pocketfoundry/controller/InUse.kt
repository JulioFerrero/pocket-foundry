package app.pocketfoundry.controller

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/**
 * Whether a kind of device ([kind]: a controller, a keyboard) is in the player's hands: from its first
 * use, [used], or with [fromConnecting] from the moment one is connected, until the last one of that
 * kind is unplugged. [onChange] hears both.
 */
class InUse(context: Context, private val kind: (InputDevice?) -> Boolean, private val fromConnecting: Boolean = false,
            private val onChange: (Boolean) -> Unit) : InputManager.InputDeviceListener {
    private val input = context.getSystemService(InputManager::class.java)
    private var on = false

    init {
        input.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        if (fromConnecting && connected(input, kind)) used()
    }

    fun used() {
        if (on) return
        on = true
        onChange(true)
    }

    fun close() = input.unregisterInputDeviceListener(this)

    override fun onInputDeviceAdded(deviceId: Int) {
        if (fromConnecting && kind(input.getInputDevice(deviceId))) used()
    }
    override fun onInputDeviceChanged(deviceId: Int) {}
    override fun onInputDeviceRemoved(deviceId: Int) {
        if (!on || connected(input, kind)) return
        on = false
        onChange(false)
    }

    companion object {
        fun connected(context: Context, kind: (InputDevice?) -> Boolean) =
            connected(context.getSystemService(InputManager::class.java), kind)

        private fun connected(input: InputManager, kind: (InputDevice?) -> Boolean) =
            input.inputDeviceIds.any { kind(input.getInputDevice(it)) }
    }
}

/**
 * Real keyboards, USB or Bluetooth: they type straight into the game through Termux:X11, and a mouse
 * points there too. With one connected when the game starts, Factorio plays in its keyboard-and-mouse
 * mode (Session), and the drawn stick and keys step aside while one is connected. Not from its first
 * key, as with a controller: Termux:X11 sends a keyboard's keys through the phone's keyboard app, which
 * types them into the game without a key event anyone else sees.
 */
object Keyboards {
    /** A keyboard with letters, plugged in or paired: not the phone's own buttons, nor a controller's. */
    fun isKeyboard(device: InputDevice?) = device != null && !device.isVirtual && device.isExternal &&
        device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

    fun connected(context: Context) = InUse.connected(context, ::isKeyboard)
}
