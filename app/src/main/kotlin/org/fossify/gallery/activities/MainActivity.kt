package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import org.fossify.gallery.scoped.MediaOrdering
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.views.MySearchMenu
import org.fossify.gallery.views.AccessibleGalleryTile
import org.fossify.gallery.views.GalleryGridSpacing
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
    private lateinit var searchMenu: MySearchMenu
    private var searchQuery = ""
    private var customColumns = 0
    private var descending = false
    private var sortField = "name"
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var scanning = false
    private val refresh = object : Runnable {
        override fun run() {
            if (resumed) {
                if (!busy && !folderSelectionOpen && !scanning) load(quiet = true)
                refreshHandler.postDelayed(this, 15000)
            }
        }
    }
    private val changed = Runnable { if (resumed && !busy && !folderSelectionOpen && !scanning) load(quiet = true) }
    private val observer = object : ContentObserver(refreshHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshHandler.removeCallbacks(changed)
            refreshHandler.postDelayed(changed, 700)
        }
    }
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
        customColumns = getPreferences(MODE_PRIVATE).getInt("scoped_columns", 2).coerceIn(1, 5)
        sortField = getPreferences(MODE_PRIVATE).getString("scoped_sort_field", "name") ?: "name"
        descending = getPreferences(MODE_PRIVATE).getBoolean("scoped_sort_descending", false)
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
        searchMenu = layoutInflater.inflate(R.layout.view_scoped_search_menu, root, false) as MySearchMenu
        searchMenu.setApplyWindowInsets(false)
        root.addView(searchMenu)
        title = text().apply { textSize = 24f }
        root.addView(title)
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
            addItemDecoration(GalleryGridSpacing(resources.displayMetrics.density))
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.isFocusableInTouchMode = true
        root.requestFocus()
        setContentView(root)
        setupSearchMenu()
        searchMenu.binding.topToolbarSearch.setText(savedInstanceState?.getString("search_query").orEmpty())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selected.isNotEmpty() -> { selected.clear(); show() }
                    searchQuery.isNotEmpty() || searchMenu.isSearchOpen -> { searchMenu.closeSearch(); show() }
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
        searchMenu.updateColors()
        resumed = true
        refreshHandler.removeCallbacks(refresh)
        refreshHandler.postDelayed(refresh, 15000)
        if (!busy && !folderSelectionOpen) load()
    }

    override fun onPause() {
        resumed = false
        refreshHandler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(observer)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("search_query", searchQuery)
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
    override fun onDestroy() { generation++; refreshHandler.removeCallbacksAndMessages(null); contentResolver.unregisterContentObserver(observer); worker.shutdownNow(); super.onDestroy() }
    private fun columns() = if (customColumns > 0) customColumns else (resources.configuration.screenWidthDp / 180).coerceIn(1, 4)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text() = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f }
    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; minHeight = dp(56)
        setTextColor(Color.BLACK); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        setOnClickListener { if (!busy) click() }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun setupSearchMenu() {
        searchMenu.setupMenu()
        searchMenu.updateHintText(getString(R.string.scoped_search))
        searchMenu.requireToolbar().apply {
            inflateMenu(R.menu.menu_scoped_gallery)
            setOnMenuItemClickListener { item ->
                if (busy) return@setOnMenuItemClickListener true
                when (item.itemId) {
                    R.id.scoped_manage_folders -> manage()
                    R.id.scoped_sort -> sortingDialog()
                    R.id.scoped_about -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("EasyVision Gallery")
                        .setMessage(R.string.scoped_help_body)
                        .setPositiveButton(android.R.string.ok, null).show()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
        }
        searchMenu.onSearchTextChangedListener = {
            searchQuery = it.trim()
            show()
        }
        searchMenu.updateColors()
    }

    private fun sortingDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        fun group(labels: List<String>, chosen: Int, horizontal: Boolean = false, onChange: (Int) -> Unit) {
            val group = RadioGroup(this).apply { orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
            labels.forEachIndexed { index, label ->
                group.addView(RadioButton(this).apply { id = View.generateViewId(); text = label; textSize = 18f; minHeight = dp(48) },
                    if (horizontal) RadioGroup.LayoutParams(0, -2, 1f) else RadioGroup.LayoutParams(-1, -2))
            }
            group.check(group.getChildAt(chosen).id)
            group.setOnCheckedChangeListener { _, id -> onChange((0 until group.childCount).first { group.getChildAt(it).id == id }) }
            panel.addView(group)
        }
        fun persist() {
            getPreferences(MODE_PRIVATE).edit().putBoolean("scoped_sort_descending", descending)
                .putString("scoped_sort_field", sortField).putInt("scoped_columns", columns()).apply()
        }
        group(listOf(getString(R.string.scoped_ascending), getString(R.string.scoped_descending)), if (descending) 1 else 0) {
            descending = it == 1; persist(); show()
        }
        val fields = listOf("name", "created", "size", "modified")
        group(listOf(R.string.order_name, R.string.order_created, R.string.order_size, R.string.order_modified).map { getString(it) }, fields.indexOf(sortField).coerceAtLeast(0)) {
            sortField = fields[it]; persist()
            show(); if (sortField == "created") load(quiet = true)
        }
        panel.addView(TextView(this).apply { setText(R.string.scoped_columns_label); textSize = 18f })
        group((1..5).map { it.toString() }, columns() - 1, true) {
            customColumns = it + 1; persist(); (grid.layoutManager as GridLayoutManager).spanCount = customColumns
        }
        AlertDialog.Builder(this).setTitle(R.string.scoped_sort_label).setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun observeFolders() {
        contentResolver.unregisterContentObserver(observer)
        if (!resumed) return
        albums.forEach { album ->
            runCatching {
                contentResolver.registerContentObserver(DocumentsContract.buildChildDocumentsUriUsingTree(album.uri,
                    DocumentsContract.getDocumentId(album.uri)), true, observer)
            }
        }
    }

    private fun manage() {
        if (folderSelectionOpen) return
        generation++
        scanTask?.cancel(true)
        scanning = false
        albums = emptyList()
        show()
        folderSelectionOpen = true
        folderSelection.launch(Intent(this, FolderSelectionActivity::class.java))
    }

    private fun load(quiet: Boolean = false) {
        val token = ++generation
        scanTask?.cancel(true)
        // Do not display cached media during a new permission check.
        if (!quiet) {
            albums = emptyList(); selected.clear(); show()
            status.setText(R.string.scoped_loading)
            status.visibility = View.VISIBLE
        }
        scanning = true
        val readCreation = sortField == "created"
        scanTask = worker.submit {
            val result = runCatching { access.scan(readCreation) }
            runOnUiThread {
                if (isDestroyed || token != generation) return@runOnUiThread
                scanning = false
                val previous = albums
                val previousError = scanError
                result.onSuccess {
                    albums = it.albums
                    scanError = if (it.unavailable.isEmpty()) "" else getString(R.string.scoped_unavailable, it.unavailable.joinToString(", "))
                    if (current != null && albums.none { album -> album.uri == current }) current = null
                }.onFailure { albums = emptyList(); scanError = getString(R.string.scoped_scan_error) }
                val byUri = albums.flatMap { it.media }.associateBy { it.uri }
                val retained = selected.mapNotNull { byUri[it.uri] }
                selected.clear(); selected.addAll(retained)
                observeFolders()
                if (!quiet || previous != albums || previousError != scanError) show()
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

    private data class Entry(val label: String, val album: FolderAccess.Album? = null, val media: FolderAccess.Media? = null)

    private fun shownEntries(album: FolderAccess.Album?): List<Entry> {
        val query = searchQuery
        val entries = if (album != null) {
            visibleMedia(album).filter { it.name.contains(query, ignoreCase = true) }.map { Entry(it.name, media = it) }
        } else {
            val folders = albums.filter { it.label.contains(query, ignoreCase = true) }.map { Entry(it.label, album = it) }
            val files = if (query.isEmpty()) emptyList() else albums.flatMap { parent ->
                visibleMedia(parent).filter { it.name.contains(query, ignoreCase = true) }
                    .map { Entry("${parent.label}/${it.name}", media = it) }
            }
            folders + files
        }
        val folders = entries.filter { it.album != null }.sortedBy { it.label.lowercase() }
        val files = entries.filter { it.media != null }
        val byUri = files.associateBy { it.media!!.uri }
        return folders + MediaOrdering.sort(files.map { it.media!! }, sortField, descending).mapNotNull { byUri[it.uri] }
    }

    private fun show() {
        val album = albums.firstOrNull { it.uri == current }
        val entries = shownEntries(album)
        title.text = album?.label.orEmpty()
        title.visibility = if (current == null) View.GONE else View.VISIBLE
        actions.visibility = if (selected.isEmpty() || picking) View.GONE else View.VISIBLE
        paste.visibility = if (album != null && clipboard.isNotEmpty() && !picking) View.VISIBLE else View.GONE
        paste.isEnabled = !busy
        status.text = when {
            busy -> getString(R.string.scoped_transferring)
            scanError.isNotEmpty() -> scanError
            access.roots().isEmpty() -> getString(R.string.scoped_empty)
            selected.isNotEmpty() -> getString(R.string.scoped_selected, selected.size)
            searchQuery.isNotEmpty() && entries.isEmpty() -> getString(R.string.scoped_no_results)
            album != null && visibleMedia(album).isEmpty() -> getString(R.string.scoped_album_empty)
            else -> ""
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        grid.adapter = Tiles(entries)
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
        current = null; searchMenu.closeSearch(); show()
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

    private inner class Tiles(private val entries: List<Entry>) : RecyclerView.Adapter<Tile>() {
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Tile = Tile(
            AccessibleGalleryTile(this@MainActivity).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) })

        override fun onBindViewHolder(holder: Tile, position: Int) {
            val entry = entries[position]
            val item = entry.media
            val album = entry.album
            val tile = holder.tile
            Glide.with(this@MainActivity).clear(tile.image)
            val name = "${if (item in selected) "✓ " else ""}${entry.label}"
            val counts = album?.let { "🖼️ ${it.images} - 🎬 ${it.videos}" }
                ?: ""
            tile.bindLabels(name, counts, videoAlbum = (album != null && album.videos > 0) || item?.isVideo == true,
                emptyAlbum = album != null && album.media.isEmpty(), selected = item in selected)
            tile.contentDescription = listOf(name, album?.let { getString(R.string.scoped_counts, it.images, it.videos) } ?: counts).joinToString(", ")
            val preview = item?.uri ?: album?.media?.firstOrNull()?.uri
            if (preview != null) Glide.with(this@MainActivity).load(preview).centerCrop().dontAnimate()
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true).into(tile.image)
            tile.setOnClickListener {
                if (busy) return@setOnClickListener
                if (album != null) { current = album.uri; selected.clear(); searchMenu.closeSearch(); show() }
                else if (selected.isNotEmpty() && !picking) { toggle(item!!); show() }
                else open(item!!)
            }
            tile.setOnLongClickListener {
                if (item != null && !picking && !busy) { toggle(item); show(); true } else false
            }
        }
        override fun onViewRecycled(holder: Tile) { Glide.with(this@MainActivity).clear(holder.tile.image) }
    }
    private fun toggle(item: FolderAccess.Media) { if (!selected.add(item)) selected.remove(item) }
    private class Tile(val tile: AccessibleGalleryTile) : RecyclerView.ViewHolder(tile)
}
