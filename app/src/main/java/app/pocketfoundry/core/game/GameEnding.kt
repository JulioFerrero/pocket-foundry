package app.pocketfoundry.core.game

import java.io.File

/**
 * Why a game the player didn't quit ended, when the graphics driver is to blame, and how far it got: in plain words
 * for Home, the Logs and a report. A driver that fails like this is passed over (Drivers.recommended).
 */
object GameEnding {
    enum class Failure(val words: String) {
        LOST_GPU("lost the GPU"), CRASHED("crashed"), OUT_OF_MEMORY("ran out of memory")
    }

    /** How far the game got, by Factorio's own log. */
    enum class Stage(val words: String) {
        STARTING("while starting"), SPRITES("loading sprites"), TEXTURES("loading textures"), PLAYING("in the game")
    }

    /**
     * [deviceLost]: the app stopped it when the driver lost the GPU. Factorio says when it crashed, and the app
     * stops it then too (Session). Any other kill (SIGKILL) is Android's, short of memory. An exit code alone is
     * Factorio's own answer to a save or a mod, not the driver.
     */
    fun failure(deviceLost: Boolean, signal: String?, log: String): Failure? = when {
        deviceLost -> Failure.LOST_GPU
        CRASH in log -> Failure.CRASHED
        signal == "SIGKILL" -> Failure.OUT_OF_MEMORY
        signal != null && signal != "SIGTERM" -> Failure.CRASHED
        else -> null
    }

    /** "PanVK-kbase lost the GPU loading textures." */
    fun sentence(driverName: String, failure: Failure, stage: Stage?) =
        listOfNotNull(driverName, failure.words, stage?.words).joinToString(" ") + "."

    /** How far the game whose log is [log] got. Factorio says so early: a long log of errors isn't read whole. */
    fun stage(log: File): Stage = stage(head(log))

    /**
     * A game the player quit before Factorio was ready, [seconds] after it started: a long load, or one stuck (#93).
     * Null once it was ready, or for one quit straight away, which says nothing.
     */
    fun quitLoading(stage: Stage, seconds: Long): String? =
        if (stage == Stage.PLAYING || seconds < QUIT_LOADING_AFTER_S) null
        else "Quit ${stage.words}, $seconds s after the game started."

    fun stage(log: String): Stage = when {
        INITIALISED in log -> Stage.PLAYING
        SPRITES_LOADED in log -> Stage.TEXTURES
        SPRITE_LOADING.any { it in log } -> Stage.SPRITES
        else -> Stage.STARTING
    }

    private const val INITIALISED = "Factorio initialised"
    /** Then Factorio makes the atlases' mipmaps: where #76's PanVK lost the GPU. */
    private const val SPRITES_LOADED = "Sprites loaded"
    private val SPRITE_LOADING = listOf("Created an atlas bitmap", "Parallel sprite loader")
    private const val CRASH = "Factorio crashed"
    private const val QUIT_LOADING_AFTER_S = 60L
    private const val LOG_HEAD = 2 shl 20

    private fun head(file: File): String = runCatching {
        file.bufferedReader().use { reader ->
            val buffer = CharArray(LOG_HEAD)
            var read = 0
            while (read < LOG_HEAD) {
                val n = reader.read(buffer, read, LOG_HEAD - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read)
        }
    }.getOrDefault("")
}
