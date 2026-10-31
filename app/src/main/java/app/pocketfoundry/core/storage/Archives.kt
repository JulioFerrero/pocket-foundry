package app.pocketfoundry.core.storage

import android.system.Os
import app.pocketfoundry.NativeXzInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

fun untarXz(input: InputStream, dest: File, strip: Int = 0) {
    val root = dest.canonicalFile
    val buffer = ByteArray(1 shl 20)
    TarArchiveInputStream(PrefetchInputStream(NativeXzInputStream(input))).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            val parts = entry.name.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.size <= strip) continue
            val target = File(root, parts.drop(strip).joinToString("/"))
            // The archive comes from the user; nothing may land outside dest.
            if (!target.canonicalPath.startsWith(root.path + File.separator) &&
                target.canonicalPath != root.path) {
                throw IOException("Archive entry escapes its folder: ${entry.name}")
            }
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

private class PrefetchInputStream(private val source: InputStream) : InputStream() {
    private class Chunk(val bytes: ByteArray, val length: Int, val error: Throwable? = null)

    private val chunks = ArrayBlockingQueue<Chunk>(8)
    @Volatile private var closed = false
    private var current: Chunk? = null
    private var pos = 0
    private val reader = thread(name = "xz-prefetch") {
        try {
            while (!closed) {
                val bytes = ByteArray(1 shl 20)
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

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var chunk = current
        while (chunk == null || pos == chunk.length) {
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

class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) :
    FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) onRead(++count) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
}
