package com.shivansh.mibox.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.max
import kotlin.math.min

/**
 * The glass tab bar. Its items are its children, in order. A soft pill marks the current page; when
 * the bar has focus a cream pill slides between tabs, its leading edge a little ahead of its
 * trailing one so it stretches as it travels, and each label turns dark exactly as far as the pill
 * covers it. Only this small view redraws while the pill moves.
 */
class TabBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var selected = 1
        private set
    var focused = 1
        private set
    private var barFocused = false
    private var focusAlpha = 0f
    private var alphaAnim: ValueAnimator? = null

    private val focusLeft = Edge(::changed)
    private val focusRight = Edge(::changed)
    private val selectLeft = Edge(::changed)
    private val selectRight = Edge(::changed)
    private var placed = false

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.INK }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.alpha(0xFFFFFF, 0.16f) }
    private val rect = RectF()
    private val nudge = SpringAnimation(this, DynamicAnimation.TRANSLATION_X, 0f).apply {
        spring.stiffness = 700f
        spring.dampingRatio = 0.42f
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    /** One edge of a pill, moved by its own spring. */
    private class Edge(onChange: () -> Unit) {
        private val value = FloatValueHolder()
        private val anim = SpringAnimation(value).apply {
            spring = SpringForce().setDampingRatio(0.85f).setStiffness(600f)
            addUpdateListener { _, _, _ -> onChange() }
        }
        val x get() = value.value

        fun snap(to: Float) {
            anim.cancel()
            value.value = to
        }

        fun glide(to: Float, stiffness: Float) {
            anim.spring.stiffness = stiffness
            anim.animateToFinalPosition(to)
        }
    }

    private fun changed() {
        invalidate()
        recolor()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!placed && childCount > 0) {
            placed = true
            snap(focusLeft, focusRight, focused)
            snap(selectLeft, selectRight, selected)
            recolor()
        }
    }

    private fun snap(left: Edge, right: Edge, index: Int) {
        val item = getChildAt(index) ?: return
        left.snap(item.left.toFloat())
        right.snap(item.right.toFloat())
        invalidate()
    }

    private fun slide(left: Edge, right: Edge, index: Int, direction: Int) {
        val item = getChildAt(index) ?: return
        // The leading edge springs faster than the trailing one: the pill stretches, then settles.
        left.glide(item.left.toFloat(), if (direction < 0) 900f else 420f)
        right.glide(item.right.toFloat(), if (direction > 0) 900f else 420f)
    }

    /** The bar takes or gives up focus; focus starts on the current page's tab. */
    fun setBarFocused(on: Boolean) {
        if (on == barFocused) return
        barFocused = on
        if (on) {
            focused = selected
            snap(focusLeft, focusRight, focused)
        }
        alphaAnim?.cancel()
        alphaAnim = ValueAnimator.ofFloat(focusAlpha, if (on) 1f else 0f).apply {
            duration = if (on) 260 else 200
            interpolator = Motion.EASE
            addUpdateListener {
                focusAlpha = it.animatedValue as Float
                changed()
            }
            start()
        }
    }

    /** Moves the focus pill one tab; false at either end. */
    fun moveFocus(delta: Int): Boolean {
        val next = focused + delta
        if (next !in 0 until childCount) return false
        focused = next
        slide(focusLeft, focusRight, next, delta)
        return true
    }

    /** Slides the focus pill straight to [index]. */
    fun focusOn(index: Int) {
        if (index == focused || index !in 0 until childCount) return
        val direction = index - focused
        focused = index
        slide(focusLeft, focusRight, index, direction)
    }

    fun select(index: Int) {
        if (index == selected) return
        val direction = index - selected
        selected = index
        slide(selectLeft, selectRight, index, direction)
        recolor()
    }

    /** Focus hit the end of the bar: a small push that springs back. */
    fun bump(dx: Int) {
        nudge.setStartVelocity(dx * Ui.px(700f)).animateToFinalPosition(0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val first = getChildAt(0) ?: return
        val top = first.top.toFloat()
        val bottom = first.bottom.toFloat()
        val r = (bottom - top) / 2f
        rect.set(selectLeft.x, top, selectRight.x, bottom)
        canvas.drawRoundRect(rect, r, r, selectPaint)
        if (focusAlpha > 0.01f) {
            focusPaint.alpha = (focusAlpha * 255).toInt()
            rect.set(focusLeft.x, top, focusRight.x, bottom)
            canvas.drawRoundRect(rect, r, r, focusPaint)
        }
    }

    /** Each label's color follows how much of it the cream pill covers right now. */
    private fun recolor() {
        val pl = focusLeft.x
        val pr = focusRight.x
        for (i in 0 until childCount) {
            val item = getChildAt(i)
            val width = max(1, item.width).toFloat()
            val cover = (max(0f, min(pr, item.right.toFloat()) - max(pl, item.left.toFloat())) / width) * focusAlpha
            val base = if (i == selected) Palette.INK else SOFT
            tint(item, mix(base, INK_DARK, cover.coerceIn(0f, 1f)))
        }
    }

    private fun tint(item: View, color: Int) {
        when (item) {
            is TextView -> if (item.currentTextColor != color) item.setTextColor(color)
            is ImageView -> item.imageTintList = ColorStateList.valueOf(color)
            is android.view.ViewGroup -> for (j in 0 until item.childCount) tint(item.getChildAt(j), color)
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private companion object {
        const val INK_DARK = 0xFF0A0A0C.toInt()
        const val SOFT = 0xFFC9C5CF.toInt()
    }
}
