package com.shivansh.mytv.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Frosted glass without live blur, which the Mali-450 can't afford. Each time the picture changes,
 * a 160x90 blurred copy is made off the main thread, and glass panels draw the part of it that
 * lies behind them, so a panel costs one small textured rectangle per frame.
 */
object Frost {
    private const val W = 160
    private const val H = 90
    private var now: BitmapShader? = null
    private var before: BitmapShader? = null
    private var mix = 1f
    private var fade: ValueAnimator? = null
    private val hosts = HashSet<View>()
    private val settled = HashSet<View>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    fun attach(host: View) {
        hosts += host
    }

    /** For a costly host, like the cached clock: one redraw when the fade ends, not one per frame. */
    fun attachSettled(host: View) {
        settled += host
    }

    /** Fades the glass over to [bitmap], made by [make]. */
    fun set(bitmap: Bitmap) {
        fade?.cancel()
        before = now
        now = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (before == null) {
            mix = 1f
            hosts.forEach { it.invalidate() }
            settled.forEach { it.invalidate() }
            return
        }
        mix = 0f
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ms(600)
            addUpdateListener {
                mix = it.animatedValue as Float
                hosts.forEach { host -> host.invalidate() }
                if (mix >= 1f) settled.forEach { host -> host.invalidate() }
            }
            start()
        }
    }

    /** The frost behind [rect], with [tint] mixed in as it's drawn: one pass, which matters on big panels. */
    fun draw(canvas: Canvas, rect: RectF, radius: Float, x: Float, y: Float, screenW: Float, screenH: Float, tint: Int = 0) {
        matrix.setScale(screenW / W, screenH / H)
        matrix.postTranslate(-x, -y)
        paint.colorFilter = if (tint != 0) PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_OVER) else null
        val first = if (mix < 1f) before else now
        first?.let {
            it.setLocalMatrix(matrix)
            paint.shader = it
            paint.alpha = 255
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        if (mix < 1f) {
            now?.let {
                it.setLocalMatrix(matrix)
                paint.shader = it
                paint.alpha = (255 * mix).toInt()
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    /** A small, soft, slightly richer and darker copy of [source]. Slow: call off the main thread. */
    fun make(source: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(source, W, H, true)
        val a = IntArray(W * H)
        small.getPixels(a, 0, W, 0, 0, W, H)
        if (small !== source) small.recycle()
        val b = IntArray(W * H)
        repeat(3) {
            box(a, b, W, H, 1, W, 5)
            box(b, a, H, W, W, 1, 3)
        }
        for (i in a.indices) {
            val c = a[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val bl = Color.blue(c)
            val luma = (r * 3 + g * 6 + bl) / 10f
            fun tone(v: Int) = ((luma + (v - luma) * 1.35f) * 0.7f).toInt().coerceIn(0, 255)
            a[i] = Color.rgb(tone(r), tone(g), tone(bl))
        }
        return Bitmap.createBitmap(a, W, H, Bitmap.Config.ARGB_8888)
    }

    /** One box-blur pass along lines of [length] pixels, [lines] of them. */
    private fun box(from: IntArray, to: IntArray, length: Int, lines: Int, step: Int, lineStep: Int, radius: Int) {
        val size = radius * 2 + 1
        for (line in 0 until lines) {
            val base = line * lineStep
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = from[base + i.coerceIn(0, length - 1) * step]
                r += (c shr 16) and 255
                g += (c shr 8) and 255
                b += c and 255
            }
            for (i in 0 until length) {
                to[base + i * step] = (0xFF shl 24) or ((r / size) shl 16) or ((g / size) shl 8) or (b / size)
                val leaving = from[base + (i - radius).coerceIn(0, length - 1) * step]
                val coming = from[base + (i + radius + 1).coerceIn(0, length - 1) * step]
                r += ((coming shr 16) and 255) - ((leaving shr 16) and 255)
                g += ((coming shr 8) and 255) - ((leaving shr 8) and 255)
                b += (coming and 255) - (leaving and 255)
            }
        }
    }
}

/**
 * A glass panel: the frost behind [host], a soft [tint], and a hairline that is brighter along the
 * top, like light on an edge. With [selectable], a selected host turns solid white.
 */
class GlassDrawable(
    private val host: View,
    private val radius: Float,
    private val tint: Int,
    private val edge: Boolean = true,
    private val selectable: Boolean = false,
) : Drawable() {
    private val rect = RectF()
    private val line = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.px(2f)
    }
    private val where = IntArray(2)
    private var selected = false

    init {
        Frost.attach(host)
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        line.set(bounds)
        val half = stroke.strokeWidth / 2f
        line.inset(half, half)
        stroke.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            0x59FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val r = radius.coerceAtMost(rect.height() / 2f)
        if (selected) {
            fill.color = Palette.INK
            canvas.drawRoundRect(rect, r, r, fill)
            return
        }
        host.getLocationInWindow(where)
        val root = host.rootView
        Frost.draw(canvas, rect, r, where[0].toFloat(), where[1].toFloat(), root.width.toFloat(), root.height.toFloat(), tint)
        if (edge) canvas.drawRoundRect(line, r, r, stroke)
    }

    override fun isStateful() = selectable

    override fun onStateChange(state: IntArray): Boolean {
        val on = selectable && state.contains(android.R.attr.state_selected)
        if (on == selected) return false
        selected = on
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
