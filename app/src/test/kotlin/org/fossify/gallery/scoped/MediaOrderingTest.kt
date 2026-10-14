package org.fossify.gallery.scoped

import android.app.Application
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MediaOrderingTest {
    private fun media(name: String, size: Long? = null, created: Long? = null, modified: Long? = null) =
        FolderAccess.Media(Uri.parse("content://test/$name"), name, "image/jpeg", size, modified, created)
    @Test fun namesIgnoreCaseAndRespectDirection() {
        val files = listOf(media("z.jpg"), media("A.jpg"), media("b.jpg"))
        assertEquals(listOf("A.jpg", "b.jpg", "z.jpg"), MediaOrdering.sort(files, "name", false).map { it.name })
        assertEquals(listOf("z.jpg", "b.jpg", "A.jpg"), MediaOrdering.sort(files, "name", true).map { it.name })
    }
    @Test fun unknownMetadataRemainsLastInBothDirections() {
        val files = listOf(media("unknown"), media("large", 20, 20, 20), media("small", 10, 10, 10))
        for (field in listOf("size", "created", "modified")) {
            assertEquals(listOf("small", "large", "unknown"), MediaOrdering.sort(files, field, false).map { it.name })
            assertEquals(listOf("large", "small", "unknown"), MediaOrdering.sort(files, field, true).map { it.name })
        }
    }
}
