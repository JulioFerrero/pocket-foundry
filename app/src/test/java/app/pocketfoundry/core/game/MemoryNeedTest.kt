package app.pocketfoundry.core.game

import app.pocketfoundry.core.system.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MemoryNeedTest {
    @get:Rule val folder = TemporaryFolder()
    private val paths by lazy { Paths(folder.root, folder.root) }

    @Test fun `Space Age at High needs the most, the base game at Medium the least, a made cache a quarter less`() {
        assertEquals(4500, MemoryNeed.neededMb(spaceAge = true, high = true, cached = false))
        assertEquals(2200, MemoryNeed.neededMb(spaceAge = true, high = false, cached = false))
        assertEquals(1100, MemoryNeed.neededMb(spaceAge = false, high = true, cached = false))
        assertEquals(900, MemoryNeed.neededMb(spaceAge = false, high = false, cached = false))
        assertEquals(3375, MemoryNeed.neededMb(spaceAge = true, high = true, cached = true))
    }

    @Test fun `the 3 GB phone that lost Space Age at Medium is short of it, the one that played the base game isn't`() {
        // A Redmi 10C, 2026-11-21: 552 MB available and 385 MB of swap free when Space Age was closed.
        val free = MemoryNeed.freeMb(mapOf("MemAvailable" to 552L * 1024, "SwapFree" to 385L * 1024))
        val spaceAge = MemoryNeed.Check(free, MemoryNeed.neededMb(spaceAge = true, high = false, cached = false))
        assertTrue(spaceAge.short)
        val base = MemoryNeed.Check(MemoryNeed.freeMb(mapOf("MemAvailable" to 652L * 1024, "SwapFree" to 508L * 1024)),
                                    MemoryNeed.neededMb(spaceAge = false, high = true, cached = false))
        assertFalse(base.short)
    }

    private fun save(vararg entries: Pair<String, Int>) = folder.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            for ((name, size) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(ByteArray(size)); zip.closeEntry()
            }
        }
    }

    @Test fun `a 2_0 save's map counts its level chunks, an older one its level_dat, none or a broken one 0`() {
        val chunks = save("s/level.dat0" to 10, "s/level.dat1" to 10, "s/level.dat2" to 10, "s/control.lua" to 10)
        assertEquals(45, MemoryNeed.mapMb(chunks))
        assertEquals(30, MemoryNeed.mapMb(save("old/level.dat" to (2 shl 20) + 5, "old/script.dat" to 10)))
        assertEquals(0, MemoryNeed.mapMb(null))
        assertEquals(0, MemoryNeed.mapMb(folder.newFile().apply { writeText("not a zip") }))
    }

    @Test fun `gridlocked's map is short on the Xiaomi 12T that lost it, flame_Sla's isn't, and the map is to blame`() {
        // 2026-11-21: 3.15 GB available and 3.98 GB of swap free as gridlocked (501 level chunks) started.
        val free = MemoryNeed.freeMb(mapOf("MemAvailable" to 3227624L, "SwapFree" to 4080636L))
        val base = MemoryNeed.neededMb(spaceAge = false, high = false, cached = true)
        val gridlocked = MemoryNeed.Check(free, base + 501 * 15, 501 * 15)
        assertTrue(gridlocked.short)
        assertTrue(gridlocked.mapHeavy)
        val flame = MemoryNeed.Check(free, base + 86 * 15, 86 * 15)
        assertFalse(flame.short)
    }

    @Test fun `without a readable meminfo there's nothing to warn about`() {
        assertNull(MemoryNeed.freeMb(emptyMap()))
        assertFalse(MemoryNeed.Check(null, 4500).short)
    }

    @Test fun `Space Age counts when it's in the game and the mod list doesn't turn it off`() {
        assertFalse(MemoryNeed.spaceAge(paths))
        File(paths.gameReadData, "space-age").mkdirs()
        assertTrue(MemoryNeed.spaceAge(paths))
        val list = File(paths.gameData, "mods/mod-list.json").apply { parentFile.mkdirs() }
        list.writeText("""{"mods": [{"name": "base", "enabled": true}, {"name": "space-age", "enabled": false}]}""")
        assertFalse(MemoryNeed.spaceAge(paths))
        list.writeText("""{"mods": [{"name": "space-age", "enabled": true}]}""")
        assertTrue(MemoryNeed.spaceAge(paths))
        list.writeText("not json")
        assertTrue(MemoryNeed.spaceAge(paths))
    }
}
