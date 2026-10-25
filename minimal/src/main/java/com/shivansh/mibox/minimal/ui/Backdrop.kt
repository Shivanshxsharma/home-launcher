package com.shivansh.mibox.minimal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.shivansh.mibox.minimal.R

/**
 * Favs' full-screen backdrop: the focused title's art, crossfaded once focus rests. The shading
 * that keeps the words and posters legible (left, bottom, a little at the top) is drawn into the
 * picture as it is decoded, so the screen is one opaque bitmap with nothing blended over it.
 */
class Backdrop(private val root: View) {
    private val lower: ImageView = root.findViewById(R.id.backdrop_lower)
    private val upper: ImageView = root.findViewById(R.id.backdrop_upper)
    private val main = Handler(Looper.getMainLooper())
    private val settle = Runnable { decode() }
    private var wanted: String? = null
    private var shown: String? = null
    private var generation = 0

    fun setShown(on: Boolean, duration: Long) = root.fadeTo(if (on) 1f else 0f, duration)

    /** [url]'s art, after [delayMs] so quick moves along the row don't decode every one; null for black. */
    fun picture(url: String?, delayMs: Long = SETTLE_MS) {
        main.removeCallbacks(settle)
        if (url == null) {
            clear()
            return
        }
        wanted = url
        if (url == shown) return
        if (delayMs > 0) main.postDelayed(settle, delayMs) else decode()
    }

    private fun clear() {
        generation++
        wanted = null
        shown = null
        upper.animate().cancel()
        upper.visibility = View.INVISIBLE
        upper.setImageDrawable(null)
        lower.setImageDrawable(null)
    }

    private fun decode() {
        val url = wanted ?: return
        val gen = ++generation
        Workers.art.execute {
            val bitmap = runCatching {
                Decode.image(url, Ui.px(1280), Ui.px(720), mutable = true).also {
                    shade(it)
                    it.prepareToDraw()
                }
            }.getOrNull()
            main.post { if (gen == generation && bitmap != null) present(url, bitmap) }
        }
    }

    private fun present(url: String, bitmap: Bitmap) {
        shown = url
        // A fade still running: settle on its picture first, then fade the new one over it.
        upper.animate().cancel()
        if (upper.visibility == View.VISIBLE && upper.drawable != null) lower.setImageDrawable(upper.drawable)
        upper.setImageBitmap(bitmap)
        upper.alpha = 0f
        upper.visibility = View.VISIBLE
        upper.animate().alpha(1f).setDuration(FADE_MS).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction {
            lower.setImageBitmap(bitmap)
            upper.visibility = View.INVISIBLE
            upper.setImageDrawable(null)
        }
    }

    private companion object {
        const val SETTLE_MS = 220L
        const val FADE_MS = 520L

        /** Darker at the left for the words, at the bottom for the posters, and a little at the top. */
        fun shade(bitmap: Bitmap) {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.shader = LinearGradient(
                0f, 0f, w * 0.66f, 0f,
                intArrayOf(0xE6000000.toInt(), 0x9E000000.toInt(), 0x00000000),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = LinearGradient(
                0f, h * 0.45f, 0f, h,
                intArrayOf(0x00000000, 0xB3000000.toInt(), 0xF5000000.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, h * 0.45f, w, h, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, h * 0.16f, 0x8C000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h * 0.16f, paint)
        }
    }
}
