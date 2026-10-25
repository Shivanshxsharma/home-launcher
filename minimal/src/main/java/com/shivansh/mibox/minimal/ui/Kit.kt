package com.shivansh.mibox.minimal.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Process
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.TextView
import com.shivansh.mibox.minimal.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.roundToInt

/**
 * Minimal is laid out at 1920x1080 px. XML uses dp = px / 2 (the box runs at 960x540dp); code
 * converts layout px with [px].
 */
object Ui {
    private var scale = 1f

    fun init(context: Context) {
        scale = context.resources.displayMetrics.density / 2f
    }

    fun px(design: Float): Float = design * scale

    fun px(design: Int): Int = (design * scale).roundToInt()
}

object Palette {
    const val BLACK = 0xFF000000.toInt()
    const val INK = 0xFFF2F0EA.toInt()
    const val DIM = 0xFF7D7B76.toInt()
    const val LINE = 0xFF2A2A2A.toInt()
    const val PANEL = 0xFF0D0D0D.toInt()
    const val ORANGE = 0xFFFF6900.toInt()

    /** [rgb] at [alpha] (0..1). */
    fun alpha(rgb: Int, alpha: Float): Int = ((alpha * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
}

object Motion {
    /** The row sliding under the focused tile, the floors moving. */
    val SETTLE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    /** The focused tile growing, with a little overshoot. */
    val LIFT = PathInterpolator(0.2f, 1.25f, 0.4f, 1f)
}

/** Archivo: expanded and heavy for the clock and titles, expanded semibold for labels, normal width to read. */
object Fonts {
    lateinit var display: Typeface
    lateinit var label: Typeface
    lateinit var body: Typeface

    fun init(context: Context) {
        if (::display.isInitialized) return
        val res = context.resources
        display = res.getFont(R.font.archivo_expanded_extrabold)
        label = res.getFont(R.font.archivo_expanded_semibold)
        body = res.getFont(R.font.archivo_regular)
    }
}

/** Background threads for decoding and loading, so the UI thread only binds finished results. */
object Workers {
    val images: ExecutorService = Executors.newFixedThreadPool(2, named("images"))
    val art: ExecutorService = Executors.newSingleThreadExecutor(named("art"))
    val content: ExecutorService = Executors.newSingleThreadExecutor(named("content"))
    val misc: ExecutorService = Executors.newSingleThreadExecutor(named("misc"))

    private fun named(name: String) = ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }
}

fun TextView.textPx(design: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(design))

/**
 * Sets [text] at the largest size from [max] down to [min] (layout px) that fits in [lines] lines
 * without breaking a word; at [min] it may still need more, and ends with an ellipsis.
 */
fun TextView.fit(text: String, max: Float, min: Float, lines: Int) {
    val shown = if (isAllCaps) text.uppercase() else text
    val width = (layoutParams.width.takeIf { it > 0 } ?: width) - paddingLeft - paddingRight
    val paint = TextPaint(paint)
    var size = max
    while (size > min) {
        paint.textSize = Ui.px(size)
        val longest = shown.split(' ').maxOfOrNull { paint.measureText(it) } ?: 0f
        if (longest <= width) {
            val layout = StaticLayout.Builder.obtain(shown, 0, shown.length, paint, width.coerceAtLeast(1))
                .setIncludePad(false).setLineSpacing(lineSpacingExtra, lineSpacingMultiplier).build()
            if (layout.lineCount <= lines) break
        }
        size -= 4f
    }
    textPx(size.coerceAtLeast(min))
    maxLines = lines
    ellipsize = android.text.TextUtils.TruncateAt.END
    this.text = text
}

/** Fades to [target]; a view that ends fully transparent is hidden so it costs nothing to draw. */
fun View.fadeTo(target: Float, duration: Long, delay: Long = 0) {
    if (target > 0f) visibility = View.VISIBLE
    animate().alpha(target).setDuration(duration).setStartDelay(delay).setInterpolator(Motion.EASE)
        .withEndAction { if (target == 0f) visibility = View.INVISIBLE }
}

/** Rounds a view's corners by clipping to its outline (cheap on the GPU for a rounded rectangle). */
class RoundOutline(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
}
