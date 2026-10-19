package com.shivansh.mibox.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.os.SystemClock

/**
 * The hero's slide dots: the current one stretches into a bar that fills while the slide is up.
 * One small view redrawn while it animates, instead of resizing views (which would mean layouts).
 */
class HeroDots @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var count = 5
        set(value) {
            field = value
            invalidate()
        }
    private var active = 0
    private var previous = 0
    private var morph = 1f
    private var progress = 0f
    private var morphAnim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.alpha(0xFFFFFF, 0.28f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.INK }
    private val rect = RectF()

    fun select(index: Int) {
        if (index == active) return
        previous = active
        active = index
        hold()
        morphAnim?.cancel()
        morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360
            interpolator = Motion.SETTLE
            addUpdateListener {
                morph = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Fills the current bar over [durationMs], the time until the next slide. It steps ten times a
     * second rather than every frame (well under a pixel a step), so a playing preview doesn't keep
     * the whole UI redrawing at 60 fps just for this bar.
     */
    fun run(durationMs: Long) {
        removeCallbacks(tick)
        fillStart = SystemClock.uptimeMillis()
        fillDuration = durationMs
        progress = 0f
        post(tick)
    }

    fun hold() {
        removeCallbacks(tick)
        fillDuration = 0
        progress = 0f
        invalidate()
    }

    private var fillStart = 0L
    private var fillDuration = 0L
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (fillDuration <= 0) return
            progress = ((SystemClock.uptimeMillis() - fillStart).toFloat() / fillDuration).coerceIn(0f, 1f)
            invalidate()
            if (progress < 1f) postDelayed(this, 100)
        }
    }

    private fun widthOf(i: Int): Float {
        val small = Ui.px(10f)
        val large = Ui.px(56f)
        return when (i) {
            active -> small + (large - small) * morph
            previous -> large - (large - small) * morph
            else -> small
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        var x = 0f
        for (i in 0 until count) {
            val w = widthOf(i)
            rect.set(x, 0f, x + w, h)
            canvas.drawRoundRect(rect, r, r, track)
            if (i == active && progress > 0f) {
                canvas.save()
                canvas.clipRect(x, 0f, x + w * progress, h)
                canvas.drawRoundRect(rect, r, r, fill)
                canvas.restore()
            }
            x += w + Ui.px(10f)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        morphAnim?.cancel()
        removeCallbacks(tick)
    }
}

/**
 * Episode progress on an Up next card: one segment per episode, watched ones lit. Drawn as two
 * batches of round-capped lines (lit and unlit), not one shape per episode.
 */
class Segments @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var done = 0
    private var total = 0
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        strokeCap = Paint.Cap.ROUND
    }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.alpha(0xFFFFFF, 0.28f)
        strokeCap = Paint.Cap.ROUND
    }
    private var lit = FloatArray(0)
    private var unlit = FloatArray(0)

    fun set(done: Int, total: Int) {
        this.done = done.coerceIn(0, total)
        this.total = total
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        if (total <= 0 || width == 0) return
        val h = height.toFloat()
        val gap = Ui.px(4f)
        val w = (width - gap * (total - 1)) / total
        val y = h / 2f
        lit = FloatArray(done * 4)
        unlit = FloatArray((total - done) * 4)
        for (i in 0 until total) {
            // Round caps add half the stroke at each end, so inset the line by that much.
            val x0 = i * (w + gap) + y
            val x1 = i * (w + gap) + w - y
            val (target, at) = if (i < done) lit to i * 4 else unlit to (i - done) * 4
            target[at] = x0
            target[at + 1] = y
            target[at + 2] = x1
            target[at + 3] = y
        }
        on.strokeWidth = h
        off.strokeWidth = h
    }

    override fun onDraw(canvas: Canvas) {
        if (lit.isNotEmpty()) canvas.drawLines(lit, on)
        if (unlit.isNotEmpty()) canvas.drawLines(unlit, off)
    }
}
