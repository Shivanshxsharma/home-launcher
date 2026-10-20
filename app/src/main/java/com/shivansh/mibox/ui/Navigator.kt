package com.shivansh.mibox.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/** A horizontal row that never scrolls itself; [Navigator] decides when it slides. */
class RowLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ) = false

    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean) = false

    override fun onInterceptFocusSearch(focused: View, direction: Int): View = focused

    /** Keep a screen of cards laid out on each side, so the next card is always there to focus. */
    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        extraLayoutSpace[0] = width
        extraLayoutSpace[1] = width
    }
}

class Gap(private val px: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.set(0, 0, px, 0)
    }
}

/**
 * D-pad focus over the home screen, as the mockup does it: row 0 is the hero, then the apps row and
 * the show rows. Each row remembers its column. Focus walks right across the first [Row.keep] cards,
 * then the row slides one card per press, so the focused card never sits at the far edge.
 */
class Navigator(
    private val rows: Array<Row?>,
    private val focusHero: () -> Unit,
    private val onMove: (row: Int, col: Int, rowChanged: Boolean) -> Unit,
) {
    /** [step] is card width plus gap, in mockup px. */
    class Row(val list: RecyclerView, val keep: Int, step: Int) {
        val stepPx = Ui.px(step)
        val count get() = list.adapter?.itemCount ?: 0
    }

    var row = 0
        private set
    var col = 0
        private set
    private val memory = IntArray(rows.size)

    private fun count(r: Int) = rows[r]?.count ?: 1

    /** Returns false at an edge. */
    fun move(dRow: Int, dCol: Int): Boolean {
        if (dRow != 0) {
            var r = row + dRow
            while (r in rows.indices && count(r) == 0) r += dRow
            if (r !in rows.indices) return false
            go(r, memory[r].coerceAtMost(count(r) - 1), direction = if (dRow > 0) View.FOCUS_DOWN else View.FOCUS_UP)
        } else {
            val c = col + dCol
            if (c !in 0 until count(row)) return false
            go(row, c, direction = if (dCol > 0) View.FOCUS_RIGHT else View.FOCUS_LEFT)
        }
        return true
    }

    /** [direction] is the way focus travelled, so the card can lean with it. */
    fun go(r: Int, c: Int, animate: Boolean = true, direction: Int = 0) {
        val rowChanged = r != row
        row = r
        col = c
        memory[r] = c
        val target = rows[r]
        if (target == null) {
            focusHero()
        } else {
            slide(target, c, animate)
            focusCard(target, c, direction)
        }
        onMove(r, c, rowChanged)
    }

    /** Puts focus back where it was, e.g. after returning from an app or a list update. */
    fun refocus() {
        val n = count(row)
        if (n == 0) go(0, 0, animate = false) else go(row, col.coerceIn(0, n - 1), animate = false)
    }

    /** Focus in from outside (the tab bar): the remembered card, else the first row that has cards. */
    fun enter(direction: Int): Boolean {
        val r = if (count(row) > 0) row else rows.indices.firstOrNull { count(it) > 0 } ?: return false
        go(r, memory[r].coerceAtMost(count(r) - 1), direction = direction)
        return true
    }

    /** Forgets every position and slides the rows home without taking focus (new lists in the rows). */
    fun reset() {
        row = 0
        col = 0
        for (r in rows.indices) {
            memory[r] = 0
            rows[r]?.list?.scrollToPosition(0)
        }
    }

    /** Back to the hero with every row slid home, like pressing Home on Google TV. */
    fun home() {
        for (r in rows.indices) {
            memory[r] = 0
            rows[r]?.let { slide(it, 0, animate = true) }
        }
        go(0, 0, direction = View.FOCUS_UP)
    }

    private fun slide(row: Row, c: Int, animate: Boolean) {
        val target = max(0, c - row.keep) * row.stepPx
        val dx = target - row.list.computeHorizontalScrollOffset()
        if (dx == 0) return
        if (animate) row.list.smoothScrollBy(dx, 0, Motion.SETTLE, 480) else row.list.scrollBy(dx, 0)
    }

    private fun focusCard(row: Row, c: Int, direction: Int) {
        val card = row.list.findViewHolderForAdapterPosition(c)?.itemView
        if (card != null) {
            if (direction != 0) card.requestFocus(direction) else card.requestFocus()
        } else {
            row.list.post { row.list.findViewHolderForAdapterPosition(c)?.itemView?.requestFocus() }
        }
    }
}
