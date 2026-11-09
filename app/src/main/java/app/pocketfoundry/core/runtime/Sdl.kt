package app.pocketfoundry.core.runtime

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.TAG
import java.io.File
import kotlin.concurrent.thread

/**
 * The game's built-in SDL hands everything to ours (runtime/sdl), whose additions (runtime/sdl/pocketfoundry) send the
 * game's sound and take its gamepad through sockets in the app's private files, rather than the abstract namespace,
 * which other apps share. The names here are the ones SDL_pocketfoundry.c reads.
 */
object Sdl {
    fun environment(paths: Paths) = mapOf(
        "SDL_VIDEO_DRIVER" to "x11",
        "SDL3_DYNAMIC_API" to paths.sdl.path,
        "SDL_AUDIO_DRIVER" to "pocketfoundry",
        "POCKETFOUNDRY_AUDIO" to paths.audioSocket.path,
        "POCKETFOUNDRY_INPUT" to paths.inputSocket.path,
        "POCKETFOUNDRY_FRAME_RATE" to paths.frameRate.path,
        // Only our virtual pad, which SDL never filters. Where /dev/input is readable, SDL would also open the real pad
        // and every press would count twice. No device has id 0000:0000.
        "SDL_GAMECONTROLLER_IGNORE_DEVICES_EXCEPT" to "0x0000/0x0000",
    )

    private val serving = HashSet<File>()

    /**
     * Listens on [file] from the first call, made once the runtime is unpacked (its install empties [Paths.tmp]): one
     * client at a time, [handle]d until it goes; one that fails is logged and the next is taken. Throws if it can't
     * listen. If listening ever stops, the next call starts again.
     */
    @Synchronized
    fun serve(file: File, handle: (LocalSocket) -> Unit) {
        if (file in serving) return
        file.delete()
        // Keep the bound socket referenced: its cleanup on GC would close the server's descriptor.
        val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
        bound.bind(LocalSocketAddress(file.path, LocalSocketAddress.Namespace.FILESYSTEM))
        val server = LocalServerSocket(bound.fileDescriptor)
        serving += file
        thread(name = file.name, isDaemon = true) {
            try {
                bound.use {
                    while (true) server.accept().use { client ->
                        try {
                            handle(client)
                        } catch (e: Exception) {
                            Log.e(TAG, "${file.name}: the client failed", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "${file.name}: stopped listening", e)
            } finally {
                synchronized(this) { serving -= file }
            }
        }
    }
}
