package app.pocketfoundry.controller

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/** Whether a kind of device is in the player's hands: from first use (or connection) until the last is unplugged. */
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

/** In use once connected: Termux:X11 types a keyboard's keys through the IME, with no key event we can see. */
object Keyboards {
    fun isKeyboard(device: InputDevice?) = device != null && !device.isVirtual && device.isExternal &&
        device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

    fun connected(context: Context) = InUse.connected(context, ::isKeyboard)
}
