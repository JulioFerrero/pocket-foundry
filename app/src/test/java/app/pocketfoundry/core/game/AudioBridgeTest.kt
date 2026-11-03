package app.pocketfoundry.core.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioBridgeTest {
    private fun header(magic: String, rate: Int, channels: Int) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .put(magic.toByteArray(Charsets.US_ASCII)).putInt(rate).putInt(channels).array()

    private fun stream(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    @Test fun `the header gives the rate and channels, little-endian`() {
        assertEquals(44100 to 2, AudioBridge.readHeader(stream(header("PFA1", 44100, 2))))
        assertEquals(48000 to 1, AudioBridge.readHeader(stream(header("PFA1", 48000, 1))))
    }

    @Test fun `the samples after the header stay unread`() {
        val input = stream(header("PFA1", 48000, 2) + byteArrayOf(9, 8))
        AudioBridge.readHeader(input)
        assertEquals(9, input.read())
    }

    @Test fun `a bad magic is refused`() {
        val e = assertThrows(IOException::class.java) { AudioBridge.readHeader(stream(header("FAB1", 48000, 2))) }
        assertEquals("Not an SDL audio stream", e.message)
    }

    @Test fun `a short header ends the stream`() {
        assertThrows(EOFException::class.java) { AudioBridge.readHeader(stream("PFA1".toByteArray() + 0x44.toByte())) }
        assertThrows(EOFException::class.java) { AudioBridge.readHeader(stream(ByteArray(0))) }
    }
}
