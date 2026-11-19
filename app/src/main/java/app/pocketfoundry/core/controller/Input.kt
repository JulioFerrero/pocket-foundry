package app.pocketfoundry.core.controller

/** How a game is played, chosen as it starts: Factorio picks its input mode then, and keeps it. */
enum class Input {
    /** Factorio's gamepad mode: the on-screen pad, or a real controller. */
    PAD,
    /** Its keyboard-and-mouse mode with Mouse & keys' keys on screen (Settings.controls KEYS). */
    KEYS,
    /** Its keyboard-and-mouse mode with a real keyboard: the on-screen controls step aside. */
    KEYBOARD,
}
