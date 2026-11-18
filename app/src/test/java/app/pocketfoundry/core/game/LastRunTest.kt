package app.pocketfoundry.core.game

import android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
import android.app.ApplicationExitInfo.REASON_LOW_MEMORY
import android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED
import android.app.ApplicationExitInfo.REASON_SIGNALED
import android.app.ApplicationExitInfo.REASON_USER_REQUESTED
import app.pocketfoundry.core.game.GameEnding.Stage
import app.pocketfoundry.core.game.LastRun.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastRunTest {
    private fun exit(reason: Int, status: Int = 0, description: String? = null, reported: Boolean = true,
                     rssKb: Long = 310 * 1024L) =
        Exit(reason, status, description, rssKb, importance = 125, timestamp = 0L, lowMemoryKillsReported = reported)

    @Test fun `what ended the app, in a sentence for Home and the report`() {
        // Issue 82: the Tab S8 building Fast start's cache.
        assertEquals("The phone ran out of memory loading sprites: Android closed the app.",
                     LastRun.sentence(exit(REASON_LOW_MEMORY), 36, Stage.SPRITES))
        assertEquals("The app crashed in the game.", LastRun.sentence(exit(REASON_CRASH_NATIVE, 11), 36, Stage.PLAYING))
        assertEquals("The app was killed (SIGKILL) while starting.",
                     LastRun.sentence(exit(REASON_SIGNALED, 9), 36, Stage.STARTING))
        // Died during the driver check, before Factorio had a log of its own.
        assertEquals("The phone ran out of memory before the game started: Android closed the app.",
                     LastRun.sentence(exit(REASON_LOW_MEMORY), 36, null))
    }

    @Test fun `a stop the player asked for, or an update, isn't a failure`() {
        assertNull(LastRun.sentence(exit(REASON_USER_REQUESTED), 36, Stage.PLAYING))
        assertNull(LastRun.sentence(exit(REASON_PACKAGE_UPDATED), 36, Stage.PLAYING))
    }

    @Test fun `without Android's record it says the app closed, and why it can't say more`() {
        assertEquals("The app closed loading sprites (Android 10 doesn't say why).",
                     LastRun.sentence(null, 29, Stage.SPRITES))
        assertEquals("The app closed loading sprites.", LastRun.sentence(null, 36, Stage.SPRITES))
        assertEquals("the app ended during this game: Android 10 doesn't record why · last memory: game 1 MB",
                     LastRun.line(null, 29, "game 1 MB"))
    }

    @Test fun `screen log gets Android's own words and the last memory read`() {
        assertEquals("the app ended (Android: LOW_MEMORY, \"lmk\"; app 310 MB, importance 125) · " +
                         "last memory: game 3215 MB, phone free 412 MB",
                     LastRun.line(exit(REASON_LOW_MEMORY, description = "lmk"), 36,
                                  "game 3215 MB, phone free 412 MB"))
        // The vivo X200 Ultra's record of a kill -9, which had no memory for the app.
        assertEquals("the app ended (Android: SIGNALED, status 9; importance 125)",
                     LastRun.line(exit(REASON_SIGNALED, 9, rssKb = 0), 36, null))
    }

    @Test fun `a plain kill on a phone that doesn't report memory kills says so`() {
        val line = LastRun.line(exit(REASON_SIGNALED, 9, reported = false), 36, null)
        assertTrue(line, line.endsWith("; this phone doesn't report low-memory kills)"))
        // A low-memory kill reported as one needs no such note.
        assertTrue(!LastRun.line(exit(REASON_LOW_MEMORY, reported = false), 36, null).contains("doesn't report"))
    }
}
