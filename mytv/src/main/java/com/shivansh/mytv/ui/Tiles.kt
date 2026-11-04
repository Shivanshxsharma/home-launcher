package com.shivansh.mytv.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One tile: a rounded card holding a picture or words, a small mark, and a name under it. */
class Cell(context: Context, width: Int, height: Int) : LinearLayout(context) {
    val card = FrameLayout(context)
    val image = ImageView(context)
    val words = TextView(context)
    val label = TextView(context)
    val mark = View(context)

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        card.setBackgroundColor(Palette.CARD)
        card.outlineProvider = RoundOutline(Ui.px(20f))
        card.clipToOutline = true
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        words.gravity = Gravity.CENTER
        words.setTextColor(Palette.INK)
        words.includeFontPadding = false
        words.visibility = GONE
        card.addView(words, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val size = Ui.px(16)
        mark.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.INK)
        }
        mark.visibility = GONE
        card.addView(mark, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply { setMargins(0, size, size, 0) })
        addView(card, LayoutParams(width, height))
        label.setTextColor(Palette.INK)
        label.includeFontPadding = false
        label.isSingleLine = true
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(24f))
        label.typeface = context.resources.getFont(com.shivansh.mytv.R.font.geist_medium)
        addView(label, LayoutParams(width, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(22) })
    }
}

/**
 * Tiles at fixed places in a row ([ROW]) or a grid, moved with translations only. The focused tile
 * lifts on a soft spring and takes a white edge; focus moves at once and the row or grid catches
 * up behind it, so a held D-pad never waits for an animation. [labelRest] is how visible names are
 * on tiles without focus.
 */
