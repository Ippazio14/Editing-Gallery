package org.fossify.gallery.activities

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.exifinterface.media.ExifInterface
import com.canhub.cropper.CropImageView
import org.fossify.gallery.R
import java.io.File
import java.io.Serializable
import java.util.concurrent.Executors
import kotlin.math.*
import kotlin.random.Random

/** Accessible photo editor. Undo keeps edit parameters, never full-resolution bitmap copies. */
class EditActivity : SimpleActivity() {
    private data class Geometry(val rotation: Boolean = false, val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) : Serializable
    private data class State(val saturation: Int = 0, val temperature: Int = 0, val brightness: Int = 0, val contrast: Int = 0,
        val width: Int = 0, val quality: Int = 85, val geometry: List<Geometry> = emptyList()) : Serializable
    private var state = State()
    private val undo = ArrayList<State>()
    private val redo = ArrayList<State>()
    private val worker = Executors.newSingleThreadExecutor()
    private var source: Bitmap? = null
    private var previewSource: Bitmap? = null
    private var preview: Bitmap? = null
    private var uri: Uri? = null
    private var generation = 0
    private var saving = false
    private var ready = false
    private lateinit var image: ImageView
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var saveButton: Button
    private lateinit var status: TextView
    private lateinit var root: LinearLayout
    private val values = HashMap<String, TextView>()
    private val editButtons = ArrayList<Button>()
    private var editorBackgroundColor = Color.BLACK
    private var editorTextColor = Color.WHITE
    private var editorSurfaceColor = Color.DKGRAY
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun label(id: Int) = getString(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = savedInstanceState?.getSerializable("edit_state") as? State ?: State()
        @Suppress("UNCHECKED_CAST")
        (savedInstanceState?.getSerializable("undo_states") as? ArrayList<State>)?.let { undo.addAll(it) }
        @Suppress("UNCHECKED_CAST")
        (savedInstanceState?.getSerializable("redo_states") as? ArrayList<State>)?.let { redo.addAll(it) }
        uri = savedInstanceState?.getString("source_uri")?.let(Uri::parse) ?: intent.data
        buildUi()
        val input = uri ?: run { finish(); return }
        worker.execute {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                stream(input).use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                // Fail clearly rather than silently exporting a reduced original.
                require(bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { label(R.string.easy_image_too_large) }
                val decoded = stream(input).use { BitmapFactory.decodeStream(it) } ?: error(label(R.string.easy_read_error))
                val orientation = stream(input).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> { setRotate(180f); postScale(-1f, 1f) }
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(-90f); postScale(-1f, 1f) }
                        8 -> setRotate(-90f)
                    }
                }
                val full = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (full !== decoded) decoded.recycle()
                val scale = min(1f, 1400f / max(full.width, full.height))
                val small = Bitmap.createScaledBitmap(full, max(1, (full.width * scale).roundToInt()), max(1, (full.height * scale).roundToInt()), true)
                runOnUiThread {
                    if (!isDestroyed) { source = full; previewSource = small; ready = true; renderPreview() }
                }
            } catch (e: Exception) { showFailure(e.message) } catch (_: OutOfMemoryError) { showFailure(label(R.string.easy_memory_error)) }
        }
    }

    private fun stream(input: Uri) = requireNotNull(if (input.scheme == "file" || input.scheme == null) File(requireNotNull(input.path)).inputStream() else contentResolver.openInputStream(input))

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable("edit_state", state)
        outState.putSerializable("undo_states", undo)
        outState.putSerializable("redo_states", redo)
        outState.putString("source_uri", uri.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        if (ready) renderPreview()
    }

    private fun button(text: String, description: String = text, action: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 22f; isAllCaps = false
        contentDescription = description; tooltipText = description
        setTextColor(editorTextColor)
        background = GradientDrawable().apply { setColor(editorSurfaceColor); cornerRadius = dp(8).toFloat(); setStroke(dp(1), editorTextColor) }
        minWidth = 0; minimumWidth = 0; minHeight = dp(58); minimumHeight = dp(58)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setOnClickListener { if (!saving) action() }
        editButtons.add(this)
    }

    private fun buildUi() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        editorBackgroundColor = if (dark) Color.rgb(20, 23, 21) else Color.rgb(247, 248, 245)
        editorTextColor = if (dark) Color.WHITE else Color.rgb(15, 22, 17)
        editorSurfaceColor = if (dark) Color.rgb(43, 49, 45) else Color.WHITE
        editButtons.clear(); values.clear()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(editorBackgroundColor) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && resources.configuration.screenWidthDp >= 680
        val workspace = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
        val photo = FrameLayout(this).apply { setBackgroundColor(Color.rgb(35, 38, 36)) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = label(R.string.easy_preview) }
        photo.addView(image, FrameLayout.LayoutParams(-1, -1))
        val history = LinearLayout(this)
        undoButton = button("↶", label(R.string.easy_undo)) { if (undo.isNotEmpty()) { redo.add(state); state = undo.removeAt(undo.lastIndex); renderPreview() } }
        redoButton = button("↷", label(R.string.easy_redo)) { if (redo.isNotEmpty()) { undo.add(state); state = redo.removeAt(redo.lastIndex); renderPreview() } }
        val auto = button("✦", label(R.string.easy_auto)) { applyAuto() }
        listOf(undoButton, redoButton, auto).forEach { history.addView(it, LinearLayout.LayoutParams(dp(48), dp(60)).apply { marginEnd = dp(3) }) }
        photo.addView(history, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val upper = LinearLayout(this); val lower = LinearLayout(this)
        upper.addView(button("⌗", label(R.string.easy_crop)) { cropDialog() }, LinearLayout.LayoutParams(dp(52), dp(60)))
        upper.addView(button("⤢", label(R.string.easy_resize)) { resizeDialog() }, LinearLayout.LayoutParams(dp(52), dp(60)).apply { marginStart = dp(4) })
        lower.addView(button("⟳", label(R.string.easy_rotate)) { change(state.copy(geometry = state.geometry + Geometry(rotation = true), width = 0)) }, LinearLayout.LayoutParams(dp(52), dp(60)))
        lower.addView(button("⚄", label(R.string.easy_random)) { change(state.copy(saturation = Random.nextInt(-25, 36), temperature = Random.nextInt(-25, 26), brightness = Random.nextInt(-15, 16), contrast = Random.nextInt(-10, 26))) }, LinearLayout.LayoutParams(dp(52), dp(60)).apply { marginStart = dp(4) })
        tools.addView(upper); tools.addView(lower, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        photo.addView(tools, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val labels = listOf("saturation" to R.string.easy_saturation, "temperature" to R.string.easy_temperature, "brightness" to R.string.easy_brightness, "contrast" to R.string.easy_contrast)
        for ((key, name) in labels) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val title = TextView(this).apply { text = label(name); textSize = 19f; setTextColor(editorTextColor) }
            val number = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER; setTextColor(editorTextColor) }
            values[key] = number
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(number, LinearLayout.LayoutParams(dp(45), -2))
            for (delta in listOf(-5, 5)) {
                val step = button(if (delta < 0) "−" else "+", label(name) + if (delta < 0) " −" else " +") { adjust(key, delta) }
                row.addView(step, LinearLayout.LayoutParams(dp(52), dp(58)).apply { marginStart = dp(4) })
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(panel) }
        if (landscape) {
            workspace.addView(scroll, LinearLayout.LayoutParams(dp(335), -1))
            workspace.addView(photo, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            workspace.addView(photo, LinearLayout.LayoutParams(-1, 0, 1f))
            workspace.addView(scroll, LinearLayout.LayoutParams(-1, dp(272)))
        }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setTextColor(editorTextColor); setPadding(dp(8), dp(4), dp(8), dp(4)); text = label(R.string.easy_loading) }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        saveButton = button(label(R.string.easy_save_copy)) { chooseOutput() }
        root.addView(saveButton, LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(dp(10), dp(4), dp(10), dp(8)) })
        updateControls()
    }

    private fun adjust(key: String, delta: Int) {
        fun next(v: Int) = (v + delta).coerceIn(-100, 100)
        change(when (key) {
            "saturation" -> state.copy(saturation = next(state.saturation))
            "temperature" -> state.copy(temperature = next(state.temperature))
            "brightness" -> state.copy(brightness = next(state.brightness))
            else -> state.copy(contrast = next(state.contrast))
        })
    }

    private fun change(next: State) {
        if (!ready || next == state) return
        undo.add(state); if (undo.size > 50) undo.removeAt(0)
        redo.clear(); state = next; renderPreview()
    }

    private fun updateControls() {
        editButtons.forEach { it.isEnabled = ready && !saving }
        undoButton.isEnabled = ready && !saving && undo.isNotEmpty()
        redoButton.isEnabled = ready && !saving && redo.isNotEmpty()
        values["saturation"]?.text = state.saturation.toString(); values["temperature"]?.text = state.temperature.toString()
        values["brightness"]?.text = state.brightness.toString(); values["contrast"]?.text = state.contrast.toString()
    }

    private fun geometry(input: Bitmap, edit: State): Bitmap {
        var bitmap = input
        for (op in edit.geometry) {
            val next = if (op.rotation) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
            else {
                val x = (op.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
                val y = (op.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
                val w = ((op.right - op.left) * bitmap.width).roundToInt().coerceIn(1, bitmap.width - x)
                val h = ((op.bottom - op.top) * bitmap.height).roundToInt().coerceIn(1, bitmap.height - y)
                Bitmap.createBitmap(bitmap, x, y, w, h)
            }
            if (bitmap !== input && next !== bitmap) bitmap.recycle()
            bitmap = next
        }
        return bitmap
    }

    private fun dimensions(edit: State): Pair<Int, Int> {
        var w = source?.width ?: 1; var h = source?.height ?: 1
        edit.geometry.forEach { op -> if (op.rotation) { val old = w; w = h; h = old } else { w = max(1, ((op.right - op.left) * w).roundToInt()); h = max(1, ((op.bottom - op.top) * h).roundToInt()) } }
        if (edit.width in 1 until w) { h = max(1, (h.toDouble() * edit.width / w).roundToInt()); w = edit.width }
        return w to h
    }

    private fun render(input: Bitmap, edit: State, exporting: Boolean): Bitmap {
        var shaped = geometry(input, edit)
        val (w, h) = if (exporting) dimensions(edit) else shaped.width to shaped.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply { setSaturation(1f + edit.saturation / 100f) }
        val contrast = 1f + edit.contrast / 100f
        val offset = 128f * (1f - contrast) + edit.brightness * 2.55f
        val warmth = edit.temperature * 0.30f
        matrix.postConcat(ColorMatrix(floatArrayOf(contrast,0f,0f,0f,offset+warmth, 0f,contrast,0f,0f,offset, 0f,0f,contrast,0f,offset-warmth, 0f,0f,0f,1f,0f)))
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(shaped, null, Rect(0,0,w,h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        }
        if (shaped !== input) shaped.recycle()
        return output
    }

    private fun renderPreview() {
        val input = previewSource ?: return
        val edit = state; val ticket = ++generation
        updateControls()
        worker.execute {
            try {
                val output = render(input, edit, false)
                runOnUiThread {
                    if (!isDestroyed && ticket == generation) {
                        val old = preview; preview = output; image.setImageBitmap(output); old?.recycle()
                        val (w,h) = dimensions(edit)
                        status.text = getString(R.string.easy_dimensions, w, h, edit.quality)
                    } else output.recycle()
                }
            } catch (e: Exception) { showFailure(e.message) } catch (_: OutOfMemoryError) { showFailure(label(R.string.easy_memory_error)) }
        }
    }

    private fun applyAuto() {
        val input = previewSource ?: return
        val snapshot = state
        worker.execute {
            val shaped = geometry(input, snapshot)
            val histogram = IntArray(256); var count = 0; var total = 0L
            for (y in 0 until shaped.height step 8) for (x in 0 until shaped.width step 8) {
                val pixel = shaped.getPixel(x,y)
                val luma = (Color.red(pixel)*0.2126 + Color.green(pixel)*0.7152 + Color.blue(pixel)*0.0722).roundToInt().coerceIn(0,255)
                histogram[luma]++; count++; total += luma
            }
            if (shaped !== input) shaped.recycle()
            fun percentile(f: Double): Int { var sum = 0; for (i in 0..255) { sum += histogram[i]; if (sum >= count * f) return i }; return 255 }
            val contrast = ((200.0 / max(1,percentile(.95)-percentile(.05)) - 1) * 100).roundToInt().coerceIn(0,35)
            val brightness = ((128.0 - total.toDouble()/max(1,count)) / 2.55).roundToInt().coerceIn(-25,25)
            runOnUiThread { if (!isDestroyed && state == snapshot) change(snapshot.copy(brightness=brightness, contrast=contrast)) }
        }
    }

    private fun cropDialog() {
        val shown = preview ?: return
        // The established cropper handles touch gestures; edit history stores only its normalized rectangle.
        val cropper = CropImageView(this).apply { setImageBitmap(shown); setAutoZoomEnabled(false) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.easy_crop).setView(cropper)
            .setNegativeButton(R.string.easy_cancel, null).setPositiveButton(R.string.easy_apply) { _, _ ->
                cropper.cropRect?.let { rect ->
                    change(state.copy(width=0, geometry=state.geometry + Geometry(left=rect.left.toFloat()/shown.width, top=rect.top.toFloat()/shown.height, right=rect.right.toFloat()/shown.width, bottom=rect.bottom.toFloat()/shown.height)))
                }
            }.create()
        dialog.setOnShowListener {
            cropper.layoutParams = cropper.layoutParams.apply { height = (resources.displayMetrics.heightPixels * .55).toInt() }
            enlargeDialogButtons(dialog)
        }
        dialog.show()
    }

    private fun resizeDialog() {
        val (w,h) = dimensions(state.copy(width=0))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(8)) }
        fun field(title: String, value: Int): EditText {
            panel.addView(TextView(this).apply { text=title; textSize=20f; setTextColor(editorTextColor) })
            return EditText(this).apply { inputType=InputType.TYPE_CLASS_NUMBER; textSize=24f; setText(value.toString()); selectAll(); panel.addView(this) }
        }
        val width = field(getString(R.string.easy_width, w), if(state.width>0) state.width else w)
        val quality = field(label(R.string.easy_quality),state.quality)
        panel.addView(TextView(this).apply { text=label(R.string.easy_ratio); textSize=18f; setTextColor(editorTextColor) })
        val dialog = AlertDialog.Builder(this).setTitle(R.string.easy_resize).setView(panel).setNegativeButton(R.string.easy_cancel,null).setPositiveButton(R.string.easy_apply,null).create()
        dialog.setOnShowListener {
            enlargeDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pixels=width.text.toString().toIntOrNull(); val q=quality.text.toString().toIntOrNull()
                if(pixels==null || pixels !in 1..w) { width.error=getString(R.string.easy_width,w); return@setOnClickListener }
                if(q==null || q !in 1..100) { quality.error=label(R.string.easy_quality); return@setOnClickListener }
                change(state.copy(width=pixels,quality=q)); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun enlargeDialogButtons(dialog: AlertDialog) {
        listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE).forEach { which -> dialog.getButton(which)?.apply { textSize=20f; minHeight=dp(60) } }
    }

    private fun chooseOutput() {
        if (!ready) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="image/jpeg"
            putExtra(Intent.EXTRA_TITLE,"Editing-Gallery-${System.currentTimeMillis()}.jpg")
        }
        startActivityForResult(intent, 8101)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=8101 || resultCode!=Activity.RESULT_OK) return
        val destination=data?.data ?: return
        val input=source ?: return
        if(destination==uri) { showFailure(label(R.string.easy_copy_only)); return }
        val edit=state
        saving=true; updateControls(); status.text=label(R.string.easy_saving)
        worker.execute {
            try {
                val result=render(input,edit,true)
                try { requireNotNull(contentResolver.openOutputStream(destination,"w")).use { check(result.compress(Bitmap.CompressFormat.JPEG,edit.quality,it)) } }
                finally { result.recycle() }
                runOnUiThread { if(!isDestroyed) { saving=false; updateControls(); status.text=label(R.string.easy_saved); setResult(Activity.RESULT_OK) } }
            } catch(e:Exception) { saving=false; showFailure(e.message) } catch(_:OutOfMemoryError) { saving=false; showFailure(label(R.string.easy_memory_error)) }
        }
    }

    private fun showFailure(message: String?) = runOnUiThread {
        if (!isDestroyed) { status.text=message ?: label(R.string.easy_read_error); updateControls() }
    }

    @Deprecated("Handled for the prototype's discard confirmation")
    override fun onBackPressed() {
        if(saving)return
        if(undo.isEmpty()) { super.onBackPressed(); return }
        val dialog=AlertDialog.Builder(this).setMessage(R.string.easy_discard).setNegativeButton(R.string.easy_cancel,null)
            .setPositiveButton(R.string.easy_exit) { _,_-> finish() }.create()
        dialog.setOnShowListener { enlargeDialogButtons(dialog) }; dialog.show()
    }

    override fun onDestroy() { generation++; worker.shutdown(); super.onDestroy() }
}
