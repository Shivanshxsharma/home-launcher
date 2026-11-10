package com.shivansh.mytv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
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

/** One tile: a rounded card holding a picture or words, and a name under it. */
class Cell(context: Context, width: Int, height: Int) : LinearLayout(context) {
    val card = FrameLayout(context)
    val image = ImageView(context)
    val words = TextView(context)
    val label = TextView(context)
    val mark = View(context)

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        forceHasOverlappingRendering(false)
        card.setBackgroundColor(0x24FFFFFF)
        card.outlineProvider = RoundOutline(Ui.px(Tiles.RADIUS))
        card.clipToOutline = true
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        val full = FrameLayout.LayoutParams.MATCH_PARENT
        card.addView(image, FrameLayout.LayoutParams(full, full))
        words.gravity = Gravity.CENTER
        words.setTextColor(Palette.INK)
        words.includeFontPadding = false
        words.visibility = GONE
        card.addView(words, FrameLayout.LayoutParams(full, full))
        val size = Ui.px(14)
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
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(23f))
        label.typeface = context.resources.getFont(R.font.geist_medium)
        addView(label, LayoutParams(width, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(24) })
    }
}

/** Light on a lifted tile, as on Apple TV: a soft sheen from the top corner and a rim along its upper edge. */
private class Sheen(private val radius: Float) : Drawable() {
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.px(2.5f)
    }
    private val rect = RectF()
    private val edge = RectF()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        edge.set(bounds)
        val half = rim.strokeWidth / 2f
        edge.inset(half, half)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        gloss.shader = LinearGradient(
            0f, 0f, w * 0.7f, h,
            intArrayOf(0x3DFFFFFF, 0x12FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.34f, 0.62f), Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x99FFFFFF.toInt(), 0x1FFFFFFF, 0x0DFFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, gloss)
        canvas.drawRoundRect(edge, radius, radius, rim)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Tiles at fixed places in a row ([ROW]) or a grid, moved with translations only. Focus works as on
 * Apple TV: no outline. The tile lifts towards you on a spring, a soft shadow and a sheen of light
 * glide over from the last tile, and the picture inside zooms a touch further than its frame, which
 * reads as depth. Pushing past the end gives a small rubber-band nudge. Focus changes at once and
 * the picture catches up, so a held D-pad never waits.
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
    private val lift: Float = 1.12f,
) {
    private val context: Context = viewport.context
    private val content = FrameLayout(context).apply { clipChildren = false }
    private val gapX = Ui.px(32)
    private val gapY = Ui.px(36)
    private val cells = ArrayList<Cell>()
    private var scroll = 0f
    private var lifted = -1
    private var lean = 0f
    private val pad = Ui.px(64)
    private val shadow = ImageView(context).apply {
        setImageBitmap(softShadow((width * lift).toInt(), (height * lift).toInt(), pad))
        alpha = 0f
        visibility = View.INVISIBLE
    }
    private val lens = View(context).apply {
        background = Sheen(Ui.px(RADIUS) * lift)
        alpha = 0f
        visibility = View.INVISIBLE
    }

    var focused = 0
        private set

    val size: Int get() = cells.size

    var lit = false
        set(value) {
            if (field == value) return
            field = value
            paint(animate = true)
        }

    init {
        val full = FrameLayout.LayoutParams.MATCH_PARENT
        // A clipping parent cuts a child to the child's own box, which scrolls away with the row.
        viewport.clipChildren = false
        viewport.addView(content, FrameLayout.LayoutParams(full, full))
    }

    /** Replaces the tiles; [keep] holds on to the focused index (clamped) rather than starting over. */
    fun set(list: List<Cell>, keep: Boolean) {
        content.removeAllViews()
        cells.clear()
        cells.addAll(list)
        content.addView(shadow, FrameLayout.LayoutParams((width * lift).toInt() + pad * 2, (height * lift).toInt() + pad * 2))
        for (cell in cells) {
            content.addView(cell, FrameLayout.LayoutParams(width, cellHeight))
            cell.pivotX = width / 2f
            cell.pivotY = height / 2f
        }
        content.addView(lens, FrameLayout.LayoutParams((width * lift).toInt(), (height * lift).toInt()))
        cells.forEachIndexed { i, cell ->
            cell.translationX = x(i).toFloat()
            cell.translationY = y(i).toFloat()
            cell.label.alpha = labelRest
        }
        focused = if (keep) focused.coerceIn(0, (size - 1).coerceAtLeast(0)) else 0
        lifted = -1
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
        lean = (if (dCol != 0) dCol else 0) * Ui.px(16f)
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
        lifted = other
        focused = other
        glideFocus(animate = true)
        scrollToFocus(animate = true)
        return true
    }

    /** Pushed past the end: the lifted tile gives a little and springs back. */
    fun nudge(dRow: Int, dCol: Int) {
        val cell = cell() ?: return
        val dx = dCol * Ui.px(14f)
        val dy = dRow * Ui.px(14f)
        bounce(cell, x(focused).toFloat(), y(focused).toFloat(), dx, dy)
        bounce(lens, lensX(), lensY(), dx, dy)
        bounce(shadow, shadowX(), shadowY(), dx, dy)
    }

    private fun bounce(view: View, restX: Float, restY: Float, dx: Float, dy: Float) {
        view.animate().translationX(restX + dx).translationY(restY + dy).setDuration(Motion.ms(90)).setStartDelay(0).setInterpolator(Motion.EASE)
            .withEndAction {
                view.animate().translationX(restX).translationY(restY).setDuration(Motion.ms(LIFT_MS)).setInterpolator(Motion.LIFT).withEndAction(null)
            }
    }

    /** OK pressed: the tile and its light squish and spring back. */
    fun squish() {
        val cell = cell() ?: return
        for (view in arrayOf<View>(cell, lens)) {
            val rest = if (view === cell) lift else 1f
            val down = rest * 0.94f
            view.animate().scaleX(down).scaleY(down).setDuration(Motion.ms(90)).setStartDelay(0).setInterpolator(Motion.EASE)
                .withEndAction {
                    view.animate().scaleX(rest).scaleY(rest).setDuration(Motion.ms(LIFT_MS)).setInterpolator(Motion.LIFT).withEndAction(null)
                }
        }
    }

    private fun x(index: Int) = left + (if (cols == ROW) index else index % cols) * (width + gapX)

    private fun y(index: Int) = top + (if (cols == ROW) 0 else index / cols) * (cellHeight + gapY)

    private fun glide(cell: Cell, index: Int) {
        cell.animate().translationX(x(index).toFloat()).translationY(y(index).toFloat())
            .setDuration(Motion.ms(GLIDE_MS)).setStartDelay(0).setInterpolator(Motion.SETTLE).withEndAction(null)
    }

    /**
     * Keeps the focused tile in view, with the next one showing past it. When a tile can't clear
     * both margins at once, its leading edge wins. Testing the far edge last let a row a few pixels
     * too tall flip between the two answers, so the grid hopped up and down at every step.
     */
    private fun scrollToFocus(animate: Boolean) {
        val horizontal = cols == ROW
        val span = if (horizontal) viewport.width else viewport.height
        if (span == 0) return
        val start = (if (horizontal) x(focused) else y(focused)).toFloat()
        val length = if (horizontal) width else cellHeight
        val margin = if (horizontal) left else top
        val ahead = if (horizontal && focused < size - 1) width / 2 else 0
        var target = scroll
        if (start + target + length + ahead > span - margin) target = span - margin - length - ahead - start
        if (start + target < margin) target = margin - start
        target = target.coerceAtMost(0f)
        if (target == scroll) return
        scroll = target
        if (!animate) {
            content.animate().cancel()
            if (horizontal) content.translationX = target else content.translationY = target
            return
        }
        val slide = content.animate().setDuration(Motion.ms(GLIDE_MS)).setStartDelay(0).setInterpolator(Motion.SETTLE)
        if (horizontal) slide.translationX(target) else slide.translationY(target)
    }

    private fun paint(animate: Boolean) {
        val next = if (lit && size > 0) focused else -1
        val wasLit = lifted >= 0
        if (!animate) {
            cells.forEachIndexed { i, cell -> show(cell, i == next, animate = false) }
        } else if (next != lifted) {
            cells.getOrNull(lifted)?.let { show(it, on = false, animate = true) }
            cells.getOrNull(next)?.let { show(it, on = true, animate = true) }
        }
        lifted = next
        if (next >= 0) glideFocus(animate = animate && wasLit)
        val on = next >= 0
        if (animate) {
            lens.fadeTo(if (on) 1f else 0f, 180)
            shadow.fadeTo(if (on) 1f else 0f, 240)
        } else {
            for (view in arrayOf(lens, shadow)) {
                view.animate().cancel()
                view.alpha = if (on) 1f else 0f
                view.visibility = if (on) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    private fun lensX() = x(focused) - width * (lift - 1f) / 2f

    private fun lensY() = y(focused) - height * (lift - 1f) / 2f

    private fun shadowX() = lensX() - pad

    private fun shadowY() = lensY() - pad + Ui.px(22f)

    private fun glideFocus(animate: Boolean) {
        val lensX = lensX()
        val lensY = lensY()
        val shadowX = shadowX()
        val shadowY = shadowY()
        if (!animate) {
            lens.translationX = lensX
            lens.translationY = lensY
            shadow.translationX = shadowX
            shadow.translationY = shadowY
            return
        }
        lens.animate().translationX(lensX).translationY(lensY).setDuration(Motion.ms(GLIDE_MS)).setStartDelay(0).setInterpolator(Motion.SETTLE)
        shadow.animate().translationX(shadowX).translationY(shadowY).setDuration(Motion.ms(GLIDE_MS)).setStartDelay(0).setInterpolator(Motion.SETTLE)
    }

    private fun show(cell: Cell, on: Boolean, animate: Boolean) {
        val scale = if (on) lift else 1f
        val zoom = if (on) ZOOM else 1f
        val label = if (on) 1f else labelRest
        if (animate) {
            cell.animate().scaleX(scale).scaleY(scale).setDuration(Motion.ms(LIFT_MS)).setStartDelay(0).setInterpolator(Motion.LIFT).withEndAction(null)
            if (on) cell.image.translationX = lean
            cell.image.animate().scaleX(zoom).scaleY(zoom).translationX(0f).setDuration(Motion.ms(LIFT_MS)).setStartDelay(0).setInterpolator(Motion.LIFT)
            cell.label.animate().alpha(label).setDuration(Motion.ms(220)).setStartDelay(0).setInterpolator(Motion.EASE)
        } else {
            cell.scaleX = scale
            cell.scaleY = scale
            cell.image.scaleX = zoom
            cell.image.scaleY = zoom
            cell.label.alpha = label
        }
    }

    companion object {
        const val ROW = Int.MAX_VALUE
        const val RADIUS = 24f
        private const val ZOOM = 1.05f
        private const val LIFT_MS = 460L
        private const val GLIDE_MS = 300L

        /**
         * A deep soft shadow, with a faint glow so a lifted tile still separates from a dark
         * picture. Drawn once: live shadows are beyond this GPU.
         */
        private fun softShadow(width: Int, height: Int, pad: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width + pad * 2, height + pad * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val r = Ui.px(RADIUS) * 1.12f
            val rect = RectF(pad.toFloat(), pad.toFloat(), (width + pad).toFloat(), (height + pad).toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0x26FFFFFF
            paint.maskFilter = BlurMaskFilter(pad * 0.34f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = 0xCC000000.toInt()
            paint.maskFilter = BlurMaskFilter(pad * 0.72f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(rect, r, r, paint)
            return bitmap
        }
    }
}

/** The options card: a short list on glass; the white row follows the remote. */
class Menu(private val card: LinearLayout) {
    class Item(val label: String, val run: () -> Unit)

    private var items: List<Item> = emptyList()
    private var focused = 0
    private val height = Ui.px(84)

    var isOpen = false
        private set

    init {
        card.background = GlassDrawable(card, Ui.px(40f), 0x8C0C0E14.toInt())
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
        card.animate().cancel()
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.alpha = 0f
        card.visibility = View.VISIBLE
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ms(420)).setStartDelay(0).setInterpolator(Motion.LIFT).withEndAction(null)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        card.fadeTo(0f, 160)
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
                    cornerRadius = Ui.px(28f)
                    setColor(Palette.INK)
                }
            } else {
                null
            }
        }
    }
}
