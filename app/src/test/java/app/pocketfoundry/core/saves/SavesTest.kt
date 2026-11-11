package app.pocketfoundry.core.saves

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

class SavesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(vararg names: String): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip -> for (name in names) zip.putNextEntry(ZipEntry(name)) }
    }

    @Test fun `a zip with level dat0 is a save`() {
        assertTrue(Saves.isSave(zip("level.dat0", "control.lua")))
    }

    @Test fun `level data in the save's folder counts`() {
        assertTrue(Saves.isSave(zip("my-base/", "my-base/level-init.dat", "my-base/preview.jpg")))
        assertTrue(Saves.isSave(zip("my-base/level.dat0")))
    }

    @Test fun `a zip without level data isn't a save`() {
        assertFalse(Saves.isSave(zip("my-mod/info.json", "my-mod/control.lua")))
        assertFalse(Saves.isSave(zip("my-base/old-level.dat0", "level.dat1")))
        assertFalse(Saves.isSave(zip()))
    }

    @Test fun `a file that isn't a zip isn't a save`() {
        assertFalse(Saves.isSave(temp.newFile().apply { writeText("level.dat0") }))
        assertFalse(Saves.isSave(temp.newFile()))
        assertFalse(Saves.isSave(File(temp.root, "missing.zip")))
    }

    private fun file(dir: File, name: String, text: String) = File(dir, name).apply { writeText(text) }

    @Test fun `an imported save keeps its name`() {
        val dir = temp.newFolder()
        assertEquals("base.zip", Saves.freeName(dir, "base.zip", file(temp.root, "in", "new"))?.name)
        assertEquals("base.zip", Saves.freeName(dir, "base", file(temp.root, "in", "new"))?.name)
    }

    @Test fun `another save by that name is kept beside it`() {
        val dir = temp.newFolder()
        file(dir, "base.zip", "old")
        file(dir, "base (2).zip", "older")
        assertEquals("base (3).zip", Saves.freeName(dir, "base.zip", file(temp.root, "in", "new"))?.name)
    }

    @Test fun `the same save again isn't added`() {
        val dir = temp.newFolder()
        file(dir, "base.zip", "old")
        file(dir, "base (2).zip", "same")
        assertNull(Saves.freeName(dir, "base.zip", file(temp.root, "in", "same")))
    }
}
