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
    data class Media(val uri: Uri, val name: String, val mime: String, val size: Long? = null, val modified: Long? = null, val created: Long? = null) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }
    data class Album(val uri: Uri, val label: String, val media: List<Media>) {
        val images: Int get() = media.count { !it.isVideo }
        val videos: Int get() = media.count { it.isVideo }
    }
    data class Scan(val albums: List<Album>, val unavailable: List<String>)

    var setupComplete: Boolean
        get() = preferences.getBoolean("selection_completed_v2", false)
        set(value) { check(preferences.edit().putBoolean("selection_completed_v2", value).commit()) }

    /** The selection screen remembers unchecked rows without retaining their access. */
    fun knownRoots(): List<Root> = (preferences.getStringSet("known_roots", emptySet()).orEmpty() +
        preferences.getStringSet("roots", emptySet()).orEmpty()).map { Uri.parse(it) }
        .map { Root(it, label(it)) }.sortedBy { it.label.lowercase() }

    fun hasReadAccess(uri: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun roots(): List<Root> = preferences.getStringSet("roots", emptySet()).orEmpty()
        .map { Uri.parse(it) }.map { Root(it, label(it)) }.sortedBy { it.label.lowercase() }

    fun add(uri: Uri, returnedFlags: Int) {
        val flags = returnedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        resolver.takePersistableUriPermission(uri, flags)
        val roots = preferences.getStringSet("roots", emptySet()).orEmpty().toMutableSet()
        roots.add(uri.toString())
        val known = preferences.getStringSet("known_roots", emptySet()).orEmpty().toMutableSet()
        known.addAll(roots)
        check(preferences.edit().putStringSet("roots", roots).putStringSet("known_roots", known).commit())
    }

    fun remove(uri: Uri) {
        val roots = preferences.getStringSet("roots", emptySet()).orEmpty().toMutableSet()
        val known = preferences.getStringSet("known_roots", emptySet()).orEmpty().toMutableSet()
        known.addAll(roots)
        // Revoke before clearing the check mark. Failures must not look like a successful revocation.
        resolver.persistedUriPermissions.filter { it.uri == uri }.forEach {
            val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            resolver.releasePersistableUriPermission(uri, flags)
        }
        roots.remove(uri.toString())
        check(preferences.edit().putStringSet("roots", roots).putStringSet("known_roots", known).commit())
    }

    private fun label(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
        if (uri.authority == "com.android.externalstorage.documents" && id.contains(':')) {
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':')
            return if (volume == "primary") path.ifBlank { "Memoria interna" } else "$volume/$path"
        }
        // This method is used while drawing the UI: never query a document provider here.
        // Downloads providers can return raw filesystem IDs, which are labels only, never read as paths.
        return when {
            id.startsWith("raw:/storage/emulated/0/") -> id.removePrefix("raw:/storage/emulated/0/")
            id.isNotBlank() -> id
            else -> uri.toString()
        }
    }

    /** Keep explicit roots, hide empty descendants, and deduplicate overlapping grants. */
    fun scan(includeCreation: Boolean = false): Scan {
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
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
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
                            if (mime.startsWith("image/") || mime.startsWith("video/")) {
                                fun number(column: String): Long? {
                                    val index = it.getColumnIndex(column)
                                    return if (index < 0 || it.isNull(index)) null else it.getLong(index)
                                }
                                val size = number(DocumentsContract.Document.COLUMN_SIZE)
                                val modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { value -> value > 0 }
                                val created = if (includeCreation) creationDate(uri, mime, size, modified) else null
                                media.add(Media(uri, name, mime, size, modified, created))
                            }
                        }
                    }
                }
                FolderWalker.Contents(directories, media.sortedBy { it.name.lowercase() })
            }, cancelled = { Thread.currentThread().isInterrupted })
        return Scan(result.albums.map { Album(it.node.value, it.node.label, it.media) }.sortedBy { it.label.lowercase() },
            (unavailable + result.unavailable).distinct())
    }

    private val dates = mutableMapOf<String, Long?>()
    // SAF has no creation-date column. Use the original capture date when available;
    // never relabel modification time as creation time.
    private fun creationDate(uri: Uri, mime: String, size: Long?, modified: Long?): Long? {
        val key = "$uri:$size:$modified"
        if (dates.containsKey(key)) return dates[key]
        val value = runCatching {
            if (mime.startsWith("image/")) {
                resolver.openInputStream(uri)?.use { input ->
                    androidx.exifinterface.media.ExifInterface(input)
                        .getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)?.let { raw ->
                            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).apply { isLenient = false }.parse(raw)?.time
                        }
                }
            } else {
                val reader = android.media.MediaMetadataRetriever()
                try {
                    reader.setDataSource(context, uri)
                    reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.let { raw ->
                        listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").firstNotNullOfOrNull { pattern ->
                            runCatching { java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                                timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false
                            }.parse(raw)?.time }.getOrNull()
                        }
                    }
                } finally { reader.release() }
            }
        }.getOrNull()?.takeIf { it > 0 }
        dates[key] = value
        return value
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
