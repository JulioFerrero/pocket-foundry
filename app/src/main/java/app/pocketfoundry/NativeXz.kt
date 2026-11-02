package app.pocketfoundry

/** liblzma through JNI (app/src/main/cpp/xz_jni.c). */
object NativeXz {
    init {
        System.loadLibrary("pocketfoundry")
    }

    @JvmStatic external fun open(): Long
    @JvmStatic external fun decode(handle: Long, input: ByteArray, inOff: Int, inLen: Int,
                                   out: ByteArray, outOff: Int, outLen: Int, inputEnded: Boolean): Long
    @JvmStatic external fun close(handle: Long)
}