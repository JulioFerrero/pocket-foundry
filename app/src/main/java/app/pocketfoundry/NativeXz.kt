package app.pocketfoundry

import java.io.IOException
import java.io.InputStream

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

/** Several times faster than the Java xz decoder. */
class NativeXzInputStream(private val input: InputStream) : InputStream() {
    private var handle = NativeXz.open().also { if (it == 0L) throw IOException("Couldn't start the xz decoder") }
    private val buffer = ByteArray(1 shl 20)
    private var pos = 0
    private var len = 0
    private var inputEnded = false
    private var ended = false
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) == 1) single[0].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (!ended) {
            if (pos == this.len && !inputEnded) {
                val n = input.read(buffer)
                if (n < 0) inputEnded = true
                pos = 0
                this.len = maxOf(n, 0)
            }
            val r = NativeXz.decode(handle, buffer, pos, this.len - pos, b, off, len, inputEnded)
            if (r < 0) throw IOException("The .xz data is damaged (liblzma error ${-r})")
            pos += ((r ushr 32) and 0x3FFFFFFF).toInt()
            val produced = (r and 0xFFFFFFFFL).toInt()
            if ((r ushr 62) and 1L == 1L) ended = true
            if (produced > 0) return produced
            if (inputEnded && pos == this.len && !ended) throw IOException("The .xz file is cut short")
        }
        return -1
    }

    override fun close() {
        if (handle != 0L) {
            NativeXz.close(handle)
            handle = 0L
        }
        input.close()
    }
}
