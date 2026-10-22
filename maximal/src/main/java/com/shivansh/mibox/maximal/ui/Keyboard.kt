package com.shivansh.mibox.maximal.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.shivansh.mibox.maximal.R

/**
 * Search's keyboard: letters and digits in a 6 x 6 grid, with Space, Delete, Clear and
 * (when the box can listen) the mic underneath. One view draws every key; the focus is a tile that
 * glides from key to key, cream while the keyboard has focus and glass while the results do.
 * The activity routes the remote's keys here; the view itself never takes key events.
 */
class Keyboard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Action { CHAR, SPACE, DELETE, CLEAR, VOICE }

    class Key(val label: String, val action: Action, val row: Int, val col: Int, val span: Int, val icon: Drawable? = null)

    /** A key was pressed. */
    var onKey: ((Key) -> Unit)? = null

    /** Shows the mic key; set once the activity knows whether the box has a speech service. */
    var voice = false
        set(value) {
            field = value
            build()
            col = col.coerceAtMost(COLS - 1)
            snap()
        }

    private val size = Ui.px(64f)
    private val gap = Ui.px(8f)
    private val radius = Ui.px(14f)
    private val dark = Palette.INK_DARK
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.alpha(0xFFFFFF, 0.08f) }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium
        textAlign = Paint.Align.CENTER
        textSize = Ui.px(26f)
    }
    private val wordPaint = Paint(textPaint).apply { textSize = Ui.px(21f) }
    private val backspace = context.getDrawable(R.drawable.ic_backspace)!!.mutate()
    private val mic = context.getDrawable(R.drawable.ic_mic)!!.mutate()

    private var keys: List<Key> = emptyList()
    private var row = 0
    private var col = 0
    private var lit = false
    private val highlight = RectF()
    private val from = RectF()
    private val to = RectF()
    private val rect = RectF()
    private var glide: ValueAnimator? = null

    val current: Key get() = keyAt(row, col)

    init {
        build()
    }

    private fun build() {
        val letters = "abcdefghijklmnopqrstuvwxyz1234567890"
        val res = context.resources
        keys = letters.mapIndexed { i, c -> Key(c.toString(), Action.CHAR, i / COLS, i % COLS, 1) } + if (voice) {
            listOf(
                Key(res.getString(R.string.key_space), Action.SPACE, ROWS - 1, 0, 2),
                Key("", Action.DELETE, ROWS - 1, 2, 1, backspace),
                Key(res.getString(R.string.key_clear), Action.CLEAR, ROWS - 1, 3, 2),
                Key("", Action.VOICE, ROWS - 1, 5, 1, mic),
            )
        } else {
            listOf(
                Key(res.getString(R.string.key_space), Action.SPACE, ROWS - 1, 0, 3),
                Key("", Action.DELETE, ROWS - 1, 3, 1, backspace),
                Key(res.getString(R.string.key_clear), Action.CLEAR, ROWS - 1, 4, 2),
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((COLS * size + (COLS - 1) * gap).toInt(), (ROWS * size + (ROWS - 1) * gap).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        snap()
    }

    /** Moves the focus one key; false at an edge, so the page can hand focus on. */
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

    fun press() {
        onKey?.invoke(current)
        // A quick dip of the tile, like a key going down.
        glide?.cancel()
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 180
            addUpdateListener {
                val dip = Ui.px(4f) * (it.animatedValue as Float)
                bounds(current, highlight)
                highlight.inset(dip, dip)
                invalidate()
            }
            start()
        }
    }

    fun setLit(on: Boolean) {
        lit = on
        invalidate()
    }

    /** Back to the first key, without animating. */
    fun reset() {
        row = 0
        col = 0
        snap()
    }

    private fun keyAt(r: Int, c: Int): Key = keys.first { it.row == r && c >= it.col && c < it.col + it.span }

    private fun bounds(key: Key, out: RectF) {
        val left = key.col * (size + gap)
        val top = key.row * (size + gap)
        out.set(left, top, left + key.span * size + (key.span - 1) * gap, top + size)
    }

    private fun snap() {
        glide?.cancel()
        bounds(current, highlight)
        invalidate()
    }

    private fun slide() {
        glide?.cancel()
        from.set(highlight)
        bounds(current, to)
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170
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
        litPaint.color = if (lit) Palette.INK else Palette.alpha(0xFFFFFF, 0.24f)
        canvas.drawRoundRect(highlight, radius, radius, litPaint)
        val focused = current
        for (key in keys) {
            bounds(key, rect)
            val color = if (lit && key === focused) dark else Palette.INK
            val icon = key.icon
            if (icon != null) {
                val half = Ui.px(15f).toInt()
                val cx = rect.centerX().toInt()
                val cy = rect.centerY().toInt()
                icon.setTint(color)
                icon.setBounds(cx - half, cy - half, cx + half, cy + half)
                icon.draw(canvas)
            } else {
                val paint = if (key.label.length > 1) wordPaint else textPaint
                paint.color = color
                canvas.drawText(key.label, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2f, paint)
            }
        }
    }

    private companion object {
        const val COLS = 6
        const val ROWS = 7
    }
}
