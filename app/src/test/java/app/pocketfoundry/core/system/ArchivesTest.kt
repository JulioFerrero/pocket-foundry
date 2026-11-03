package app.pocketfoundry.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ArchivesTest {
    @get:Rule val temp = TemporaryFolder()

    /** Canonical, as untarXz passes it. */
    private val root by lazy { temp.newFolder("root").canonicalFile }

    private fun assertEscapes(name: String, strip: Int = 0) {
        assertThrows(name, IOException::class.java) { entryTarget(root, name, strip) }
    }

    @Test fun `an entry lands under root`() {
        assertEquals(File(root, "a/b.txt"), entryTarget(root, "a/b.txt", 0))
    }

    @Test fun `strip drops the top folders`() {
        assertEquals(File(root, "bin/x64/factorio"), entryTarget(root, "factorio/bin/x64/factorio", 1))
        assertEquals(File(root, "x64/factorio"), entryTarget(root, "factorio/bin/x64/factorio", 2))
    }

    @Test fun `the stripped folders themselves are skipped`() {
        assertNull(entryTarget(root, "factorio/", 1))
        assertNull(entryTarget(root, "factorio", 1))
        assertNull(entryTarget(root, "", 0))
        assertNull(entryTarget(root, "./", 0))
    }

    @Test fun `dot and empty parts don't count`() {
        assertEquals(File(root, "data/base"), entryTarget(root, "./factorio//data/./base", 1))
    }

    @Test fun `an absolute name stays under root`() {
        assertEquals(File(root, "etc/passwd"), entryTarget(root, "/etc/passwd", 0))
    }

    @Test fun `dot dot that stays inside root is allowed`() {
        assertEquals(File(root, "a/../b"), entryTarget(root, "a/../b", 0))
        assertEquals(File(root, "a/.."), entryTarget(root, "a/..", 0))
    }

    @Test fun `dot dot out of root throws`() {
        assertEscapes("../evil")
        assertEscapes("..")
        assertEscapes("a/../../evil")
        assertEscapes("factorio/../../evil", strip = 1)
        assertEscapes("factorio/..", strip = 1)
    }

    @Test fun `a sibling folder whose name starts like root's is outside`() {
        assertEscapes("../root-other/x")
    }

    @Test fun `a symlink out of root is caught`() {
        Files.createSymbolicLink(File(root, "link").toPath(), temp.newFolder("outside").toPath())
        assertEscapes("link/passwd")
    }

    @Test fun `a root that isn't canonical refuses every entry`() {
        val link = File(temp.root, "via-link").apply { Files.createSymbolicLink(toPath(), root.toPath()) }
        assertThrows(IOException::class.java) { entryTarget(link, "a", 0) }
    }

    /** NativeXz.decode's result as xz_jni.c packs it, which NativeXzInputStream reads. */
    private fun result(consumed: Long, produced: Long, end: Boolean = false) =
        (consumed shl 32) or produced or (if (end) 1L shl 62 else 0L)

    @Test fun `the result holds the bytes consumed and produced`() {
        val r = result(1000, 65536)
        assertEquals(1000, NativeXzInputStream.consumed(r))
        assertEquals(65536, NativeXzInputStream.produced(r))
        assertFalse(NativeXzInputStream.atEnd(r))
    }

    @Test fun `bit 62 marks the end and isn't counted as consumed`() {
        val r = result(5, 0, end = true)
        assertEquals(5, NativeXzInputStream.consumed(r))
        assertEquals(0, NativeXzInputStream.produced(r))
        assertTrue(NativeXzInputStream.atEnd(r))
    }

    @Test fun `full buffers on both sides fit`() {
        val r = result(MIB.toLong(), Int.MAX_VALUE.toLong(), end = true)
        assertEquals(MIB, NativeXzInputStream.consumed(r))
        assertEquals(Int.MAX_VALUE, NativeXzInputStream.produced(r))
        assertTrue(NativeXzInputStream.atEnd(r))
    }

    @Test fun `zero is nothing done`() {
        assertEquals(0, NativeXzInputStream.consumed(0))
        assertEquals(0, NativeXzInputStream.produced(0))
        assertFalse(NativeXzInputStream.atEnd(0))
    }
}
