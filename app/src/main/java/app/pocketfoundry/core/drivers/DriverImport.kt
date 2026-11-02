package app.pocketfoundry.core.drivers

import android.content.Context
import android.net.Uri
import app.pocketfoundry.core.system.MIB
import app.pocketfoundry.NativeXzInputStream
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.Uris
import app.pocketfoundry.core.game.Runtime
import app.pocketfoundry.sha256
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Linux (glibc) build of a Mesa Vulkan driver (Turnip, PanVK…), as a .tar.gz, .tar.xz or .zip, or the library
 * alone. Other libraries in the package stay beside it, for what it needs and the runtime lacks.
 */
object DriverImport {
    private val SHARED_LIBRARY = Regex("""[^/]+\.so(\.\d+)*""")
    private val KIND_PATTERN = Regex("""libvulkan_([a-z0-9]+)""")
    private val KIND_NAMES = mapOf("freedreno" to "Turnip", "panfrost" to "PanVK", "radeon" to "RADV", "powervr" to "PowerVR")
    private val GZIP = byteArrayOf(0x1F, 0x8B.toByte())
    private val XZ = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZSTD = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    fun import(context: Context, uri: Uri): Drivers.Driver {
        val paths = Paths(context)
        Runtime.install(context)   // to check the libraries against
        val staging = File(paths.drivers, ".importing").apply { deleteRecursively(); mkdirs() }
        try {
            val fileName = Uris.displayName(context, uri)
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the selected file")
            val icd = BufferedInputStream(input).use { unpack(it, staging, fileName) }
            val libraries = staging.listFiles().orEmpty().filter { SHARED_LIBRARY.matches(it.name) }
            val named = icd?.optString("library_path")?.substringAfterLast('/')
            val library = libraries.firstOrNull { it.name == named }
                ?: libraries.singleOrNull { it.name.startsWith("libvulkan_") }
                ?: throw IOException("No Vulkan driver in that file (no libvulkan_….so)")
            val needed = Elf.neededLibraries(library)
            if ("libc.so.6" !in needed) throw IOException("That driver is built for Android, not Linux: it can't load here")
            val missing = needed.filter { need -> !File(paths.lib, need).exists() && libraries.none { it.name == need } }
            if (missing.isNotEmpty()) throw IOException("That driver needs ${missing.joinToString()}, which Pocket Foundry doesn't have")
            val kind = KIND_NAMES[KIND_PATTERN.find(named ?: library.name)?.groupValues?.get(1)] ?: "Vulkan"
            val version = mesaVersion(library) ?: "unknown"
            val id = driverId(kind, version, library.sha256())
            val driver = Drivers.Driver(id, "$kind $version", "Imported · ${fileName ?: "driver"}")
            keep(driver, libraries, library, icd, paths)
            return driver
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Unpacks the shared libraries into [staging]. Returns the package's ICD manifest, if it has one. */
    private fun unpack(stream: BufferedInputStream, staging: File, fileName: String?): JSONObject? {
        if (Elf.isElf(stream)) {
            File(staging, fileName?.substringAfterLast('/')?.takeIf { SHARED_LIBRARY.matches(it) } ?: "libvulkan_driver.so")
                .outputStream().use { stream.copyTo(it) }
            return null
        }
        var icd: JSONObject? = null
        val links = HashMap<String, String>()
        openArchive(stream).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                when {
                    entry.isDirectory -> {}
                    entry is TarArchiveEntry && entry.isSymbolicLink -> links[name] = entry.linkName.substringAfterLast('/')
                    name.endsWith(".json") -> runCatching {
                        JSONObject(entries.readBytes().decodeToString()).getJSONObject("ICD")
                    }.getOrNull()?.let { icd = it }
                    SHARED_LIBRARY.matches(name) -> File(staging, name).outputStream().use { entries.copyTo(it) }
                }
            }
        }
        // libfoo.so.1 -> libfoo.so.1.2 and the like: a copy under the linked name.
        for ((name, target) in links) File(staging, target).takeIf { it.exists() }?.copyTo(File(staging, name), overwrite = true)
        return icd
    }

    /** A .tar.gz, .tar.xz, .zip or plain .tar, told apart by its first bytes. */
    private fun openArchive(stream: BufferedInputStream): ArchiveInputStream<*> {
        val head = stream.peek(XZ.size)
        fun starts(magic: ByteArray) = magic.indices.all { head[it] == magic[it] }
        return when {
            starts(GZIP) -> TarArchiveInputStream(GzipCompressorInputStream(stream))
            starts(XZ) -> TarArchiveInputStream(NativeXzInputStream(stream))
            starts(ZIP) -> ZipArchiveInputStream(stream)
            starts(ZSTD) -> throw IOException("Zstandard (.tzst) drivers aren't supported: use a .tar.gz, .tar.xz or .zip")
            else -> TarArchiveInputStream(stream)
        }
    }

