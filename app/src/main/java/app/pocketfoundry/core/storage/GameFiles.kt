package app.pocketfoundry.core.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.pocketfoundry.R
import app.pocketfoundry.core.engine.Paths
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Factorio's own folder (mods, saves, config, scenarios, blueprints) in the phone's Files app and every file picker,
 * so a mod can be added or removed, config.ini edited or a save shared without a computer. The game stays out, and
 * so does player-data.json, which holds the factorio.com login. Document ids are paths under the folder, "/" itself.
 */
class GameFilesProvider : DocumentsProvider() {
    private val base by lazy { Paths(context!!).gameData.canonicalFile }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT)
            .add(Root.COLUMN_DOCUMENT_ID, "/")
            .add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, "Factorio's mods, saves and config")
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_AVAILABLE_BYTES, base.freeSpace)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { add(file(documentId)) }

    override fun queryChildDocuments(parentId: String, projection: Array<String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            file(parentId).listFiles()?.filterNot(::hidden)?.forEach { add(it) }
            setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentId))
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun getDocumentType(documentId: String) = mime(file(documentId))

    override fun isChildDocument(parentId: String, documentId: String) =
        file(documentId).path.startsWith(file(parentId).path + "/")

    /** A name already taken gets " (1)", " (2)"… before its extension, as Files expects. */
    override fun createDocument(parentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentId)
        if (displayName.startsWith(".")) throw IOException("Names starting with a dot stay hidden")
        var file = File(parent, displayName)
        var n = 1
        while (file.exists() || hidden(file)) {
            val dot = displayName.lastIndexOf('.').takeIf { it > 0 && mimeType != Document.MIME_TYPE_DIR } ?: displayName.length
            file = File(parent, displayName.substring(0, dot) + " (${n++})" + displayName.substring(dot))
        }
        val made = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!made) throw IOException("Couldn't create $displayName")
        changed(parent)
        return id(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = file(documentId)
        if (file == base || !file.deleteRecursively()) throw IOException("Couldn't delete ${file.name}")
        changed(file.parentFile!!)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = file(documentId)
        val renamed = File(file.parentFile, displayName)
        if (file == base || renamed.exists() || hidden(renamed) || !file.renameTo(renamed)) {
            throw IOException("Couldn't rename ${file.name}")
        }
        changed(file.parentFile!!)
        return id(renamed)
    }

    private fun MatrixCursor.add(file: File) {
        var flags = if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (file != base) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id(file))
            .add(Document.COLUMN_DISPLAY_NAME, if (file == base) context!!.getString(R.string.app_name) else file.name)
            .add(Document.COLUMN_MIME_TYPE, mime(file))
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** Only paths inside the folder, never a hidden one. */
    private fun file(documentId: String): File {
        val file = File(base, documentId).canonicalFile
        if ((file != base && !file.path.startsWith(base.path + "/")) || hidden(file)) throw FileNotFoundException(documentId)
        return file
    }

    private fun id(file: File) = "/" + file.relativeTo(base).path

    private fun hidden(file: File) =
        file.name.startsWith(".") || (file.parentFile == base && file.name == "player-data.json")

    /** Factorio's text files as text, so any text editor offers to open them. */
    private fun mime(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase()
        if (extension in TEXT) return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun changed(dir: File) =
        context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, id(dir)), null)

    companion object {
        const val AUTHORITY = "app.pocketfoundry.files"
        const val ROOT = "factorio"
        private val TEXT = setOf("json", "ini", "cfg", "lua", "log", "txt", "md")
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
