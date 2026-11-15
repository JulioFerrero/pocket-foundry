package app.pocketfoundry.core.game

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateTest {
    /** [perTick] frames every 100 ms, [ticks] times, from a count of [from]. */
    private fun FrameRate.feed(perTick: Int, ticks: Int, from: Long = 0): Long {
        var frames = from
        add(frames)
        repeat(ticks) { frames += perTick; add(frames) }
        return frames
    }

    @Test fun `six frames every tenth of a second is 60 FPS`() {
        val rate = FrameRate()
        rate.feed(perTick = 6, ticks = 20)
        assertEquals(60f, rate.current, 0.01f)
    }

    @Test fun `the graph keeps ten seconds, oldest first`() {
        val rate = FrameRate()
        val frames = rate.feed(perTick = 6, ticks = 150)
        rate.feed(perTick = 3, ticks = 10, from = frames)
        val history = rate.history()
        assertEquals(FrameRate.SAMPLES, history.size)
        assertEquals(60f, history.first(), 0.01f)
        assertEquals(30f, history.last(), 0.01f)
    }

    @Test fun `nothing shows before the game's first frame`() {
        val rate = FrameRate()
        repeat(5) { rate.add(null) }
        assertEquals(0f, rate.current)
        assertEquals(0, rate.history().size)
    }

    @Test fun `a new game counting from zero again doesn't make a huge spike`() {
        val rate = FrameRate()
        val frames = rate.feed(perTick = 6, ticks = 20, from = 50_000)
        rate.add(6)
        assertEquals(true, rate.history().all { it <= 60f })
        assertEquals(true, frames > 6)
    }
}
