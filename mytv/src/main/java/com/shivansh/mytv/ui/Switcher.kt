package com.shivansh.mytv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.shivansh.mytv.App
import com.shivansh.mytv.R
import kotlin.math.abs

/** Cards drawn in a chosen order, so the focused one always sits on top of the stack. */
class CardStack(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private var order = IntArray(0)

    init {
        isChildrenDrawingOrderEnabled = true
        clipChildren = false
    }

    override fun hasOverlappingRendering() = false

    /** Draws [views] in this order, back to front. */
    fun arrange(views: List<View>) {
        order = views.map { indexOfChild(it) }.filter { it >= 0 }.toIntArray()
        invalidate()
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int) =
        if (order.size == childCount) order[drawingPosition] else drawingPosition
}

/**
 * The app switcher, as on Apple TV: recent apps as one stack of large cards. Left and right walk
 * through them, OK opens one, and up flicks the focused card off the top, closing that app. Down
 * reaches Clear all. Only the remote's Apps button opens it. Cards show each app's banner, because
 * Android doesn't let a launcher capture another app's screen.
 */
class Switcher(
    private val root: View,
    private val listener: Listener,
    private val art: (App, (Bitmap) -> Unit) -> Unit,
) {
    interface Listener {
        fun onOpenApp(app: App)

        /** The card went off the top: close this app. */
        fun onCloseApp(app: App)

        fun onClearAll()

        /** Back, or nothing left to show. */
        fun onLeave()
    }

    private val context: Context = root.context
    private val stack: CardStack = root.findViewById(R.id.switcher_stack)
    private val title: TextView = root.findViewById(R.id.switcher_title)
    private val hint: View = root.findViewById(R.id.switcher_hint)
    private val clear: TextView = root.findViewById(R.id.switcher_clear)
    private val empty: View = root.findViewById(R.id.switcher_empty)
    private val width = Ui.px(CARD_W)
    private val height = Ui.px(CARD_H)
    private val pad = Ui.px(90)
    private val shadow = ImageView(context)
    private val apps = ArrayList<App>()
    private val cards = ArrayList<View>()
    private val leaving = ArrayList<View>()
    private val main = Handler(Looper.getMainLooper())
    private val pillOff = pill(0x29FFFFFF)
    private val pillOn = pill(Palette.INK)
    private val emptyAll = Runnable {
        stack.removeAllViews()
        cards.clear()
        leaving.clear()
    }
    private var focused = 0
    private var onClear = false
    private var lastStep = 0L

    var isOpen = false
        private set

    fun open(list: List<App>) {
        main.removeCallbacks(emptyAll)
        emptyAll.run()
        apps.clear()
        apps.addAll(list)
        focused = 0
        onClear = false
        if (shadow.drawable == null) shadow.setImageBitmap(softShadow())
        stack.addView(shadow, FrameLayout.LayoutParams(width + pad * 2, height + pad * 2, Gravity.CENTER))
        for (app in apps) {
            val card = card(app)
            cards += card
            stack.addView(card, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        }
        place(animate = false)
        isOpen = true
        root.animate().cancel()
        root.alpha = 0f
        root.visibility = View.VISIBLE
        stack.scaleX = 0.94f
        stack.scaleY = 0.94f
        root.fadeTo(1f, 200)
        stack.animate().scaleX(1f).scaleY(1f).setDuration(Motion.ms(420)).setStartDelay(0).setInterpolator(Motion.LIFT)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        root.fadeTo(0f, 170)
        main.postDelayed(emptyAll, Motion.ms(240))
    }

    fun key(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val first = event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1, first)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1, first)
            KeyEvent.KEYCODE_DPAD_UP -> if (first) {
                if (onClear) focusClear(false) else dismiss()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (first && apps.isNotEmpty()) focusClear(true)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (first) {
                if (onClear) listener.onClearAll() else apps.getOrNull(focused)?.let(listener::onOpenApp)
            }
            KeyEvent.KEYCODE_BACK -> if (first) listener.onLeave()
        }
    }

    private fun step(delta: Int, first: Boolean) {
        if (onClear || apps.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (!first && now - lastStep < REPEAT_MS) return
        lastStep = now
        val next = focused + delta
        if (next !in apps.indices) {
            if (first) nudge(delta)
            return
        }
        focused = next
        place(animate = true)
    }

    private fun focusClear(on: Boolean) {
        if (onClear == on) return
        onClear = on
        place(animate = true)
    }

    /** Up: the card leaves off the top and its neighbours close the gap. */
    private fun dismiss() {
        val card = cards.getOrNull(focused) ?: return
        val app = apps.removeAt(focused)
        cards.removeAt(focused)
        leaving += card
        card.animate().translationY(-Ui.px(FLICK)).alpha(0f).setDuration(Motion.ms(FLICK_MS)).setStartDelay(0).setInterpolator(Motion.EASE)
            .withEndAction {
                leaving -= card
                stack.removeView(card)
                if (isOpen) arrange()
            }
        focused = focused.coerceAtMost((apps.size - 1).coerceAtLeast(0))
        place(animate = true)
        listener.onCloseApp(app)
        if (apps.isEmpty()) main.postDelayed({ if (isOpen && apps.isEmpty()) listener.onLeave() }, Motion.ms(LEAVE_MS))
    }

    private fun nudge(delta: Int) {
        val card = cards.getOrNull(focused) ?: return
        card.animate().translationX(delta * Ui.px(28f)).setDuration(Motion.ms(90)).setStartDelay(0).setInterpolator(Motion.EASE)
            .withEndAction { card.animate().translationX(0f).setDuration(Motion.ms(420)).setInterpolator(Motion.LIFT).withEndAction(null) }
    }

    private fun place(animate: Boolean) {
        cards.forEachIndexed { i, card ->
            val d = i - focused
            val n = abs(d)
            val x = if (n == 0) 0f else (if (d > 0) 1 else -1) * (Ui.px(SIDE) + (n - 1) * Ui.px(STEP))
            val scale = SCALES[n.coerceAtMost(SCALES.size - 1)]
            val alpha = ALPHAS[n.coerceAtMost(ALPHAS.size - 1)] * (if (onClear) 0.6f else 1f)
            if (animate) {
                card.animate().translationX(x).translationY(0f).scaleX(scale).scaleY(scale).alpha(alpha)
                    .setDuration(Motion.ms(MOVE_MS)).setStartDelay(0).setInterpolator(Motion.SETTLE).withEndAction(null)
            } else {
                card.animate().cancel()
                card.translationX = x
                card.translationY = 0f
                card.scaleX = scale
                card.scaleY = scale
                card.alpha = alpha
            }
        }
        shadow.alpha = if (cards.isEmpty()) 0f else 1f
        arrange()
        title.text = apps.getOrNull(focused)?.label.orEmpty()
        val any = apps.isNotEmpty()
        empty.visibility = if (any) View.GONE else View.VISIBLE
        hint.visibility = if (any) View.VISIBLE else View.INVISIBLE
        clear.visibility = if (any) View.VISIBLE else View.INVISIBLE
        clear.background = if (onClear) pillOn else pillOff
        clear.setTextColor(if (onClear) Palette.DARK else Palette.INK)
    }

    /** Far cards at the back, the shadow, then the focused card, with any card on its way out on top. */
    private fun arrange() {
        val front = cards.getOrNull(focused)
        val back = cards.withIndex().filter { it.value !== front }.sortedByDescending { abs(it.index - focused) }.map { it.value }
        stack.arrange(back + shadow + listOfNotNull(front) + leaving)
    }

    private fun card(app: App) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Palette.CARD)
        outlineProvider = RoundOutline(Ui.px(RADIUS))
        clipToOutline = true
        art(app) { setImageBitmap(it) }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = Ui.px(60f)
        setColor(color)
    }

    /** One soft shadow under the front card, drawn once: it stays put while the cards move. */
    private fun softShadow(): Bitmap {
        val bitmap = Bitmap.createBitmap(width + pad * 2, height + pad * 2, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt()
            maskFilter = BlurMaskFilter(pad * 0.7f, BlurMaskFilter.Blur.NORMAL)
        }
        val rect = RectF(pad.toFloat(), pad.toFloat() + Ui.px(24f), (width + pad).toFloat(), (height + pad).toFloat() + Ui.px(24f))
        Canvas(bitmap).drawRoundRect(rect, Ui.px(RADIUS), Ui.px(RADIUS), paint)
        return bitmap
    }

    companion object {
        const val CARD_W = 760
        const val CARD_H = 428
        private const val RADIUS = 34f
        private const val SIDE = 500f
        private const val STEP = 190f
        private const val FLICK = 900f
        private const val FLICK_MS = 300L
        private const val MOVE_MS = 380L
        private const val LEAVE_MS = 460L
        private const val REPEAT_MS = 170L
        private val SCALES = floatArrayOf(1f, 0.74f, 0.6f, 0.5f)
        private val ALPHAS = floatArrayOf(1f, 0.6f, 0.28f, 0f)
    }
}
