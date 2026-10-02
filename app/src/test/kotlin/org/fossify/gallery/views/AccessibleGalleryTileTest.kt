package org.fossify.gallery.views

import android.app.Application
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AccessibleGalleryTileTest {
    private fun layout(tile: AccessibleGalleryTile, width: Int = 240) {
        tile.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        tile.layout(0, 0, tile.measuredWidth, tile.measuredHeight)
    }

    @Test fun pathAndCountsAreInsideThumbnailAtOppositeCorners() {
        val tile = AccessibleGalleryTile(RuntimeEnvironment.getApplication())
        tile.bindLabels("dcim/cartella1", "3 immagini, 5 video", true, false, false)
        layout(tile)
        assertEquals(tile.image.left, tile.nameOverlay.left)
        assertEquals(tile.image.top, tile.nameOverlay.top)
        assertEquals(tile.image.right, tile.countOverlay.right)
        assertEquals(tile.image.bottom, tile.countOverlay.bottom)
        assertTrue(tile.nameOverlay.bottom < tile.countOverlay.top)
        assertTrue(tile.hasFilmBorder)
    }

    @Test fun largeFontAndLongPathsDoNotOverlapCountsOrLeaveTheThumbnail() {
        val context = RuntimeEnvironment.getApplication()
        val config = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val tile = AccessibleGalleryTile(context.createConfigurationContext(config))
        tile.bindLabels("dcim/cartella-con-nome-molto-lungo/sottocartella/fotografie", "123 immagini, 456 video", true, false, false)
        layout(tile, 200)
        assertTrue(tile.nameOverlay.bottom < tile.countOverlay.top)
        assertTrue(tile.countOverlay.right <= tile.image.right)
        assertTrue(tile.countOverlay.bottom <= tile.image.bottom)
        assertTrue(tile.nameOverlay.lineCount > 1)
    }

    @Test fun recycledTileRemovesFilmAndRestoresOpacityForImageOnlyAlbum() {
        val tile = AccessibleGalleryTile(RuntimeEnvironment.getApplication())
        tile.bindLabels("Movies", "0 immagini, 1 video", true, false, false)
        assertNotNull(tile.foreground)
        tile.bindLabels("DCIM", "0 immagini, 0 video", false, true, false)
        assertNull(tile.foreground)
        assertNotNull(tile.image.drawable)
        assertTrue(tile.image.alpha < 1f)
        assertEquals(1f, tile.nameOverlay.alpha, 0f)
        assertEquals(1f, tile.countOverlay.alpha, 0f)
        tile.bindLabels("Pictures", "1 immagine, 0 video", false, false, false)
        assertFalse(tile.hasFilmBorder)
        assertFalse(tile.isEmptyAlbum)
        assertNull(tile.foreground)
        assertEquals(1f, tile.image.alpha, 0f)
    }

    @Test fun horizontalAndVerticalGapsAreAtLeastFivePhysicalPixels() {
        val context = RuntimeEnvironment.getApplication()
        for (density in listOf(0.75f, 1f, 2f, 3f)) {
            val spacing = GalleryGridSpacing(density)
            val offsets = Rect()
            spacing.getItemOffsets(offsets, View(context), RecyclerView(context), RecyclerView.State())
            assertTrue(offsets.left + offsets.right >= 5)
            assertTrue(offsets.top + offsets.bottom >= 5)
        }
    }
}
