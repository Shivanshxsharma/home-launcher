package com.shivansh.mibox.ui

import android.view.View

/**
 * The top-right corner of the tab bar: network, Android settings, and the profile. The remote
 * reaches it by going right past the last tab. The focused one is marked selected, which turns its
 * pill white (see corner_bg), and grows a little.
 */
class Corner(private val items: List<View>) {
    /** -1 while the remote is elsewhere. */
    var focused = -1
        private set

    fun enter() = focus(0)

    fun leave() = focus(-1)

    /** Left or right along the corner; false past either end. */
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
            val scale = if (on) GROW else 1f
            item.animate().scaleX(scale).scaleY(scale).setDuration(200).setStartDelay(0).setInterpolator(Motion.EASE)
        }
    }

    companion object {
        const val NETWORK = 0
        const val SETTINGS = 1
        const val PROFILE = 2

        private const val GROW = 1.12f
    }
}
