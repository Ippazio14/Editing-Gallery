package org.fossify.gallery.scoped

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** The only source of albums in the scoped gallery. Never resolves a URI to a filesystem path. */
class FolderAccess(private val context: Context) {
    private val preferences = context.getSharedPreferences("authorized_folders", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    data class Root(val uri: Uri, val label: String)
    data class Media(val uri: Uri, val name: String, val mime: String) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }
    data class Album(val uri: Uri, val label: String, val media: List<Media>) {
        val images: Int get() = media.count { !it.isVideo }
        val videos: Int get() = media.count { it.isVideo }
    }
    data class Scan(val albums: List<Album>, val unavailable: List<String>)

    fun roots(): List<Root> = preferences.getStringSet("roots", emptySet()).orEmpty()
        .map { Uri.parse(it) }.map { Root(it, label(it)) }.sortedBy { it.label.lowercase() }

    fun add(uri: Uri, returnedFlags: Int) {
        val flags = returnedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        resolver.takePersistableUriPermission(uri, flags)
        val roots = preferences.getStringSet("roots", emptySet()).orEmpty().toMutableSet()
        roots.add(uri.toString())
        check(preferences.edit().putStringSet("roots", roots).commit())
    }

    fun remove(uri: Uri) {
        val roots = preferences.getStringSet("roots", emptySet()).orEmpty().toMutableSet()
        roots.remove(uri.toString())
        check(preferences.edit().putStringSet("roots", roots).commit())
        resolver.persistedUriPermissions.filter { it.uri == uri }.forEach {
            val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            resolver.releasePersistableUriPermission(uri, flags)
        }
    }

    private fun label(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
        if (uri.authority == "com.android.externalstorage.documents" && id.contains(':')) {
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':')
            return if (volume == "primary") path.ifBlank { "Memoria interna" } else "$volume/$path"
        }
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() ?: uri.toString()
    }

    /** Empty parents and descendants are albums; overlapping grants are deduplicated. */
    fun scan(): Scan {
        val unavailable = mutableListOf<String>()
        val readableRoots = roots().mapNotNull { root ->
            if (resolver.persistedUriPermissions.none { it.uri == root.uri && it.isReadPermission }) {
                unavailable.add(root.label)
                null
            } else {
                runCatching {
                    FolderWalker.Node(DocumentsContract.buildDocumentUriUsingTree(root.uri,
                        DocumentsContract.getTreeDocumentId(root.uri)), root.label)
                }.getOrElse { unavailable.add(root.label); null }
            }
        }
        val labels = mutableMapOf<String, String>()
        readableRoots.forEach { labels[it.value.toString()] = it.label }
        val result = FolderWalker.walk<Uri, Media>(readableRoots,
            key = { "${it.authority}:${DocumentsContract.getDocumentId(it)}" },
            read = { directory ->
                val path = labels.getValue(directory.toString())
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory,
                    DocumentsContract.getDocumentId(directory))
                val media = mutableListOf<Media>()
                val directories = mutableListOf<FolderWalker.Node<Uri>>()
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                val cursor = resolver.query(children, projection, null, null, null)
                    ?: throw IOException("Provider unavailable")
                cursor.use {
                    while (it.moveToNext()) {
                        val childId = it.getString(0)
                        val name = it.getString(1) ?: childId
                        val type = it.getString(2).orEmpty()
                        val uri = DocumentsContract.buildDocumentUriUsingTree(directory, childId)
                        if (type == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val label = "$path/$name"
                            labels[uri.toString()] = label
                            directories.add(FolderWalker.Node(uri, label))
                        } else {
                            val mime = if (type.startsWith("image/") || type.startsWith("video/")) type else
                                MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()).orEmpty()
                            if (mime.startsWith("image/") || mime.startsWith("video/")) media.add(Media(uri, name, mime))
                        }
                    }
                }
                FolderWalker.Contents(directories, media.sortedBy { it.name.lowercase() })
            }, cancelled = { Thread.currentThread().isInterrupted })
        return Scan(result.albums.map { Album(it.node.value, it.node.label, it.media) }.sortedBy { it.label.lowercase() },
            (unavailable + result.unavailable).distinct())
    }

    fun canWrite(uri: Uri): Boolean = DocumentFile.fromSingleUri(context, uri)?.canWrite() == true

    /** Copy first, close the streams, then delete only on success. No overwriting existing files. */
    fun transfer(media: Media, destination: Uri, move: Boolean) {
        // Use the exact document URI, not the tree root, for destination subfolders.
        val exact = DocumentFile.fromSingleUri(context, destination) ?: throw IOException("Cartella non disponibile")
        require(exact.canWrite() && exact.exists()) { "Cartella non scrivibile" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(destination, DocumentsContract.getDocumentId(destination))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) require(it.getString(0) != media.name) { "Esiste già un file con questo nome" }
        } ?: throw IOException("Cartella non disponibile")
        val target = DocumentsContract.createDocument(resolver, destination, media.mime, media.name)
            ?: throw IOException("Impossibile creare il file")
        try {
            val input = resolver.openInputStream(media.uri) ?: throw IOException("Impossibile leggere il file")
            input.use { source ->
                val output = resolver.openOutputStream(target, "w") ?: throw IOException("Impossibile scrivere il file")
                output.use { source.copyTo(it) }
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw error
        }
        if (move && !DocumentsContract.deleteDocument(resolver, media.uri)) {
            throw IOException("Copia creata; impossibile eliminare l'originale")
        }
    }
}
