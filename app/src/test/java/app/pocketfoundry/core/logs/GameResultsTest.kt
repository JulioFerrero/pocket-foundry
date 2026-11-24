package app.pocketfoundry.core.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class GameResultsTest {
    @Test fun `the middle and the slowest tenth of the rates, rounded`() {
        val rates = (1..10).map { it * 6f }   // 6, 12 … 60
        assertEquals(36, GameResults.percentile(rates, 0.5))
        assertEquals(12, GameResults.percentile(rates, 0.1))
        assertEquals(0, GameResults.percentile(emptyList(), 0.5))
        assertEquals(59, GameResults.percentile(listOf(59.4f), 0.1))
    }
}
