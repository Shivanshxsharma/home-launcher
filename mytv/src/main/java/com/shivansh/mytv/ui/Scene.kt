package com.shivansh.mytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import com.shivansh.mytv.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object Decode {
    private fun target(decoder: ImageDecoder, info: ImageDecoder.ImageInfo, width: Int, height: Int, mutable: Boolean) {
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        decoder.isMutableRequired = mutable
        val size = info.size
        val scale = max(width.toFloat() / size.width, height.toFloat() / size.height)
        if (scale < 1f) decoder.setTargetSize(max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
    }

    /** No larger than needed to cover [width] x [height]; the low-RAM policy gives RGB_565 for JPEGs. */
    fun asset(assets: AssetManager, path: String, width: Int, height: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ -> target(decoder, info, width, height, false) }

    /** TMDB art by URL, downloaded once into [ImageFiles]. */
    fun url(url: String, width: Int, height: Int, mutable: Boolean = false): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ImageFiles.file(url))) { decoder, info, _ -> target(decoder, info, width, height, mutable) }
}

/**
 * The full-screen picture: a wallpaper, or the focused title's backdrop with the shading for its
 * words drawn in as it decodes. 1280x720 RGB_565 off the main thread; a change fades the new one
 * over the old, so never more than two are in memory and the screen is otherwise still.
 */
class Scenery(private val lower: ImageView, private val upper: ImageView, private val assets: AssetManager) {
    private val main = Handler(Looper.getMainLooper())
    private var shown: String? = null
    private var wanted: String? = null
    private var generation = 0
    private val settle = Runnable { load() }

    fun wallpaper(index: Int, fadeMs: Long) = change(path(index), delayMs = 0, fadeMs = fadeMs)

    /** A backdrop, after focus has rested, so quick moves along a row don't decode every one. */
    fun backdrop(url: String) = change(url, delayMs = REST_MS, fadeMs = 620)

    private var fade = 0L

    private fun change(key: String, delayMs: Long, fadeMs: Long) {
        main.removeCallbacks(settle)
        wanted = key
        fade = fadeMs
        if (key == shown) return
        if (delayMs > 0) main.postDelayed(settle, delayMs) else load()
    }

    private fun load() {
        val key = wanted ?: return
        val gen = ++generation
        val pool = if (key.startsWith("https://")) Worker.net else Worker.pool
        pool.execute {
            val bitmap = runCatching {
                if (key.startsWith("https://")) Decode.url(key, 1280, 720, mutable = true).also(::shade) else Decode.asset(assets, key, 1280, 720)
            }.getOrNull()
            bitmap?.prepareToDraw()
            main.post { if (gen == generation && bitmap != null) present(key, bitmap) }
        }
    }

    private fun present(key: String, bitmap: Bitmap) {
        shown = key
        upper.animate().cancel()
        if (upper.visibility == View.VISIBLE && upper.drawable != null) lower.setImageDrawable(upper.drawable)
        upper.setImageBitmap(bitmap)
        upper.alpha = 0f
        upper.visibility = View.VISIBLE
        upper.animate().alpha(1f).setDuration(Motion.ms(fade)).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction {
            lower.setImageBitmap(bitmap)
            upper.visibility = View.INVISIBLE
            upper.setImageDrawable(null)
        }
    }

