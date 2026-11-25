package app.pocketfoundry.core.logs

import app.pocketfoundry.core.drivers.Drivers
import app.pocketfoundry.core.logs.GameResults.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class GameResultsTest {
    @Test fun `the drivers' outcomes keep the words Acrarium has grouped them by since 0_9_4-beta_1`() {
        assertEquals(listOf("played", "stopped", "didn't start", "failed"),
                     Drivers.Outcome.values().map { Outcome.of(it).words })
    }

    @Test fun `every game but one played sends its logs`() {
        assertEquals(listOf(Outcome.PLAYED), Outcome.values().filter { !it.withLogs })
    }

    @Test fun `the middle and the slowest tenth of the rates, rounded`() {
        val rates = (1..10).map { it * 6f }   // 6, 12 … 60
        assertEquals(36, GameResults.percentile(rates, 0.5))
        assertEquals(12, GameResults.percentile(rates, 0.1))
        assertEquals(0, GameResults.percentile(emptyList(), 0.5))
        assertEquals(59, GameResults.percentile(listOf(59.4f), 0.1))
    }
}
