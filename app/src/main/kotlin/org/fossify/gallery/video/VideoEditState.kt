package org.fossify.gallery.video

import java.io.Serializable
import kotlin.math.ceil
import kotlin.math.max

data class VideoEditState(
    val startMs: Long = 0, val endMs: Long = 0,
    val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f,
    val rotation: Int = 0, val height: Int = 0,
    val saturation: Int = 0, val temperature: Int = 0, val brightness: Int = 0, val contrast: Int = 0
) : Serializable {
    fun valid(duration: Long): Boolean = startMs >= 0 && endMs <= duration && endMs > startMs &&
        left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top &&
        rotation in listOf(0, 90, 180, 270) && height >= 0

    fun workUnits(width: Int, sourceHeight: Int, fps: Float): Double =
        (endMs - startMs).coerceAtLeast(1) / 1000.0 *
            max(0.25, width.toDouble() * sourceHeight / (1920.0 * 1080)) * max(0.5, fps / 30.0)

    fun estimateSeconds(width: Int, sourceHeight: Int, fps: Float, measuredRate: Float?): LongRange {
        val rate = measuredRate?.takeIf { it.isFinite() && it > 0 }?.toDouble() ?: 1.0
        val seconds = workUnits(width, sourceHeight, fps) * rate
        return max(1L, ceil(seconds * 0.5).toLong())..max(2L, ceil(seconds * 3.0).toLong())
    }
}
