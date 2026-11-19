package app.pocketfoundry.core.game

import app.pocketfoundry.core.game.FrameCounter.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateTest {
    private val frameNs = 1_000_000_000L / 60

    /**
     * A game drawing every [everyNs] from frame [from] at [startNs], looked at every tick that runs [lateNs] late, for
     * [ticks] ticks. Returns the frame count and the time where it stopped.
     */
    private fun FrameRate.play(ticks: Int, everyNs: Long = frameNs, lateNs: Long = 0, from: Long = 0,
                               startNs: Long = 0): Pair<Long, Long> {
        var now = startNs
        var frames = from
        repeat(ticks) {
            now += FrameRate.TICK_MS * 1_000_000 + lateNs
            val drawn = (now - startNs) / everyNs + from
            frames = drawn
            add(Sample(frames, startNs + (drawn - from) * everyNs), now)
        }
        return frames to now
    }

    @Test fun `a steady 60 reads 60, however late the ticks run`() {
        val rate = FrameRate()
        // Android runs a 250 ms tick a few ms late: the old count over an assumed second read 61 to 63.
        rate.play(ticks = 20, lateNs = 7_000_000)
        assertEquals(60f, rate.current, 0.01f)
        assertTrue(rate.history().all { it in 59.99f..60.01f })
    }

    @Test fun `30 reads 30`() {
        val rate = FrameRate()
        rate.play(ticks = 12, everyNs = frameNs * 2)
        assertEquals(30f, rate.current, 0.01f)
    }

    @Test fun `the graph keeps ten seconds, oldest first`() {
        val rate = FrameRate()
        val (frames, now) = rate.play(ticks = 60)
        rate.play(ticks = 10, everyNs = frameNs * 2, from = frames, startNs = now)
        val history = rate.history()
        assertEquals(FrameRate.SAMPLES, history.size)
        assertEquals(60f, history.first(), 0.01f)
        assertEquals(30f, history.last(), 0.01f)
    }

    @Test fun `nothing shows before the game's first frame`() {
        val rate = FrameRate()
        repeat(5) { rate.add(null, it * 250_000_000L) }
        assertEquals(0f, rate.current)
        assertEquals(0, rate.history().size)
    }

    @Test fun `a game that stops drawing reads 0`() {
        val rate = FrameRate()
        val (frames, now) = rate.play(ticks = 8)
        rate.add(Sample(frames, now - 10_000_000), now + 2_000_000_000)
        assertEquals(0f, rate.current)
    }

    @Test fun `a new game counting from zero again doesn't make a huge spike`() {
        val rate = FrameRate()
        val (_, now) = rate.play(ticks = 8, from = 50_000)
        rate.play(ticks = 8, startNs = now)
        assertTrue(rate.history().all { it <= 60.01f })
        assertEquals(60f, rate.current, 0.01f)
    }
}
