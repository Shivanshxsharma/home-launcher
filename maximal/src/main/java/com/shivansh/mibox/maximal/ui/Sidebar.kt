package com.shivansh.mibox.maximal.ui

import android.animation.ObjectAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shivansh.mibox.maximal.R

/**
 * The left sidebar: the sections, then Profile and Settings under a divider. A thin bar on the
 * right edge marks the section on screen; while the sidebar has the remote, a cream pill glides
 * to the item in focus. It never takes Android focus; the activity moves [focused].
 */
class Sidebar(private val root: ViewGroup) {
    enum class Item { HOME, SHOWS, MOVIES, APPS, SEARCH, PROFILE, SETTINGS }

    private val labels: List<TextView> = listOf(R.id.nav_sections, R.id.nav_account).flatMap { id ->
        val group = root.findViewById<ViewGroup>(id)
        (0 until group.childCount).map { group.getChildAt(it) as TextView }
    }
    private val pill: View = root.findViewById(R.id.sidebar_pill)
    private val bar: View = root.findViewById(R.id.sidebar_bar)

    var focused = 0
        private set

    /** The section on screen (one of the first five items). */
    var selected = 0
        private set

    private var lit = false

    /** The pill and bar move on their own animators, so a move never cuts the pill's fade short. */
    private var pillGlide: ObjectAnimator? = null
    private var barGlide: ObjectAnimator? = null

    val focusedItem: Item get() = Item.entries[focused]

    init {
        pill.background = GradientDrawable().apply {
            cornerRadius = Ui.px(16f)
            setColor(Palette.INK)
        }
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) place(animate = false)
        }
    }

    fun setLit(on: Boolean) {
        lit = on
        if (on) focused = selected
        paint()
        place(animate = false)
        pill.fadeTo(if (on) 1f else 0f, 180)
    }

    fun move(delta: Int): Boolean {
        val next = focused + delta
        if (next !in labels.indices) return false
        focused = next
        paint()
        place(animate = true)
        return true
    }

    fun select(item: Item) {
        selected = item.ordinal
        paint()
        place(animate = true)
    }

    private fun paint() {
        labels.forEachIndexed { i, label ->
            label.setTextColor(
                when {
                    lit && i == focused -> Palette.INK_DARK
                    i == selected -> Palette.INK
                    else -> Palette.QUIET
                },
            )
            label.typeface = if (i == selected || (lit && i == focused)) Fonts.medium else Fonts.regular
        }
    }

    private fun place(animate: Boolean) {
        val focusLabel = labels.getOrNull(focused) ?: return
        val selectedLabel = labels.getOrNull(selected) ?: return
        val pillY = yOf(focusLabel) + (focusLabel.height - pill.height) / 2f
        val barY = yOf(selectedLabel) + (selectedLabel.height - bar.height) / 2f
        pillGlide?.cancel()
        barGlide?.cancel()
        if (animate) {
            pillGlide = glide(pill, pillY, 260)
            barGlide = glide(bar, barY, 320)
        } else {
            pill.translationY = pillY
            bar.translationY = barY
        }
    }

    private fun glide(view: View, to: Float, duration: Long) = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, to).apply {
        this.duration = duration
        interpolator = Motion.SETTLE
        start()
    }

    /** [view]'s top inside the sidebar. */
    private fun yOf(view: View): Float {
        var y = 0f
        var v: View = view
        while (v !== root) {
            y += v.top
            v = v.parent as View
        }
        return y
    }
}
