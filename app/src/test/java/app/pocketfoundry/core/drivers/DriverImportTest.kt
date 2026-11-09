package app.pocketfoundry.core.drivers

import app.pocketfoundry.core.system.MIB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DriverImportTest {
    @get:Rule val temp = TemporaryFolder()

    /** A library of zeros with [text] at [at]. */
    private fun library(text: String, at: Int = 100, size: Int = at + text.length + 100): File =
        temp.newFile().apply { writeBytes(ByteArray(size).also { text.toByteArray().copyInto(it, at) }) }

    @Test fun `the id is kind, version and the hash's first 8 characters, lowercased`() {
        assertEquals("panvk-26.3.0-devel-01234567", DriverImport.driverId("PanVK", "26.3.0-devel", "0123456789abcdef"))
    }

    @Test fun `an imported PanVK is told apart by its id`() {
        assertTrue(DriverImport.isPanVK(DriverImport.driverId("PanVK", "26.3.0-devel", "0123456789abcdef")))
        assertFalse(DriverImport.isPanVK(DriverImport.driverId("Turnip", "26.3.0", "0123456789abcdef")))
        assertFalse(DriverImport.isPanVK("phone"))
    }

    @Test fun `other characters in the version become a dash`() {
        assertEquals("turnip-25.1.0-git-1a2b--ffffffff",
                     DriverImport.driverId("Turnip", "25.1.0 (git-1A2B)", "ffffffffff"))
    }

    @Test fun `a version can't add folders to the id`() {
        assertEquals("vulkan-..-..-x-abc", DriverImport.driverId("Vulkan", "../../x", "abc"))
    }

    @Test fun `the Mesa version is read from the library`() {
        assertEquals("25.1.0-devel", DriverImport.mesaVersion(library("Mesa 25.1.0-devel (git-abc)")))
    }

    @Test fun `the first Mesa version wins`() {
        assertEquals("24.0.0", DriverImport.mesaVersion(library("Mesa 24.0.0\u0000Mesa 25.0.0")))
    }

    @Test fun `a library without a Mesa version has none`() {
        assertNull(DriverImport.mesaVersion(library("Mesa 25.1 and Mesa")))
        assertNull(DriverImport.mesaVersion(temp.newFile()))
    }

    @Test fun `a version split by the 1 MiB read is still found`() {
        assertEquals("24.3.1", DriverImport.mesaVersion(library("Mesa 24.3.1", at = MIB - 5)))
    }

    @Test fun `a version at the very end of the file is found`() {
        assertEquals("24.0.0", DriverImport.mesaVersion(library("Mesa 24.0.0", size = 100 + "Mesa 24.0.0".length)))
    }

    @Test fun `a version whose suffix the 1 MiB read cuts is still whole`() {
        assertEquals("26.3.0-devel", DriverImport.mesaVersion(library("Mesa 26.3.0-devel", at = MIB - 14)))
    }

    private val loadAddress = 0x10000L

    /**
     * A 64-bit little-endian ELF: one loaded segment holding the whole file at [loadAddress], then the dynamic
     * segment, its entries and the string table they point into ([strtab] overrides the table's address).
     */
    private fun elf(needed: List<String>, machine: Int = 183, dynamic: Boolean = true, strtab: Long? = null): File {
        val strings = ByteArrayOutputStream().apply { write(0) }
        val offsets = needed.map { strings.size().toLong().also { _ -> strings.write(it.toByteArray() + 0.toByte()) } }
        val segments = if (dynamic) 2 else 1
        val dynOffset = 64 + 56 * segments
        val dynSize = 16 * (needed.size + 2)
        val strOffset = dynOffset + dynSize
        val size = strOffset + strings.size()
        val bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1))
            putShort(18, machine.toShort())
            putLong(32, 64)       // program headers
            putShort(54, 56)      // their size
            putShort(56, segments.toShort())
            segment(64, type = 1, offset = 0, size = size.toLong())   // PT_LOAD
            if (dynamic) segment(120, type = 2, offset = dynOffset.toLong(), size = dynSize.toLong())
            position(dynOffset)
            for (at in offsets) putLong(1).putLong(at)                // DT_NEEDED
            putLong(5).putLong(strtab ?: (loadAddress + strOffset))  // DT_STRTAB
            putLong(0).putLong(0)                                     // DT_NULL
            put(strings.toByteArray())
        }.array()
        return temp.newFile().apply { writeBytes(bytes) }
    }

    private fun ByteBuffer.segment(at: Int, type: Int, offset: Long, size: Long) {
        putInt(at, type).putLong(at + 8, offset).putLong(at + 16, loadAddress + offset).putLong(at + 32, size)
    }

    @Test fun `lists the needed libraries in order`() {
        assertEquals(listOf("libc.so.6", "libm.so.6", "libz.so.1"),
                     Elf.neededLibraries(elf(listOf("libc.so.6", "libm.so.6", "libz.so.1"))))
    }

    @Test fun `a library without needed entries needs nothing`() {
        assertEquals(emptyList<String>(), Elf.neededLibraries(elf(emptyList())))
    }

    @Test fun `a library without a dynamic segment needs nothing`() {
        assertEquals(emptyList<String>(), Elf.neededLibraries(elf(listOf("libc.so.6"), dynamic = false)))
    }

    @Test fun `a library for another CPU is refused`() {
        val e = assertThrows(IOException::class.java) { Elf.neededLibraries(elf(listOf("libc.so.6"), machine = 62)) }
        assertEquals("That driver isn't an arm64 library", e.message)
    }

    @Test fun `a file that isn't ELF is refused`() {
        val file = temp.newFile().apply { writeText("x".repeat(64)) }
        assertThrows(IOException::class.java) { Elf.neededLibraries(file) }
    }

    @Test fun `a file shorter than the ELF header is refused`() {
        val file = temp.newFile().apply { writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte())) }
        assertThrows(IOException::class.java) { Elf.neededLibraries(file) }
    }

    @Test fun `a string table outside every loaded segment is an IOException`() {
        assertThrows(IOException::class.java) {
            Elf.neededLibraries(elf(listOf("libc.so.6"), strtab = 0x900000))
        }
    }

    @Test fun `isElf sees the magic and leaves the stream where it was`() {
        val stream = BufferedInputStream(elf(listOf("libc.so.6")).inputStream())
        assertTrue(Elf.isElf(stream))
        assertEquals(0x7F, stream.read())
    }

    @Test fun `isElf is false for other data and for short streams`() {
        assertFalse(Elf.isElf(BufferedInputStream(ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 3, 4, 0)))))
        assertFalse(Elf.isElf(BufferedInputStream(ByteArrayInputStream(byteArrayOf(0x7F, 'E'.code.toByte())))))
        assertFalse(Elf.isElf(BufferedInputStream(ByteArrayInputStream(ByteArray(0)))))
    }
}
