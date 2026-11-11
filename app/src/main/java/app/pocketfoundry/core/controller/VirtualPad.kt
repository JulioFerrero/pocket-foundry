package app.pocketfoundry.core.controller

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one pad the game sees (InputBridge), pressed by several [Source]s at once. A button is down while any source
 * holds it and an axis goes as far as the source pushing it furthest, so one letting go never releases what another
 * holds. Only changes reach the game. Called on every touch: nothing here allocates.
 */
object VirtualPad {
    enum class Source { SCREEN, GAMEPAD, GESTURES, DEBUG }

    /** A stick at full tilt, or a trigger fully pulled. */
    const val AXIS_MAX = 32767

    private val SOURCES = Source.entries.size
    private val held = Array(SOURCES) { BooleanArray(PadButton.entries.size) }
    private val pushed = Array(SOURCES) { IntArray(PadAxis.entries.size) }
    private val sentButtons = BooleanArray(PadButton.entries.size)
    private val sentAxes = IntArray(PadAxis.entries.size)

    @Synchronized
    fun button(source: Source, button: PadButton, down: Boolean) {
        held[source.ordinal][button.ordinal] = down
        sendButton(button.ordinal)
    }

    /** [value] from -AXIS_MAX to AXIS_MAX for a stick, from 0 for a trigger. */
    @Synchronized
    fun axis(source: Source, axis: PadAxis, value: Int) {
        pushed[source.ordinal][axis.ordinal] = value
        sendAxis(axis.ordinal)
    }

    /** [value] from -1 to 1. */
    fun axis(source: Source, axis: PadAxis, value: Float) =
        axis(source, axis, (value.coerceIn(-1f, 1f) * AXIS_MAX).roundToInt())

    /** [old] and [new] are DpadDir bits. Returns whether one went down. */
    fun dpad(source: Source, old: Int, new: Int): Boolean {
        val changed = old xor new
        for (dir in DpadDir.ALL) {
            if (changed and dir.bit != 0) button(source, dir.button, new and dir.bit != 0)
        }
        return new and changed != 0
    }

    @Synchronized
    fun release(source: Source) {
        held[source.ordinal].fill(false)
        pushed[source.ordinal].fill(0)
        for (i in sentButtons.indices) sendButton(i)
        for (i in sentAxes.indices) sendAxis(i)
    }

    /** A new connection from the game's SDL starts with its pad at rest: it gets what's held now. */
    @Synchronized
    internal fun connected() {
        sentButtons.fill(false)
        sentAxes.fill(0)
        for (i in sentButtons.indices) sendButton(i)
        for (i in sentAxes.indices) sendAxis(i)
    }

    @Synchronized internal fun isDown(button: PadButton) = sentButtons[button.ordinal]
    @Synchronized internal fun valueOf(axis: PadAxis) = sentAxes[axis.ordinal]

    private fun sendButton(index: Int) {
        var down = false
        for (source in held) down = down || source[index]
        if (down == sentButtons[index]) return
        sentButtons[index] = down
        InputBridge.button(PadButton.entries[index], down)
    }

    private fun sendAxis(index: Int) {
        var value = 0
        for (source in pushed) if (abs(source[index]) > abs(value)) value = source[index]
        if (value == sentAxes[index]) return
        sentAxes[index] = value
        InputBridge.axis(PadAxis.entries[index], value)
    }
}
