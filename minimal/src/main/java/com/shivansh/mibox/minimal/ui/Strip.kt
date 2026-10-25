package com.shivansh.mibox.minimal.ui

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.LinearLayout

/**
 * A row of tiles with the Mi TV selector: the focused tile grows, lightens under a glassy sheen
 * with a glow rising from its foot, and a short white bar sits on its bottom edge. The focused
 * tile always sits at the left margin and the row slides beneath it, so the bar never moves; a
 * move is one translation of the track plus one tile's scale. Tiles already passed fade out.
 */
class Strip(private val track: LinearLayout, private val bar: View) {
    private val gap = Ui.px(32)
    private var step = 0
    private val outline = RoundOutline(Ui.px(8f))
    private val glow = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(0x59FFFFFF, 0x00FFFFFF)
        setGradientCenter(0.5f, 1f)
    }

    /** The light on the focused tile, moved from tile to tile (a row's tiles are all one size). */
    private val light = LayerDrawable(
        arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x2EFFFFFF, 0x0DFFFFFF, 0x00FFFFFF)),
            glow,
        ),
    )

    var focused = 0
        private set

    val size: Int get() = track.childCount

    /** True while the remote is in this row; otherwise no tile is lifted. */
    var lit = false
        set(value) {
            field = value
            bar.fadeTo(if (value && size > 0) 1f else 0f, 200)
            paint(animate = true)
        }

    /** Replaces the tiles; [keep] holds on to the focused index (clamped) rather than starting over. */
    fun set(tiles: List<View>, width: Int, height: Int, keep: Boolean) {
        track.animate().cancel()
        track.removeAllViews()
        tiles.forEachIndexed { i, tile ->
            tile.outlineProvider = outline
            tile.clipToOutline = true
            track.addView(tile, LinearLayout.LayoutParams(width, height).apply { if (i > 0) marginStart = gap })
        }
        step = width + gap
        glow.gradientRadius = width * 0.6f
        // The bar sits on the focused tile's bottom edge, centred, once it has grown.
        val top = (track.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val barHeight = bar.layoutParams.height
        bar.translationX = Ui.px(96) + width / 2f - bar.layoutParams.width / 2f
        bar.translationY = top + height * (1 + FOCUS_SCALE) / 2f - barHeight / 2f
        focused = if (keep) focused.coerceIn(0, (tiles.size - 1).coerceAtLeast(0)) else 0
        track.translationX = -focused.toFloat() * step
        // A fade started before the tiles arrived would otherwise hide the bar when it ends.
        bar.animate().cancel()
        bar.visibility = if (lit && tiles.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        bar.alpha = if (bar.visibility == View.VISIBLE) 1f else 0f
        paint(animate = false)
    }

    /** One tile left or right; false past either end. */
    fun move(delta: Int): Boolean {
        val next = focused + delta
        if (next !in 0 until size) return false
        focused = next
        track.animate().translationX(-next.toFloat() * step).setDuration(280).setStartDelay(0).setInterpolator(Motion.SETTLE)
        paint(animate = true)
        return true
    }

    fun tile(index: Int = focused): View? = track.getChildAt(index)

    private fun paint(animate: Boolean) {
        for (i in 0 until size) {
            val tile = track.getChildAt(i)
            val on = lit && i == focused
            val alpha = when {
                i < focused -> 0f
                i == focused -> 1f
                else -> 0.5f
            }
            val scale = if (on) FOCUS_SCALE else 1f
            if (on) tile.foreground = light else if (tile.foreground === light) tile.foreground = null
            if (animate) {
                tile.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(240).setStartDelay(0).setInterpolator(Motion.LIFT)
            } else {
                tile.alpha = alpha
                tile.scaleX = scale
                tile.scaleY = scale
            }
        }
    }

    private companion object {
        const val FOCUS_SCALE = 1.1f
    }
}
