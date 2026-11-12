package app.pocketfoundry.core.drivers

import android.content.Context
import android.net.Uri
import android.util.Log
import app.pocketfoundry.core.system.Http
import app.pocketfoundry.core.system.MIB
import app.pocketfoundry.core.system.NativeXzInputStream
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.Uris
import app.pocketfoundry.core.runtime.Runtime
import app.pocketfoundry.core.system.sha256
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
 * alone. Other libraries in the package stay beside it, for what it needs and the runtime lacks. The catalog's
 * drivers are kept the same way once downloaded, with the catalog's entry for them.
 */
object DriverImport {
    private val SHARED_LIBRARY = Regex("""[^/]+\.so(\.\d+)*""")
    private val KIND_PATTERN = Regex("""libvulkan_([a-z0-9]+)""")
    private const val PANVK = "PanVK"
    private val KIND_NAMES = mapOf("freedreno" to "Turnip", "panfrost" to PANVK, "radeon" to "RADV", "powervr" to "PowerVR")
    private val GZIP = byteArrayOf(0x1F, 0x8B.toByte())
    private val XZ = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZSTD = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    /**
     * Each driver's folder holds its libraries, a Vulkan driver's ICD manifest naming its own, and what the list
     * shows: an imported one's name and the file it came from, a downloaded one's catalog entry.
     */
    private const val ICD = "icd.json"
    private const val INFO = "driver.json"
    private const val MOBILEGLUES = "libmobileglues.so"
    private const val MOBILEGLUES_SHIM = "libmgx11.so"
    private const val CATALOG = "catalog"

    fun folder(paths: Paths, id: String) = File(paths.drivers, id)

    fun icd(paths: Paths, id: String) = File(folder(paths, id), ICD)

    fun has(paths: Paths, id: String) = File(folder(paths, id), INFO).exists()

    fun remove(paths: Paths, id: String) = folder(paths, id).deleteRecursively()

    /** The imported ones, newest first. */
    fun all(paths: Paths): List<Drivers.Driver> = infos(paths).mapNotNull { (folder, info) ->
        if (info.has(CATALOG)) return@mapNotNull null
        // "detail" is "Imported · <file>" in drivers imported before 0.8.18.
        Drivers.Driver(folder.name, info.getString("name"), Drivers.Origin.IMPORTED,
                       info.optString("file").ifEmpty { info.optString("detail").substringAfter(" · ") })
    }

    /** The catalog's, as downloaded. */
    fun downloaded(paths: Paths): List<Catalog.Entry> = infos(paths).mapNotNull { (_, info) ->
        info.optJSONObject(CATALOG)?.let { runCatching { Catalog.Entry(it) }.getOrNull() }
    }

    fun downloaded(paths: Paths, id: String): Catalog.Entry? = runCatching {
        JSONObject(File(folder(paths, id), INFO).readText()).optJSONObject(CATALOG)?.let { Catalog.Entry(it) }
    }.getOrNull()

    private fun infos(paths: Paths): List<Pair<File, JSONObject>> =
        paths.drivers.listFiles().orEmpty().sortedByDescending { it.lastModified() }.mapNotNull { folder ->
            try {
                folder to JSONObject(File(folder, INFO).readText())
            } catch (e: Exception) {
                Log.w(TAG, "Not a driver: $folder", e)
                null
            }
        }

