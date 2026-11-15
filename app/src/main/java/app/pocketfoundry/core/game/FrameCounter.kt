package app.pocketfoundry.core.game

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The frames the game has handed over, as our SDL counts them at each buffer swap (runtime/sdl/pocketfoundry): every
 * driver's frames pass there, so the FPS counter reads the same whichever one draws. [file] holds [MAGIC], then a
 * little-endian uint32 that wraps.
 */
class FrameCounter(private val file: File) {
    private var map: ByteBuffer? = null

    /** Frames so far, or null before the game's first frame made the file. */
    fun frames(): Long? {
        val map = map ?: open() ?: return null
        return map.getInt(4).toLong() and 0xFFFF_FFFFL
    }

    private fun open(): ByteBuffer? = runCatching {
        if (file.length() < SIZE) return null
        RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, SIZE) }
            .order(ByteOrder.LITTLE_ENDIAN)
            .takeIf { buffer -> String(CharArray(MAGIC.length) { buffer.get(it).toInt().toChar() }) == MAGIC }
    }.getOrNull()?.also { map = it }

    companion object {
        const val MAGIC = "PFF1"
        private const val SIZE = 8L
    }
}

/**
 * Frames a second over the last second, sampled every [TICK_MS] for [SAMPLES] ticks (ten seconds): the counter's
 * number and its graph. A count lower than the last one is a new game: it starts from there.
 */
class FrameRate {
    private val history = FloatArray(SAMPLES)
    private val perTick = IntArray(TICKS_PER_SECOND)
    private var ticks = 0
    private var last: Long? = null

    /** The rate now; 0 until a second has passed. */
    var current = 0f
        private set

    fun add(frames: Long?) {
        val previous = last
        last = frames
        if (frames == null || previous == null) return
        val made = if (frames >= previous) frames - previous else frames
        perTick[ticks % TICKS_PER_SECOND] = made.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ticks++
        val window = minOf(ticks, TICKS_PER_SECOND)
        current = perTick.take(window).sum() * 1000f / (window * TICK_MS)
        history[(ticks - 1) % SAMPLES] = current
    }

    /** The rates of the last ten seconds, oldest first; fewer before ten seconds have passed. */
    fun history(): FloatArray {
        val count = minOf(ticks, SAMPLES)
        return FloatArray(count) { history[(ticks - count + it) % SAMPLES] }
    }

    companion object {
        const val TICK_MS = 100L
        const val SAMPLES = 100
        private const val TICKS_PER_SECOND = (1000 / TICK_MS).toInt()
    }
}
