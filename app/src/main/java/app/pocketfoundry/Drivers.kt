package app.pocketfoundry

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The graphics driver the game draws with. Bundled: Zink on our Turnip (the default), and
 * freedreno, OpenGL straight to the GPU (slower; Phase 1 measured it). Imported: other Turnip
 * builds for glibc arm64 Linux, such as lfdevs/mesa-for-android-container's turnip_*.tar.gz,
 * which run under our Zink. AdrenoTools zips are built for Android's bionic and can't load in
 * the game's process, so the import checks the library before taking it.
 */
object Drivers {
    class Driver(val id: String, val name: String, val detail: String)

    const val ZINK = "zink"
    const val FREEDRENO = "freedreno"
    private const val LIBRARY = "libvulkan_freedreno.so"

    private fun dir(paths: Paths) = File(paths.files, "drivers")

    fun all(context: Context): List<Driver> {
        val paths = Paths(context)
        // The runtime's Mesa names its gallium library after its version.
        val mesa = paths.lib.list()?.firstOrNull { it.startsWith("libgallium-") }
            ?.removePrefix("libgallium-")?.removeSuffix(".so")?.let { "Mesa $it" } ?: "Mesa"
        val bundled = listOf(
            Driver(ZINK, "Zink + Turnip", "Bundled · $mesa"),
            Driver(FREEDRENO, "Freedreno", "Bundled · OpenGL, slower"),
        )
        val imported = dir(paths).listFiles { f -> File(f, "driver.json").exists() }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { d ->
                runCatching {
                    val info = JSONObject(File(d, "driver.json").readText())
                    Driver(d.name, info.getString("name"), info.getString("detail"))
                }.getOrNull()
            }
        return bundled + imported
    }

    /** The chosen driver, falling back to Zink when an imported one has gone. */
    fun chosen(context: Context): String {
        val id = Settings.driver(context)
        return if (all(context).any { it.id == id }) id else ZINK
    }

