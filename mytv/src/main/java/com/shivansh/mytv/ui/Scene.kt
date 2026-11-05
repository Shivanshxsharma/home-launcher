package com.shivansh.mytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import com.shivansh.mytv.R
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object Decode {
    private fun target(decoder: ImageDecoder, info: ImageDecoder.ImageInfo, width: Int, height: Int, mutable: Boolean) {
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = mutable
        val size = info.size
        val scale = max(width.toFloat() / size.width, height.toFloat() / size.height)
        if (scale < 1f) decoder.setTargetSize(max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
    }

    /** Full colour (RGB_565 bands and speckles soft skies), no larger than needed to cover the size. */
    fun asset(assets: AssetManager, path: String, width: Int, height: Int, mutable: Boolean = false): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ -> target(decoder, info, width, height, mutable) }

    /** TMDB art by URL, downloaded once into [ImageFiles]. */
    fun url(url: String, width: Int, height: Int, mutable: Boolean = false): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ImageFiles.file(url))) { decoder, info, _ -> target(decoder, info, width, height, mutable) }
}

/**
 * The full-screen picture: a wallpaper, or the focused title's backdrop with the shading for its
 * words drawn in as it decodes. Decoded off the main thread, with its frost made alongside; a change
 * fades the new one over the old, so never more than two are in memory.
 */
class Scenery(private val lower: ImageView, private val upper: ImageView, private val depth: ImageView, private val assets: AssetManager) {
    private val main = Handler(Looper.getMainLooper())
    private var shown: String? = null
    private var wanted: String? = null
    private var generation = 0
    private var fade = 0L
    private val settle = Runnable { load() }

    fun wallpaper(index: Int, fadeMs: Long) {
        if (index in NAMES.indices) change(path(index), delayMs = 0, fadeMs = fadeMs)
    }

