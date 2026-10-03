package org.fossify.gallery.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/** Both labels are overlays on the same thumbnail, with room for large accessibility fonts. */
class AccessibleGalleryTile(context: Context) : FrameLayout(context) {
    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.rgb(45, 45, 45))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val nameOverlay = overlay().apply {
        gravity = Gravity.LEFT
        setTypeface(typeface, Typeface.BOLD)
    }
    val countOverlay = overlay().apply { gravity = Gravity.RIGHT }
    private var fullName = ""
    var hasFilmBorder = false
        private set
    var isEmptyAlbum = false
        private set

    init {
        isFocusable = true
        setBackgroundColor(Color.BLACK)
        addView(image, LayoutParams(-1, -1))
        addView(nameOverlay, LayoutParams(-1, -2, Gravity.TOP or Gravity.LEFT))
        addView(countOverlay, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun overlay() = TextView(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        textSize = 16f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        setShadowLayer(dp(5).toFloat(), 0f, 0f, Color.BLACK)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setPadding(dp(6), dp(4), dp(6), dp(4))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Full labels remain in the tile accessibility description.
    }

    fun bindLabels(name: String, counts: String, videoAlbum: Boolean, emptyAlbum: Boolean, selected: Boolean) {
        fullName = name
        nameOverlay.text = name
        countOverlay.text = counts
        countOverlay.visibility = if (counts.isEmpty()) View.GONE else View.VISIBLE
        contentDescription = listOf(name, counts).filter { it.isNotEmpty() }.joinToString(", ")
        isSelected = selected
        hasFilmBorder = videoAlbum
        isEmptyAlbum = emptyAlbum
        val rail = if (videoAlbum) dp(12) else 0
        setPadding(0, 0, 0, 0)
        (nameOverlay.layoutParams as LayoutParams).apply {
            setMargins(rail, rail, rail, 0)
            nameOverlay.layoutParams = this
        }
        (countOverlay.layoutParams as LayoutParams).apply {
            setMargins(rail, 0, rail, rail)
            countOverlay.layoutParams = this
        }
        foreground = if (videoAlbum || selected) FilmFrame(resources.displayMetrics.density, videoAlbum, selected) else null
        image.alpha = if (emptyAlbum) 0.45f else 1f
        if (emptyAlbum) image.setImageBitmap(emptyPreview)
        else image.setImageDrawable(null)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val margin = if (hasFilmBorder) dp(24) else 0
        val innerWidth = (width - margin).coerceAtLeast(1)
        val available = (innerWidth - nameOverlay.paddingLeft - nameOverlay.paddingRight).coerceAtLeast(1)
        val parts = fullName.split('/')
        nameOverlay.text = if (parts.size > 2 && nameOverlay.paint.measureText(fullName) > available) {
            "${parts.first()}/…/${parts.last()}"
        } else fullName
        nameOverlay.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        countOverlay.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val labelsHeight = nameOverlay.measuredHeight +
            (if (countOverlay.visibility == View.GONE) 0 else countOverlay.measuredHeight)
        val height = max(max(width, dp(180)), labelsHeight + paddingTop + paddingBottom + dp(32))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    private class FilmFrame(private val density: Float, private val film: Boolean, private val selected: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (film) {
                val rail = 12f * density
                paint.color = Color.BLACK
                paint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, rail, h, paint)
                canvas.drawRect(w - rail, 0f, w, h, paint)
                canvas.drawRect(0f, 0f, w, rail, paint)
                canvas.drawRect(0f, h - rail, w, h, paint)
                paint.color = Color.LTGRAY
                var x = 17f * density
                while (x + 7f * density < w - rail) {
                    canvas.drawRoundRect(x, 3f * density, x + 7f * density, 9f * density, density, density, paint)
                    canvas.drawRoundRect(x, h - 9f * density, x + 7f * density, h - 3f * density, density, density, paint)
                    x += 14f * density
                }
                var y = 17f * density
                while (y + 7f * density < h - rail) {
                    canvas.drawRoundRect(3f * density, y, 9f * density, y + 7f * density, density, density, paint)
                    canvas.drawRoundRect(w - 9f * density, y, w - 3f * density, y + 7f * density, density, density, paint)
                    y += 14f * density
                }
            }
            if (selected) {
                paint.color = Color.CYAN
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f * density
                canvas.drawRect(paint.strokeWidth / 2, paint.strokeWidth / 2,
                    w - paint.strokeWidth / 2, h - paint.strokeWidth / 2, paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        // An empty root has no photo to blur. This soft folder silhouette is its neutral thumbnail.
        // Software rendering keeps the blur compatible with Android 9 as well as newer devices.
        private val emptyPreview: Bitmap by lazy {
            Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(50, 50, 50))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.LTGRAY
                    maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(24f, 34f, 57f, 50f, 4f, 4f, paint)
                canvas.drawRoundRect(24f, 44f, 96f, 88f, 5f, 5f, paint)
            }
        }
    }
}

/** Adjacent tiles have a complete gap, never less than five physical pixels. */
class GalleryGridSpacing(density: Float) : RecyclerView.ItemDecoration() {
    val gapPixels = max(5, (6f * density).toInt())
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val half = gapPixels / 2
        outRect.set(half, half, gapPixels - half, gapPixels - half)
    }
}
