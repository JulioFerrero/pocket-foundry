package app.pocketfoundry.core.controller

/** [dx] and [dy] are where it points on screen, with y down. */
enum class DpadDir(val bit: Int, val button: PadButton, val dx: Float, val dy: Float) {
    UP(1, PadButton.DPAD_UP, 0f, -1f),
    DOWN(2, PadButton.DPAD_DOWN, 0f, 1f),
    LEFT(4, PadButton.DPAD_LEFT, -1f, 0f),
    RIGHT(8, PadButton.DPAD_RIGHT, 1f, 0f);

    companion object {
        /** An array, so a for loop over it allocates nothing. */
        val ALL = entries.toTypedArray()

        fun state(x: Float, y: Float, threshold: Float) =
            (if (y < -threshold) UP.bit else 0) or (if (y > threshold) DOWN.bit else 0) or
                (if (x < -threshold) LEFT.bit else 0) or (if (x > threshold) RIGHT.bit else 0)
    }
}
