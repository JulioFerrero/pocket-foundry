package app.pocketfoundry.core.drivers

import android.content.Context
import android.util.Log
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.TAG
import org.json.JSONObject
import java.io.File

/**
 * The drivers inside the app (runtime/gles, packed by runtime/assemble): Phone's OpenGL ES, MobileGlues, so every
 * phone has it before the CPU, with downloads off or without a connection. Each is a package and its entry as
 * pocket-foundry-drivers' catalog lists it, but without a download: installed like one, a newer build from the catalog
 * takes over as an update does.
 */
object BundledDrivers {
    private const val FOLDER = "drivers"
    @Volatile private var entries: List<Catalog.Entry>? = null

    fun entries(context: Context): List<Catalog.Entry> = entries ?: read(context).also { entries = it }

    fun find(context: Context, id: String) = entries(context).firstOrNull { it.id == id }

    private fun read(context: Context): List<Catalog.Entry> = runCatching {
        context.assets.list(FOLDER).orEmpty().filter { it.endsWith(".json") }.map { name ->
            Catalog.Entry(JSONObject(context.assets.open("$FOLDER/$name").bufferedReader().use { it.readText() }))
        }
    }.onFailure { Log.w(TAG, "The app's own drivers are unreadable", it) }.getOrDefault(emptyList())

    /**
     * Off the main thread: each one not installed, or installed from an older app, from the app itself. One
     * downloaded from the catalog stays.
     */
    fun installMissing(context: Context) {
        val paths = Paths(context)
        for (entry in entries(context)) {
            val installed = DriverImport.downloaded(paths, entry.id)
            if (installed != null && (installed.url.isNotEmpty() || installed.sha256 == entry.sha256)) continue
            val archive = File(context.cacheDir, "driver-${entry.id}")
            try {
                context.assets.open("$FOLDER/${entry.id}.tar.xz").use { input ->
                    archive.outputStream().use { input.copyTo(it) }
                }
                DriverImport.install(context, entry, archive)
                // A new build starts with nothing known about how it played.
                DriverRecords.forgetGames(context, entry.id)
            } catch (e: Exception) {
                Log.w(TAG, "${entry.name} from the app didn't install", e)
            } finally {
                archive.delete()
            }
        }
    }
}
