package org.fossify.gallery.scoped

/** Platform-independent traversal. Read failures never trigger a broader storage scan. */
internal object FolderWalker {
    data class Node<T>(val value: T, val label: String)
    data class Contents<T, M>(val folders: List<Node<T>>, val media: List<M>)
    data class Album<T, M>(val node: Node<T>, val media: List<M>)
    data class Result<T, M>(val albums: List<Album<T, M>>, val unavailable: List<String>)

    fun <T, M> walk(
        roots: List<Node<T>>,
        key: (T) -> String,
        read: (T) -> Contents<T, M>,
        cancelled: () -> Boolean = { false }
    ): Result<T, M> {
        val pending = ArrayDeque(roots)
        val visited = hashSetOf<String>()
        val albums = mutableListOf<Album<T, M>>()
        val unavailable = mutableListOf<String>()
        while (pending.isNotEmpty() && !cancelled()) {
            val node = pending.removeFirst()
            if (!visited.add(key(node.value))) continue
            try {
                val contents = read(node.value)
                albums.add(Album(node, contents.media))
                pending.addAll(contents.folders)
            } catch (_: Exception) {
                unavailable.add(node.label)
            }
        }
        return Result(albums, unavailable)
    }
}
