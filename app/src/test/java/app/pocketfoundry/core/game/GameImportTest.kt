package app.pocketfoundry.core.game

import app.pocketfoundry.core.system.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameImportTest {
    @get:Rule val folder = TemporaryFolder()
    private val paths by lazy { Paths(folder.root, folder.root) }

    private fun game(vararg files: String) = folder.newFolder().also { dir ->
        for (path in files) File(dir, path).apply { parentFile.mkdirs() }.writeText("")
    }

    @Test fun `the full game is played`() {
        assertNull(GameImport.refusal(paths, game("bin/arm64/factorio", "data/base/graphics/entity/inserter.png")))
    }

    @Test fun `the headless server is refused, with the same binary and no graphics`() {
        // Issue 83: it imported, then exited at once with "This is a headless executable".
        val refusal = GameImport.refusal(paths, game("bin/arm64/factorio", "data/base/info.json"))
        assertTrue(refusal!!.startsWith("That's Factorio's headless server"))
    }

    @Test fun `the headless server's sprite folder, with only the sprites' lua files, is refused too`() {
        // 2.1.24's headless build has data/base/graphics/entity/ with about 400 .lua files and no PNG (2026-11-27).
        val refusal = GameImport.refusal(paths, game("bin/arm64/factorio", "data/base/graphics/entity/inserter/inserter.lua"))
        assertEquals(GameImport.HEADLESS, refusal)
        assertNull(GameImport.refusal(paths, game("bin/arm64/factorio", "data/base/graphics/entity/inserter/inserter.lua",
                                                  "data/base/graphics/entity/inserter/inserter-hand.png")))
    }

    @Test fun `another build is refused by its missing binary`() {
        assertEquals("That isn't the Linux arm64 build of Factorio (bin/arm64/factorio is missing)",
                     GameImport.refusal(paths, game("bin/x64/factorio", "data/base/graphics/entity/inserter.png")))
    }
}
