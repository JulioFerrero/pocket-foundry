package app.pocketfoundry.core.game

import app.pocketfoundry.core.game.GameEnding.Failure
import app.pocketfoundry.core.game.GameEnding.Stage
import app.pocketfoundry.core.system.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEndingTest {
    @Test fun `a lost GPU, a crash and Android's kill are the driver's, an exit code alone isn't`() {
        assertEquals(Failure.LOST_GPU, GameEnding.failure(deviceLost = true, signal = "SIGKILL", log = ""))
        assertEquals(Failure.OUT_OF_MEMORY, GameEnding.failure(deviceLost = false, signal = "SIGKILL", log = ""))
        assertEquals(Failure.CRASHED, GameEnding.failure(deviceLost = false, signal = "SIGSEGV", log = ""))
        assertEquals(Failure.CRASHED, GameEnding.failure(deviceLost = false, signal = null, log = "Factorio crashed."))
        // Stopped by the app once its crash dialog came up.
        assertEquals(Failure.CRASHED, GameEnding.failure(deviceLost = false, signal = "SIGKILL", log = "Factorio crashed."))
        // A save or a mod Factorio refused.
        assertEquals(null, GameEnding.failure(deviceLost = false, signal = null, log = "Error ModManager.cpp:1560"))
        assertEquals(null, GameEnding.failure(deviceLost = false, signal = "SIGTERM", log = ""))
    }

    @Test fun `how far the game got, by Factorio's log`() {
        assertEquals(Stage.STARTING, GameEnding.stage("   0.234 Video driver: x11"))
        assertEquals(Stage.SPRITES, GameEnding.stage("  14.745 Parallel sprite loader initialized (threads: 4)"))
        // #76: PanVK lost the GPU while Factorio made the atlases' mipmaps.
        assertEquals(Stage.TEXTURES, GameEnding.stage("  77.560 Sprites loaded\n  82.111 Generated mipmaps (3)"))
        assertEquals(Stage.PLAYING, GameEnding.stage("  77.560 Sprites loaded\n  90.0 Factorio initialised"))
    }

    @Test fun `a game quit before it was ready, after a long wait, may have been stuck`() {
        // #93: stuck at loading sprites 50% on an Adreno 618.
        assertEquals("Quit loading sprites, 95 s after the game started.", GameEnding.quitLoading(Stage.SPRITES, 95))
        assertEquals("Quit while starting, 60 s after the game started.", GameEnding.quitLoading(Stage.STARTING, 60))
        // Quit straight away, or from the menu: nothing to learn.
        assertEquals(null, GameEnding.quitLoading(Stage.SPRITES, 59))
        assertEquals(null, GameEnding.quitLoading(Stage.PLAYING, 600))
    }

    @Test fun `Factorio's own last error says why it stopped by itself, and the headless build gets the import's words`() {
        val mods = """
               0.053 System info: [CPU: arm64, 8 cores, RAM: 7438 MB]
               0.066 Error ModManager.cpp:1786: Failed to load mod "alien-biomes":
               0.152 Error Util.cpp:81: Duplicate mod elevated-rails.
               3.302 Goodbye
            X Error:  GLXBadCurrentWindow
        """.trimIndent()
        assertEquals("Factorio stopped: Duplicate mod elevated-rails.", GameEnding.gameError(mods))
        assertEquals(GameImport.HEADLESS, GameEnding.gameError(
            "Error: This is a headless executable, cannot start in graphical mode (use --start-server).\n"))
        assertNull(GameEnding.gameError("   0.000 Factorio 2.1.24\n   3.302 Goodbye\n"))
        assertTrue(GameEnding.gameError("   1.000 Error Util.cpp:81: " + "x".repeat(300))!!.endsWith("…"))
    }

    @Test fun `out of memory with High sprites lowers them, with Medium or for anything else the driver goes`() {
        assertTrue(GameEnding.lowersSprites(Failure.OUT_OF_MEMORY, Settings.HIGH))
        assertFalse(GameEnding.lowersSprites(Failure.OUT_OF_MEMORY, Settings.MEDIUM))
        assertFalse(GameEnding.lowersSprites(Failure.LOST_GPU, Settings.HIGH))
        assertFalse(GameEnding.lowersSprites(null, Settings.HIGH))
        assertEquals("The phone ran out of memory loading sprites. The next game loads Medium sprites.",
                     GameEnding.spritesLowered(Stage.SPRITES))
    }

    @Test fun `in a sentence`() {
        assertEquals("PanVK-kbase lost the GPU loading textures.",
                     GameEnding.sentence("PanVK-kbase", Failure.LOST_GPU, Stage.TEXTURES))
    }
}
