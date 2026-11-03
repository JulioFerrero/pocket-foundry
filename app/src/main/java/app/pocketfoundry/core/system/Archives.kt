package app.pocketfoundry.core.system

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

fun untarXz(input: InputStream, dest: File, strip: Int = 0) {
    val root = dest.canonicalFile
    val buffer = ByteArray(MIB)
    TarArchiveInputStream(PrefetchInputStream(NativeXzInputStream(input))).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            val target = entryTarget(root, entry.name, strip) ?: continue
            when {
                entry.isDirectory -> target.mkdirs()
                entry.isSymbolicLink -> {
                    target.parentFile?.mkdirs()
                    target.delete()
                    Os.symlink(entry.linkName, target.path)
                }
                entry.isFile -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        while (true) {
                            val n = tar.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    if (entry.mode and 0b001_001_001 != 0) target.setExecutable(true, false)
                }
            }
        }
    }
}

/** Where an entry lands under [root], or null for the top folders [strip] drops. */
internal fun entryTarget(root: File, name: String, strip: Int): File? {
    val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.size <= strip) return null
    val target = File(root, parts.drop(strip).joinToString("/"))
    // The archive comes from the user; nothing may land outside root.
    val path = target.canonicalPath
    if (path != root.path && !path.startsWith(root.path + File.separator)) {
        throw IOException("Archive entry escapes its folder: $name")
    }
    return target
}

/** Decodes up to 8 MiB ahead on its own thread, so xz decoding overlaps disk writes. */
private class PrefetchInputStream(private val source: InputStream) : InputStream() {
    private class Chunk(val bytes: ByteArray, val length: Int, val error: Throwable? = null)

    private val chunks = ArrayBlockingQueue<Chunk>(8)
    @Volatile private var closed = false
    private var current: Chunk? = null
    private var pos = 0
    private val single = ByteArray(1)
    private val reader = thread(name = "xz-prefetch") {
        try {
            while (!closed) {
                val bytes = ByteArray(MIB)
                var filled = 0
                while (filled < bytes.size) {
                    val n = source.read(bytes, filled, bytes.size - filled)
                    if (n < 0) break
                    filled += n
                }
                chunks.put(Chunk(bytes, filled))
                if (filled < bytes.size) break
            }
        } catch (e: Throwable) {
            if (!closed) chunks.put(Chunk(ByteArray(0), 0, e))
        }
    }

    override fun read(): Int = if (read(single, 0, 1) == 1) single[0].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var chunk = current
        while (chunk == null || pos == chunk.length) {
            // A short chunk was the last.
            if (chunk != null && chunk.length < chunk.bytes.size) return -1
            chunk = chunks.take()
            chunk.error?.let { throw IOException(it.message, it) }
            current = chunk
            pos = 0
        }
        val n = minOf(len, chunk.length - pos)
        System.arraycopy(chunk.bytes, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() {
        closed = true
        chunks.clear()
        reader.interrupt()
        reader.join()   // the native decoder must not be freed while it's decoding
        source.close()
    }
}



/** Several times faster than the Java xz decoder. */
class NativeXzInputStream(private val input: InputStream) : InputStream() {
    private var handle = NativeXz.open().also { if (it == 0L) throw IOException("Couldn't start the xz decoder") }
    private val buffer = ByteArray(MIB)
    private var pos = 0
    private var filled = 0
    private var inputEnded = false
    private var ended = false
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) == 1) single[0].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (!ended) {
            if (pos == filled && !inputEnded) {
                val n = input.read(buffer)
                if (n < 0) inputEnded = true
                pos = 0
                filled = maxOf(n, 0)
            }
            val r = NativeXz.decode(handle, buffer, pos, filled - pos, b, off, len, inputEnded)
            if (r < 0) throw IOException("The .xz data is damaged (liblzma error ${-r})")
            pos += consumed(r)
            if (atEnd(r)) ended = true
            val n = produced(r)
            if (n > 0) return n
            if (inputEnded && pos == filled && !ended) throw IOException("The .xz file is cut short")
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

    /** NativeXz.decode's result: consumed << 32 | produced, with bit 62 set at the end of the data (xz_jni.c). */
    internal companion object {
        private const val END_BIT = 62

        fun consumed(r: Long) = ((r ushr 32) and 0x3FFFFFFF).toInt()
        fun produced(r: Long) = (r and 0xFFFFFFFFL).toInt()
        fun atEnd(r: Long) = (r ushr END_BIT) and 1L == 1L
    }
}

/** Tells [onPercent] how much of [size] bytes has been read, in whole percent, when that changes; nothing if [size] is unknown. */
class ProgressInputStream(input: InputStream, private val size: Long, private val onPercent: (Int) -> Unit) :
    FilterInputStream(input) {
    private var count = 0L
    private var percent = -1

    override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) counted(it) }

    private fun counted(bytes: Int) {
        count += bytes
        if (size <= 0) return
        val now = (count * 100 / size).toInt()
        if (now == percent) return
        percent = now
        onPercent(now)
    }
}
