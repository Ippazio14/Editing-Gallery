package org.fossify.gallery.views

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

/** Shared vector controls; spoken labels remain available without visible button text. */
object GalleryChrome {
    fun icon(context: Context, drawable: Int, label: Int, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val inset = (12 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset, inset, inset)
        contentDescription = context.getString(label)
        tooltipText = contentDescription
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(110, 0, 0, 0))
        }
        setOnClickListener { action() }
    }

    fun filename(context: Context, name: String) = TextView(context).apply {
        text = name
        contentDescription = name
        textSize = 16f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        setTextColor(Color.WHITE)
        setShadowLayer(5f * resources.displayMetrics.density, 0f, 0f, Color.BLACK)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.MIDDLE
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
}