    /** Off the main thread: [entry]'s package from the catalog, checked against its SHA-256, then kept. */
    fun download(context: Context, entry: Catalog.Entry, onPercent: (Int) -> Unit) {
        val paths = Paths(context)
        val archive = File(context.cacheDir, "driver-${entry.id}")
        val staging = paths.driverStaging.apply { deleteRecursively(); mkdirs() }
        try {
            Http.download(entry.url, archive, onPercent)
            if (archive.sha256() != entry.sha256) throw IOException("the download doesn't match the catalog")
            val icd = BufferedInputStream(archive.inputStream()).use { unpack(it, staging, archive.name) }
            val libraries = staging.listFiles().orEmpty().filter { SHARED_LIBRARY.matches(it.name) }
            val info = JSONObject().put("name", entry.name).put(CATALOG, entry.json)
            if (entry.kind != Catalog.Kind.VULKAN) keep(entry.id, info, libraries, null, null, paths)
            else {
                val library = libraries.firstOrNull { it.name.startsWith("libvulkan_") }
                    ?: throw IOException("no Vulkan driver in the download")
                keep(entry.id, info, libraries, library, icd, paths)
            }
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    fun import(context: Context, uri: Uri): Drivers.Driver {
        val paths = Paths(context)
        Runtime.prepare(context)
        val staging = paths.driverStaging.apply { deleteRecursively(); mkdirs() }
        try {
            val fileName = Uris.displayName(context, uri)
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the selected file")
            val icd = BufferedInputStream(input).use { unpack(it, staging, fileName) }
            val libraries = staging.listFiles().orEmpty().filter { SHARED_LIBRARY.matches(it.name) }
            if (libraries.any { it.name == MOBILEGLUES } && libraries.any { it.name == MOBILEGLUES_SHIM })
                return keepMobileGlues(paths, libraries, fileName)
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
            val driver = Drivers.Driver(id, "$kind $version", Drivers.Origin.IMPORTED, fileName ?: "driver")
            keep(id, JSONObject().put("name", driver.name).put("file", driver.note), libraries, library, icd, paths)
            return driver
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * A package of pocket-foundry-drivers' gles kind (MobileGlues and its shim), to try a build before it's in the
     * catalog: kept as a downloaded one, which the catalog has no newer build of.
     */
    private fun keepMobileGlues(paths: Paths, libraries: List<File>, fileName: String?): Drivers.Driver {
        val id = "mobileglues-" + libraries.first { it.name == MOBILEGLUES }.sha256().take(8)
        val entry = JSONObject().put("id", id).put("name", "MobileGlues (imported)").put("kind", "gles")
            .put("version", "imported").put("about", "MobileGlues imported from ${fileName ?: "a file"}.")
            .put("for", "Any GPU").put("file", JSONObject().put("url", "").put("sha256", "").put("size", 0))
        keep(id, JSONObject().put("name", entry.getString("name")).put(CATALOG, entry), libraries, null, null, paths)
        val listed = Catalog.Entry(entry)
        return Drivers.Driver(id, listed.name, Drivers.Origin.CATALOG, listed.version, listed.about, listed.forGpus,
                              listed)
    }

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
        for ((name, target) in links) File(staging, target).takeIf { it.exists() }?.copyTo(File(staging, name), overwrite = true)
        return icd
    }

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
    /** Factorio's GPU compressor fails on PanVK: Drivers.compression tells it apart by its id. */
    fun isPanVK(id: String) = id.startsWith(PANVK.lowercase() + "-")

    internal fun driverId(kind: String, version: String, hash: String) =
        kind.lowercase() + "-" + version.lowercase().replace(Regex("[^a-z0-9.]+"), "-") + "-" + hash.take(8)

    /** [library]: a Vulkan driver's, which its ICD manifest names; an OpenGL driver has none. */
    private fun keep(id: String, info: JSONObject, libraries: List<File>, library: File?, icd: JSONObject?,
                     paths: Paths) {
        val target = folder(paths, id).apply { deleteRecursively(); mkdirs() }
        for (file in libraries) if (!file.renameTo(File(target, file.name))) throw IOException("Couldn't keep the driver")
        if (library != null) {
            val apiVersion = icd?.optString("api_version")?.ifEmpty { null } ?: "1.4.0"
            val icdEntry = JSONObject().put("library_path", File(target, library.name).path).put("library_arch", "64")
                .put("api_version", apiVersion)
            val manifest = JSONObject().put("file_format_version", "1.0.1").put("ICD", icdEntry)
            File(target, ICD).writeText(manifest.toString(2))
        }
        File(target, INFO).writeText(info.toString())
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
        // DT_STRTAB is an address, not a file offset.
        val load = segments.firstOrNull { it.type == PT_LOAD && strtab >= it.vaddr && strtab < it.vaddr + it.size }
            ?: throw IOException("Not a usable library: its string table isn't loaded")
        val base = load.offset + (strtab - load.vaddr)
        needed.map { at ->
            f.seek(base + at)
            buildString { while (true) { val c = f.read(); if (c <= 0) break; append(c.toChar()) } }
        }
    }
}

private fun BufferedInputStream.peek(n: Int): ByteArray {
    mark(n)
    val head = ByteArray(n).also { read(it) }
    reset()
    return head
}
