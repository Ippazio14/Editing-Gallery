package org.fossify.gallery.scoped

object MediaOrdering {
    // Unknown dates/sizes remain last in either direction; names resolve equal keys.
    fun sort(media: List<FolderAccess.Media>, field: String, descending: Boolean): List<FolderAccess.Media> =
        media.sortedWith { a, b ->
            val names = a.name.compareTo(b.name, ignoreCase = true)
            if (field == "name") {
                if (descending) -names else names
            } else {
                fun value(m: FolderAccess.Media) = when (field) {
                    "created" -> m.created
                    "size" -> m.size
                    else -> m.modified
                }
                val av = value(a); val bv = value(b)
                when {
                    av == null && bv == null -> names
                    av == null -> 1
                    bv == null -> -1
                    av == bv -> names
                    descending -> bv.compareTo(av)
                    else -> av.compareTo(bv)
                }
            }
        }
}
