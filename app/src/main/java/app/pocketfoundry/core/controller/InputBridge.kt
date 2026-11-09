package app.pocketfoundry.core.controller

import android.content.Context
import android.net.LocalSocket
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import app.pocketfoundry.core.runtime.Sdl
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.system.TAG
import java.io.DataInputStream
import java.io.IOException

/** SDL_GamepadButton, in SDL's order, up to the last one the pad of SDL_pocketfoundry.c has. */
enum class PadButton {
    SOUTH, EAST, WEST, NORTH, BACK, GUIDE, START, LEFT_STICK, RIGHT_STICK,
    LEFT_SHOULDER, RIGHT_SHOULDER, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT
}

/** SDL_GamepadAxis, in SDL's order. Sticks go -32768..32767, triggers 0..32767. */
enum class PadAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER }

/**
 * The virtual gamepad our SDL shows the game. 4-byte messages: a kind, an index and a little-endian 16-bit value.
 * To the game 'B' (button, 1 down or 0) and 'A' (axis); back from it 'R' (rumble strength, 0 stops).
 */
object InputBridge {
    private const val AXIS_MAX = VirtualPad.AXIS_MAX
    private const val AXIS_MIN = -32768
    /** SDL's 16-bit rumble strength, halved to fit the message (SDL_pocketfoundry.c). */
    private const val RUMBLE_MAX = 32767
    /** SDL resends a held rumble every 2 s, so this lasts a little longer. */
    private const val RUMBLE_MS = 2500L
    internal const val BUTTON = 'B'
    internal const val AXIS = 'A'
    internal const val RUMBLE = 'R'

    @Volatile private var client: LocalSocket? = null
    /** Only touched under the lock. */
    private val message = ByteArray(4)

    internal val connected get() = client != null

    /** The rumble's target is asked at each rumble, as pads come and go. */
    fun start(context: Context) = Sdl.serve(Paths(context).inputSocket) { accepted ->
        val phone = context.getSystemService(Vibrator::class.java)
        val settings = Settings(context)
        fun rumbleTarget() = gamepadVibrator(context) ?: phone
        client = accepted
        Log.i(TAG, "Gamepad plugged in")
        VirtualPad.connected()
        val received = ByteArray(4)
        var vibrator: Vibrator? = null
        try {
            val input = DataInputStream(accepted.inputStream)
            while (true) {
                input.readFully(received)
                if (received[0] != RUMBLE.code.toByte()) continue
                val target = rumbleTarget()
                if (target !== vibrator) vibrator?.cancel()
                vibrator = target
                val strength = rumbleStrength(received)
                // Lasts until the game sends 0.
                if (strength == 0 || !settings.vibration) target?.cancel()
                else target?.vibrate(VibrationEffect.createOneShot(RUMBLE_MS, amplitude(strength)))
            }
        } catch (e: IOException) {
            // Unplugged.
        } finally {
            vibrator?.cancel()
            client = null
            Log.i(TAG, "Gamepad unplugged")
        }
    }

    /** VirtualPad's: everything that presses the pad goes through it. */
    internal fun button(button: PadButton, down: Boolean) = send(BUTTON, button.ordinal, if (down) 1 else 0)

    internal fun axis(axis: PadAxis, value: Int) = send(AXIS, axis.ordinal, wireValue(axis, value))

    /** SDL's virtual pad rests its triggers at -32768, where 0 is half pulled. */
    internal fun wireValue(axis: PadAxis, value: Int): Int {
        val trigger = axis == PadAxis.LEFT_TRIGGER || axis == PadAxis.RIGHT_TRIGGER
        return if (trigger) value.coerceIn(0, AXIS_MAX) * 2 + AXIS_MIN else value.coerceIn(AXIS_MIN, AXIS_MAX)
    }

    internal fun rumbleStrength(message: ByteArray) = (message[2].toInt() and 0xFF) or ((message[3].toInt() and 0x7F) shl 8)

    internal fun amplitude(strength: Int) = (strength * 255 / RUMBLE_MAX).coerceIn(1, 255)

    private fun send(kind: Char, index: Int, value: Int) {
        val socket = client ?: return
        try {
            synchronized(this) {
                message[0] = kind.code.toByte()
                message[1] = index.toByte()
                message[2] = value.toByte()
                message[3] = (value shr 8).toByte()
                socket.outputStream.write(message)
            }
        } catch (e: IOException) {
            // The reader thread handles the disconnect.
        }
    }
}
