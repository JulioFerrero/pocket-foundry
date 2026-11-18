package app.pocketfoundry.core.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryWatchTest {
    @Test fun `a reading, from proc's kB`() {
        assertEquals("game 3215 MB (+120 MB swapped), phone free 412 MB, swap free 980 MB",
                     MemoryWatch.describe(3215 * 1024L, 120 * 1024L, 412 * 1024L, 980 * 1024L))
        // Nothing swapped out, and a phone without swap.
        assertEquals("game 3215 MB, phone free 412 MB", MemoryWatch.describe(3215 * 1024L, 0L, 412 * 1024L, null))
    }
}
