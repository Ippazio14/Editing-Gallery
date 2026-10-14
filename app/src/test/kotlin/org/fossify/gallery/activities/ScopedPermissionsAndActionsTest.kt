package org.fossify.gallery.activities

import android.content.pm.PackageManager
import android.net.Uri
import android.widget.ImageButton
import com.squareup.picasso.Picasso
import org.fossify.commons.views.MySearchMenu
import org.fossify.gallery.R
import org.fossify.gallery.scoped.FolderAccess
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "it")
class ScopedPermissionsAndActionsTest {
    @After fun resetPicasso() {
        ReflectionHelpers.setStaticField(Picasso::class.java, "singleton", null)
    }

    @Test fun mergedManifestDeclaresNoBroadStorageOrMediaAccess() {
        val app = RuntimeEnvironment.getApplication()
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        val forbidden = setOf(
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.MANAGE_MEDIA",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION"
        )
        assertEquals(emptySet<String>(), permissions.intersect(forbidden))
    }

    @Test fun mediaSelectionShowsOnlyDeleteCutCopyPasteIconsInThatOrder() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val selected = ReflectionHelpers.getField<MutableSet<FolderAccess.Media>>(activity, "selected")
            selected.add(FolderAccess.Media(Uri.parse("content://example.documents/document/photo"), "photo.jpg", "image/jpeg"))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "show")
            val search = ReflectionHelpers.getField<MySearchMenu>(activity, "searchMenu")
            val menu = search.requireToolbar().menu
            val visible = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }
            assertEquals(listOf(R.id.scoped_delete, R.id.scoped_cut, R.id.scoped_copy, R.id.scoped_paste), visible.map { it.itemId })
            visible.forEach {
                assertTrue(it.actionView is ImageButton)
                assertEquals(it.title.toString(), it.actionView!!.contentDescription.toString())
            }
            assertFalse(menu.findItem(R.id.scoped_paste).isEnabled)
        }
    }

    @Test fun pasteStaysInTheToolbarAfterChoosingADestinationWithoutAnotherSelection() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            ReflectionHelpers.setField(activity, "current", Uri.parse("content://example.documents/tree/destination/document/destination"))
            ReflectionHelpers.setField(activity, "clipboard", listOf(
                FolderAccess.Media(Uri.parse("content://example.documents/document/photo"), "photo.jpg", "image/jpeg")
            ))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "show")
            val search = ReflectionHelpers.getField<MySearchMenu>(activity, "searchMenu")
            val menu = search.requireToolbar().menu
            assertTrue(menu.findItem(R.id.scoped_paste).isVisible)
            assertTrue(menu.findItem(R.id.scoped_paste).isEnabled)
            assertFalse(menu.findItem(R.id.scoped_copy).isEnabled)
        }
    }
}