    /** A backdrop, after focus has rested, so moving along a row doesn't decode every one. */
    fun backdrop(url: String) = change(url, delayMs = REST_MS, fadeMs = 800)

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
        val web = key.startsWith("https://")
        (if (web) Worker.net else Worker.pool).execute {
            var frost: Bitmap? = null
            var front: Bitmap? = null
            val bitmap = runCatching {
                val picture = if (web) Decode.url(key, 1280, 720, mutable = true) else Decode.asset(assets, key, 1920, 1080, mutable = true)
                if (!web) calm(picture)
                frost = Frost.make(picture)
                if (web) shade(picture) else floor(picture)
                if (!web) front = foreground(picture, key)
                picture.prepareToDraw()
                picture
            }.getOrNull()
            main.post {
                val glass = frost
                if (gen == generation && bitmap != null && glass != null) present(key, bitmap, glass, front)
            }
        }
    }

    /** The part of the wallpaper that stands in front of the clock, where it has a mask. */
    private fun foreground(picture: Bitmap, key: String): Bitmap? {
        val maskPath = key.replace("wallpapers/", "wallpapers/masks/").replace(".jpg", ".png")
        val mask = runCatching { assets.open(maskPath).use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        val w = picture.width
        val band = (picture.height * BAND).toInt()
        val out = Bitmap.createBitmap(w, band, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(picture, 0f, 0f, null)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        canvas.drawBitmap(mask, null, RectF(0f, 0f, w.toFloat(), picture.height.toFloat()), paint)
        mask.recycle()
        out.prepareToDraw()
        return out
    }

    private fun present(key: String, bitmap: Bitmap, frost: Bitmap, front: Bitmap?) {
        shown = key
        Frost.set(frost)
        depth.animate().cancel()
        depth.setImageBitmap(front)
        depth.alpha = 0f
        depth.animate().alpha(1f).setDuration(Motion.ms(fade)).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction(null)
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
        /** The wallpapers in this build, by file name. Local builds carry them; a public one may have none. */
        var NAMES: List<String> = emptyList()
            private set
        private const val REST_MS = 420L

        /** How much of the wallpaper's height the clock can reach, and so the foreground needs. */
        private const val BAND = 0.62f
        private const val CALM_LUMA = 92f

        fun scan(assets: AssetManager) {
            NAMES = assets.list("wallpapers").orEmpty().filter { it.endsWith(".jpg") }.map { it.removeSuffix(".jpg") }.sorted()
        }

        fun path(index: Int) = "wallpapers/${NAMES[index]}.jpg"

        fun thumb(index: Int) = "wallpapers/thumbs/${NAMES[index]}.jpg"

        /** Dims a bright picture just enough for thin white type, judged where the clock sits. */
        private fun calm(bitmap: Bitmap) {
            val tiny = Bitmap.createScaledBitmap(bitmap, 32, 18, true)
            var sum = 0f
            for (y in 0 until 11) {
                for (x in 0 until 32) {
                    val c = tiny.getPixel(x, y)
                    sum += (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10f
                }
            }
            tiny.recycle()
            val mean = sum / (11 * 32)
            if (mean <= CALM_LUMA) return
            val dim = (1f - CALM_LUMA / mean).coerceAtMost(0.55f)
            Canvas(bitmap).drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
        }

        /** A little shade behind the bar and the time, on any picture. */
        private fun crown(canvas: Canvas, w: Float, h: Float) {
            val paint = Paint()
            paint.shader = LinearGradient(0f, 0f, 0f, h * 0.24f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h * 0.24f, paint)
        }

        /** A wallpaper's gentle fall-off at the foot, drawn in so it isn't blended on every frame. */
        private fun floor(bitmap: Bitmap) {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val canvas = Canvas(bitmap)
            crown(canvas, w, h)
            val paint = Paint()
            paint.shader = LinearGradient(0f, h * 0.55f, 0f, h, 0x00000000, 0x8C000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h * 0.55f, w, h, paint)
        }

        /** Darker at the left for the words and along the bottom for the posters. */
        private fun shade(bitmap: Bitmap) {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val canvas = Canvas(bitmap)
            crown(canvas, w, h)
            val paint = Paint()
            paint.shader = LinearGradient(
                0f, 0f, w * 0.72f, 0f,
                intArrayOf(0xD9000000.toInt(), 0x73000000, 0x00000000),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = LinearGradient(0f, h * 0.48f, 0f, h, 0x00000000, 0xE6000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h * 0.48f, w, h, paint)
        }
    }
}

/**
 * The large clock: six faces drawn into one cached layer, so the big shapes cost nothing between
 * minutes; each frame just draws the texture. They came out of what people actually pin and shot
 * for TVs: flip tiles, tall lock-screen numerals, a moon dial, a script weekday, a luminous ring.
 *
 * - Glass: tall numerals that catch the light, with the scenery's foreground in front of them.
 * - Flip: two frosted tiles; the minute folds over as it changes.
 * - Moon: a dress-watch dial with rose-gold numerals, stars and tonight's real moon.
 * - Weekday: pale thin digits with the day written across them.
 * - Halo: a thin ring that fills through the hour.
 * - Serif: a high-contrast editorial face.
 */
class ClockFace(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val tall = context.resources.getFont(R.font.sofia_tall)
    private val thin = context.resources.getFont(R.font.geist_thin)
    private val light = context.resources.getFont(R.font.geist_light)
    private val medium = context.resources.getFont(R.font.geist_medium)
    private val semibold = context.resources.getFont(R.font.geist_semibold)
    private val serif = context.resources.getFont(R.font.instrument_serif)
    private val italic = context.resources.getFont(R.font.instrument_serif_italic)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val bounds = Rect()
    private val where = IntArray(2)
    private val stars: List<FloatArray> = Random(7).let { r -> List(90) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat()) } }
    private var face = 0
    private var hour = ""
    private var oldHour = ""
    private var minute = ""
    private var oldMinute = ""
    private var meridiem = ""
    private var time: LocalDateTime = LocalDateTime.now()
    private var weekday = ""
    private var date = ""
    private var longDate = ""
    private var turn = 1f
    private var turning: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Frost.attachSettled(this)
    }

    fun setFace(index: Int) {
        face = index
        invalidate()
    }

    fun set(now: LocalDateTime, hour: String, meridiem: String) {
        val next = "%02d".format(now.minute)
        val changed = minute.isNotEmpty() && next != minute
        oldMinute = minute
        oldHour = this.hour
        this.hour = hour
        this.minute = next
        this.meridiem = meridiem
        time = now
        weekday = now.format(WEEKDAY)
        date = now.format(DATE)
        longDate = "$weekday, $date"
        turning?.cancel()
        if (!changed || !isShown) {
            turn = 1f
            invalidate()
            return
        }
        turning = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ms(if (face == FLIP) 620 else 900)
            interpolator = if (face == FLIP) Motion.EASE else Motion.SETTLE
            addUpdateListener {
                turn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun px(value: Float) = Ui.px(value)

    private fun digits(typeface: Typeface, size: Float, spacing: Float = 0f, shadow: Boolean = true) {
        text.reset()
        text.isAntiAlias = true
        text.typeface = typeface
        text.textSize = px(size)
        text.letterSpacing = spacing
        text.color = Palette.INK
        text.fontFeatureSettings = "tnum"
        if (shadow) text.setShadowLayer(px(28f), 0f, px(3f), 0x8C000000.toInt())
    }

    private fun caption(words: String, x: Float, y: Float, canvas: Canvas, center: Boolean, size: Float = 34f, color: Int = Palette.INK, face: Typeface = medium) {
        small.reset()
        small.isAntiAlias = true
        small.typeface = face
        small.textSize = px(size)
        small.color = color
        small.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        small.setShadowLayer(px(14f), 0f, px(2f), 0x99000000.toInt())
        canvas.drawText(words, x, y, small)
    }

    override fun onDraw(canvas: Canvas) {
        when (face) {
            GLASS -> glass(canvas)
            FLIP -> flip(canvas)
            MOON -> moonDial(canvas)
            WEEKDAY_FACE -> weekday(canvas)
            HALO -> halo(canvas)
            else -> serif(canvas)
        }
    }

    /** The minutes at [x, y], the old ones leaving upwards while the new ones arrive. */
    private fun minutes(canvas: Canvas, x: Float, y: Float) {
        val rise = px(40f)
        val shown = text.alpha
        if (turn < 1f && oldMinute.isNotEmpty()) {
            text.alpha = (shown * (1f - turn)).toInt()
            canvas.drawText(oldMinute, x, y - rise * turn, text)
        }
        text.alpha = (shown * turn).toInt()
        canvas.drawText(minute, x, y + rise * (1f - turn), text)
        text.alpha = shown
    }

    private fun glass(canvas: Canvas) {
        val center = width / 2f
        caption(longDate, center, px(40f), canvas, center = true)
        digits(tall, 470f, -0.01f)
        text.fontFeatureSettings = null
        val top = px(60f)
        val baseline = px(446f)
        text.shader = LinearGradient(0f, top, 0f, baseline, 0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), Shader.TileMode.CLAMP)
        val h = text.measureText(hour)
        val c = text.measureText(":")
        val m = text.measureText(minute)
        var x = center - (h + c + m) / 2f
        canvas.drawText(hour, x, baseline, text)
        x += h
        canvas.drawText(":", x, baseline - px(8f), text)
        x += c
        minutes(canvas, x, baseline)
        text.shader = null
    }

    private fun flip(canvas: Canvas) {
        val w = px(330f)
        val h = px(372f)
        val gap = px(24f)
        val top = px(64f)
        val left = width / 2f - w - gap / 2f
        caption(longDate, width / 2f, px(40f), canvas, center = true)
        getLocationInWindow(where)
        digits(semibold, 226f, -0.02f, shadow = false)
        text.textAlign = Paint.Align.CENTER
        text.getTextBounds("0", 0, 1, bounds)
        val base = top + h / 2f + bounds.height() / 2f
        val hourTurns = oldHour.isNotEmpty() && oldHour != hour
        tile(canvas, left, top, w, h, base, hour, oldHour, if (hourTurns) turn else 1f)
        tile(canvas, left + w + gap, top, w, h, base, minute, oldMinute, turn)
        if (meridiem.isNotEmpty()) {
            caption(meridiem, left + px(24f), top + h - px(26f), canvas, center = false, size = 26f)
        }
    }

    private fun tile(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, base: Float, now: String, old: String, t: Float) {
        val r = px(40f)
        val mid = top + h / 2f
        val cx = left + w / 2f
        box.set(left, top, left + w, top + h)
        // A shadow under the tile, then the frost behind it.
        fill.reset()
        fill.isAntiAlias = true
        fill.color = 0x00000000
        fill.setShadowLayer(px(30f), 0f, px(14f), 0x99000000.toInt())
        fill.color = 0x66000000
        canvas.drawRoundRect(box, r, r, fill)
        fill.clearShadowLayer()
        canvas.save()
        canvas.translate(0f, 0f)
        val root = rootView
        canvas.translate(left, top)
        box.set(0f, 0f, w, h)
        Frost.draw(canvas, box, r, where[0] + left, where[1] + top, root.width.toFloat(), root.height.toFloat())
        canvas.restore()
        box.set(left, top, left + w, top + h)
        fill.color = 0x8C0C0E13.toInt()
        canvas.drawRoundRect(box, r, r, fill)
        if (t >= 1f || old.isEmpty()) {
            canvas.drawText(now, cx, base, text)
        } else {
            // The new number's top and the old one's foot stay still while a flap folds over.
            half(canvas, left, top, w, mid, now, cx, base)
            half(canvas, left, mid, w, top + h, old, cx, base)
            canvas.save()
            val fold = if (t < 0.5f) 1f - t * 2f else t * 2f - 1f
            canvas.translate(0f, mid)
            canvas.scale(1f, fold.coerceAtLeast(0.001f))
            canvas.translate(0f, -mid)
            val flapTop = if (t < 0.5f) top else mid
            val flapEnd = if (t < 0.5f) mid else top + h
            canvas.clipRect(left, flapTop, left + w, flapEnd)
            fill.color = 0xF2191C24.toInt()
            box.set(left, top, left + w, top + h)
            canvas.drawRoundRect(box, r, r, fill)
            canvas.drawText(if (t < 0.5f) old else now, cx, base, text)
            canvas.restore()
        }
        // The split, and a lit top edge.
        line.reset()
        line.isAntiAlias = true
        line.strokeWidth = px(4f)
        line.color = 0xB3000000.toInt()
        canvas.drawLine(left, mid, left + w, mid, line)
        line.style = Paint.Style.STROKE
        line.strokeWidth = px(2f)
        line.shader = LinearGradient(0f, top, 0f, top + h, 0x59FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP)
        box.set(left + px(1f), top + px(1f), left + w - px(1f), top + h - px(1f))
        canvas.drawRoundRect(box, r, r, line)
        line.shader = null
    }

    private fun half(canvas: Canvas, left: Float, top: Float, w: Float, bottom: Float, digits: String, cx: Float, base: Float) {
        canvas.save()
        canvas.clipRect(left, top, left + w, bottom)
        canvas.drawText(digits, cx, base, text)
        canvas.restore()
    }

    private fun moonDial(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = height / 2f - px(4f)
        getLocationInWindow(where)
        val root = rootView
        // The dial: frost under smoked glass, on a soft shadow.
        fill.reset()
        fill.isAntiAlias = true
        fill.color = 0x66000000
        fill.setShadowLayer(px(36f), 0f, px(16f), 0xA6000000.toInt())
        canvas.drawCircle(cx, cy, r, fill)
        fill.clearShadowLayer()
        canvas.save()
        canvas.translate(cx - r, cy - r)
        box.set(0f, 0f, r * 2f, r * 2f)
        Frost.draw(canvas, box, r, where[0] + cx - r, where[1] + cy - r, root.width.toFloat(), root.height.toFloat())
        canvas.restore()
        fill.shader = RadialGradient(cx, cy - r * 0.2f, r * 1.2f, 0xD90E1220.toInt(), 0xF2060810.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null
        for (s in stars) {
            val a = s[0] * 6.2832
            val d = sqrt(s[1]) * r * 0.74f
            fill.color = Palette.INK
            fill.alpha = (40 + s[2] * 150).toInt()
            canvas.drawCircle(cx + d * cos(a).toFloat(), cy + d * sin(a).toFloat(), px(0.8f + s[2] * 1.3f), fill)
        }
        fill.alpha = 255
        // Rim and minute track.
        line.reset()
        line.isAntiAlias = true
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.color = GOLD
        line.strokeWidth = px(2f)
        line.alpha = 170
        canvas.drawCircle(cx, cy, r - px(1f), line)
        line.alpha = 255
        for (i in 0 until 60) {
            if (i % 5 == 0) continue
            val a = Math.toRadians(i * 6.0)
            fill.color = GOLD
            fill.alpha = 150
            canvas.drawCircle(cx + (r - px(14f)) * sin(a).toFloat(), cy - (r - px(14f)) * cos(a).toFloat(), px(1.6f), fill)
        }
        fill.alpha = 255
        // Numerals.
        small.reset()
        small.isAntiAlias = true
        small.typeface = serif
        small.textSize = px(46f)
        small.color = GOLD
        small.textAlign = Paint.Align.CENTER
        val nr = r - px(44f)
        for (n in 1..12) {
            val a = Math.toRadians(n * 30.0)
            canvas.drawText(n.toString(), cx + nr * sin(a).toFloat(), cy - nr * cos(a).toFloat() + px(16f), small)
        }
        moon(canvas, cx, cy + r * 0.34f, r * 0.15f)
        caption(weekday.uppercase(Locale.getDefault()).take(3) + "  " + time.dayOfMonth, cx, cy - r * 0.34f, canvas, center = true, size = 24f, color = GOLD)
        // Hands, on a short sweep as the minute turns.
        val mins = time.minute - (1f - turn)
        hand(canvas, cx, cy, (time.hour % 12 + mins / 60.0) * 30.0, r * 0.5f, px(6f))
        hand(canvas, cx, cy, mins * 6.0, r * 0.76f, px(3.5f))
        fill.color = GOLD
        canvas.drawCircle(cx, cy, px(9f), fill)
        fill.color = 0xFF10121A.toInt()
        canvas.drawCircle(cx, cy, px(3.5f), fill)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, degrees: Double, length: Float, width: Float) {
        val a = Math.toRadians(degrees)
        line.color = 0xFFF6E4D2.toInt()
        line.strokeWidth = width
        line.setShadowLayer(px(8f), 0f, px(3f), 0x99000000.toInt())
        val s = sin(a).toFloat()
        val c = cos(a).toFloat()
        canvas.drawLine(cx - length * 0.14f * s, cy + length * 0.14f * c, cx + length * s, cy - length * c, line)
        line.clearShadowLayer()
    }

    /** Tonight's moon, from the 29.53-day cycle counted from a known new moon. */
    private fun moon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val days = (time.toEpochSecond(ZoneOffset.UTC) - NEW_MOON) / 86400.0
        val phase = ((days / SYNODIC) % 1.0 + 1.0) % 1.0
        val waxing = phase < 0.5
        val k = cos(phase * 2 * PI).toFloat()
        fill.color = 0x26F6E4D2
        canvas.drawCircle(cx, cy, r, fill)
        val path = Path()
        box.set(cx - r, cy - r, cx + r, cy + r)
        path.addArc(box, if (waxing) -90f else 90f, 180f)
        box.set(cx - r * abs(k), cy - r, cx + r * abs(k), cy + r)
        path.arcTo(box, if (waxing) 90f else -90f, if (k > 0f) -180f else 180f)
        path.close()
        fill.color = 0xFFF6E4D2.toInt()
        canvas.drawPath(path, fill)
    }

    private fun weekday(canvas: Canvas) {
        digits(light, 330f, -0.03f)
        text.alpha = 225
        val baseline = px(262f)
        canvas.drawText(hour, 0f, baseline, text)
        var x = text.measureText(hour) + px(8f)
        canvas.drawText(":", x, baseline - px(12f), text)
        x += text.measureText(":") + px(8f)
        minutes(canvas, x, baseline)
        text.alpha = 255
        caption(weekday, px(10f), px(392f), canvas, center = false, size = 168f, face = italic)
        caption(date, px(18f), px(452f), canvas, center = false, size = 34f)
    }

    private fun halo(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = height / 2f - px(20f)
        line.reset()
        line.isAntiAlias = true
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.strokeWidth = px(3f)
        line.color = 0x38FFFFFF
        canvas.drawCircle(cx, cy, r, line)
        val sweep = (time.minute - (1f - turn) + 0.5f) / 60f * 360f
        box.set(cx - r, cy - r, cx + r, cy + r)
        line.strokeWidth = px(7f)
        line.color = Palette.INK
        line.setShadowLayer(px(20f), 0f, 0f, 0xCCFFFFFF.toInt())
        canvas.drawArc(box, -90f, sweep, false, line)
        line.clearShadowLayer()
        val a = Math.toRadians(sweep.toDouble())
        fill.reset()
        fill.isAntiAlias = true
        fill.color = Palette.INK
        fill.setShadowLayer(px(22f), 0f, 0f, 0xFFFFFFFF.toInt())
        canvas.drawCircle(cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat(), px(11f), fill)
        fill.clearShadowLayer()
        digits(light, 158f, -0.03f)
        text.textAlign = Paint.Align.LEFT
        val h = text.measureText(hour)
        val c = text.measureText(":")
        val m = text.measureText(minute)
        var x = cx - (h + c + m) / 2f
        val baseline = cy + px(44f)
        canvas.drawText(hour, x, baseline, text)
        x += h
        canvas.drawText(":", x, baseline - px(6f), text)
        x += c
        minutes(canvas, x, baseline)
        caption(date, cx, cy + px(104f), canvas, center = true, size = 28f)
    }

    private fun serif(canvas: Canvas) {
        digits(serif, 360f)
        text.fontFeatureSettings = null
        val baseline = px(310f)
        canvas.drawText(hour, 0f, baseline, text)
        var x = text.measureText(hour) + px(16f)
        canvas.drawText(":", x, baseline - px(14f), text)
        x += text.measureText(":") + px(16f)
        minutes(canvas, x, baseline)
        caption(longDate, px(8f), px(386f), canvas, center = false)
    }

    companion object {
        const val GLASS = 0
        const val FLIP = 1
        const val MOON = 2
        const val WEEKDAY_FACE = 3
        const val HALO = 4
        const val SERIF = 5
        const val COUNT = 6
        private const val GOLD = 0xFFE7C4A6.toInt()
        private const val SYNODIC = 29.530588853

        /** A new moon: 6 January 2000, 18:14 UTC. */
        private const val NEW_MOON = 947182440L
        private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())
    }
}
