package com.shivansh.mibox.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Process
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.TextView
import com.shivansh.mibox.R
import com.shivansh.mibox.data.TitleStyle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The mockup is drawn at 1920x1080 px. XML uses dp = mockup px / 2 (the box runs at 960x540dp);
 * code converts mockup px with [px].
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
    const val NIGHT = 0xFF050507.toInt()
    const val INK = 0xFFF5F3EE.toInt()
    const val CARD = 0xFF121216.toInt()

    /** [rgb] at [alpha] (0..1). */
    fun alpha(rgb: Int, alpha: Float): Int = ((alpha * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
}

/** The mockup's CSS curves. */
object Motion {
    /** cubic-bezier(.2,.8,.2,1): row moves, scrolling, entrances. */
    val SETTLE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    /** cubic-bezier(.2,1.3,.4,1): the focus lift, with a little overshoot. */
    val LIFT = PathInterpolator(0.2f, 1.3f, 0.4f, 1f)

    /** cubic-bezier(.1,.6,.3,1): the slow backdrop zoom. */
    val DRIFT = PathInterpolator(0.1f, 0.6f, 0.3f, 1f)

    /** CSS `ease`. */
    val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
}

object Fonts {
    lateinit var serif: Typeface
    lateinit var condensed: Typeface
    lateinit var regular: Typeface
    lateinit var semibold: Typeface

    fun init(context: Context) {
        if (::serif.isInitialized) return
        val res = context.resources
        serif = res.getFont(R.font.instrument_serif)
        condensed = res.getFont(R.font.big_shoulders_extrabold)
        regular = res.getFont(R.font.geist_regular)
        semibold = res.getFont(R.font.geist_semibold)
    }
}

/** Background threads for decoding, so the UI thread only ever binds finished bitmaps. */
object Workers {
    val images: ExecutorService = Executors.newFixedThreadPool(2, named("images"))
    val backdrop: ExecutorService = Executors.newSingleThreadExecutor(named("backdrop"))
    val apps: ExecutorService = Executors.newSingleThreadExecutor(named("apps"))
    val content: ExecutorService = Executors.newSingleThreadExecutor(named("content"))
    /** The Shows and Movies tabs: genre shelves and the full records of focused titles. */
    val browse: ExecutorService = Executors.newFixedThreadPool(2, named("browse"))

    private fun named(name: String) = ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }
}

fun TextView.textPx(design: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(design))

/** Sets a show title in its type treatment at [design] px (before the style's own scale). */
fun TextView.showTitle(style: TitleStyle, design: Float) {
    typeface = when (style) {
        TitleStyle.SERIF -> Fonts.serif
        TitleStyle.CONDENSED -> Fonts.condensed
        TitleStyle.PLAIN -> Fonts.semibold
    }
    isAllCaps = style == TitleStyle.CONDENSED
    letterSpacing = when (style) {
        TitleStyle.SERIF -> -0.01f
        TitleStyle.CONDENSED -> 0.01f
        TitleStyle.PLAIN -> -0.035f
    }
    textPx(design * style.scale)
}

/**
 * CSS `line-height` for display type: each line is [multiplier] x the text size, and the font's
 * taller natural line box is trimmed off the margins, so [top] and [bottom] (mockup px) land where
 * the mockup puts them.
 */
fun TextView.lineBox(multiplier: Float, top: Float, bottom: Float) {
    val metrics = paint.fontMetrics
    val box = textSize * multiplier
    val trim = (metrics.descent - metrics.ascent - box) / 2f
    setLineHeight(max(1, box.roundToInt()))
    val lp = layoutParams as ViewGroup.MarginLayoutParams
    val t = (Ui.px(top) - trim).roundToInt()
    val b = (Ui.px(bottom) - trim).roundToInt()
    if (lp.topMargin != t || lp.bottomMargin != b) {
        lp.topMargin = t
        lp.bottomMargin = b
        layoutParams = lp
    }
}

/** Fades to [target]; a view that ends fully transparent is hidden so it costs nothing to draw. */
fun View.fadeTo(target: Float, duration: Long, delay: Long = 0) {
    if (target > 0f) visibility = View.VISIBLE
    animate().alpha(target).setDuration(duration).setStartDelay(delay).setInterpolator(Motion.EASE)
        .withEndAction { if (target == 0f) visibility = View.INVISIBLE }
}

/** Moves translationY to [target] on its own animator, so it can run longer than a fade on the same view. */
fun View.glideY(target: Float, duration: Long) {
    (getTag(R.id.glide_animator) as? ObjectAnimator)?.cancel()
    if (translationY == target) return
    val anim = ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, target).apply {
        this.duration = duration
        interpolator = Motion.SETTLE
    }
    setTag(R.id.glide_animator, anim)
    anim.start()
}

class RoundOutline(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}
