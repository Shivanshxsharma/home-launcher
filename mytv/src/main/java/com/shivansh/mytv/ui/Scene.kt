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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import com.shivansh.mytv.R
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
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

    /** A picture on disk: one the phone sent, or a wallpaper made from one. */
    fun file(file: File, width: Int, height: Int, mutable: Boolean = false): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ -> target(decoder, info, width, height, mutable) }

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

    fun wallpaper(wall: Wall?, fadeMs: Long) {
        if (wall != null) change(wall.picture, delayMs = 0, fadeMs = fadeMs)
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
        val added = key.startsWith("/")
        (if (web) Worker.net else Worker.pool).execute {
            var frost: Bitmap? = null
            var front: Bitmap? = null
            val bitmap = runCatching {
                val picture = when {
                    web -> Decode.url(key, 1280, 720, mutable = true)
                    added -> Decode.file(File(key), 1920, 1080, mutable = true)
                    else -> Decode.asset(assets, key, 1920, 1080, mutable = true)
                }
                if (!web) calm(picture)
                frost = Frost.make(picture)
                if (web) shade(picture) else floor(picture)
                if (!web && !added) front = foreground(picture, key)
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
        private const val REST_MS = 420L

        /** How much of the wallpaper's height the clock can reach, and so the foreground needs. */
        private const val BAND = 0.62f
        private const val CALM_LUMA = 92f

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
 * The large clock: six faces drawn into one cached layer, so the big shapes cost nothing; each
 * frame just draws the texture, and they are drawn again only when the minute changes. They came out of what people actually pin and shot
 * for TVs: flip tiles, tall lock-screen numerals, a moon dial, a script weekday, a luminous ring.
 *
 * - Glass: tall numerals that catch the light, with the scenery's foreground in front of them.
 * - Flip: two frosted tiles; the minute folds over as it changes.
 * - Moon: a dress-watch dial with rose-gold numerals, stars and tonight's real moon.
 * - Weekday: pale thin digits with the day written across them.
 * - Halo: a thin ring that fills through the hour.
 * - Serif: a high-contrast editorial face.
 *
 * And six compact panels at the left, leaving the scenery clear. They show only what the box really
 * knows, with no made-up tiles: nothing that needs a location, an account or a service.
 *
 * - Stack: the hour over the minutes.
 * - Month: the time above this month's calendar.
 * - Ring: the time inside a ring that fills through the hour.
 * - Date: the date as a card.
 * - Last app: the time and the app opened last.
 * - Ambient: tonight's moon, its phase and how much of it is lit.
 *
 * And three small ones in the top-left corner, level with the bar, that leave the picture alone.
 *
 * - Mini: the time and date in thin type, as on Apple TV's screensavers.
 * - Pill: a glass capsule the height of the bar.
 * - Dial: a small watch face with the day beside it.
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
    private var lastAppName = ""
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

    /** The app opened last, for the Last app face; empty if none. */
    fun setLastApp(label: String) {
        if (label == lastAppName) return
        lastAppName = label
        if (face == LAST_APP) invalidate()
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
        if (!changed || !isShown || face != FLIP) {
            turn = 1f
            invalidate()
            // Only the cached layer's alpha moves. Redrawing these big shapes on every frame, as a
            // rolling minute did, held the whole screen at 30 fps for a second each minute.
            if (changed && isShown && face <= SERIF) {
                alpha = 0.7f
                animate().alpha(1f).setDuration(Motion.ms(600)).setStartDelay(0).setInterpolator(Motion.EASE)
            }
            return
        }
        turning = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.ms(620)
            interpolator = Motion.EASE
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
            STACK -> stack(canvas)
            MONTH -> month(canvas)
            RING -> ring(canvas)
            DATE_CARD -> dateCard(canvas)
            LAST_APP -> lastApp(canvas)
            AMBIENT -> ambient(canvas)
            MINI -> mini(canvas)
            PILL -> capsule(canvas)
            DIAL -> smallDial(canvas)
            else -> {
                // The large faces sit lower than the top of this view, under the bar.
                canvas.save()
                canvas.translate(0f, px(LARGE_TOP))
                when (face) {
                    GLASS -> glass(canvas)
                    FLIP -> flip(canvas)
                    MOON -> moonDial(canvas)
                    WEEKDAY_FACE -> weekday(canvas)
                    HALO -> halo(canvas)
                    else -> serif(canvas)
                }
                canvas.restore()
            }
        }
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
        canvas.drawText(minute, x, baseline, text)
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
        where[1] += px(LARGE_TOP).toInt()
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
        val cy = px(LARGE_H) / 2f
        val r = px(LARGE_H) / 2f - px(4f)
        getLocationInWindow(where)
        where[1] += px(LARGE_TOP).toInt()
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

    /** Where the moon is in its cycle, 0 to 1, counted from a known new moon. */
    private fun moonPhase(): Double {
        val days = (time.toEpochSecond(ZoneOffset.UTC) - NEW_MOON) / 86400.0
        return ((days / SYNODIC) % 1.0 + 1.0) % 1.0
    }

    /** Tonight's moon, drawn from its real phase. */
    private fun moon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val phase = moonPhase()
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
        canvas.drawText(minute, x, baseline, text)
        text.alpha = 255
        caption(weekday, px(10f), px(392f), canvas, center = false, size = 168f, face = italic)
        caption(date, px(18f), px(452f), canvas, center = false, size = 34f)
    }

    private fun halo(canvas: Canvas) {
        val cx = width / 2f
        val cy = px(LARGE_H) / 2f
        val r = px(LARGE_H) / 2f - px(20f)
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
        canvas.drawText(minute, x, baseline, text)
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
        canvas.drawText(minute, x, baseline, text)
        caption(longDate, px(8f), px(386f), canvas, center = false)
    }

    // ---- The panels: compact faces at the left, showing only what the box really knows ----

    private fun panel(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) {
        fill.reset()
        fill.isAntiAlias = true
        fill.color = 0x4D000000
        fill.setShadowLayer(px(32f), 0f, px(14f), 0x8C000000.toInt())
        box.set(x, y, x + w, y + h)
        canvas.drawRoundRect(box, r, r, fill)
        fill.clearShadowLayer()
        getLocationInWindow(where)
        val root = rootView
        canvas.save()
        canvas.translate(x, y)
        box.set(0f, 0f, w, h)
        Frost.draw(canvas, box, r, where[0] + x, where[1] + y, root.width.toFloat(), root.height.toFloat(), PANEL_TINT)
        canvas.restore()
        line.reset()
        line.isAntiAlias = true
        line.style = Paint.Style.STROKE
        line.strokeWidth = px(2f)
        line.shader = LinearGradient(0f, y, 0f, y + h, 0x59FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP)
        box.set(x + px(1f), y + px(1f), x + w - px(1f), y + h - px(1f))
        canvas.drawRoundRect(box, r, r, line)
        line.shader = null
    }

    private fun write(
        canvas: Canvas,
        words: String,
        x: Float,
        y: Float,
        size: Float,
        face: Typeface = medium,
        color: Int = Palette.INK,
        align: Paint.Align = Paint.Align.CENTER,
        spacing: Float = 0f,
    ) {
        small.reset()
        small.isAntiAlias = true
        small.typeface = face
        small.textSize = px(size)
        small.color = color
        small.textAlign = align
        small.letterSpacing = spacing
        small.fontFeatureSettings = "tnum"
        canvas.drawText(words, x, y, small)
    }

    /** The time centred on [cx], with AM or PM beside it, small, on a 12-hour clock. */
    private fun timeLine(canvas: Canvas, cx: Float, y: Float, size: Float, face: Typeface = semibold) {
        val words = "$hour:$minute"
        small.reset()
        small.typeface = face
        small.textSize = px(size)
        small.fontFeatureSettings = "tnum"
        val w = small.measureText(words)
        small.typeface = medium
        small.textSize = px(size * 0.26f)
        val tag = if (meridiem.isEmpty()) 0f else small.measureText(meridiem) + px(8f)
        val left = cx - (w + tag) / 2f
        write(canvas, words, left, y, size, face, align = Paint.Align.LEFT)
        if (meridiem.isNotEmpty()) write(canvas, meridiem, left + w + px(8f), y, size * 0.26f, align = Paint.Align.LEFT)
    }

    private fun rule(canvas: Canvas, x0: Float, x1: Float, y: Float) {
        line.reset()
        line.isAntiAlias = true
        line.strokeWidth = px(2f)
        line.color = 0x2EFFFFFF
        canvas.drawLine(x0, y, x1, y, line)
    }

    /** The thin coloured line down the edge of a panel. */
    private fun accent(canvas: Canvas, x: Float, y0: Float, y1: Float) {
        line.reset()
        line.isAntiAlias = true
        line.strokeCap = Paint.Cap.ROUND
        line.strokeWidth = px(5f)
        line.shader = LinearGradient(0f, y0, 0f, y1, ACCENT, null, Shader.TileMode.CLAMP)
        canvas.drawLine(x, y0, x, y1, line)
        line.shader = null
    }

    private fun stack(canvas: Canvas) {
        val w = px(300f)
        val y = px(12f)
        panel(canvas, 0f, y, w, px(440f), px(44f))
        accent(canvas, px(22f), y + px(44f), y + px(396f))
        val cx = w / 2f + px(8f)
        digits(light, 158f, -0.02f, shadow = false)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(hour.padStart(2, '0'), cx, y + px(178f), text)
        canvas.drawText(minute, cx, y + px(326f), text)
        write(canvas, time.format(SHORT_DATE), cx, y + px(396f), 30f)
    }

    private fun month(canvas: Canvas) {
        val w = px(400f)
        val y = px(12f)
        val cx = w / 2f
        panel(canvas, 0f, y, w, px(572f), px(44f))
        timeLine(canvas, cx, y + px(112f), 92f)
        write(canvas, longDate, cx, y + px(160f), 25f, color = 0xE6FFFFFF.toInt())
        rule(canvas, px(32f), w - px(32f), y + px(190f))
        val first = time.toLocalDate().withDayOfMonth(1)
        val start = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val pitch = (w - px(44f)) / 7f
        val left = px(22f) + pitch / 2f
        for (i in 0 until 7) {
            val day = start.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault())
            write(canvas, day, left + i * pitch, y + px(232f), 21f, color = 0x99FFFFFF.toInt())
        }
        val offset = (first.dayOfWeek.value - start.value + 7) % 7
        for (d in 1..first.lengthOfMonth()) {
            val cell = offset + d - 1
            val x = left + (cell % 7) * pitch
            val baseline = y + px(282f) + (cell / 7) * px(50f)
            val today = d == time.dayOfMonth
            if (today) {
                fill.reset()
                fill.isAntiAlias = true
                fill.color = Palette.INK
                canvas.drawCircle(x, baseline - px(8f), px(21f), fill)
            }
            write(canvas, d.toString(), x, baseline, 23f, color = if (today) Palette.DARK else Palette.INK)
        }
    }

    /** The ring fills through the hour, so it shows something real. */
    private fun ring(canvas: Canvas) {
        val d = px(400f)
        val y = px(12f)
        val r = d / 2f
        panel(canvas, 0f, y, d, d, r)
        val cy = y + r
        val rr = r - px(26f)
        line.reset()
        line.isAntiAlias = true
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.strokeWidth = px(11f)
        line.color = 0x2EFFFFFF
        canvas.drawCircle(r, cy, rr, line)
        line.color = Palette.INK
        line.shader = SweepGradient(r, cy, ACCENT, null).apply {
            setLocalMatrix(Matrix().apply { setRotate(-92f, r, cy) })
        }
        box.set(r - rr, cy - rr, r + rr, cy + rr)
        canvas.drawArc(box, -90f, (time.minute + 0.5f) / 60f * 360f, false, line)
        line.shader = null
        timeLine(canvas, r, cy + px(26f), 92f, light)
        write(canvas, time.format(SHORT_DATE), r, cy + px(78f), 27f, color = 0xE6FFFFFF.toInt())
    }

    private fun dateCard(canvas: Canvas) {
        val w = px(300f)
        val y = px(12f)
        val cx = w / 2f
        panel(canvas, 0f, y, w, px(530f), px(44f))
        write(canvas, time.format(SHORT_DAY).uppercase(Locale.getDefault()), cx, y + px(76f), 42f, spacing = 0.06f)
        digits(semibold, 164f, -0.02f, shadow = false)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("%02d".format(time.dayOfMonth), cx, y + px(232f), text)
        write(canvas, time.format(SHORT_MONTH).uppercase(Locale.getDefault()), cx, y + px(296f), 42f, spacing = 0.06f)
        fill.reset()
        fill.isAntiAlias = true
        fill.shader = LinearGradient(px(24f), 0f, w - px(24f), 0f, ACCENT, null, Shader.TileMode.CLAMP)
        box.set(px(24f), y + px(330f), w - px(24f), y + px(392f))
        canvas.drawRoundRect(box, px(22f), px(22f), fill)
        fill.shader = null
        timeLine(canvas, cx, y + px(486f), 74f)
    }

    private fun lastApp(canvas: Canvas) {
        val w = px(360f)
        val y = px(12f)
        val cx = w / 2f
        val shown = lastAppName.isNotEmpty()
        panel(canvas, 0f, y, w, px(if (shown) 368f else 214f), px(44f))
        timeLine(canvas, cx, y + px(116f), 92f)
        write(canvas, time.format(SHORT_DATE), cx, y + px(168f), 28f, color = 0xE6FFFFFF.toInt())
        if (!shown) return
        rule(canvas, px(32f), w - px(32f), y + px(204f))
        write(canvas, "LAST OPENED", cx, y + px(254f), 19f, color = 0x99FFFFFF.toInt(), spacing = 0.12f)
        small.textSize = px(34f)
        small.typeface = medium
        var name = lastAppName
        while (name.length > 3 && small.measureText(name) > w - px(56f)) name = name.dropLast(2) + "…"
        write(canvas, name, cx, y + px(310f), 34f)
    }

    private fun ambient(canvas: Canvas) {
        val y = px(12f)
        accent(canvas, px(6f), y + px(14f), y + px(452f))
        val x = px(38f)
        moon(canvas, x + px(44f), y + px(62f), px(44f))
        digits(light, 112f, -0.02f)
        canvas.drawText("$hour:$minute", x, y + px(242f), text)
        if (meridiem.isNotEmpty()) {
            val after = x + text.measureText("$hour:$minute") + px(10f)
            caption(meridiem, after, y + px(242f), canvas, center = false, size = 28f)
        }
        caption(longDate, x + px(2f), y + px(298f), canvas, center = false, size = 30f)
        rule(canvas, x, x + px(300f), y + px(336f))
        val phase = moonPhase()
        val lit = ((1.0 - cos(phase * 2 * PI)) / 2.0 * 100.0).roundToInt()
        caption(moonName(phase), x + px(2f), y + px(390f), canvas, center = false, size = 30f)
        caption("$lit% lit tonight", x + px(2f), y + px(434f), canvas, center = false, size = 25f, color = 0xCCFFFFFF.toInt())
    }

    private fun moonName(phase: Double) = when {
        phase < 0.03 || phase >= 0.97 -> "New moon"
        phase < 0.22 -> "Waxing crescent"
        phase < 0.28 -> "First quarter"
        phase < 0.47 -> "Waxing gibbous"
        phase < 0.53 -> "Full moon"
        phase < 0.72 -> "Waning gibbous"
        phase < 0.78 -> "Last quarter"
        else -> "Waning crescent"
    }

    // ---- The small ones: up in the corner, level with the bar, and out of the picture's way ----

    private fun mini(canvas: Canvas) {
        digits(light, 88f, -0.02f)
        val words = "$hour:$minute"
        canvas.drawText(words, 0f, px(92f), text)
        if (meridiem.isNotEmpty()) caption(meridiem, text.measureText(words) + px(10f), px(92f), canvas, center = false, size = 24f)
        caption(time.format(SHORT_DATE), px(3f), px(134f), canvas, center = false, size = 26f)
    }

    /** A capsule the height of the bar, so the two read as one row. */
    private fun capsule(canvas: Canvas) {
        val h = px(68f)
        val y = px(6f)
        val words = "$hour:$minute"
        val day = time.format(SHORT_DATE)
        small.reset()
        small.typeface = semibold
        small.textSize = px(36f)
        small.fontFeatureSettings = "tnum"
        val timeW = small.measureText(words)
        small.typeface = medium
        small.textSize = px(18f)
        val tagW = if (meridiem.isEmpty()) 0f else small.measureText(meridiem) + px(7f)
        small.textSize = px(26f)
        small.fontFeatureSettings = null
        val dayW = small.measureText(day)
        val pad = px(32f)
        val gap = px(20f)
        panel(canvas, 0f, y, pad * 2 + timeW + tagW + gap * 2 + dayW, h, h / 2f)
        val baseline = y + h / 2f + px(13f)
        var x = pad
        write(canvas, words, x, baseline, 36f, semibold, align = Paint.Align.LEFT)
        x += timeW
        if (meridiem.isNotEmpty()) write(canvas, meridiem, x + px(7f), baseline, 18f, align = Paint.Align.LEFT)
        x += tagW + gap
        line.reset()
        line.isAntiAlias = true
        line.strokeWidth = px(2f)
        line.color = 0x38FFFFFF
        canvas.drawLine(x, y + px(20f), x, y + h - px(20f), line)
        write(canvas, day, x + gap, y + h / 2f + px(10f), 26f, color = 0xE6FFFFFF.toInt(), align = Paint.Align.LEFT)
    }

    private fun smallDial(canvas: Canvas) {
        val d = px(156f)
        val y = px(2f)
        val r = d / 2f
        panel(canvas, 0f, y, d, d, r)
        val cy = y + r
        line.reset()
        line.isAntiAlias = true
        line.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val major = i % 3 == 0
            val outer = r - px(10f)
            val inner = outer - px(if (major) 12f else 6f)
            line.strokeWidth = px(if (major) 3.5f else 2.5f)
            line.color = if (major) Palette.INK else 0x8CFFFFFF.toInt()
            val s = sin(a).toFloat()
            val c = cos(a).toFloat()
            canvas.drawLine(r + inner * s, cy - inner * c, r + outer * s, cy - outer * c, line)
        }
        val minutes = time.minute.toDouble()
        pointer(canvas, r, cy, (time.hour % 12 + minutes / 60.0) * 30.0, r * 0.46f, px(6f))
        pointer(canvas, r, cy, minutes * 6.0, r * 0.7f, px(4f))
        fill.reset()
        fill.isAntiAlias = true
        fill.color = ORANGE
        canvas.drawCircle(r, cy, px(7f), fill)
        fill.color = 0xFF10121A.toInt()
        canvas.drawCircle(r, cy, px(2.5f), fill)
        caption(weekday, d + px(24f), cy - px(4f), canvas, center = false, size = 34f)
        caption(date, d + px(24f), cy + px(36f), canvas, center = false, size = 26f, color = 0xE6FFFFFF.toInt())
    }

    private fun pointer(canvas: Canvas, cx: Float, cy: Float, degrees: Double, length: Float, width: Float) {
        val a = Math.toRadians(degrees)
        line.color = Palette.INK
        line.strokeWidth = width
        canvas.drawLine(cx, cy, cx + length * sin(a).toFloat(), cy - length * cos(a).toFloat(), line)
    }

    companion object {
        const val GLASS = 0
        const val FLIP = 1
        const val MOON = 2
        const val WEEKDAY_FACE = 3
        const val HALO = 4
        const val SERIF = 5
        const val STACK = 6
        const val MONTH = 7
        const val RING = 8
        const val DATE_CARD = 9
        const val LAST_APP = 10
        const val AMBIENT = 11
        const val MINI = 12
        const val PILL = 13
        const val DIAL = 14
        const val COUNT = 15
        private const val ORANGE = 0xFFFF9F0A.toInt()

        /** Where the large faces start in this view, and the room they were designed for. */
        private const val LARGE_TOP = 108f
        private const val LARGE_H = 480f
        private const val PANEL_TINT = 0x8C0E1118.toInt()

        /** Violet into pink into orange, the panels' one touch of colour. */
        private val ACCENT = intArrayOf(0xFF7C5CFF.toInt(), 0xFFE05CC8.toInt(), 0xFFFF9A5C.toInt())
        private const val GOLD = 0xFFE7C4A6.toInt()
        private const val SYNODIC = 29.530588853

        /** A new moon: 6 January 2000, 18:14 UTC. */
        private const val NEW_MOON = 947182440L
        private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())
        private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
        private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        private val SHORT_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
    }
}
