package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.gallery.R
import org.fossify.gallery.scoped.FolderAccess
import java.util.concurrent.Executors

/** Folder permissions are real SAF grants, not filters over unrestricted MediaStore access. */
class MainActivity : SimpleActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var access: FolderAccess
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var back: Button
    private lateinit var paste: Button
    private lateinit var actions: LinearLayout
    private var albums = emptyList<FolderAccess.Album>()
    private var current: Uri? = null
    private var generation = 0
    private var scanTask: java.util.concurrent.Future<*>? = null
    private var busy = false
    private val selected = linkedSetOf<FolderAccess.Media>()
    private var clipboard = emptyList<FolderAccess.Media>()
    private var cutting = false
    private var scanError = ""
    private val picking: Boolean get() = intent.action == Intent.ACTION_PICK || intent.action == Intent.ACTION_GET_CONTENT
    private var folderSelectionOpen = false
    private val folderSelection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        folderSelectionOpen = false
        clipboard = emptyList()
        selected.clear()
        current = null
        scanError = ""
        // Results normally arrive before onResume. Also handle delivery to an already resumed host.
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        access = FolderAccess(this)
        folderSelectionOpen = savedInstanceState?.getBoolean("folder_selection_open") ?: false
        current = savedInstanceState?.getString("album")?.let(Uri::parse)
        val uris = savedInstanceState?.getStringArrayList("clipboard_uris").orEmpty()
        val names = savedInstanceState?.getStringArrayList("clipboard_names").orEmpty()
        val mimes = savedInstanceState?.getStringArrayList("clipboard_mimes").orEmpty()
        clipboard = if (uris.size == names.size && names.size == mimes.size) uris.indices.map {
            FolderAccess.Media(Uri.parse(uris[it]), names[it], mimes[it])
        } else emptyList()
        cutting = savedInstanceState?.getBoolean("cutting") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + dp(8), bars.top + dp(8), bars.right + dp(8), bars.bottom + dp(8))
            insets
        }
        title = text().apply { textSize = 24f }
        root.addView(title)
        val navigation = LinearLayout(this)
        back = button(getString(R.string.scoped_back)) { current = null; selected.clear(); show() }
        navigation.addView(back, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(button(getString(R.string.scoped_folders)) { manage() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(navigation)
        status = text().apply { textSize = 18f; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)
        actions = LinearLayout(this)
        actions.addView(button(getString(R.string.scoped_copy)) { copy(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(getString(R.string.scoped_cut)) { copy(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(getString(R.string.scoped_cancel)) { selected.clear(); show() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        paste = button(getString(R.string.scoped_paste)) { paste() }
        root.addView(paste)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columns())
            itemAnimator = null
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button(getString(R.string.scoped_refresh)) { load() })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selected.isNotEmpty() -> { selected.clear(); show() }
                    current != null -> { current = null; show() }
                    else -> finish()
                }
            }
        })
        if (!folderSelectionOpen && savedInstanceState == null &&
            (intent.action == Intent.ACTION_APPLICATION_PREFERENCES || !access.setupComplete)) {
            manage()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!busy && !folderSelectionOpen) load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("folder_selection_open", folderSelectionOpen)
        outState.putString("album", current?.toString())
        outState.putStringArrayList("clipboard_uris", ArrayList(clipboard.map { it.uri.toString() }))
        outState.putStringArrayList("clipboard_names", ArrayList(clipboard.map { it.name }))
        outState.putStringArrayList("clipboard_mimes", ArrayList(clipboard.map { it.mime }))
        outState.putBoolean("cutting", cutting)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (grid.layoutManager as GridLayoutManager).spanCount = columns()
    }
    override fun onDestroy() { generation++; worker.shutdownNow(); super.onDestroy() }
    private fun columns() = (resources.configuration.screenWidthDp / 180).coerceIn(1, 4)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text() = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f }
    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; minHeight = dp(56)
        setTextColor(Color.BLACK); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        setOnClickListener { if (!busy) click() }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun manage() {
        if (folderSelectionOpen) return
        generation++
        scanTask?.cancel(true)
        albums = emptyList()
        show()
        folderSelectionOpen = true
        folderSelection.launch(Intent(this, FolderSelectionActivity::class.java))
    }

    private fun load() {
        val token = ++generation
        scanTask?.cancel(true)
        // Do not display cached media during a new permission check.
        albums = emptyList(); selected.clear(); show()
        status.setText(R.string.scoped_loading)
        scanTask = worker.submit {
            val result = runCatching { access.scan() }
            runOnUiThread {
                if (isDestroyed || token != generation) return@runOnUiThread
                result.onSuccess {
                    albums = it.albums
                    scanError = if (it.unavailable.isEmpty()) "" else getString(R.string.scoped_unavailable, it.unavailable.joinToString(", "))
                    if (current != null && albums.none { album -> album.uri == current }) current = null
                }.onFailure { scanError = getString(R.string.scoped_scan_error) }
                show()
            }
        }
    }

    private fun visibleMedia(album: FolderAccess.Album): List<FolderAccess.Media> {
        if (!picking) return album.media
        val types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() ?: listOf(intent.type ?: "*/*")
        return album.media.filter { media -> types.any { type ->
            type == "*/*" || type == media.mime || type.endsWith("/*") && media.mime.startsWith(type.substringBefore('/').plus('/'))
        } }
    }

    private fun show() {
        val album = albums.firstOrNull { it.uri == current }
        title.text = album?.label ?: getString(R.string.scoped_title)
        back.visibility = if (current == null) View.GONE else View.VISIBLE
        actions.visibility = if (selected.isEmpty() || picking) View.GONE else View.VISIBLE
        paste.visibility = if (album != null && clipboard.isNotEmpty() && !picking) View.VISIBLE else View.GONE
        paste.isEnabled = !busy
        status.text = when {
            busy -> getString(R.string.scoped_transferring)
            scanError.isNotEmpty() -> scanError
            access.roots().isEmpty() -> getString(R.string.scoped_empty)
            selected.isNotEmpty() -> getString(R.string.scoped_selected, selected.size)
            album != null && visibleMedia(album).isEmpty() -> getString(R.string.scoped_album_empty)
            album != null && !picking -> getString(R.string.scoped_selection_hint)
            else -> ""
        }
        grid.adapter = Tiles(album?.let { visibleMedia(it) })
    }

    private fun open(media: FolderAccess.Media) {
        if (picking) {
            setResult(RESULT_OK, Intent().setDataAndType(media.uri, media.mime).apply {
                clipData = ClipData.newRawUri(media.name, media.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            finish()
        } else {
            startActivity(Intent(this, ScopedViewerActivity::class.java).setDataAndType(media.uri, media.mime)
                .putExtra("name", media.name))
        }
    }

    private fun copy(move: Boolean) {
        clipboard = selected.toList(); cutting = move; selected.clear()
        current = null; show()
        toast(getString(R.string.scoped_choose_destination))
    }
    private fun paste() {
        val destination = current ?: return
        val items = clipboard.toList()
        busy = true; show()
        worker.execute {
            val failed = mutableListOf<FolderAccess.Media>()
            val errors = mutableListOf<String>()
            for (item in items) {
                try { access.transfer(item, destination, cutting) }
                catch (error: Exception) { failed.add(item); errors.add("${item.name}: ${error.message}") }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    busy = false; clipboard = failed
                    toast(if (errors.isEmpty()) getString(R.string.scoped_transfer_done) else errors.joinToString("\n"))
                    load()
                }
            }
        }
    }

    private inner class Tiles(private val media: List<FolderAccess.Media>?) : RecyclerView.Adapter<Tile>() {
        override fun getItemCount() = media?.size ?: albums.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Tile {
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            val label = text().apply {
                setBackgroundColor(Color.BLACK); setPadding(dp(6), dp(6), dp(6), dp(6))
                // No single-line limit or ellipsis: full path stays readable at large font sizes.
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val image = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.DKGRAY)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            box.addView(label, LinearLayout.LayoutParams(-1, -2))
            box.addView(image, LinearLayout.LayoutParams(-1, dp(160)))
            return Tile(box, label, image)
        }
        override fun onBindViewHolder(holder: Tile, position: Int) {
            Glide.with(this@MainActivity).clear(holder.image)
            holder.image.setImageDrawable(null)
            val item = media?.get(position)
            val album = if (media == null) albums[position] else null
            holder.label.text = album?.let { "${it.label}\n${getString(R.string.scoped_counts, it.images, it.videos)}" }
                ?: "${if (item in selected) "✓ " else ""}${item!!.name}${if (item.isVideo) "\n${getString(R.string.scoped_video)}" else ""}"
            holder.box.contentDescription = holder.label.text
            holder.box.isFocusable = true
            holder.box.setBackgroundColor(if (item in selected) Color.rgb(0, 72, 110) else Color.BLACK)
            val preview = item?.uri ?: album?.media?.firstOrNull()?.uri
            if (preview != null) Glide.with(this@MainActivity).load(preview).centerCrop()
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true).into(holder.image)
            holder.box.setOnClickListener {
                if (busy) return@setOnClickListener
                if (album != null) { current = album.uri; selected.clear(); show() }
                else if (selected.isNotEmpty() && !picking) { toggle(item!!); show() }
                else open(item!!)
            }
            holder.box.setOnLongClickListener {
                if (item != null && !picking && !busy) { toggle(item); show(); true } else false
            }
        }
        override fun onViewRecycled(holder: Tile) { Glide.with(this@MainActivity).clear(holder.image) }
    }
    private fun toggle(item: FolderAccess.Media) { if (!selected.add(item)) selected.remove(item) }
    private class Tile(val box: LinearLayout, val label: TextView, val image: ImageView) : RecyclerView.ViewHolder(box)
}
