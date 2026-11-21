package app.pocketfoundry.core.game

import app.pocketfoundry.core.game.CpuBoost.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuBoostTest {
    /** [seconds] of [fps], and whether it boosted at the end. */
    private fun Need.play(seconds: Int, fps: Double?, allowed: Boolean = true): Boolean {
        var on = false
        repeat(seconds) { on = second(fps, allowed) }
        return on
    }

    @Test fun `a game keeping up never boosts, one behind does after 3 s`() {
        val need = Need(60)
        assertFalse(need.play(600, 59.9))
        assertFalse(need.play(600, 55.0))
        assertFalse(need.play(2, 18.0))
        assertTrue(need.play(1, 18.0))
        assertTrue(need.play(600, 45.0))
    }

    @Test fun `a second keeping up starts the count again`() {
        val need = Need(30)
        need.play(2, 20.0)
        need.play(1, 30.0)
        assertFalse(need.play(2, 20.0))
        assertTrue(need.play(1, 20.0))
    }

    @Test fun `heat, battery saver or no frames stop it at once`() {
        val need = Need(60)
        assertTrue(need.play(3, 18.0))
        assertFalse(need.play(1, 18.0, allowed = false))
        assertTrue(need.play(3, 18.0))
        assertFalse(need.play(1, null))
        assertFalse(need.play(2, 18.0))
    }

    @Test fun `after 2 minutes keeping up it tries without, and back on at once if the game falls behind`() {
        val need = Need(60)
        need.play(3, 18.0)
        assertTrue(need.play(Need.FIRST_WAIT - 1, 60.0))
        assertFalse(need.play(1, 60.0))
        // One weak second, as the clocks settle, isn't enough.
        assertFalse(need.play(1, 50.0))
        assertFalse(need.play(1, 60.0))
        assertTrue(need.play(2, 20.0))
        // Needed after all: the next try waits twice as long.
        assertTrue(need.play(2 * Need.FIRST_WAIT - 1, 60.0))
        assertFalse(need.play(1, 60.0))
        assertFalse(need.play(Need.TRIAL, 60.0))
        // Not needed: the game keeps up, and the next boost tries again after the first wait.
        assertFalse(need.play(600, 60.0))
        need.play(3, 18.0)
        assertFalse(need.play(Need.FIRST_WAIT, 60.0))
    }

    @Test fun `the wait before a try grows to 16 minutes at most`() {
        val need = Need(60)
        need.play(3, 18.0)
        var wait = Need.FIRST_WAIT
        repeat(6) {
            assertTrue(need.play(wait - 1, 60.0))
            assertFalse(need.play(1, 60.0))
            assertTrue(need.play(2, 20.0))
            wait = minOf(wait * 2, Need.LONGEST_WAIT)
        }
        assertEquals(Need.LONGEST_WAIT, wait)
    }
}
