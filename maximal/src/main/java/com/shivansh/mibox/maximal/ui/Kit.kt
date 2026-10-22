package com.shivansh.mibox.maximal.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Process
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.maximal.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.roundToInt

/**
 * Maximal is laid out at 1920x1080 px. XML uses dp = px / 2 (the box runs at 960x540dp); code
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
    const val NIGHT = 0xFF050507.toInt()
    const val INK = 0xFFF5F3EE.toInt()
    const val INK_DARK = 0xFF0A0A0C.toInt()
    const val QUIET = 0xFF9F9BA8.toInt()
    const val CARD = 0xFF141419.toInt()

    /** The star beside a TMDB score. */
    const val STAR = 0xFFF5C451.toInt()

    /** [rgb] at [alpha] (0..1). */
    fun alpha(rgb: Int, alpha: Float): Int = ((alpha * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
}

object Motion {
    /** Rail scrolls, entrances and the focus pill. */
    val SETTLE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    /** The focus lift, with a little overshoot. */
    val LIFT = PathInterpolator(0.2f, 1.3f, 0.4f, 1f)

    val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
}

/** Archivo, cut into static instances: wide and heavy for titles, normal width for everything else. */
object Fonts {
    lateinit var title: Typeface
    lateinit var wide: Typeface
    lateinit var regular: Typeface
    lateinit var medium: Typeface

    fun init(context: Context) {
        if (::title.isInitialized) return
        val res = context.resources
        title = res.getFont(R.font.archivo_expanded_extrabold)
        wide = res.getFont(R.font.archivo_expanded_semibold)
        regular = res.getFont(R.font.archivo_regular)
        medium = res.getFont(R.font.archivo_medium)
    }
}

/** Background threads for decoding and loading, so the UI thread only binds finished results. */
object Workers {
    val images: ExecutorService = Executors.newFixedThreadPool(2, named("images"))
    val backdrop: ExecutorService = Executors.newSingleThreadExecutor(named("backdrop"))
    val content: ExecutorService = Executors.newSingleThreadExecutor(named("content"))
    val misc: ExecutorService = Executors.newSingleThreadExecutor(named("misc"))

    private fun named(name: String) = ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }
}

/** TMDB's score as "★ 8.4" with a gold star, or "" when the title has none. */
fun scoreText(show: Show): CharSequence {
    val label = show.scoreLabel
    if (label.isEmpty()) return ""
    return SpannableString("★ $label").apply { setSpan(ForegroundColorSpan(Palette.STAR), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
}

fun TextView.textPx(design: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(design))

/** Fades to [target]; a view that ends fully transparent is hidden so it costs nothing to draw. */
fun View.fadeTo(target: Float, duration: Long, delay: Long = 0) {
    if (target > 0f) visibility = View.VISIBLE
    animate().alpha(target).setDuration(duration).setStartDelay(delay).setInterpolator(Motion.EASE)
        .withEndAction { if (target == 0f) visibility = View.INVISIBLE }
}