    /** What the game's environment needs on top of Session's defaults for the chosen driver. */
    fun environment(context: Context): Map<String, String> = when (val id = chosen(context)) {
        ZINK -> emptyMap()
        FREEDRENO -> mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "kgsl")
        // Imported Turnips lack our wait-before-present patch: they present through the
        // software path, which copies each frame only once the GPU has finished it.
        else -> mapOf("VK_DRIVER_FILES" to File(dir(Paths(context)), "$id/icd.json").path,
                      "MESA_VK_WSI_DEBUG" to "sw")
    }

    fun remove(context: Context, id: String) {
        if (id == ZINK || id == FREEDRENO) return
        File(dir(Paths(context)), id).deleteRecursively()
    }

    /** Takes the Turnip library out of [uri], checks it can load here, and keeps it. Returns it. */
    fun import(context: Context, uri: Uri): Driver {
        val paths = Paths(context)
        if (!Runtime.isInstalled(context)) Runtime.install(context)   // to check the libraries against
        val staging = File(dir(paths), ".importing").apply { deleteRecursively(); mkdirs() }
        try {
            val library = File(staging, LIBRARY)
            var apiVersion: String? = null
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the selected file")
            archive(input).use { entries ->
                while (true) {
                    val entry = entries.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    when {
                        entry.isDirectory -> {}
                        name == LIBRARY -> library.outputStream().use { entries.copyTo(it) }
                        name.startsWith("freedreno_icd") && name.endsWith(".json") -> apiVersion = runCatching {
                            JSONObject(entries.readBytes().decodeToString()).getJSONObject("ICD").getString("api_version")
                        }.getOrNull()
                    }
                }
            }
            if (!library.exists()) throw IOException("No Turnip in that file ($LIBRARY is missing)")
            val needed = neededLibraries(library)
            if ("libc.so.6" !in needed) throw IOException("That Turnip is built for Android, not Linux: it can't load here")
            val missing = needed.filter { !File(paths.lib, it).exists() }
            if (missing.isNotEmpty()) throw IOException("That Turnip needs ${missing.joinToString()}, which Pocket Foundry doesn't have")

            val version = mesaVersion(library) ?: "unknown"
            val id = "turnip-" + version.lowercase().replace(Regex("[^a-z0-9.]+"), "-")
            val target = File(dir(paths), id).apply { deleteRecursively(); mkdirs() }
            val placed = File(target, LIBRARY)
            if (!library.renameTo(placed)) throw IOException("Couldn't keep the driver")
            File(target, "icd.json").writeText(JSONObject().put("file_format_version", "1.0.1").put("ICD",
                JSONObject().put("library_path", placed.path).put("library_arch", "64")
                    .put("api_version", apiVersion ?: "1.4.0")).toString(2))
            val driver = Driver(id, "Turnip $version", "Imported · ${displayName(context, uri) ?: "driver"}")
            File(target, "driver.json").writeText(JSONObject().put("name", driver.name).put("detail", driver.detail).toString())
            return driver
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Entries of a .tar.gz, .tar.xz, .tar or .zip, told apart by their first bytes. */
    private fun archive(input: InputStream): ArchiveInputStream<*> {
        val stream = BufferedInputStream(input)
        stream.mark(8)
        val head = ByteArray(6).also { stream.read(it) }
        stream.reset()
        fun starts(vararg b: Int) = b.indices.all { head[it] == b[it].toByte() }
        return when {
            starts(0x1F, 0x8B) -> TarArchiveInputStream(GzipCompressorInputStream(stream))
            starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> TarArchiveInputStream(NativeXzInputStream(stream))
            starts(0x50, 0x4B, 0x03, 0x04) -> ZipArchiveInputStream(stream)
            starts(0x28, 0xB5, 0x2F, 0xFD) -> throw IOException("Zstandard (.tzst) drivers aren't supported: use a .tar.gz, .tar.xz or .zip")
            else -> TarArchiveInputStream(stream)
        }
    }

    /** "27.0.0-devel" from the "Mesa 27.0.0-devel" the library carries. */
    private fun mesaVersion(library: File): String? {
        val pattern = Regex("Mesa (\\d+\\.\\d+\\.\\d+[-A-Za-z0-9.]*)")
        library.inputStream().buffered().use { input ->
            val chunk = ByteArray(1 shl 20)
            var carry = ""
            while (true) {
                val n = input.read(chunk)
                if (n < 0) return null
                val text = carry + String(chunk, 0, n, Charsets.ISO_8859_1)
                pattern.find(text)?.let { return it.groupValues[1] }
                carry = text.takeLast(64)
            }
        }
    }

    /** The DT_NEEDED names of a 64-bit little-endian arm64 ELF library. */
    private fun neededLibraries(file: File): List<String> = RandomAccessFile(file, "r").use { f ->
        fun read(offset: Long, size: Int): ByteBuffer {
            val bytes = ByteArray(size)
            f.seek(offset)
            f.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        val header = read(0, 64)
        if (header.getInt(0) != 0x464C457F || header.get(4).toInt() != 2 || header.get(5).toInt() != 1 ||
            header.getShort(18).toInt() != 183) {
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
        val dynamic = segments.firstOrNull { it.type == 2 } ?: return@use emptyList()
        val entries = read(dynamic.offset, dynamic.size.toInt())
        val needed = ArrayList<Long>()
        var strtab = 0L
        while (entries.remaining() >= 16) {
            val tag = entries.getLong()
            val value = entries.getLong()
            when (tag) { 0L -> break; 1L -> needed += value; 5L -> strtab = value }
        }
        // DT_STRTAB is an address: find the loaded segment holding it.
        val load = segments.first { it.type == 1 && strtab >= it.vaddr && strtab < it.vaddr + it.size }
        val base = load.offset + (strtab - load.vaddr)
        needed.map { at ->
            f.seek(base + at)
            buildString { while (true) { val c = f.read(); if (c <= 0) break; append(c.toChar()) } }
        }
    }
}
