package com.shivansh.mytv.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shivansh.mytv.R

/** One tile: a rounded card holding a picture or words, a white edge for focus, and a name under it. */
class Cell(context: Context, width: Int, height: Int) : LinearLayout(context) {
    val card = FrameLayout(context)
    val image = ImageView(context)
    val words = TextView(context)
    val label = TextView(context)
    val mark = View(context)
    val edge = View(context)

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val radius = Ui.px(22f)
        card.setBackgroundColor(Palette.CARD)
        card.outlineProvider = RoundOutline(radius)
        card.clipToOutline = true
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        val full = FrameLayout.LayoutParams.MATCH_PARENT
        card.addView(image, FrameLayout.LayoutParams(full, full))
        words.gravity = Gravity.CENTER
        words.setTextColor(Palette.INK)
        words.includeFontPadding = false
        words.visibility = GONE
        card.addView(words, FrameLayout.LayoutParams(full, full))
        val size = Ui.px(16)
        mark.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.INK)
        }
        mark.visibility = GONE
        card.addView(mark, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply { setMargins(0, size, size, 0) })
        // The edge fades in over the picture, with a little light from the top: a lit, raised card.
        edge.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius
            setStroke(Ui.px(5), Palette.INK)
        }
        edge.alpha = 0f
        edge.visibility = INVISIBLE
        card.addView(edge, FrameLayout.LayoutParams(full, full))
        addView(card, LayoutParams(width, height))
        label.setTextColor(Palette.INK)
        label.includeFontPadding = false
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(24f))
        label.typeface = context.resources.getFont(R.font.geist_medium)
        addView(label, LayoutParams(width, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(22) })
    }
}

/**
 * Tiles at fixed places in a row ([ROW]) or a grid, moved with translations only. The focused tile
 * lifts on a soft spring and lights up; focus moves at once and the row or grid catches up behind
 * it, so a held D-pad never waits for an animation. [labelRest] is how visible names are on tiles
 * without focus.
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
    private val gapX = Ui.px(36)
    private val gapY = Ui.px(36)
    private val cells = ArrayList<Cell>()
    private var scroll = 0f
    private var liftedIndex = -1

    var focused = 0
        private set

    val size: Int get() = cells.size

    var lit = false
        set(value) {
            field = value
            paint(animate = true)
        }

    init {
        val full = FrameLayout.LayoutParams.MATCH_PARENT
        viewport.addView(content, FrameLayout.LayoutParams(full, full))
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
        cells.forEachIndexed { i, cell ->
            cell.translationX = x(i).toFloat()
            cell.translationY = y(i).toFloat()
            cell.label.alpha = labelRest
        }
        focused = if (keep) focused.coerceIn(0, (size - 1).coerceAtLeast(0)) else 0
        liftedIndex = -1
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

    /** Swaps the focused tile with its neighbour; true if it moved. */
    fun swap(delta: Int): Boolean {
        val other = focused + delta
        if (other !in 0 until size) return false
        val moving = cells[focused]
        cells[focused] = cells[other]
        cells[other] = moving
        glide(cells[focused], focused)
        glide(moving, other)
        liftedIndex = other
        focused = other
        scrollToFocus(animate = true)
        return true
    }

    /** OK pressed: the tile squishes and springs back. */
    fun squish() {
        val cell = cell() ?: return
        cell.animate().scaleX(SQUISH).scaleY(SQUISH).setDuration(Motion.ms(90)).setStartDelay(0).setInterpolator(Motion.EASE)
            .withEndAction {
                val scale = if (lit && cell === cell()) LIFT else 1f
                cell.animate().scaleX(scale).scaleY(scale).setDuration(Motion.ms(LIFT_MS)).setInterpolator(Motion.LIFT).withEndAction(null)
            }
    }

    private fun x(index: Int) = left + (if (cols == ROW) index else index % cols) * (width + gapX)

    private fun y(index: Int) = top + (if (cols == ROW) 0 else index / cols) * (cellHeight + gapY)

    private fun glide(cell: Cell, index: Int) {
        cell.animate().translationX(x(index).toFloat()).translationY(y(index).toFloat())
            .setDuration(Motion.ms(300)).setStartDelay(0).setInterpolator(Motion.SETTLE)
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
        content.animate().cancel()
        if (!animate) {
            if (horizontal) content.translationX = target else content.translationY = target
            return
        }
        val slide = content.animate().setDuration(Motion.ms(340)).setStartDelay(0).setInterpolator(Motion.SETTLE)
        if (horizontal) slide.translationX(target) else slide.translationY(target)
    }

    /** Only the tile losing focus and the one gaining it are touched. */
    private fun paint(animate: Boolean) {
        val next = if (lit) focused else -1
        if (!animate) {
            cells.forEachIndexed { i, cell -> show(cell, i == next, animate = false) }
        } else if (next != liftedIndex) {
            cells.getOrNull(liftedIndex)?.let { show(it, on = false, animate = true) }
            cells.getOrNull(next)?.let { show(it, on = true, animate = true) }
        }
        liftedIndex = next
    }

    private fun show(cell: Cell, on: Boolean, animate: Boolean) {
        val scale = if (on) LIFT else 1f
        val label = if (on) 1f else labelRest
        if (animate) {
            cell.animate().scaleX(scale).scaleY(scale).setDuration(Motion.ms(LIFT_MS)).setStartDelay(0).setInterpolator(Motion.LIFT).withEndAction(null)
            cell.edge.fadeTo(if (on) 1f else 0f, if (on) 160 else 220)
            cell.label.animate().alpha(label).setDuration(Motion.ms(200)).setStartDelay(0).setInterpolator(Motion.EASE)
        } else {
            cell.scaleX = scale
            cell.scaleY = scale
            cell.label.alpha = label
            cell.edge.alpha = if (on) 1f else 0f
            cell.edge.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
    }

    companion object {
        const val ROW = Int.MAX_VALUE
        private const val LIFT = 1.1f
        private const val SQUISH = 1.02f
        private const val LIFT_MS = 520L
    }
}

/** The options card: a short list; the white row glides with the remote. */
class Menu(private val card: LinearLayout) {
    class Item(val label: String, val run: () -> Unit)

    private var items: List<Item> = emptyList()
    private var focused = 0
    private val height = Ui.px(84)

    var isOpen = false
        private set

    init {
        card.background = GradientDrawable().apply {
            cornerRadius = Ui.px(36f)
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
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(28f))
                    typeface = context.resources.getFont(R.font.geist_medium)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height),
            )
        }
        paint()
        isOpen = true
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.visibility = View.VISIBLE
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ms(420)).setStartDelay(0).setInterpolator(Motion.LIFT).withEndAction(null)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        card.fadeTo(0f, 140)
    }

    fun key(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (focused > 0) focused--
            KeyEvent.KEYCODE_DPAD_DOWN -> if (focused < items.size - 1) focused++
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) {
                val item = items[focused]
                close()
                item.run()
                return
            }
            KeyEvent.KEYCODE_BACK -> {
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
                    cornerRadius = Ui.px(26f)
                    setColor(Palette.INK)
                }
            } else {
                null
            }
        }
    }
}
