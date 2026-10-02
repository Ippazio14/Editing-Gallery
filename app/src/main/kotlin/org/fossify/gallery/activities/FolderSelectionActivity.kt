package org.fossify.gallery.activities

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.gallery.R
import org.fossify.gallery.scoped.FolderAccess
import java.util.concurrent.Executors

/** Explicit introduction → truthful folder check marks → Continue. No media scans run here. */
class FolderSelectionActivity : SimpleActivity() {
    private lateinit var access: FolderAccess
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var add: Button
    private lateinit var proceed: Button
    private val worker = Executors.newSingleThreadExecutor()
    private var working = false
    private var pickerOpen = false
    private var notice = ""
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerOpen = false
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val flags = result.data!!.flags
            changeAccess { access.add(uri, flags) }
        } else {
            notice = getString(R.string.scoped_selection_cancelled)
            renderRows()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        access = FolderAccess(this)
        pickerOpen = savedInstanceState?.getBoolean("picker_open") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + dp(16), bars.top + dp(8), bars.right + dp(16), bars.bottom + dp(8))
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label(getString(R.string.scoped_select_title), 26f))
        content.addView(label(getString(R.string.scoped_access_intro), 22f))
        content.addView(label(getString(R.string.scoped_check_help), 18f))
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)
        status = label("", 18f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        add = button(R.string.scoped_add) { choose(null) }
        root.addView(add)
        proceed = button(R.string.scoped_continue) {
            changeAccess(finishAfter = true) { access.setupComplete = true }
        }
        root.addView(proceed)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!working) finish() }
        })
        renderRows()
    }

    override fun onResume() {
        super.onResume()
        if (!working) renderRows()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker_open", pickerOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // A committed permission change may finish during recreation; the new screen reads its result.
        worker.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(label: Int, action: () -> Unit) = Button(this).apply {
        setText(label)
        textSize = 22f
        isAllCaps = false
        minHeight = dp(60)
        setTextColor(Color.BLACK)
        backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        setOnClickListener { if (!working && !pickerOpen) action() }
    }

    private fun renderRows() {
        if (!::rows.isInitialized) return
        val granted = access.roots().filter { access.hasReadAccess(it.uri) }.map { it.uri }.toSet()
        val choices = linkedMapOf<Uri, String>()
        // These are shortcuts to the Android chooser, not a scan of unapproved storage.
        for (name in listOf("DCIM", "Pictures", "Movies")) {
            choices[DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$name")] = name
        }
        access.knownRoots().forEach { choices[it.uri] = it.label }
        rows.removeAllViews()
        for ((uri, path) in choices) {
            val checked = uri in granted
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
            }
            val caption = label(path, 22f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            row.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
            val check = CheckBox(this).apply {
                contentDescription = path
                isChecked = checked
                minWidth = dp(64)
                minHeight = dp(64)
                buttonTintList = ColorStateList.valueOf(Color.WHITE)
                isEnabled = !working && !pickerOpen
                setOnClickListener {
                    // Never show a grant optimistically: cancellation must leave the row unchecked.
                    isChecked = checked
                    if (checked) changeAccess { access.remove(uri) } else choose(uri)
                }
            }
            row.addView(check)
            row.setOnClickListener { if (check.isEnabled) check.performClick() }
            rows.addView(row)
        }
        add.isEnabled = !working && !pickerOpen
        proceed.isEnabled = !working && !pickerOpen
        status.text = when {
            working -> getString(R.string.scoped_saving_access)
            notice.isNotEmpty() -> notice
            else -> getString(R.string.scoped_checked_count, granted.size)
        }
    }

    private fun choose(initial: Uri?) {
        notice = ""
        pickerOpen = true
        renderRows()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        initial?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it))) }
        try {
            picker.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            pickerOpen = false
            notice = getString(R.string.scoped_grant_error)
            renderRows()
        }
    }

    private fun changeAccess(finishAfter: Boolean = false, change: () -> Unit) {
        working = true
        notice = ""
        renderRows()
        worker.execute {
            val result = runCatching(change)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                working = false
                if (result.isSuccess && finishAfter) {
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    if (result.isFailure) notice = getString(R.string.scoped_grant_error)
                    renderRows()
                }
            }
        }
    }
}
