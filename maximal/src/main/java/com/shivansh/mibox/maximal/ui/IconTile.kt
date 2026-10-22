package com.shivansh.mibox.maximal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** An app without a TV banner: its icon in the middle of a dark wash of its own color. */
class IconTile(private val icon: Bitmap, private val glow: Int, private val radius: Float) : Drawable() {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val dst = RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(bounds)
        face.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            mix(glow, Palette.NIGHT, 0.7f), mix(glow, Palette.NIGHT, 0.9f), Shader.TileMode.CLAMP,
        )
        val size = rect.height() * 0.58f
        dst.set(rect.centerX() - size / 2f, rect.centerY() - size / 2f, rect.centerX() + size / 2f, rect.centerY() + size / 2f)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, face)
        canvas.drawBitmap(icon, null, dst, art)
    }

    override fun setAlpha(alpha: Int) {
        face.alpha = alpha
        art.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        art.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}