class Tiles(
    private val viewport: FrameLayout,
    private val width: Int,
    private val height: Int,
    private val cellHeight: Int,
    private val cols: Int,
    private val labelRest: Float = 0f,
    private val left: Int = Ui.px(96),
    private val top: Int = Ui.px(24),
) {
    private val content = FrameLayout(viewport.context).apply { clipChildren = false }
    private val gapX = Ui.px(40)
    private val gapY = Ui.px(36)
    private val cells = ArrayList<Cell>()
    private val edge = GradientDrawable().apply {
        cornerRadius = Ui.px(20f)
        setStroke(Ui.px(5), Palette.INK)
    }
    private var scroll = 0f

    var focused = 0
        private set

    val size: Int get() = cells.size

    var lit = false
        set(value) {
            field = value
            paint(animate = true)
        }

    init {
        viewport.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** Replaces the tiles; [keep] holds on to the focused index (clamped) rather than starting over. */
    fun set(list: List<Cell>, keep: Boolean) {
        content.removeAllViews()
        cells.clear()
        cells.addAll(list)
        for (cell in cells) {
            content.addView(cell, FrameLayout.LayoutParams(width, cellHeight))
            cell.pivotX = width / 2f
            cell.pivotY = height / 2f
        }
        cells.forEachIndexed { i, cell -> place(cell, i, animate = false) }
        focused = if (keep) focused.coerceIn(0, (size - 1).coerceAtLeast(0)) else 0
        scrollToFocus(animate = false)
        paint(animate = false)
    }

    fun cell(index: Int = focused): Cell? = cells.getOrNull(index)

    /** One step; false past an edge, so the screen can hand focus on. */
    fun move(dRow: Int, dCol: Int): Boolean {
        if (size == 0) return false
        val next = when {
            cols == ROW -> if (dRow != 0) -1 else focused + dCol
            dCol != 0 -> {
                val col = focused % cols + dCol
                if (col !in 0 until cols) -1 else focused + dCol
            }
            else -> {
                val target = focused + dRow * cols
                val lastRow = (size - 1) / cols
                // Down onto a short last row lands on its final tile.
                if (target >= size && focused / cols < lastRow) size - 1 else target
            }
        }
        if (next !in 0 until size) return false
        focused = next
        scrollToFocus(animate = true)
        paint(animate = true)
        return true
    }

    /** Swaps the focused tile with its neighbour, staying within the first [limit]; true if it moved. */
    fun swap(delta: Int, limit: Int): Boolean {
        val other = focused + delta
        if (other !in 0 until limit.coerceAtMost(size)) return false
        val moving = cells[focused]
        cells[focused] = cells[other]
        cells[other] = moving
        place(cells[focused], focused, animate = true)
        place(moving, other, animate = true)
        focused = other
        scrollToFocus(animate = true)
        return true
    }

    private fun x(index: Int) = left + (if (cols == ROW) index else index % cols) * (width + gapX)

    private fun y(index: Int) = top + (if (cols == ROW) 0 else index / cols) * (cellHeight + gapY)

    private fun place(cell: Cell, index: Int, animate: Boolean) {
        if (animate) {
            cell.animate().translationX(x(index).toFloat()).translationY(y(index).toFloat())
                .setDuration(260).setStartDelay(0).setInterpolator(Motion.SETTLE)
        } else {
            cell.translationX = x(index).toFloat()
            cell.translationY = y(index).toFloat()
        }
    }

    private fun scrollToFocus(animate: Boolean) {
        val horizontal = cols == ROW
        val span = if (horizontal) viewport.width else viewport.height
        if (span == 0) return
        val start = (if (horizontal) x(focused) else y(focused)).toFloat()
        val length = if (horizontal) width else cellHeight
        val margin = if (horizontal) left else top
        val target = when {
            start + scroll < margin -> margin - start
            start + scroll + length > span - margin -> span - margin - length - start
            else -> scroll
        }.coerceAtMost(0f)
        if (target == scroll) return
        scroll = target
        val move = content.animate().setDuration(if (animate) 240 else 0).setStartDelay(0).setInterpolator(Motion.SETTLE)
        if (horizontal) move.translationX(target) else move.translationY(target)
    }

    private fun paint(animate: Boolean) {
        cells.forEachIndexed { i, cell ->
            val on = lit && i == focused
            val scale = if (on) LIFT else 1f
            if (on) cell.card.foreground = edge else if (cell.card.foreground === edge) cell.card.foreground = null
            val label = if (on) 1f else labelRest
            if (animate) {
                // Scale only: place() may be running a translation on this same view's animator.
                if (cell.scaleX != scale) {
                    cell.animate().scaleX(scale).scaleY(scale).setDuration(LIFT_MS).setStartDelay(0).setInterpolator(Motion.LIFT)
                }
                cell.label.alpha = label
            } else {
                cell.scaleX = scale
                cell.scaleY = scale
                cell.label.alpha = label
            }
        }
    }

    companion object {
        const val ROW = Int.MAX_VALUE
        private const val LIFT = 1.1f
        private const val LIFT_MS = 420L
    }
}

/** A plain vertical list of rows used by the options card. */
class Menu(private val card: LinearLayout) {
    class Item(val label: String, val run: () -> Unit)

    private var items: List<Item> = emptyList()
    private var focused = 0
    private val height = Ui.px(84)

    var isOpen = false
        private set

    /** Called after the card closes, whether by choice or Back. */
    var onClose: (() -> Unit)? = null

    init {
        card.background = GradientDrawable().apply {
            cornerRadius = Ui.px(32f)
            setColor(Palette.CARD)
        }
    }

    fun open(list: List<Item>) {
        items = list
        focused = 0
        card.removeAllViews()
        for (item in list) {
            card.addView(
                TextView(card.context).apply {
                    text = item.label
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    setPadding(Ui.px(30), 0, Ui.px(30), 0)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(28f))
                    typeface = context.resources.getFont(com.shivansh.mytv.R.font.geist_medium)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height),
            )
        }
        paint()
        isOpen = true
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.fadeTo(1f, 160)
        card.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.LIFT)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        card.fadeTo(0f, 140)
        onClose?.invoke()
    }

    fun key(event: android.view.KeyEvent) {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> if (focused > 0) focused--
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (focused < items.size - 1) focused++
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) {
                val item = items[focused]
                close()
                item.run()
                return
            }
            android.view.KeyEvent.KEYCODE_BACK -> {
                if (event.repeatCount == 0) close()
                return
            }
            else -> return
        }
        paint()
    }

    private fun paint() {
        for (i in 0 until card.childCount) {
            val row = card.getChildAt(i) as TextView
            val on = i == focused
            row.setTextColor(if (on) Palette.DARK else Palette.INK)
            row.background = if (on) {
                GradientDrawable().apply {
                    cornerRadius = Ui.px(22f)
                    setColor(Palette.INK)
                }
            } else {
                null
            }
        }
    }
}
