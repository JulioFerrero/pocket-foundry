package app.pocketfoundry.core.saves

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File

/**
 * Pocket Foundry before 0.9, when its id was app.pocketfoundry: another app to Android. Its Files provider
 * (GameFilesProvider) shares Factorio's folder, so this one brings the saves, mods and config over, with the access
 * Android's folder picker grants. The game itself is imported again: the provider doesn't share it.
 */
object OldApp {
    const val ID = "app.pocketfoundry"
    private const val AUTHORITY = "$ID.files"
    /** Where the folder picker opens: the old app's Factorio folder ("/" in its provider). */
    val folder: Uri = DocumentsContract.buildDocumentUri(AUTHORITY, "/")

    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(ID, 0) }.isSuccess

    /** The folder picked in the picker is the old app's, not some other. */
    fun isOldAppsFolder(tree: Uri) = tree.authority == AUTHORITY

    /** What the game remakes by itself: its sprite atlas (hundreds of MB, made for the old app's settings) and logs. */
    private val REMADE = Regex("""atlas-cache\.dat.*|crop-cache\.dat|factorio-(current|previous)\.log""")

    private class Item(val id: String, val path: String, val size: Long)

    /**
     * Copies what the old app's folder ([tree], from the picker) holds into [target], keeping the files [target]
     * already has. [progress] goes 0 to 100. Returns how many files came over.
     */
    fun bring(context: Context, tree: Uri, target: File, progress: (Int) -> Unit): Int {
        require(isOldAppsFolder(tree)) { "That's not the old Pocket Foundry's folder" }
        val resolver = context.contentResolver
        val items = mutableListOf<Item>()
        fun walk(parentId: String, path: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                                  Document.COLUMN_SIZE)
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1)
                    if (!GameFilesProvider.isPlainName(name) || REMADE.matches(name)) continue
                    val childPath = if (path.isEmpty()) name else "$path/$name"
                    if (cursor.getString(2) == Document.MIME_TYPE_DIR) walk(id, childPath)
                    else items += Item(id, childPath, cursor.getLong(3))
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "")

        val total = items.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        var copied = 0
        for (item in items) {
            val file = File(target, item.path)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                // A file appears whole or not at all: a copy cut short can't pass for a save.
                val part = File(file.path + ".part")
                resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, item.id))?.use { input ->
                    part.outputStream().use { input.copyTo(it) }
                } ?: error("Couldn't read ${item.path}")
                check(part.renameTo(file)) { "Couldn't keep ${item.path}" }
                copied++
            }
            done += item.size
            progress((100 * done / total).toInt())
        }
        return copied
    }
}
