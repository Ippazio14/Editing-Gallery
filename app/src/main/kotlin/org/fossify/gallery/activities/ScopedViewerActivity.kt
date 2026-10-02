package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import org.fossify.gallery.R
import org.fossify.gallery.scoped.FolderAccess
import java.util.concurrent.Executors

/** URI-only viewer: opening a granted document never asks for general storage permissions. */
class ScopedViewerActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var video: VideoView? = null
    private var position = 0
    private var playing = false
    private var deleting = false
    private lateinit var uri: Uri
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: run { finish(); return }
        position = savedInstanceState?.getInt("position") ?: 0
        playing = savedInstanceState?.getBoolean("playing") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + dp(8), bars.top + dp(8), bars.right + dp(8), bars.bottom + dp(8))
            insets
        }
        fun button(label: Int, action: () -> Unit) = Button(this).apply {
            setText(label); textSize = 18f; isAllCaps = false; minHeight = dp(56)
            setTextColor(Color.BLACK); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { if (!deleting) action() }
        }
        root.addView(button(R.string.scoped_back) { finish() })
        root.addView(TextView(this).apply {
            text = intent.getStringExtra("name"); textSize = 22f; setTextColor(Color.WHITE)
        })
        val isVideo = intent.type?.startsWith("video/") == true
        if (isVideo) {
            video = VideoView(this).apply {
                setMediaController(MediaController(this@ScopedViewerActivity).also { it.setAnchorView(this) })
                setVideoURI(uri)
                setOnPreparedListener { seekTo(position); if (playing) start() }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@ScopedViewerActivity, R.string.scoped_video_error, Toast.LENGTH_LONG).show(); true
                }
            }
            root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(button(R.string.scoped_play_pause) {
                video?.let { if (it.isPlaying) it.pause() else it.start() }
            })
        } else {
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val zoom = Matrix()
            var scale = 1f
            var x = 0f
            var y = 0f
            val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val next = (scale * detector.scaleFactor).coerceIn(1f, 8f)
                    image.scaleType = ImageView.ScaleType.MATRIX
                    zoom.postScale(next / scale, next / scale, detector.focusX, detector.focusY)
                    scale = next; image.imageMatrix = zoom
                    return true
                }
            })
            image.setOnTouchListener { _, event ->
                if (image.scaleType != ImageView.ScaleType.MATRIX) zoom.set(image.imageMatrix)
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_MOVE && !detector.isInProgress && scale > 1f) {
                    zoom.postTranslate(event.x - x, event.y - y); image.imageMatrix = zoom
                }
                x = event.x; y = event.y
                if (event.actionMasked == MotionEvent.ACTION_UP) image.performClick()
                true
            }
            Glide.with(this).load(uri).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .skipMemoryCache(true).into(image)
            root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(button(R.string.scoped_edit) {
                startActivity(Intent(this, EditActivity::class.java).setDataAndType(uri, intent.type))
            })
        }
        root.addView(button(R.string.scoped_share) {
            val send = Intent(Intent.ACTION_SEND).setType(intent.type).putExtra(Intent.EXTRA_STREAM, uri).apply {
                clipData = ClipData.newRawUri(intent.getStringExtra("name"), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.scoped_share)))
        })
        root.addView(button(R.string.scoped_delete) {
            AlertDialog.Builder(this).setMessage(R.string.scoped_delete_confirm)
                .setNegativeButton(R.string.scoped_cancel, null)
                .setPositiveButton(R.string.scoped_delete) { _, _ -> delete() }.show()
        }.apply { isEnabled = FolderAccess(this@ScopedViewerActivity).canWrite(uri) })
        setContentView(root)
    }

    private fun delete() {
        deleting = true
        worker.execute {
            val success = runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
            runOnUiThread {
                if (!isDestroyed) {
                    deleting = false
                    if (success) finish() else Toast.makeText(this, R.string.scoped_delete_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    override fun onPause() {
        video?.let { position = it.currentPosition; playing = it.isPlaying; it.pause() }
        super.onPause()
    }
    override fun onResume() { super.onResume(); if (playing) video?.start() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("position", video?.currentPosition ?: position)
        outState.putBoolean("playing", video?.isPlaying == true || playing)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { video?.stopPlayback(); worker.shutdownNow(); super.onDestroy() }
}
