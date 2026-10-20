package com.shivansh.mibox.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * Fake frosted glass. Android 9 has no live blur, and the Mali-450 could not afford one anyway,
 * so each backdrop gets a tiny pre-blurred copy (240x135) that is stretched to full screen.
 * A blur this wide hides the 8x upscale.
 */
object Glass {
    const val WIDTH = 240
    const val HEIGHT = 135

    /** Where the frosted band behind the apps row starts, in mockup px out of 1080. */
    const val BAND_TOP = 730f

    /**
     * Glass for controls (tab bar, round buttons, chips): a light veil and a lit keyline, no blur.
     * [height] is the control's height in device px; ends are fully rounded.
     */
    fun control(height: Float, fill: Int = Palette.alpha(0xFFFFFF, 0.12f)): Drawable = LayerDrawable(
        arrayOf(
            GradientDrawable().apply {
                cornerRadius = height / 2f
                setColor(fill)
            },
            Keyline(height / 2f, Ui.px(1.5f), strength = 0.85f),
        ),
    )

    /** Full-screen glass: the backdrop blurred (~48px in mockup terms) and a little more saturated. */
    fun full(tiny: Bitmap): Bitmap = saturate(blur(tiny, radius = 6), 1.35f)

    /**
     * The band behind the apps row: the bottom of [glass], darkened toward the bottom edge and
     * faded in from transparent at its top, all baked into one small bitmap.
     */
    fun band(glass: Bitmap): Bitmap {
        val w = glass.width
        val top = (glass.height * BAND_TOP / 1080f).toInt()
        val h = glass.height - top
        val band = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(band)
        canvas.drawBitmap(glass, Rect(0, top, w, glass.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val shade = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(Palette.alpha(Palette.NIGHT, 0f), Palette.alpha(Palette.NIGHT, 0.25f), Palette.alpha(Palette.NIGHT, 0.55f)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        val mask = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h * 0.38f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        return band
    }

    private fun saturate(src: Bitmap, amount: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(amount) }) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Three box-blur passes, close to a Gaussian with sigma ~ radius. Only ever run on tiny bitmaps. */
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) {
            boxPass(a, b, w, h, radius, horizontal = true)
            boxPass(b, a, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, len - 1) * stride]
                sr += p shr 16 and 0xFF
                sg += p shr 8 and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * stride] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[base + (i - r).coerceAtLeast(0) * stride]
                val inn = src[base + (i + r + 1).coerceAtMost(len - 1) * stride]
                sr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                sg += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
