package org.fossify.gallery.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A lightweight, non-interactive film border that stays legible on any thumbnail. */
class FilmstripOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private val density = resources.displayMetrics.density

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, 10f * density, 10f * density, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val band = 12f * density
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, band, height.toFloat(), paint)
        canvas.drawRect(width - band, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.WHITE
        var y = 9f * density
        while (y + 6f * density < height - 4f * density) {
            canvas.drawRoundRect(3f * density, y, band - 3f * density, y + 6f * density, density, density, paint)
            canvas.drawRoundRect(width - band + 3f * density, y, width - 3f * density, y + 6f * density, density, density, paint)
            y += 13f * density
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        bounds.inset(density, density)
        canvas.drawRoundRect(bounds, 10f * density, 10f * density, paint)
        canvas.restore()
    }
}
