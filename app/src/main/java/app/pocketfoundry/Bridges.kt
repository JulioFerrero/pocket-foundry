package app.pocketfoundry

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import app.pocketfoundry.controller.Gamepads
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/*
 * The game can't load Android's libraries, so its sound and gamepad go through these sockets
 * to our SDL; runtime/sdl/pocketfoundry/SDL_pocketfoundry.c documents the protocol.
 */

private const val TAG = "PocketFoundry"

/** In the app's private files rather than the abstract namespace, which other apps share. */
private fun serve(file: File, name: String, handle: (LocalSocket) -> Unit) = thread(name = name, isDaemon = true) {
    while (true) {
        try {
            file.delete()
            // Keep the bound socket referenced: its cleanup on GC would close the server's descriptor.
            LocalSocket(LocalSocket.SOCKET_STREAM).use { bound ->
                bound.bind(LocalSocketAddress(file.path, LocalSocketAddress.Namespace.FILESYSTEM))
                val server = LocalServerSocket(bound.fileDescriptor)
                while (true) {
                    server.accept().use(handle)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "$name: listening again after $e")
            Thread.sleep(1000)
        }
    }
}

object AudioBridge {
    private var started = false

    @Synchronized
    fun start(paths: Paths) {
        if (started) return
        started = true
        serve(paths.audioSocket, "pocketfoundry-audio") { client ->
            try {
                play(DataInputStream(client.inputStream))
            } catch (e: IOException) {
                Log.w(TAG, "Audio stream ended: $e")
            }
        }
    }

    private fun play(input: DataInputStream) {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        input.readFully(header.array())
        if (String(header.array(), 0, 4, Charsets.US_ASCII) != "FAB1") throw IOException("Not an SDL audio stream")
        val freq = header.getInt(4)
        val channels = header.getInt(8)
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frameBytes = 4 * channels

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
            .setBufferSizeInBytes(maxOf(minimum, frameBytes * 2048))
            .build()
        // Android may give a bigger buffer than asked, and its size is the lag to the speaker: two of SDL's chunks.
        track.bufferSizeInFrames = 2048
        Log.i(TAG, "Audio: $freq Hz, $channels channels, buffer ${track.bufferSizeInFrames} of ${track.bufferCapacityInFrames} frames")

        val bytes = ByteArray(frameBytes * 256)
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
}

/** SDL_GamepadButton, in SDL's order. */
enum class PadButton {
    SOUTH, EAST, WEST, NORTH, BACK, GUIDE, START, LEFT_STICK, RIGHT_STICK,
    LEFT_SHOULDER, RIGHT_SHOULDER, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT
}

/** SDL_GamepadAxis, in SDL's order. Sticks go -32768..32767, triggers 0..32767. */
enum class PadAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER }

object InputBridge {
    private var started = false
    @Volatile private var client: LocalSocket? = null

    val connected get() = client != null

    @Synchronized
    fun start(context: Context, paths: Paths) {
        if (started) return
        started = true
        val app = context.applicationContext
        val phone = context.getSystemService(Vibrator::class.java)
        serve(paths.inputSocket, "pocketfoundry-input") { accepted ->
            client = accepted
            Log.i(TAG, "Gamepad plugged in")
            try {
                readRumble(DataInputStream(accepted.inputStream), { Gamepads.vibrator(app) ?: phone }) { Settings.vibration(app) }
            } catch (e: IOException) {
                // Unplugged.
            } finally {
                client = null
                Log.i(TAG, "Gamepad unplugged")
            }
        }
    }

    fun button(button: PadButton, down: Boolean) = send('B', button.ordinal, if (down) 1 else 0)

    /** SDL's virtual pad rests its triggers at -32768, where 0 is half pulled. */
    fun axis(axis: PadAxis, value: Int) {
        val trigger = axis == PadAxis.LEFT_TRIGGER || axis == PadAxis.RIGHT_TRIGGER
        send('A', axis.ordinal, if (trigger) value.coerceIn(0, 32767) * 2 - 32768 else value.coerceIn(-32768, 32767))
    }

    private fun send(kind: Char, index: Int, value: Int) {
        val socket = client ?: return
        val message = byteArrayOf(kind.code.toByte(), index.toByte(), value.toByte(), (value shr 8).toByte())
        try {
            synchronized(this) { socket.outputStream.write(message) }
        } catch (e: IOException) {
            // The reader thread handles the disconnect.
        }
    }

    private fun readRumble(input: DataInputStream, motor: () -> Vibrator?, wanted: () -> Boolean) {
        val message = ByteArray(4)
        var vibrator: Vibrator? = null
        try {
            while (true) {
                input.readFully(message)
                if (message[0] != 'R'.code.toByte()) continue
                val strength = (message[2].toInt() and 0xFF) or ((message[3].toInt() and 0x7F) shl 8)
                val now = motor()
                if (now !== vibrator) vibrator?.cancel()
                vibrator = now ?: continue
                if (strength == 0 || !wanted()) {
                    now.cancel()
                } else {
                    // Lasts until the game sends 0: SDL repeats it before this runs out.
                    val amplitude = (strength * 255 / 32767).coerceIn(1, 255)
                    now.vibrate(VibrationEffect.createOneShot(2000, amplitude))
                }
            }
        } finally {
            vibrator?.cancel()
        }
    }
}
