package app.pocketfoundry.core.game

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The frames the game has handed over, as our SDL counts them at each buffer swap (runtime/sdl/pocketfoundry): every
 * driver's frames pass there, so the FPS counter reads the same whichever one draws. [file] holds [MAGIC], the count
 * (a little-endian uint32 that wraps), the time of the last swap (CLOCK_MONOTONIC nanoseconds, as System.nanoTime) and
 * a sequence that is odd while those two change.
 */
class FrameCounter(private val file: File) {
    private var map: ByteBuffer? = null

    /** A count and the time of its last frame. */
    class Sample(val frames: Long, val swappedNs: Long)

    /** The frames so far and when the last one was swapped, or null before the game's first frame made the file. */
    fun sample(): Sample? {
        val map = map ?: open() ?: return null
        repeat(TRIES) {
            val before = map.getInt(SEQUENCE)
            if (before and 1 != 0) return@repeat
            val frames = map.getInt(FRAMES).toLong() and 0xFFFF_FFFFL
            val swapped = map.getLong(SWAPPED)
            if (map.getInt(SEQUENCE) == before) return Sample(frames, swapped)
        }
        return null
    }

    private fun open(): ByteBuffer? = runCatching {
        if (file.length() < SIZE) return null
        RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, SIZE) }
            .order(ByteOrder.LITTLE_ENDIAN)
            .takeIf { buffer -> String(CharArray(MAGIC.length) { buffer.get(it).toInt().toChar() }) == MAGIC }
    }.getOrNull()?.also { map = it }

    companion object {
        const val MAGIC = "PFF2"
        private const val FRAMES = 4
        private const val SWAPPED = 8
        private const val SEQUENCE = 16
        private const val SIZE = 24L
        /** A swap takes nanoseconds: a read that keeps overlapping one gives up for this tick. */
        private const val TRIES = 4
    }
}

/**
 * Frames a second, sampled every [TICK_MS] for the counter's number and its graph of the last ten seconds. The rate is
 * the frames between two samples over the time between their last frames, which SDL stamps: exact however late a
 * tick runs, so a steady 60 shows 60. A count lower than the last one is a new game: it starts from there.
 */
class FrameRate {
    private val samples = ArrayDeque<FrameCounter.Sample>()
    private val history = ArrayDeque<Float>()

    /** The rate over about the last second; 0 until there's one, or when the game stops drawing. */
    var current = 0f
        private set

    /** [sample] taken at [nowNs] (System.nanoTime, the clock SDL stamps with). */
    fun add(sample: FrameCounter.Sample?, nowNs: Long) {
        if (sample == null) return
        if (samples.isNotEmpty() && sample.frames < samples.last().frames) samples.clear()
        samples.addLast(sample)
        while (samples.size > WINDOW_TICKS + 1) samples.removeFirst()
        val first = samples.first()
        val frames = sample.frames - first.frames
        val span = sample.swappedNs - first.swappedNs
        current = when {
            // No frame for longer than the window: the game has stopped drawing, whatever it did before.
            nowNs - sample.swappedNs > WINDOW_NS -> 0f
            frames <= 0 || span <= 0 -> current.takeIf { samples.size <= 1 } ?: 0f
            else -> (frames * 1e9 / span).toFloat()
        }
        if (samples.size > 1) {
            history.addLast(current)
            while (history.size > SAMPLES) history.removeFirst()
        }
    }

    /** The rates of the last ten seconds, oldest first; fewer before ten seconds have passed. */
    fun history(): FloatArray = history.toFloatArray()

    companion object {
        /** Four updates a second: each redraws the game screen's overlay, which costs the app's main and render threads. */
        const val TICK_MS = 250L
        /** Ten seconds of graph. */
        const val SAMPLES = (10_000 / TICK_MS).toInt()
        private const val WINDOW_TICKS = (1000 / TICK_MS).toInt()
        private const val WINDOW_NS = 1_000_000_000L
    }
}
