package app.pocketfoundry.core.game

import app.pocketfoundry.core.game.GameEnding.Failure
import app.pocketfoundry.core.game.GameEnding.Stage
import org.junit.Assert.assertEquals
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

    @Test fun `in a sentence`() {
        assertEquals("PanVK-kbase lost the GPU loading textures.",
                     GameEnding.sentence("PanVK-kbase", Failure.LOST_GPU, Stage.TEXTURES))
    }
}
