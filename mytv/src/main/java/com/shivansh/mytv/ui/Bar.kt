package com.shivansh.mytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The bar's selection pill. Its two edges travel separately, the leading one first, so the pill
 * stretches towards the next tab and squeezes back as it lands, like liquid in a tube.
 */
class Pill(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var left = 0f
    private var right = 0f
    private var edges: List<ValueAnimator> = emptyList()

    override fun hasOverlappingRendering() = false

    /** Takes no width of its own, so the bar wraps its tabs, not the screen. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
        setMeasuredDimension(if (exact) MeasureSpec.getSize(widthMeasureSpec) else 0, MeasureSpec.getSize(heightMeasureSpec))
    }

    fun color(value: Int) {
        paint.color = value
        invalidate()
    }

    fun place(toLeft: Float, toRight: Float, animate: Boolean) {
        edges.forEach { it.cancel() }
        if (!animate || right == 0f) {
            left = toLeft
            right = toRight
            invalidate()
            return
        }
        val forward = toLeft > left
        edges = listOf(
            edge(left, toLeft, if (forward) TRAIL_MS else LEAD_MS) { left = it },
            edge(right, toRight, if (forward) LEAD_MS else TRAIL_MS) { right = it },
        )
    }

    private fun edge(from: Float, to: Float, ms: Long, set: (Float) -> Unit) = ValueAnimator.ofFloat(from, to).apply {
        duration = Motion.ms(ms)
        interpolator = Motion.SETTLE
        addUpdateListener {
            set(it.animatedValue as Float)
            invalidate()
        }
        start()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(left, 0f, right, height.toFloat())
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
    }

    private companion object {
        const val LEAD_MS = 240L
        const val TRAIL_MS = 400L
    }
}

/** The floating bar: tabs and the pill under them, white while the remote is on the bar. */
class Bar(private val tabs: LinearLayout, private val pill: Pill) {
    private val views = (0 until tabs.childCount).map { tabs.getChildAt(it) as TextView }
    private var index = 0
    private var focused = false

    init {
        tabs.post { paint(animate = false) }
    }

    fun set(index: Int, focused: Boolean) {
        val moved = index != this.index
        this.index = index
        this.focused = focused
        paint(animate = moved)
    }

    private fun paint(animate: Boolean) {
        val tab = views[index]
        if (tab.width > 0) pill.place(tabs.left.toFloat() - pill.left + tab.left, tabs.left.toFloat() - pill.left + tab.right, animate)
        pill.color(if (focused) Palette.INK else 0x33FFFFFF)
        views.forEachIndexed { i, view ->
            val on = i == index
            view.setTextColor(if (on && focused) Palette.DARK else Palette.INK)
            view.alpha = if (on) 1f else 0.72f
        }
    }
}

/** The top-right corner: network and settings. The selected one turns white (see corner_bg). */
class Corner(private val items: List<View>) {
    /** -1 while the remote is elsewhere. */
    var focused = -1
        private set

    fun enter() = focus(0)

    fun leave() = focus(-1)

    fun move(delta: Int): Boolean {
        val next = focused + delta
        if (next !in items.indices) return false
        focus(next)
        return true
    }

    private fun focus(index: Int) {
        focused = index
        items.forEachIndexed { i, item ->
            val on = i == index
            item.isSelected = on
            val scale = if (on) 1.12f else 1f
            item.animate().scaleX(scale).scaleY(scale).setDuration(Motion.ms(420)).setStartDelay(0).setInterpolator(Motion.LIFT)
        }
    }

    companion object {
        const val NETWORK = 0
        const val SETTINGS = 1
    }
}