    /**
     * Different builds often share a Mesa version (PanVK forks are all 26.3.0-devel): the library's hash keeps them
     * apart, and the same file imported again still replaces itself.
     */
    internal fun driverId(kind: String, version: String, hash: String) =
        kind.lowercase() + "-" + version.lowercase().replace(Regex("[^a-z0-9.]+"), "-") + "-" + hash.take(8)

    private fun keep(driver: Drivers.Driver, libraries: List<File>, library: File, icd: JSONObject?, paths: Paths) {
        val target = File(paths.drivers, driver.id).apply { deleteRecursively(); mkdirs() }
        for (file in libraries) if (!file.renameTo(File(target, file.name))) throw IOException("Couldn't keep the driver")
        val apiVersion = icd?.optString("api_version")?.ifEmpty { null } ?: "1.4.0"
        val icdEntry = JSONObject().put("library_path", File(target, library.name).path).put("library_arch", "64").put("api_version", apiVersion)
        File(target, "icd.json").writeText(JSONObject().put("file_format_version", "1.0.1").put("ICD", icdEntry).toString(2))
        File(target, "driver.json").writeText(JSONObject().put("name", driver.name).put("detail", driver.detail).toString())
    }

    internal fun mesaVersion(library: File): String? {
        val pattern = Regex("Mesa (\\d+\\.\\d+\\.\\d+[-A-Za-z0-9.]*)")
        library.inputStream().buffered().use { input ->
            val chunk = ByteArray(MIB)
            var carry = ""
            while (true) {
                val n = input.read(chunk)
                if (n < 0) return pattern.find(carry)?.groupValues?.get(1)
                val text = carry + String(chunk, 0, n, Charsets.ISO_8859_1)
                // A match running to the end of what's read may go on in the next read.
                pattern.find(text)?.takeIf { it.range.last < text.lastIndex }?.let { return it.groupValues[1] }
                carry = text.takeLast(64)
            }
        }
    }
}

/** Just enough ELF to tell what a 64-bit little-endian arm64 library needs. */
object Elf {
    private val MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1
    private const val EM_AARCH64 = 183
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L

    fun isElf(stream: BufferedInputStream) = stream.peek(MAGIC.size).contentEquals(MAGIC)

    /** DT_NEEDED: the libraries it loads. */
    fun neededLibraries(file: File): List<String> = RandomAccessFile(file, "r").use { f ->
        fun read(offset: Long, size: Int): ByteBuffer {
            val bytes = ByteArray(size)
            f.seek(offset)
            f.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        val header = read(0, 64)
        if (!ByteArray(MAGIC.size) { header.get(it) }.contentEquals(MAGIC) || header.get(4).toInt() != ELFCLASS64 ||
            header.get(5).toInt() != ELFDATA2LSB || header.getShort(18).toInt() != EM_AARCH64) {
            throw IOException("That driver isn't an arm64 library")
        }
        val phOffset = header.getLong(32)
        val phSize = header.getShort(54).toInt()
        val phCount = header.getShort(56).toInt()
        class Segment(val type: Int, val offset: Long, val vaddr: Long, val size: Long)
        val segments = (0 until phCount).map {
            val p = read(phOffset + it.toLong() * phSize, 56)
            Segment(p.getInt(0), p.getLong(8), p.getLong(16), p.getLong(32))
        }
        val dynamic = segments.firstOrNull { it.type == PT_DYNAMIC } ?: return@use emptyList()
        val entries = read(dynamic.offset, dynamic.size.toInt())
        val needed = ArrayList<Long>()
        var strtab = 0L
        while (entries.remaining() >= 16) {
            val tag = entries.getLong()
            val value = entries.getLong()
            when (tag) { DT_NULL -> break; DT_NEEDED -> needed += value; DT_STRTAB -> strtab = value }
        }
        // DT_STRTAB is an address: find the loaded segment holding it.
        val load = segments.firstOrNull { it.type == PT_LOAD && strtab >= it.vaddr && strtab < it.vaddr + it.size }
            ?: throw IOException("Not a usable library: its string table isn't loaded")
        val base = load.offset + (strtab - load.vaddr)
        needed.map { at ->
            f.seek(base + at)
            buildString { while (true) { val c = f.read(); if (c <= 0) break; append(c.toChar()) } }
        }
    }
}

/** The first [n] bytes, leaving the stream where it was. */
private fun BufferedInputStream.peek(n: Int): ByteArray {
    mark(n)
    val head = ByteArray(n).also { read(it) }
    reset()
    return head
}
