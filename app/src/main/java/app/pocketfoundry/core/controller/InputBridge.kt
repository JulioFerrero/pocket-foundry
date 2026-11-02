package app.pocketfoundry.core.controller

import android.net.LocalSocket
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.serve
import java.io.DataInputStream
import java.io.IOException

/** SDL_GamepadButton, in SDL's order. */
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
    /** A pad axis at full tilt, or a trigger fully pulled. */
    const val AXIS_MAX = 32767
    private const val AXIS_MIN = -32768
    /** SDL resends a held rumble every 2 s, so this lasts a little longer. */
    private const val RUMBLE_MS = 2500L

    @Volatile private var client: LocalSocket? = null
    /** The message being sent; only under the lock. */
    private val message = ByteArray(4)

    val connected get() = client != null

    /** [rumbleTarget] is asked at each rumble, as pads come and go; [enabled] is the player's setting. */
    fun start(paths: Paths, rumbleTarget: () -> Vibrator?, enabled: () -> Boolean) = serve(paths.inputSocket) { accepted ->
        client = accepted
        Log.i(TAG, "Gamepad plugged in")
        val received = ByteArray(4)
        var vibrator: Vibrator? = null
        try {
            val input = DataInputStream(accepted.inputStream)
            while (true) {
                input.readFully(received)
                if (received[0] != 'R'.code.toByte()) continue
                val target = rumbleTarget()
                if (target !== vibrator) vibrator?.cancel()
                vibrator = target
                val strength = rumbleStrength(received)
                // Lasts until the game sends 0.
                if (strength == 0 || !enabled()) target?.cancel()
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

    fun button(button: PadButton, down: Boolean) = send('B', button.ordinal, if (down) 1 else 0)

    fun axis(axis: PadAxis, value: Int) = send('A', axis.ordinal, wireValue(axis, value))

    /** SDL's virtual pad rests its triggers at -32768, where 0 is half pulled. */
    internal fun wireValue(axis: PadAxis, value: Int): Int {
        val trigger = axis == PadAxis.LEFT_TRIGGER || axis == PadAxis.RIGHT_TRIGGER
        return if (trigger) value.coerceIn(0, AXIS_MAX) * 2 + AXIS_MIN else value.coerceIn(AXIS_MIN, AXIS_MAX)
    }

    internal fun rumbleStrength(message: ByteArray) = (message[2].toInt() and 0xFF) or ((message[3].toInt() and 0x7F) shl 8)

    internal fun amplitude(strength: Int) = (strength * 255 / AXIS_MAX).coerceIn(1, 255)

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
