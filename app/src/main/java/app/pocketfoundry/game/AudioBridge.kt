package app.pocketfoundry.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.pocketfoundry.Paths
import app.pocketfoundry.TAG
import app.pocketfoundry.serve
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The game's sound, from our SDL. A 12-byte header: "PFA1", then the sample rate and the channel count as
 * little-endian 32-bit ints. Then interleaved 32-bit float samples until the game closes its audio device.
 */
object AudioBridge {
    private const val MAGIC = "PFA1"
    /** Two of SDL's 1024-frame chunks: the lag to the speaker. */
    private const val LAG_FRAMES = 2048
    private const val READ_FRAMES = 256

    fun start(paths: Paths) = serve(paths.audioSocket) { client ->
        try {
            play(DataInputStream(client.inputStream))
        } catch (e: IOException) {
            Log.w(TAG, "Audio stream ended: $e")
        }
    }

    private fun play(input: DataInputStream) {
        val (freq, channels) = readHeader(input)
        val track = newTrack(freq, channels)
        Log.i(TAG, "Audio: $freq Hz, $channels channels, buffer ${track.bufferSizeInFrames} of ${track.bufferCapacityInFrames} frames")
        val bytes = ByteArray(4 * channels * READ_FRAMES)
        val samples = FloatArray(bytes.size / 4)
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        track.play()
        try {
            while (true) {
                input.readFully(bytes)
                view.rewind()
                view.get(samples)
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: EOFException) {
            // The game closed its audio device.
        } finally {
            track.stop()
            track.release()
        }
    }

    /** The sample rate and the channel count. */
    internal fun readHeader(input: DataInputStream): Pair<Int, Int> {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        input.readFully(header.array())
        if (String(header.array(), 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) throw IOException("Not an SDL audio stream")
        return header.getInt(4) to header.getInt(8)
    }

    private fun newTrack(freq: Int, channels: Int): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(freq, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(freq)
                .setChannelMask(mask)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minimum, 4 * channels * LAG_FRAMES))
            .build()
        // Android may give a bigger buffer than asked, and its size is the lag to the speaker.
        track.bufferSizeInFrames = LAG_FRAMES
        return track
    }
}
