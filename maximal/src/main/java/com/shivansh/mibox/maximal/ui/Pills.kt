package com.shivansh.mibox.maximal.ui

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Frosted controls: a light veil and a hairline edge, no blur. */
object Frost {
    fun pill(height: Float): Drawable = GradientDrawable().apply {
        cornerRadius = height / 2f
        setColor(Palette.alpha(0xFFFFFF, 0.14f))
        setStroke(Ui.px(2), Palette.alpha(0xFFFFFF, 0.28f))
    }

    fun lit(height: Float): Drawable = GradientDrawable().apply {
        cornerRadius = height / 2f
        setColor(Palette.INK)
    }

    /** The round play button: frosted at rest, cream when focused. */
    fun circle(lit: Boolean): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (lit) {
            setColor(Palette.INK)
        } else {
            setColor(Palette.alpha(0xFFFFFF, 0.18f))
            setStroke(Ui.px(3), Palette.alpha(0xFFFFFF, 0.42f))
        }
    }

    /** A dark glass card for panels over the art. */
    fun card(radius: Float): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Palette.alpha(0x101014, 0.94f))
        setStroke(Ui.px(2), Palette.alpha(0xFFFFFF, 0.16f))
    }
}

/**
 * A short vertical list of pill buttons (icon and label). It never takes Android focus; the
 * activity moves [focused] with the remote and the focused pill is lit cream.
 */
class Pills(private val container: LinearLayout) {
    class Pill(val icon: Int, val label: String, val run: () -> Unit)

    private var items: List<Pill> = emptyList()
    private val height = Ui.px(60f)

    var focused = -1
        private set

    val size: Int get() = items.size

    fun set(list: List<Pill>) {
        items = list
        container.removeAllViews()
        list.forEachIndexed { i, pill -> container.addView(view(pill), params(i)) }
        focused = focused.coerceAtMost(list.size - 1)
        paint()
    }

    /** -1 for none. */
    fun focus(index: Int) {
        focused = if (index in items.indices) index else -1
        paint()
    }

    /** Up or down; false past either end. */
    fun move(delta: Int): Boolean {
        val next = focused + delta
        if (next !in items.indices) return false
        focus(next)
        return true
    }

    fun press() {
        items.getOrNull(focused)?.run?.invoke()
    }

    private fun paint() {
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as LinearLayout
            val lit = i == focused
            row.background = if (lit) Frost.lit(height) else Frost.pill(height)
            val color = if (lit) Palette.INK_DARK else Palette.INK
            (row.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (row.getChildAt(1) as TextView).setTextColor(color)
            val scale = if (lit) 1.05f else 1f
            row.animate().scaleX(scale).scaleY(scale).setDuration(240).setStartDelay(0).setInterpolator(Motion.LIFT)
        }
    }

    private fun view(pill: Pill): View = LinearLayout(container.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.px(28), 0, Ui.px(34), 0)
        addView(
            ImageView(context).apply {
                setImageResource(pill.icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(Ui.px(22), Ui.px(22)),
        )
        addView(
            TextView(context).apply {
                text = pill.label
                typeface = Fonts.medium
                includeFontPadding = false
                letterSpacing = 0.02f
                textPx(23f)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.px(14)
            },
        )
    }

    private fun params(i: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height.toInt()).apply {
        if (i > 0) topMargin = Ui.px(14)
        gravity = Gravity.CENTER_HORIZONTAL
    }
}