    companion object {
        val NAMES = listOf("dawn", "mist", "tide", "ridge", "salt", "orbit", "monsoon")
        private const val REST_MS = 320L

        fun path(index: Int) = "wallpapers/${NAMES[index]}.jpg"

        /** Darker at the left for the words and along the bottom for the posters. */
        private fun shade(bitmap: Bitmap) {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.shader = LinearGradient(
                0f, 0f, w * 0.7f, 0f,
                intArrayOf(0xD9000000.toInt(), 0x80000000.toInt(), 0x00000000),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = LinearGradient(0f, h * 0.5f, 0f, h, 0x00000000, 0xE6000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h * 0.5f, w, h, paint)
        }
    }
}

/**
 * The large clock, five faces drawn on one view. It repaints once a minute; when the minute turns
 * the old digits rise and fade as the new ones come up behind them.
 *
 * - Contour: heavy hours, the minutes in outline, after ColorOS's long clock and its lit edges.
 * - Long: light, wide digits.
 * - Shapes: the hour in a circle and the minutes in a soft square, after Material's bold shapes.
 * - Dial: a quiet analog face, the minutes traced around the rim.
 * - Serif: an editorial, high-contrast face.
 */
class ClockFace(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val bold = context.resources.getFont(R.font.geist_bold)
    private val regular = context.resources.getFont(R.font.geist_regular)
    private val serif = context.resources.getFont(R.font.instrument_serif)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var face = 0
    private var hour = ""
    private var minute = ""
    private var oldMinute = ""
    private var hour24 = 0
    private var minuteNumber = 0
    private var turn = 1f
    private var turning: ValueAnimator? = null

    override fun hasOverlappingRendering() = false

    fun setFace(index: Int) {
        face = index
        invalidate()
    }

    fun set(hour: String, minute: String, hour24: Int, minuteNumber: Int) {
        val changed = this.minute.isNotEmpty() && minute != this.minute
        oldMinute = this.minute
        this.hour = hour
        this.minute = minute
        this.hour24 = hour24
        this.minuteNumber = minuteNumber
        turning?.cancel()
        if (!changed || !isShown) {
            turn = 1f
            invalidate()
            return
        }
        turning = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ms(700)
            interpolator = Motion.SETTLE
            addUpdateListener {
                turn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        when (face) {
            CONTOUR -> contour(canvas)
            LONG -> long(canvas)
            SHAPES -> shapes(canvas)
            DIAL -> dial(canvas)
            else -> serif(canvas)
        }
    }

    private fun size(value: Float) = Ui.px(value)

    /** The minutes at [x, y], the old ones leaving upwards while the new ones arrive. */
    private fun minutes(canvas: Canvas, x: Float, y: Float) {
        val rise = size(46f)
        val alpha = text.alpha
        if (turn < 1f && oldMinute.isNotEmpty()) {
            text.alpha = (alpha * (1f - turn)).toInt()
            canvas.drawText(oldMinute, x, y - rise * turn, text)
        }
        text.alpha = (alpha * turn).toInt()
        canvas.drawText(minute, x, y + rise * (1f - turn), text)
        text.alpha = alpha
    }

    private fun contour(canvas: Canvas) {
        text.reset()
        text.isAntiAlias = true
        text.fontFeatureSettings = "tnum"
        text.typeface = bold
        text.textSize = size(290f)
        text.color = Palette.INK
        val baseline = size(282f)
        canvas.drawText(hour, 0f, baseline, text)
        val x = text.measureText(hour) + size(28f)
        text.style = Paint.Style.STROKE
        text.strokeWidth = size(5f)
        minutes(canvas, x, baseline)
        text.style = Paint.Style.FILL
    }

    private fun long(canvas: Canvas) {
        text.reset()
        text.isAntiAlias = true
        text.fontFeatureSettings = "tnum"
        text.typeface = regular
        text.textSize = size(280f)
        text.letterSpacing = 0.02f
        text.color = Palette.INK
        val baseline = size(276f)
        canvas.drawText(hour, 0f, baseline, text)
        var x = text.measureText(hour)
        text.alpha = 150
        canvas.drawText(":", x, baseline - size(14f), text)
        x += text.measureText(":")
        text.alpha = 255
        minutes(canvas, x, baseline)
    }

    private fun shapes(canvas: Canvas) {
        val s = size(300f)
        line.style = Paint.Style.FILL
        line.color = Palette.INK
        canvas.drawCircle(s / 2f, s / 2f, s / 2f, line)
        line.color = 0xFFFFB27A.toInt()
        rect.set(s + size(24f), 0f, s * 2 + size(24f), s)
        canvas.drawRoundRect(rect, size(84f), size(84f), line)
        text.reset()
        text.isAntiAlias = true
        text.fontFeatureSettings = "tnum"
        text.typeface = bold
        text.textSize = size(164f)
        text.textAlign = Paint.Align.CENTER
        text.color = Palette.DARK
        val baseline = s / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(hour, s / 2f, baseline, text)
        minutes(canvas, rect.centerX(), baseline)
        text.textAlign = Paint.Align.LEFT
    }

    private fun dial(canvas: Canvas) {
        val r = min(height.toFloat(), size(320f)) / 2f - size(8f)
        val cx = r + size(8f)
        val cy = height / 2f
        line.style = Paint.Style.STROKE
        line.color = Palette.INK
        line.alpha = 60
        line.strokeWidth = size(6f)
        canvas.drawCircle(cx, cy, r, line)
        line.alpha = 255
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -90f, minuteNumber * 6f, false, line)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val outer = r - size(22f)
            val inner = outer - size(if (i % 3 == 0) 22f else 8f)
            line.strokeWidth = size(if (i % 3 == 0) 6f else 4f)
            line.alpha = if (i % 3 == 0) 255 else 140
            canvas.drawLine(cx + inner * sin(a).toFloat(), cy - inner * cos(a).toFloat(), cx + outer * sin(a).toFloat(), cy - outer * cos(a).toFloat(), line)
        }
        line.alpha = 255
        val minuteAngle = (minuteNumber - (1f - turn)) * 6.0
        hand(canvas, cx, cy, (hour24 % 12 + minuteAngle / 360.0) * 30.0, r * 0.5f, size(15f))
        hand(canvas, cx, cy, minuteAngle, r * 0.72f, size(9f))
        line.style = Paint.Style.FILL
        line.color = 0xFFFFB27A.toInt()
        canvas.drawCircle(cx, cy, size(12f), line)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, degrees: Double, length: Float, width: Float) {
        val a = Math.toRadians(degrees)
        line.strokeWidth = width
        canvas.drawLine(cx, cy, cx + length * sin(a).toFloat(), cy - length * cos(a).toFloat(), line)
    }

    private fun serif(canvas: Canvas) {
        text.reset()
        text.isAntiAlias = true
        text.typeface = serif
        text.textSize = size(340f)
        text.color = Palette.INK
        val baseline = size(286f)
        canvas.drawText(hour, 0f, baseline, text)
        var x = text.measureText(hour) + size(14f)
        canvas.drawText(":", x, baseline - size(10f), text)
        x += text.measureText(":") + size(14f)
        minutes(canvas, x, baseline)
    }

    companion object {
        const val CONTOUR = 0
        const val LONG = 1
        const val SHAPES = 2
        const val DIAL = 3
        const val SERIF = 4
        const val COUNT = 5
    }
}
