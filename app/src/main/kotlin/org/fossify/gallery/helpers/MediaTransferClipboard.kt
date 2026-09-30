package org.fossify.gallery.helpers

/** Private to this app; never exposes photo paths to the system clipboard. */
object MediaTransferClipboard {
    var paths: List<String> = emptyList()
        private set
    var copy: Boolean = true
        private set
    var busy = false
    val hasItems get() = paths.isNotEmpty()

    fun set(items: List<String>, isCopy: Boolean): Boolean {
        if (busy) return false
        paths = items.distinct().toList()
        copy = isCopy
        return true
    }

    fun clear() {
        if (!busy) paths = emptyList()
    }

    fun retain(items: List<String>) {
        paths = items.toList()
    }
}
