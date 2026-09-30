package org.fossify.gallery.extensions

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.helpers.MediaTransferClipboard
import java.io.File

/** Copies are published only after the complete stream has been written and closed. */
fun BaseSimpleActivity.pasteMediaTransfer(destination: String, finished: () -> Unit) {
    val clipboard = MediaTransferClipboard
    if (clipboard.busy || !clipboard.hasItems) return
    val paths = clipboard.paths.toList()
    val copy = clipboard.copy
    if (paths.any { it.getParentPath().trimEnd('/') == destination.trimEnd('/') }) {
        toast(org.fossify.commons.R.string.source_and_destination_same)
        return
    }
    clipboard.busy = true
    finished()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        ensureBackgroundThread {
            val failed = ArrayList<String>()
            for (path in paths) {
                var target: File? = null
                try {
                    val source = File(path)
                    var name = source.name
                    var suffix = 1
                    while (File(destination, name).exists()) {
                        name = source.nameWithoutExtension + " (${suffix++})" + if (source.extension.isEmpty()) "" else ".${source.extension}"
                    }
                    val targetFile = File(destination, name)
                    target = targetFile
                    val size = source.length()
                    val written = source.inputStream().use { input -> targetFile.outputStream().use { input.copyTo(it) } }
                    check(written == size)
                    targetFile.setLastModified(source.lastModified())
                    if (!copy) check(source.delete())
                    rescanPaths(arrayListOf(targetFile.absolutePath, path)) { }
                    target = null
                } catch (_: Exception) {
                    // A completed copy is kept if removing its original failed.
                    target?.takeIf { it.length() != File(path).length() }?.delete()
                    failed.add(path)
                }
            }
            runOnUiThread {
                clipboard.busy = false
                clipboard.retain(failed)
                toast(if (failed.isEmpty()) R.string.transfer_done else R.string.transfer_failed)
                finished()
            }
        }
        return
    }
    ensureBackgroundThread {
        val failed = ArrayList<String>()
        val completed = ArrayList<Triple<String, Uri, String>>()
        val newPaths = ArrayList<String>()
        try {
            val volume = getSystemService(StorageManager::class.java).storageVolumes.firstOrNull {
                val root = it.directory?.absolutePath ?: return@firstOrNull false
                destination.startsWith("$root/")
            } ?: error("Destination is not a media storage volume")
            val root = requireNotNull(volume.directory).absolutePath
            val volumeName = requireNotNull(volume.mediaStoreVolumeName)
            val relative = destination.removePrefix("$root/").trimEnd('/') + "/"
            for (path in paths) {
                var pending: Uri? = null
                try {
                    val source = contentResolver.query(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE),
                        "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null
                    )?.use { cursor ->
                        check(cursor.moveToFirst())
                        Triple(cursor.getLong(0), cursor.getString(1), cursor.getLong(2))
                    } ?: error("Source is unavailable")
                    val mime = requireNotNull(source.second ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension.lowercase()))
                    val collection = when {
                        mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volumeName)
                        mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volumeName)
                        else -> error("Unsupported media type")
                    }
                    val sourceCollection = if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val sourceUri = ContentUris.withAppendedId(sourceCollection, source.first)
                    // Keep both files on name conflicts; never overwrite an existing photograph.
                    val original = File(path)
                    var name = original.name
                    var suffix = 1
                    fun nameExists(candidate: String): Boolean = contentResolver.query(
                        MediaStore.Files.getContentUri(volumeName), arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(relative, candidate), null
                    )?.use { it.moveToFirst() } == true || File(destination, candidate).exists()
                    while (nameExists(name)) {
                        name = original.nameWithoutExtension + " (${suffix++})" + if (original.extension.isEmpty()) "" else ".${original.extension}"
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val targetUri = requireNotNull(contentResolver.insert(collection, values))
                    pending = targetUri
                    val written = requireNotNull(contentResolver.openInputStream(sourceUri)).use { input ->
                        requireNotNull(contentResolver.openOutputStream(targetUri, "w")).use { output -> input.copyTo(output) }
                    }
                    check(source.third <= 0 || written == source.third) { "Incomplete copy" }
                    check(contentResolver.update(targetUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null) == 1)
                    newPaths.add("$destination/$name")
                    completed.add(Triple(path, sourceUri, "$destination/$name"))
                    pending = null
                } catch (_: Exception) {
                    pending?.let { runCatching { contentResolver.delete(it, null, null) } }
                    failed.add(path)
                }
            }
        } catch (_: Exception) {
            failed.clear()
            failed.addAll(paths)
        }
        runOnUiThread {
            fun finishTransfer(deleted: Boolean) {
                clipboard.busy = false
                clipboard.retain(failed)
                rescanPaths(newPaths) { }
                completed.forEach { (path, _) -> applicationContext.rescanFolderMedia(path.getParentPath()) }
                applicationContext.rescanFolderMedia(destination)
                toast(when {
                    !copy && !deleted && completed.isNotEmpty() -> R.string.transfer_kept
                    failed.isNotEmpty() -> R.string.transfer_failed
                    else -> R.string.transfer_done
                })
                finished()
            }
            if (!copy && completed.isNotEmpty()) {
                // Android removes only originals whose full copy was successfully published.
                val batches = completed.map { it.second }.chunked(2000)
                fun deleteBatch(index: Int) {
                    if (index == batches.size) {
                        finishTransfer(true)
                    } else {
                        try {
                            // Preflight also ensures failures release the transfer lock.
                            MediaStore.createDeleteRequest(contentResolver, batches[index])
                            deleteSDK30Uris(batches[index]) { approved ->
                                if (approved) {
                                    val moved = completed.drop(index * 2000).take(2000)
                                    ensureBackgroundThread {
                                        moved.forEach { applicationContext.updateDBMediaPath(it.first, it.third) }
                                        runOnUiThread { deleteBatch(index + 1) }
                                    }
                                } else finishTransfer(false)
                            }
                        } catch (_: Exception) {
                            finishTransfer(false)
                        }
                    }
                }
                deleteBatch(0)
            } else {
                finishTransfer(false)
            }
        }
    }
}
