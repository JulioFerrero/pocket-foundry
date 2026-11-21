package app.pocketfoundry.core.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameCounterTest {
    @get:Rule val folder = TemporaryFolder()

    /** The file as our SDL writes it (SDL_pocketfoundry.c's FrameStamp). */
    private fun stamp(frames: Int, swappedNs: Long, phoneBoosts: Int, magic: String = FrameCounter.MAGIC) =
        folder.newFile().apply {
            writeBytes(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(magic.toByteArray()); putInt(frames); putLong(swappedNs); putInt(2); putInt(phoneBoosts)
            }.array())
        }

    @Test fun `a sample is the count, the last swap and whether the phone's booster takes the frames`() {
        val boosted = FrameCounter(stamp(5, 123L, 1)).sample()!!
        assertEquals(5, boosted.frames)
        assertEquals(123L, boosted.swappedNs)
        assertTrue(boosted.phoneBoosts)
        assertFalse(FrameCounter(stamp(5, 123L, 0)).sample()!!.phoneBoosts)
    }

    @Test fun `another file, or none, has no sample`() {
        assertNull(FrameCounter(stamp(5, 123L, 0, magic = "PFF1")).sample())
        assertNull(FrameCounter(folder.root.resolve("none")).sample())
    }
}
