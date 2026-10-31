package app.pocketfoundry.core.graphics

import android.content.Context
import android.net.Uri
import app.pocketfoundry.NativeXzInputStream
import app.pocketfoundry.core.engine.Paths
import app.pocketfoundry.core.engine.Runtime
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.storage.displayName
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
import java.security.MessageDigest
import kotlin.collections.get
import kotlin.collections.iterator

object Drivers {
    class Driver(val id: String, val name: String, val detail: String)

    const val ZINK = "zink"
    const val FREEDRENO = "freedreno"
    const val CPU = "cpu"

    private fun dir(paths: Paths) = File(paths.files, "drivers")

    fun all(context: Context): List<Driver> {
        val paths = Paths(context)
        val mesa = paths.lib.list()?.firstOrNull { it.startsWith("libgallium-") }
            ?.removePrefix("libgallium-")?.removeSuffix(".so")?.let { "Mesa $it" } ?: "Mesa"
        val bundled = listOf(
            Driver(ZINK, "Zink + Turnip", "Bundled · $mesa"),
            Driver(FREEDRENO, "Freedreno", "Bundled · OpenGL, slower"),
            Driver(CPU, "CPU", "Experimental · slow, uses a lot of battery"),
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

    fun chosen(context: Context): String {
        val id = Settings.driver(context)
        return if (all(context).any { it.id == id }) id else ZINK
    }

    fun imported(context: Context) = chosen(context).let { it != ZINK && it != FREEDRENO && it != CPU }

    fun cpu(context: Context) = chosen(context) == CPU

    fun panvk(context: Context) = chosen(context).startsWith("panvk-")

    /** Over Session's defaults, which are Zink on our Turnip. */
    fun environment(context: Context): Map<String, String> = when (val id = chosen(context)) {
        ZINK -> emptyMap()
        FREEDRENO -> mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "kgsl")
        // llvmpipe, Mesa on the CPU. Software GLX picks Zink while the override names it, so it names llvmpipe.
        // 256-bit code (two NEON registers at a time) and bilinear filtering were the fastest that still looked right.
        CPU -> mapOf("LIBGL_ALWAYS_SOFTWARE" to "1", "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
                     "GALLIUM_DRIVER" to "llvmpipe", "LP_NATIVE_VECTOR_WIDTH" to "256", "LP_PERF" to "no_mip_linear")
        // Imported drivers lack our wait-before-present patch; the software path waits for the GPU instead.
        else -> mapOf("VK_DRIVER_FILES" to File(dir(Paths(context)), "$id/icd.json").path,
                      "MESA_VK_WSI_DEBUG" to "sw")
    }

    /** The libraries an imported driver brought, searched after the runtime's. */
    fun libraries(context: Context): File? = if (imported(context)) File(dir(Paths(context)), chosen(context)) else null

    /** Settings → Driver → Options: `NAME=value` pairs for the game's environment, to test drivers with. */
    fun options(context: Context): Map<String, String> = Settings.driverOptions(context).split(Regex("\\s+"))
        .mapNotNull { OPTION.matchEntire(it)?.destructured }.associate { (name, value) -> name to value }

    private val OPTION = Regex("""([A-Za-z_][A-Za-z0-9_]*)=(\S*)""")

    fun remove(context: Context, id: String) {
        if (id == ZINK || id == FREEDRENO || id == CPU) return
        File(dir(Paths(context)), id).deleteRecursively()
    }

    /**
     * A Linux (glibc) build of a Mesa Vulkan driver (Turnip, PanVK…), as a .tar.gz, .tar.xz or .zip, or the library
     * alone. Other libraries in the package stay beside it, for what it needs and the runtime lacks.
     */
    fun import(context: Context, uri: Uri): Driver {
        val paths = Paths(context)
        if (!Runtime.isInstalled(context)) Runtime.install(context)   // to check the libraries against
        val staging = File(dir(paths), ".importing").apply { deleteRecursively(); mkdirs() }
        try {
            val fileName = displayName(context, uri)
            var icd: JSONObject? = null
            val links = HashMap<String, String>()
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the selected file")
            BufferedInputStream(input).use { stream ->
                if (isElf(stream)) {
                    File(staging, fileName?.substringAfterLast('/')?.takeIf { SHARED.matches(it) } ?: "libvulkan_driver.so")
                        .outputStream().use { stream.copyTo(it) }
                } else archive(stream).use { entries ->
                    while (true) {
                        val entry = entries.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        when {
                            entry.isDirectory -> {}
                            entry is TarArchiveEntry && entry.isSymbolicLink -> links[name] = entry.linkName.substringAfterLast('/')
                            name.endsWith(".json") -> runCatching {
                                JSONObject(entries.readBytes().decodeToString()).getJSONObject("ICD")
                            }.getOrNull()?.let { icd = it }
                            SHARED.matches(name) -> File(staging, name).outputStream().use { entries.copyTo(it) }
                        }
                    }
                }
            }
            // libfoo.so.1 -> libfoo.so.1.2 and the like: a copy under the linked name.
            for ((name, target) in links) File(staging, target).takeIf { it.exists() }?.copyTo(File(staging, name), overwrite = true)

            val libraries = staging.listFiles().orEmpty().filter { SHARED.matches(it.name) }
            val named = icd?.optString("library_path")?.substringAfterLast('/')
            val library = libraries.firstOrNull { it.name == named }
                ?: libraries.singleOrNull { it.name.startsWith("libvulkan_") }
                ?: throw IOException("No Vulkan driver in that file (no libvulkan_….so)")
            val needed = neededLibraries(library)
            if ("libc.so.6" !in needed) throw IOException("That driver is built for Android, not Linux: it can't load here")
            val missing = needed.filter { need -> !File(paths.lib, need).exists() && libraries.none { it.name == need } }
            if (missing.isNotEmpty()) throw IOException("That driver needs ${missing.joinToString()}, which Pocket Foundry doesn't have")

            val kind = KINDS[KIND.find(named ?: library.name)?.groupValues?.get(1)] ?: "Vulkan"
            val version = mesaVersion(library) ?: "unknown"
            // Different builds often share a Mesa version (PanVK forks are all 26.3.0-devel): the library's hash keeps
            // them apart, and the same file imported again still replaces itself.
            val id = kind.lowercase() + "-" + version.lowercase().replace(Regex("[^a-z0-9.]+"), "-") + "-" + sha256(library).take(8)
            val target = File(dir(paths), id).apply { deleteRecursively(); mkdirs() }
            for (file in libraries) if (!file.renameTo(File(target, file.name))) throw IOException("Couldn't keep the driver")
            File(target, "icd.json").writeText(JSONObject().put("file_format_version", "1.0.1").put("ICD",
                JSONObject().put("library_path", File(target, library.name).path).put("library_arch", "64")
                    .put("api_version", icd?.optString("api_version")?.ifEmpty { null } ?: "1.4.0")).toString(2))
            val driver = Driver(id, "$kind $version", "Imported · ${fileName ?: "driver"}")
            File(target, "driver.json").writeText(JSONObject().put("name", driver.name).put("detail", driver.detail).toString())
            return driver
        } finally {
            staging.deleteRecursively()
        }
    }

    private val SHARED = Regex("""[^/]+\.so(\.\d+)*""")
    private val KIND = Regex("""libvulkan_([a-z0-9]+)""")
    private val KINDS = mapOf("freedreno" to "Turnip", "panfrost" to "PanVK", "radeon" to "RADV", "powervr" to "PowerVR")

    private fun isElf(stream: BufferedInputStream): Boolean {
        stream.mark(4)
        val head = ByteArray(4).also { stream.read(it) }
        stream.reset()
        return head.contentEquals(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
    }

    private fun archive(stream: BufferedInputStream): ArchiveInputStream<*> {
        stream.mark(8)
        val head = ByteArray(6).also { stream.read(it) }
        stream.reset()
        fun starts(vararg b: Int) = b.indices.all { head[it] == b[it].toByte() }
        return when {
            starts(0x1F, 0x8B) -> TarArchiveInputStream(GzipCompressorInputStream(stream))
            starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> TarArchiveInputStream(
                NativeXzInputStream(
                    stream
                )
            )
            starts(0x50, 0x4B, 0x03, 0x04) -> ZipArchiveInputStream(stream)
            starts(0x28, 0xB5, 0x2F, 0xFD) -> throw IOException("Zstandard (.tzst) drivers aren't supported: use a .tar.gz, .tar.xz or .zip")
            else -> TarArchiveInputStream(stream)
        }
    }

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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                digest.update(chunk, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** DT_NEEDED of a 64-bit little-endian arm64 ELF. */
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
