package org.fossify.gallery.activities

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.ui.PlayerView
import com.canhub.cropper.CropImageView
import org.fossify.gallery.R
import org.fossify.gallery.video.VideoEditState
import org.fossify.gallery.views.GalleryChrome
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** All preview/export inputs are granted URIs. Full encoding happens only after confirmation. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoEditActivity : SimpleActivity() {
    private lateinit var sourceUri: Uri
    private lateinit var player: ExoPlayer
    private lateinit var preview: PlayerView
    private lateinit var status: TextView
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private val controls = mutableListOf<View>()
    private val values = mutableMapOf<String, TextView>()
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private var state = VideoEditState()
    private var initial = VideoEditState()
    private var saved: VideoEditState? = null
    private val undo = arrayListOf<VideoEditState>()
    private val redo = arrayListOf<VideoEditState>()
    private var duration = 0L
    private var width = 1920
    private var height = 1080
    private var fps = 30f
    private var ready = false
    private var metadataFailed = false
    private var exporting = false
    private var copying = false
    private var pickerOpen = false
    private var transformer: Transformer? = null
    private var destination: Uri? = null
    private var temporary: File? = null
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var exportStart = 0L
    private var pending: VideoEditState? = null
    private var cropBitmap: Bitmap? = null
    private var cropDialog: AlertDialog? = null
    private var frameLoading = false
    private val outputPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { output ->
        pickerOpen = false
        if (output != null && output != sourceUri) {
            destination = output
            startExport(pending ?: state)
        } else updateControls()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun time(ms: Long): String = "%02d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)
    private fun text(value: String, size: Float = 18f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE) }
    private fun icon(drawable: Int, label: Int, action: () -> Unit) = GalleryChrome.icon(this, drawable, label) {
        if (ready && !exporting && !pickerOpen && !frameLoading) action()
    }.also { controls.add(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceUri = intent.data ?: run { finish(); return }
        @Suppress("DEPRECATION")
        state = savedInstanceState?.getSerializable("video_state") as? VideoEditState ?: VideoEditState()
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        (savedInstanceState?.getSerializable("video_undo") as? ArrayList<VideoEditState>)?.let { undo.addAll(it) }
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        (savedInstanceState?.getSerializable("video_redo") as? ArrayList<VideoEditState>)?.let { redo.addAll(it) }
        @Suppress("DEPRECATION")
        saved = savedInstanceState?.getSerializable("video_saved") as? VideoEditState
        @Suppress("DEPRECATION")
        pending = savedInstanceState?.getSerializable("video_pending") as? VideoEditState
        pickerOpen = savedInstanceState?.getBoolean("video_picker") ?: false
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { if (!exporting) status.text = getString(R.string.video_preview_error) }
        })
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exporting) { confirmCancel(); return }
                if (state != initial && state != saved) {
                    AlertDialog.Builder(this@VideoEditActivity).setMessage(R.string.easy_discard)
                        .setNegativeButton(R.string.easy_cancel, null).setPositiveButton(R.string.easy_exit) { _, _ -> finish() }.show()
                } else finish()
            }
        })
        worker.execute {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(this, sourceUri)
                val d = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val w = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                val h = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val rate = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
                require(d > 0)
                runOnUiThread {
                    if (!isDestroyed) {
                        duration = d; width = if (rotation % 180 == 0) w else h; height = if (rotation % 180 == 0) h else w; fps = rate
                        initial = VideoEditState(endMs = d)
                        if (state.endMs == 0L) state = initial
                        ready = true; refreshPreview()
                    }
                }
            } catch (_: Exception) { runOnUiThread { if (!isDestroyed) { metadataFailed = true; status.setText(R.string.scoped_video_error) } } }
            finally { metadata.release() }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        preview.player = null
        controls.clear(); values.clear(); buildUi()
        if (ready) status.text = "${width} × $height · ${time(state.endMs - state.startMs)}"
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val header = FrameLayout(this)
        val nameRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(GalleryChrome.icon(this, R.drawable.ic_easy_back, R.string.scoped_back) { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(dp(56), dp(64)))
        nameRow.addView(GalleryChrome.filename(this, intent.getStringExtra("name").orEmpty()), LinearLayout.LayoutParams(0, dp(64), 1f))
        header.addView(nameRow, FrameLayout.LayoutParams((resources.configuration.screenWidthDp * resources.displayMetrics.density / 2).toInt() - dp(36), dp(64), Gravity.START))
        header.addView(icon(R.drawable.ic_easy_save, R.string.easy_save_copy) { confirmExport() }, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(64)))
        val photo = FrameLayout(this)
        preview = PlayerView(this).apply {
            player = this@VideoEditActivity.player
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(false); setShowPreviousButton(false)
            setShowFastForwardButton(false); setShowRewindButton(false)
        }
        photo.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val history = LinearLayout(this)
        undoButton = icon(R.drawable.ic_easy_undo, R.string.easy_undo) { if (undo.isNotEmpty()) { redo.add(state); state = undo.removeAt(undo.lastIndex); refreshPreview() } }
        redoButton = icon(R.drawable.ic_easy_redo, R.string.easy_redo) { if (redo.isNotEmpty()) { undo.add(state); state = redo.removeAt(redo.lastIndex); refreshPreview() } }
        history.addView(undoButton, LinearLayout.LayoutParams(dp(48), dp(56)))
        history.addView(redoButton, LinearLayout.LayoutParams(dp(48), dp(56)))
        photo.addView(history, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val upper = LinearLayout(this); val lower = LinearLayout(this)
        upper.addView(icon(R.drawable.ic_easy_crop, R.string.easy_crop) { crop() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        upper.addView(icon(R.drawable.ic_easy_resize, R.string.video_resize) { resize() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        lower.addView(icon(R.drawable.ic_easy_rotate, R.string.easy_rotate) { change(state.copy(rotation = (state.rotation + 90) % 360)) }, LinearLayout.LayoutParams(dp(48), dp(56)))
        lower.addView(icon(R.drawable.ic_easy_trim, R.string.video_trim) { trim() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        tools.addView(upper); tools.addView(lower)
        photo.addView(tools, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        root.addView(photo, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        for ((key, label) in listOf("saturation" to R.string.easy_saturation, "temperature" to R.string.easy_temperature,
            "brightness" to R.string.easy_brightness, "contrast" to R.string.easy_contrast)) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(getString(label)), LinearLayout.LayoutParams(0, -2, 1f))
            val number = text("0").apply { gravity = Gravity.CENTER }; values[key] = number
            row.addView(number, LinearLayout.LayoutParams(dp(42), -2))
            for (delta in listOf(-5, 5)) {
                val button = Button(this).apply {
                    text = if (delta < 0) "−" else "+"; textSize = 22f
                    contentDescription = getString(label) + " " + text
                    setOnClickListener {
                        val value = ((when (key) { "saturation" -> state.saturation; "temperature" -> state.temperature; "brightness" -> state.brightness; else -> state.contrast }) + delta).coerceIn(-100, 100)
                        change(when (key) { "saturation" -> state.copy(saturation = value); "temperature" -> state.copy(temperature = value); "brightness" -> state.copy(brightness = value); else -> state.copy(contrast = value) })
                    }
                }
                controls.add(button); row.addView(button, LinearLayout.LayoutParams(dp(54), dp(54)))
            }
            panel.addView(row)
        }
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, dp(if (resources.configuration.orientation == 2) 108 else 216)))
        status = text(getString(R.string.video_loading), 16f).apply { gravity = Gravity.CENTER }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        setContentView(root); updateControls()
    }

    private fun change(next: VideoEditState) {
        if (!ready || exporting || pickerOpen || !next.valid(duration) || next == state) return
        undo.add(state); if (undo.size > 50) undo.removeAt(0)
        redo.clear(); state = next; refreshPreview()
    }
    private fun updateControls() {
        val enabled = ready && !exporting && !pickerOpen && !frameLoading
        controls.forEach { it.isEnabled = enabled }
        undoButton.isEnabled = enabled && undo.isNotEmpty(); redoButton.isEnabled = enabled && redo.isNotEmpty()
        values["saturation"]?.text = state.saturation.toString(); values["temperature"]?.text = state.temperature.toString()
        values["brightness"]?.text = state.brightness.toString(); values["contrast"]?.text = state.contrast.toString()
    }
    private fun effects(edit: VideoEditState): List<Effect> = buildList {
        if (edit.left != 0f || edit.right != 1f || edit.top != 0f || edit.bottom != 1f)
            add(Crop(edit.left * 2 - 1, edit.right * 2 - 1, 1 - edit.bottom * 2, 1 - edit.top * 2))
        if (edit.rotation != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-edit.rotation.toFloat()).build())
        if (edit.height > 0) add(Presentation.createForHeight(edit.height))
        if (edit.saturation != 0) add(HslAdjustment.Builder().adjustSaturation(edit.saturation.toFloat()).build())
        if (edit.temperature != 0) {
            val warm = edit.temperature / 300f
            add(RgbAdjustment.Builder().setRedScale(1f + warm).setBlueScale(1f - warm).build())
        }
        if (edit.brightness != 0) add(Brightness(edit.brightness / 100f))
        if (edit.contrast != 0) add(Contrast(edit.contrast.coerceAtMost(99) / 100f))
    }
    private fun item(edit: VideoEditState) = MediaItem.Builder().setUri(sourceUri).setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(edit.startMs).setEndPositionMs(edit.endMs).build()).build()
    private fun refreshPreview() {
        updateControls()
        try {
            val position = player.currentPosition.coerceAtMost((state.endMs - state.startMs - 1).coerceAtLeast(0))
            player.setVideoEffects(effects(state)); player.setMediaItem(item(state), position); player.prepare()
            status.text = "${width} × $height · ${time(state.endMs - state.startMs)}"
        } catch (_: Exception) { status.setText(R.string.video_preview_error) }
    }

    private fun trim() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        fun field(label: Int, value: Long): EditText {
            panel.addView(TextView(this).apply { setText(label); textSize = 18f })
            return EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; textSize = 22f; setText((value / 1000.0).toString()); panel.addView(this) }
        }
        val start = field(R.string.video_start, state.startMs)
        val startBar = SeekBar(this).apply { max = 1000; progress = (state.startMs * 1000 / duration).toInt(); minHeight = dp(56); contentDescription = getString(R.string.video_start) }; panel.addView(startBar)
        val end = field(R.string.video_end, state.endMs)
        val endBar = SeekBar(this).apply { max = 1000; progress = (state.endMs * 1000 / duration).toInt(); minHeight = dp(56); contentDescription = getString(R.string.video_end) }; panel.addView(endBar)
        fun connect(bar: SeekBar, field: EditText) {
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, user: Boolean) { if (user) field.setText((duration * value / 1000 / 1000.0).toString()) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val seconds = s?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: return
                    bar.progress = (seconds * 1000000 / duration).toInt().coerceIn(0, 1000)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        for (bar in listOf(startBar, endBar)) {
            bar.thumb = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE); setStroke(dp(2), Color.DKGRAY); setSize(dp(30), dp(30))
            }
        }
        connect(startBar, start); connect(endBar, end)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.video_trim).setView(panel).setNegativeButton(R.string.easy_cancel, null).setPositiveButton(R.string.easy_apply, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = start.text.toString().replace(',', '.').toDoubleOrNull()
            val b = end.text.toString().replace(',', '.').toDoubleOrNull()
            if (a == null || b == null || !a.isFinite() || !b.isFinite() || a < 0 || b * 1000 > duration || b <= a) { end.error = getString(R.string.video_trim_error); return@setOnClickListener }
            val next = state.copy(startMs = (a * 1000).toLong(), endMs = (b * 1000).toLong())
            if (!next.valid(duration)) { end.error = getString(R.string.video_trim_error); return@setOnClickListener }
            change(next); dialog.dismiss()
        } }; dialog.show()
    }
    private fun resize() {
        val labels = arrayOf(getString(R.string.video_original), "1080p", "720p", "480p", "360p")
        val heights = listOf(0, 1080, 720, 480, 360)
        AlertDialog.Builder(this).setTitle(R.string.video_resize).setSingleChoiceItems(labels, heights.indexOf(state.height)) { dialog, which ->
            val maxHeight = if (state.rotation % 180 == 0) height * (state.bottom - state.top) else width * (state.right - state.left)
            change(state.copy(height = if (which == 0) 0 else heights[which].coerceAtMost(maxHeight.toInt().coerceAtLeast(2)))); dialog.dismiss()
        }.setNegativeButton(R.string.easy_cancel, null).show()
    }
    private fun crop() {
        player.pause(); frameLoading = true; updateControls(); status.setText(R.string.video_loading)
        val at = (state.startMs + player.currentPosition).coerceAtMost(duration - 1)
        worker.execute {
            val reader = MediaMetadataRetriever()
            val bitmap = try { reader.setDataSource(this, sourceUri); reader.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 960) }
                catch (_: Exception) { null } finally { reader.release() }
            runOnUiThread {
                if (isDestroyed) { bitmap?.recycle(); return@runOnUiThread }
                frameLoading = false; updateControls()
                if (bitmap == null) { status.setText(R.string.video_preview_error); return@runOnUiThread }
                cropBitmap = bitmap
                val cropper = CropImageView(this).apply {
                    setImageBitmap(bitmap); isAutoZoomEnabled = false
                    cropRect = android.graphics.Rect((state.left * bitmap.width).roundToInt(), (state.top * bitmap.height).roundToInt(),
                        (state.right * bitmap.width).roundToInt(), (state.bottom * bitmap.height).roundToInt())
                }
                val dialog = AlertDialog.Builder(this).setTitle(R.string.easy_crop).setView(cropper)
                    .setNegativeButton(R.string.easy_cancel, null).setPositiveButton(R.string.easy_apply) { _, _ ->
                        cropper.cropRect?.let { rect -> change(state.copy(left = rect.left.toFloat() / bitmap.width,
                            top = rect.top.toFloat() / bitmap.height, right = rect.right.toFloat() / bitmap.width,
                            bottom = rect.bottom.toFloat() / bitmap.height, height = 0)) }
                    }.create()
                cropDialog = dialog
                dialog.setOnShowListener { cropper.layoutParams = cropper.layoutParams.apply { height = (resources.displayMetrics.heightPixels * .55).toInt() } }
                dialog.setOnDismissListener { cropper.setImageBitmap(null); bitmap.recycle(); cropBitmap = null; cropDialog = null; if (!exporting) status.text = time(state.endMs - state.startMs) }
                dialog.show()
            }
        }
    }

    private fun confirmExport() {
        player.pause()
        val prefs = getSharedPreferences("video_export", MODE_PRIVATE)
        val rate = if (prefs.contains("rate")) prefs.getFloat("rate", 1f) else null
        val estimate = state.estimateSeconds(width, height, fps, rate)
        val range = "${time(estimate.first * 1000)} – ${time(estimate.last * 1000)}"
        AlertDialog.Builder(this).setMessage(getString(R.string.video_confirm, range))
            .setNegativeButton(R.string.easy_cancel, null).setPositiveButton(R.string.video_start_export) { _, _ ->
                pending = state; pickerOpen = true; updateControls()
                outputPicker.launch("EasyVision-${System.currentTimeMillis()}.mp4")
            }.show()
    }
    private fun startExport(edit: VideoEditState) {
        // A picker result may arrive before metadata loading after process recreation.
        if (metadataFailed) { removeDestination(); return }
        if (!ready) { handler.postDelayed({ if (!isDestroyed) startExport(edit) }, 200); return }
        if (!edit.valid(duration)) { removeDestination(); return }
        exporting = true; cancelled.set(false); updateControls(); player.pause()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        progressText = TextView(this).apply { textSize = 20f; setText(R.string.video_processing) }; panel.addView(progressText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }; panel.addView(progressBar)
        progressDialog = AlertDialog.Builder(this).setTitle(R.string.video_processing).setView(panel).setCancelable(false)
            .setNegativeButton(R.string.easy_cancel, null).show()
        progressDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { cancelExport() }
        exportStart = SystemClock.elapsedRealtime()
        try {
            val output = File.createTempFile("easyvision-video-", ".mp4", cacheDir); output.delete(); temporary = output
            transformer = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { copyResult(edit, output) }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { failExport() }
                }).build()
            val edited = EditedMediaItem.Builder(item(edit)).setEffects(Effects(emptyList(), effects(edit))).build()
            transformer!!.start(edited, output.absolutePath)
            handler.post(progressPoll)
        } catch (_: Exception) { failExport() }
    }
    private val progressPoll = object : Runnable {
        override fun run() {
            if (!exporting || copying) return
            val holder = ProgressHolder()
            val progress = transformer?.getProgress(holder)
            if (progress == Transformer.PROGRESS_STATE_AVAILABLE) {
                progressBar?.isIndeterminate = false; progressBar?.progress = holder.progress
                progressText?.text = "${getString(R.string.video_processing)} ${holder.progress}%"
            }
            handler.postDelayed(this, 500)
        }
    }
    private fun copyResult(edit: VideoEditState, output: File) {
        handler.removeCallbacks(progressPoll); copying = true
        progressText?.setText(R.string.easy_saving); progressBar?.isIndeterminate = false; progressBar?.progress = 0
        val target = destination ?: run { failExport(); return }
        val elapsed = (SystemClock.elapsedRealtime() - exportStart) / 1000f
        worker.execute {
            val success = runCatching {
                output.inputStream().use { input -> requireNotNull(contentResolver.openOutputStream(target, "w")).use { out ->
                    val buffer = ByteArray(256 * 1024); var total = 0L; val length = output.length().coerceAtLeast(1)
                    while (true) {
                        check(!cancelled.get())
                        val read = input.read(buffer); if (read < 0) break
                        out.write(buffer, 0, read); total += read
                        val percent = (total * 100 / length).toInt()
                        runOnUiThread { if (!isDestroyed) progressBar?.progress = percent }
                    }
                    check(!cancelled.get())
                } }
            }.isSuccess
            output.delete()
            if (!success || cancelled.get()) runCatching { DocumentsContract.deleteDocument(contentResolver, target) }
            runOnUiThread {
                if (!isDestroyed) {
                    if (success && !cancelled.get()) {
                        val rate = (elapsed / edit.workUnits(width, height, fps)).toFloat().coerceAtLeast(.01f)
                        getSharedPreferences("video_export", MODE_PRIVATE).edit().putFloat("rate", rate).apply()
                        destination = null; saved = edit; finishExport(); status.setText(R.string.easy_saved)
                        setResult(Activity.RESULT_OK, Intent().setData(target))
                    } else { removeDestination(); finishExport(); status.setText(if (cancelled.get()) R.string.video_cancelled else R.string.video_export_error) }
                }
            }
        }
    }
    private fun confirmCancel() {
        AlertDialog.Builder(this).setMessage(R.string.video_cancel_question).setNegativeButton(R.string.easy_cancel, null)
            .setPositiveButton(R.string.video_stop) { _, _ -> cancelExport() }.show()
    }
    private fun removeDestination() {
        val target = destination; destination = null
        if (target != null && target != sourceUri) worker.execute { runCatching { DocumentsContract.deleteDocument(contentResolver, target) } }
    }
    private fun cancelExport() {
        cancelled.set(true); transformer?.cancel(); handler.removeCallbacks(progressPoll)
        if (!copying) { temporary?.delete(); removeDestination(); finishExport(); status.setText(R.string.video_cancelled) }
        else { progressText?.setText(R.string.video_stopping); progressDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false }
    }
    private fun failExport() { temporary?.delete(); removeDestination(); finishExport(); status.setText(R.string.video_export_error) }
    private fun finishExport() {
        exporting = false; copying = false; transformer = null; temporary = null
        handler.removeCallbacks(progressPoll); progressDialog?.dismiss(); progressDialog = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateControls()
    }
    override fun onPause() { if (::player.isInitialized) player.pause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable("video_state", state); outState.putSerializable("video_saved", saved)
        outState.putSerializable("video_undo", undo); outState.putSerializable("video_redo", redo)
        outState.putSerializable("video_pending", pending); outState.putBoolean("video_picker", pickerOpen)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        cancelled.set(true); handler.removeCallbacksAndMessages(null); transformer?.cancel(); if (::player.isInitialized) player.release()
        if (!copying) { temporary?.delete(); removeDestination() }
        cropDialog?.dismiss(); progressDialog?.dismiss(); worker.shutdown(); super.onDestroy()
    }
}
