package app.pocketfoundry.saves

import app.pocketfoundry.saves.GameFilesProvider.Companion.isPlainName
import app.pocketfoundry.saves.GameFilesProvider.Companion.numberedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFilesProviderTest {
    @Test fun `the number goes before the extension`() {
        assertEquals("save (1).zip", numberedName("save.zip", 1, isDir = false))
        assertEquals("config (12).ini", numberedName("config.ini", 12, isDir = false))
    }

    @Test fun `only the last extension counts`() {
        assertEquals("mods.tar (2).gz", numberedName("mods.tar.gz", 2, isDir = false))
    }

    @Test fun `a name without an extension gets the number at the end`() {
        assertEquals("notes (3)", numberedName("notes", 3, isDir = false))
        assertEquals("notes (1).", numberedName("notes.", 1, isDir = false))
    }

    @Test fun `a leading dot isn't an extension`() {
        assertEquals(".hidden (1)", numberedName(".hidden", 1, isDir = false))
    }

    @Test fun `folders have no extension`() {
        assertEquals("mods (1)", numberedName("mods", 1, isDir = true))
        assertEquals("my.folder (2)", numberedName("my.folder", 2, isDir = true))
    }

    @Test fun `a name without a slash passes, dots and all`() {
        assertTrue(isPlainName("save.zip"))
        assertTrue(isPlainName(".hidden"))
        assertTrue(isPlainName("my mods..zip"))
    }

    @Test fun `a slash, dot, dot dot or nothing is refused`() {
        assertFalse(isPlainName("../x"))
        assertFalse(isPlainName("mods/x"))
        assertFalse(isPlainName("/x"))
        assertFalse(isPlainName("."))
        assertFalse(isPlainName(".."))
        assertFalse(isPlainName(""))
    }
}
