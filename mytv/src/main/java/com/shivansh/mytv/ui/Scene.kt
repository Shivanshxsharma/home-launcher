package com.shivansh.mytv.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.shivansh.mytv.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The wallpaper: one picture at 1280x720 RGB_565, decoded off the main thread. A change fades the
 * new one over the old, so never more than two are in memory, and the screen is still otherwise:
 * nothing is redrawn between changes.
 */
class Wallpaper(private val lower: ImageView, private val upper: ImageView, private val assets: AssetManager) {
    private val main = Handler(Looper.getMainLooper())
    private var shown = -1
    private var generation = 0

    fun show(index: Int, fadeMs: Long) {
        if (index == shown) return
        shown = index
        val gen = ++generation
        Worker.pool.execute {
            val bitmap = runCatching { decode(assets, path(index), 1280, 720).also { it.prepareToDraw() } }.getOrNull()
            main.post { if (gen == generation && bitmap != null) present(bitmap, fadeMs) }
        }
    }

    private fun present(bitmap: Bitmap, fadeMs: Long) {
        upper.animate().cancel()
        if (upper.visibility == View.VISIBLE && upper.drawable != null) lower.setImageDrawable(upper.drawable)
        upper.setImageBitmap(bitmap)
        upper.alpha = 0f
        upper.visibility = View.VISIBLE
        upper.animate().alpha(1f).setDuration(fadeMs).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction {
            lower.setImageBitmap(bitmap)
            upper.visibility = View.INVISIBLE
            upper.setImageDrawable(null)
        }
    }

    companion object {
        val NAMES = listOf("dawn", "mist", "tide", "ridge", "salt", "orbit", "monsoon")

        fun path(index: Int) = "wallpapers/${NAMES[index]}.jpg"

        /** No larger than needed to cover [width] x [height]; low-RAM policy gives RGB_565 for JPEGs. */
        fun decode(assets: AssetManager, path: String, width: Int, height: Int): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                val size = info.size
                val scale = max(width.toFloat() / size.width, height.toFloat() / size.height)
                if (scale < 1f) decoder.setTargetSize(max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
            }
    }
}

/** The analog face: a thin ring of hour marks and two rounded hands, redrawn once a minute. */
class Dial(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        strokeCap = Paint.Cap.ROUND
    }
    private var hour = 0
    private var minute = 0

    override fun hasOverlappingRendering() = false

    fun set(hour: Int, minute: Int) {
        this.hour = hour
        this.minute = minute
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)
        mark.alpha = 200
        mark.strokeWidth = Ui.px(5f)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val outer = r - Ui.px(6f)
            val inner = outer - Ui.px(if (i % 3 == 0) 26f else 12f)
            canvas.drawLine(cx + inner * sin(a).toFloat(), cy - inner * cos(a).toFloat(), cx + outer * sin(a).toFloat(), cy - outer * cos(a).toFloat(), mark)
        }
        mark.alpha = 255
        hand(canvas, cx, cy, (hour % 12 + minute / 60.0) * 30.0, r * 0.52f, Ui.px(14f))
        hand(canvas, cx, cy, minute * 6.0, r * 0.78f, Ui.px(9f))
        canvas.drawCircle(cx, cy, Ui.px(13f), mark)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, degrees: Double, length: Float, width: Float) {
        val a = Math.toRadians(degrees)
        mark.strokeWidth = width
        canvas.drawLine(cx, cy, cx + length * sin(a).toFloat(), cy - length * cos(a).toFloat(), mark)
    }
}

/** The clock and its four faces. It only repaints when the activity says a minute has passed. */
class Clock(root: View) {
    private val date: TextView = root.findViewById(R.id.date)
    private val stacked: View = root.findViewById(R.id.face_stacked)
    private val hours: TextView = root.findViewById(R.id.stacked_hours)
    private val minutes: TextView = root.findViewById(R.id.stacked_minutes)
    private val wide: TextView = root.findViewById(R.id.face_wide)
    private val dial: Dial = root.findViewById(R.id.face_dial)
    private val serif: TextView = root.findViewById(R.id.face_serif)
    private val faces = listOf(stacked, wide, dial, serif)

    fun setFace(index: Int) {
        faces.forEachIndexed { i, face -> face.visibility = if (i == index) View.VISIBLE else View.GONE }
        tick()
    }

    fun tick() {
        val now = LocalDateTime.now()
        val h24 = DateFormat.is24HourFormat(date.context)
        val hour = if (h24) "%02d".format(now.hour) else ((now.hour + 11) % 12 + 1).toString()
        val minute = "%02d".format(now.minute)
        hours.text = hour.padStart(2, '0')
        minutes.text = minute
        wide.text = "$hour:$minute"
        serif.text = "$hour:$minute"
        dial.set(now.hour, now.minute)
        date.text = now.format(DATE)
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
    }
}
