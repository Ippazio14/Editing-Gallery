package org.fossify.gallery.activities

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.util.concurrent.atomic.AtomicInteger
import androidx.recyclerview.widget.RecyclerView
import org.robolectric.shadows.ShadowContentResolver
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import org.fossify.gallery.R
import org.fossify.gallery.scoped.FolderAccess
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "it")
class FolderSelectionActivityTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun views(activity: Activity) = descendants(activity.findViewById(android.R.id.content))
    private fun button(activity: Activity, id: Int) = views(activity).filterIsInstance<Button>()
        .first { it.text.toString() == activity.getString(id) }
    private fun check(activity: Activity, name: String) = views(activity).filterIsInstance<CheckBox>()
        .first { it.contentDescription.toString() == name }
    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("UI did not reach the expected state")
    }
    private fun tree(path: String) = DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents", "primary:$path")

    @Test fun introductionAndChecklistAppearBeforeAnySystemPicker() {
        Robolectric.buildActivity(FolderSelectionActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertTrue(views(activity).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.scoped_access_intro) })
            assertFalse(check(activity, "DCIM").isChecked)
            assertFalse(check(activity, "Pictures").isChecked)
            assertFalse(check(activity, "Movies").isChecked)
            assertTrue(button(activity, R.string.scoped_continue).isEnabled)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun cancellingFolderPickerLeavesCheckboxEmptyAndContinueAvailable() {
        Robolectric.buildActivity(FolderSelectionActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            check(activity, "DCIM").performClick()
            val launched = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launched.intent.action)
            assertFalse(check(activity, "DCIM").isChecked)
            shadowOf(activity).receiveResult(launched.intent, Activity.RESULT_CANCELED, null)
            assertFalse(check(activity, "DCIM").isChecked)
            assertTrue(button(activity, R.string.scoped_continue).isEnabled)
        }
    }

    @Test fun grantedFolderIsCheckedAndContinueFinishesSelection() {
        Robolectric.buildActivity(FolderSelectionActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            check(activity, "Pictures").performClick()
            val launched = shadowOf(activity).nextStartedActivityForResult
            val granted = Intent().setData(tree("Pictures")).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            shadowOf(activity).receiveResult(launched.intent, Activity.RESULT_OK, granted)
            awaitUi { check(activity, "Pictures").isChecked && button(activity, R.string.scoped_continue).isEnabled }
            button(activity, R.string.scoped_continue).performClick()
            awaitUi { activity.isFinishing }
            assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
            assertTrue(FolderAccess(activity).setupComplete)
        }
    }

    @Test fun arbitraryDownloadFoldersRemainListedWhenUnchecked() {
        val access = FolderAccess(RuntimeEnvironment.getApplication())
        access.add(tree("Download/festa"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        access.add(tree("Download/pics"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Robolectric.buildActivity(FolderSelectionActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertTrue(check(activity, "Download/festa").isChecked)
            assertTrue(check(activity, "Download/pics").isChecked)
            check(activity, "Download/festa").performClick()
            awaitUi { !check(activity, "Download/festa").isChecked && button(activity, R.string.scoped_continue).isEnabled }
            assertFalse(access.hasReadAccess(tree("Download/festa")))
            assertTrue(access.hasReadAccess(tree("Download/pics")))
        }
    }

    @Test fun missingSystemGrantIsNotShownAsChecked() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("authorized_folders", Context.MODE_PRIVATE).edit()
            .putStringSet("roots", setOf(tree("DCIM").toString())).commit()
        Robolectric.buildActivity(FolderSelectionActivity::class.java).setup().use {
            assertFalse(check(it.get(), "DCIM").isChecked)
        }
    }

    @Test fun mainOpensIntroductionThenShowsFiveAlbumsAfterContinue() {
        val reads = AtomicInteger()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = DocumentsContract.Document.MIME_TYPE_DIR
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
                reads.incrementAndGet()
                val result = MatrixCursor(projection)
                if (DocumentsContract.getDocumentId(uri) == "primary:Pictures") {
                    for (name in listOf("a", "b", "c", "d")) {
                        result.addRow(arrayOf("primary:Pictures/$name", name, DocumentsContract.Document.MIME_TYPE_DIR))
                    }
                }
                return result
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        ShadowContentResolver.registerProviderInternal("com.android.externalstorage.documents", provider)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val launched = shadowOf(activity).nextStartedActivityForResult
            assertEquals(FolderSelectionActivity::class.java.name, launched.intent.component!!.className)
            assertEquals(0, reads.get())
            val access = FolderAccess(activity)
            access.add(tree("Pictures"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            access.setupComplete = true
            shadowOf(activity).receiveResult(launched.intent, Activity.RESULT_OK, Intent())
            awaitUi { views(activity).filterIsInstance<RecyclerView>().first().adapter?.itemCount == 5 }
            assertEquals(5, reads.get())
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun completedSetupDoesNotLaunchTheFolderChooserAgain() {
        FolderAccess(RuntimeEnvironment.getApplication()).setupComplete = true
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        }
    }
}
