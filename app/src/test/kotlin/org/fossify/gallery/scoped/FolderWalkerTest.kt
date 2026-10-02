package org.fossify.gallery.scoped

import org.junit.Assert.*
import org.junit.Test

class FolderWalkerTest {
    private fun node(id: String) = FolderWalker.Node(id, id)
    private fun contents(vararg folders: String, media: List<String> = emptyList()) =
        FolderWalker.Contents(folders.map(::node), media)

    @Test fun rootAndFourChildrenProduceFiveAlbumsIncludingEmptyFolders() {
        val tree = mapOf("Pictures" to contents("Pictures/a", "Pictures/b", "Pictures/c", "Pictures/d"),
            "Pictures/a" to contents(media = listOf("photo.jpg", "video.mp4")),
            "Pictures/b" to contents(), "Pictures/c" to contents(), "Pictures/d" to contents())
        val result = FolderWalker.walk(listOf(node("Pictures")), { it }, { tree.getValue(it) })
        assertEquals(5, result.albums.size)
        assertTrue(result.albums.first().media.isEmpty())
        assertEquals(listOf("photo.jpg", "video.mp4"), result.albums[1].media)
        assertTrue(result.unavailable.isEmpty())
    }

    @Test fun nestedDescendantsAppearAtTheSameLevelAndCountsAreNotRolledUp() {
        val tree = mapOf("Pictures" to contents("Pictures/a", media = listOf("root.jpg")),
            "Pictures/a" to contents("Pictures/a/deep", media = listOf("video.mp4")),
            "Pictures/a/deep" to contents(media = listOf("deep.jpg")))
        val result = FolderWalker.walk(listOf(node("Pictures")), { it }, { tree.getValue(it) })
        assertEquals(listOf("Pictures", "Pictures/a", "Pictures/a/deep"), result.albums.map { it.node.label })
        assertEquals(listOf(1, 1, 1), result.albums.map { it.media.size })
    }

    @Test fun overlappingRootsAndProviderCyclesAreVisitedOnce() {
        val reads = mutableListOf<String>()
        val tree = mapOf("parent" to contents("child"), "child" to contents("parent"))
        val result = FolderWalker.walk(listOf(node("parent"), node("child")), { it }, {
            reads.add(it); tree.getValue(it)
        })
        assertEquals(2, result.albums.size)
        assertEquals(listOf("parent", "child"), reads)
    }

    @Test fun failedAccessIsReportedWithoutScanningUnrelatedFolders() {
        val reads = mutableListOf<String>()
        val result = FolderWalker.walk<String, String>(listOf(node("revoked"), node("allowed")), { it }, {
            reads.add(it)
            if (it == "revoked") throw SecurityException()
            contents(media = listOf("ok.jpg"))
        })
        assertEquals(listOf("revoked"), result.unavailable)
        assertEquals(listOf("allowed"), result.albums.map { it.node.value })
        assertEquals(listOf("revoked", "allowed"), reads)
    }

    @Test fun noAuthorizedRootsMeansNoProviderReads() {
        val result = FolderWalker.walk<String, String>(emptyList(), { it }, { error("Must not read storage") })
        assertTrue(result.albums.isEmpty())
    }

    @Test fun cancellationStopsFurtherProviderQueries() {
        var reads = 0
        val result = FolderWalker.walk(listOf(node("root")), { it }, {
            reads++; contents("child")
        }, cancelled = { reads == 1 })
        assertEquals(1, result.albums.size)
        assertEquals(1, reads)
    }
}
