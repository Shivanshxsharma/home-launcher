package com.shivansh.mytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.shivansh.mytv.R

/**
 * Search's keyboard: letters and digits in a 6 x 6 grid with Space, Delete and Clear underneath.
 * One view draws every key, and the white tile glides from key to key. The activity routes the
 * remote here; the view never takes key events itself.
 */
class Keys(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    enum class Action { CHAR, SPACE, DELETE, CLEAR }

    class Key(val label: String, val action: Action, val row: Int, val col: Int, val span: Int)

    var onKey: ((Key) -> Unit)? = null

    private val size = Ui.px(64f)
    private val gap = Ui.px(8f)
    private val radius = Ui.px(16f)
    private val font = context.resources.getFont(R.font.geist_medium)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CARD }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        textAlign = Paint.Align.CENTER
        textSize = Ui.px(26f)
    }
    private val wordPaint = Paint(textPaint).apply { textSize = Ui.px(21f) }
    private val keys: List<Key> = "abcdefghijklmnopqrstuvwxyz1234567890".mapIndexed { i, c ->
        Key(c.toString(), Action.CHAR, i / COLS, i % COLS, 1)
    } + listOf(
        Key(context.getString(R.string.key_space), Action.SPACE, ROWS - 1, 0, 2),
        Key(context.getString(R.string.key_delete), Action.DELETE, ROWS - 1, 2, 2),
        Key(context.getString(R.string.key_clear), Action.CLEAR, ROWS - 1, 4, 2),
    )
    private var row = 0
    private var col = 0
    private var lit = false
    private val highlight = RectF()
    private val from = RectF()
    private val to = RectF()
    private val rect = RectF()
    private var glide: ValueAnimator? = null

    private val current: Key get() = keys.first { it.row == row && col >= it.col && col < it.col + it.span }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((COLS * size + (COLS - 1) * gap).toInt(), (ROWS * size + (ROWS - 1) * gap).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds(current, highlight)
    }

    /** One key; false at an edge, so the page can hand focus on. */
    fun move(dRow: Int, dCol: Int): Boolean {
        if (dCol != 0) {
            val key = current
            val next = if (dCol > 0) key.col + key.span else key.col - 1
            if (next !in 0 until COLS) return false
            col = next
        } else {
            val next = row + dRow
            if (next !in 0 until ROWS) return false
            row = next
        }
        slide()
        return true
    }

    fun press() = onKey?.invoke(current)

    fun setLit(on: Boolean) {
        lit = on
        invalidate()
    }

    private fun bounds(key: Key, out: RectF) {
        val left = key.col * (size + gap)
        val top = key.row * (size + gap)
        out.set(left, top, left + key.span * size + (key.span - 1) * gap, top + size)
    }

    private fun slide() {
        glide?.cancel()
        from.set(highlight)
        bounds(current, to)
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ms(170)
            interpolator = Motion.SETTLE
            addUpdateListener {
                val t = it.animatedValue as Float
                highlight.set(
                    from.left + (to.left - from.left) * t,
                    from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t,
                    from.bottom + (to.bottom - from.bottom) * t,
                )
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (key in keys) {
            bounds(key, rect)
            canvas.drawRoundRect(rect, radius, radius, keyPaint)
        }
        litPaint.color = if (lit) Palette.INK else 0xFF3A3F49.toInt()
        canvas.drawRoundRect(highlight, radius, radius, litPaint)
        val focused = current
        for (key in keys) {
            bounds(key, rect)
            val paint = if (key.label.length > 1) wordPaint else textPaint
            paint.color = if (lit && key === focused) Palette.DARK else Palette.INK
            canvas.drawText(key.label, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2f, paint)
        }
    }

    private companion object {
        const val COLS = 6
        const val ROWS = 7
    }
}
