package app.pocketfoundry.core.system

/** Loops at the idle priority that keep given cores looking busy, through JNI (app/src/main/cpp/busy_cores.c). */
object BusyCores {
    init {
        System.loadLibrary("pocketfoundry")
    }

    /** A handle for [stop], 0 if none started. */
    @JvmStatic external fun start(cores: IntArray): Long
    @JvmStatic external fun stop(handle: Long)
}
