package com.shivansh.mytv.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.os.Process
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/** MY TV is laid out at 1920x1080 px. XML uses dp = px / 2; code converts layout px with [px]. */
object Ui {
    private var scale = 1f

    fun init(context: Context) {
        scale = context.resources.displayMetrics.density / 2f
    }

    fun px(design: Float): Float = design * scale

    fun px(design: Int): Int = (design * scale).roundToInt()
}

object Palette {
    const val SURFACE = 0xFF101217.toInt()
    const val CARD = 0xFF1E2128.toInt()
    const val INK = 0xFFFFFFFF.toInt()
    const val SOFT = 0xFFB7BCC6.toInt()
    const val DIM = SOFT
    const val DARK = 0xFF101217.toInt()
    const val ORANGE = 0xFFFFB27A.toInt()
    const val STAR = 0xFFFFD27A.toInt()
}

/** A soft spring: rises quickly, passes the mark by a few percent, and settles. */
class Spring : TimeInterpolator {
    override fun getInterpolation(t: Float): Float = if (t >= 1f) 1f else 1f - exp(-6f * t) * cos(7f * t)
}

object Motion {
    val LIFT = Spring()

    /** Rows sliding, pages changing. */
    val SETTLE = PathInterpolator(0.3f, 0.7f, 0.1f, 1f)
    val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    /**
     * [wanted] ms in real time. Android scales every animator by the box's "animator duration"
     * setting; at 0.5 a spring plays at double speed and looks cut off, so this undoes it.
     */
    fun ms(wanted: Long): Long {
        val scale = ValueAnimator.getDurationScale()
        return if (scale > 0f) (wanted / scale).toLong() else wanted
    }
}

/** Low-priority threads: one for decoding and app art, one for the network. */
object Worker {
    val pool: ExecutorService = single("work")
    val net: ExecutorService = single("net")

    private fun single(name: String) = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }
}

/** Rounds a view's corners by clipping to its outline. */
class RoundOutline(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
}

/** Fades to [target]; a view that ends fully transparent is hidden so it costs nothing to draw. */
fun View.fadeTo(target: Float, duration: Long) {
    if (target > 0f) visibility = View.VISIBLE
    animate().alpha(target).setDuration(Motion.ms(duration)).setStartDelay(0).setInterpolator(Motion.EASE)
        .withEndAction { if (target == 0f) visibility = View.INVISIBLE }
}
