package com.shivansh.mibox.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A CSS `linear-gradient(angle, ...)`: 0deg runs bottom to top, 90deg left to right.
 * With a [radius] it fills a rounded rect, so a card face needs no clip.
 */
class LinearScrim(
    private val cssAngle: Float,
    private val colors: IntArray,
    private val stops: FloatArray,
    private val radius: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        paint.shader = shader(cssAngle, colors, stops, bounds.width().toFloat(), bounds.height().toFloat()).apply {
            setLocalMatrix(Matrix().apply { setTranslate(bounds.left.toFloat(), bounds.top.toFloat()) })
        }
    }

    override fun draw(canvas: Canvas) {
        if (radius > 0f) canvas.drawRoundRect(rect, radius, radius, paint) else canvas.drawRect(rect, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** The gradient for a [width] x [height] box at the origin. */
        fun shader(cssAngle: Float, colors: IntArray, stops: FloatArray, width: Float, height: Float): LinearGradient {
            val rad = Math.toRadians(cssAngle.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val half = (abs(width * dx) + abs(height * dy)) / 2f
            val cx = width / 2f
            val cy = height / 2f
            return LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, stops, Shader.TileMode.CLAMP)
        }
    }
}

/**
 * A card's picture with its rounded corners drawn by a bitmap shader: one draw, no clipping.
 * The bitmap is stretched to the bounds (it is decoded at the card's aspect ratio).
 */
class RoundedImage(private val bitmap: Bitmap, private val radius: Float) : Drawable() {
    private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = this@RoundedImage.shader }
    private val rect = RectF()
    private val matrix = Matrix()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        matrix.setRectToRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), rect, Matrix.ScaleToFit.FILL)
        shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) = canvas.drawRoundRect(rect, radius, radius, paint)

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Bakes a card's scrim (for text or marks drawn over the art) into the decoded picture itself. */
class CardFinish(private val cssAngle: Float, private val from: Float, private val end: Float) {
    val key = "$cssAngle:$from:$end"

    fun apply(bitmap: Bitmap) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val scrim = Paint().apply {
            isDither = true
            shader = LinearScrim.shader(cssAngle, intArrayOf(Palette.alpha(0, from), 0), floatArrayOf(0f, end), w, h)
        }
        Canvas(bitmap).drawRect(0f, 0f, w, h, scrim)
    }
}

/**
 * The glass keyline: a hairline rim lit from the top left, as if light catches the edge.
 * [strength] scales the whole rim; focus brings it up by fading the view, not by redrawing.
 */
class Keyline(private val radius: Float, private val width: Float, private val strength: Float = 1f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@Keyline.width
    }
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        rect.inset(width / 2f, width / 2f)
        fun white(a: Float) = Palette.alpha(0xFFFFFF, (a * strength).coerceAtMost(1f))
        paint.shader = LinearScrim.shader(
            135f,
            intArrayOf(white(0.75f), white(0.16f), white(0.06f), white(0.32f)),
            floatArrayOf(0f, 0.32f, 0.68f, 1f),
            bounds.width().toFloat(),
            bounds.height().toFloat(),
        ).apply { setLocalMatrix(Matrix().apply { setTranslate(bounds.left.toFloat(), bounds.top.toFloat()) }) }
    }

    override fun draw(canvas: Canvas) {
        val r = max(0f, radius - width / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Light on a focused card, kept inside its rounded shape without clipping: a soft glare that
 * drifts as the card tilts ([glareX], [glare]) and a single bright band that sweeps across it
 * once on focus ([sweep], -1 when idle). Both are fixed shaders moved by their local matrix.
 */
class Sheen(private val radius: Float, private val bandAlpha: Float) : Drawable() {
    var glare = 0f
        set(value) {
            field = value
            invalidateSelf()
        }
    var glareX = 0.3f
        set(value) {
            field = value
            invalidateSelf()
        }
    var sweep = -1f
        set(value) {
            field = value
            invalidateSelf()
        }

    private val rect = RectF()
    private val glarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glareShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Palette.alpha(0xFFFFFF, 0.22f), Palette.alpha(0xFFFFFF, 0.07f), 0x00FFFFFF),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP,
    )
    private var bandShader: LinearGradient? = null
    private val matrix = Matrix()

    init {
        glarePaint.shader = glareShader
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        // A band a third of the card wide, leaning like light through a window.
        val w = bounds.width().toFloat()
        bandShader = LinearGradient(
            0f, 0f, w * 0.34f, -w * 0.06f,
            intArrayOf(0x00FFFFFF, Palette.alpha(0xFFFFFF, bandAlpha), 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        bandPaint.shader = bandShader
    }

    override fun draw(canvas: Canvas) {
        val w = rect.width()
        val h = rect.height()
        if (glare > 0.01f) {
            matrix.setScale(w * 1.05f, w * 1.05f)
            matrix.postTranslate(rect.left + w * glareX, rect.top - h * 0.35f)
            glareShader.setLocalMatrix(matrix)
            glarePaint.alpha = (glare * 255).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, radius, radius, glarePaint)
        }
        val band = bandShader
        if (band != null && sweep > -0.6f && sweep < 1.6f) {
            matrix.setTranslate(rect.left + w * (sweep - 0.17f), rect.top)
            band.setLocalMatrix(matrix)
            canvas.drawRoundRect(rect, radius, radius, bandPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        glarePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glarePaint.colorFilter = colorFilter
        bandPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Soft glows and shadows (CSS box-shadow) baked once per card size into a small alpha mask.
 * Views tint and stretch it; nothing is blurred while drawing.
 */
object Halo {
    private const val QUARTER = 4f
    private val cache = HashMap<String, Bitmap>()

    /** How far past the card the mask reaches, on every side. */
    val pad: Int get() = Ui.px(128)

    /** [blur] and [spread] are CSS box-shadow values in device px. */
    fun mask(width: Int, height: Int, radius: Float, blur: Float = Ui.px(90f), spread: Float = -Ui.px(6f)): Bitmap {
        val key = "$width:$height:$radius:$blur:$spread"
        return cache.getOrPut(key) {
            val p = pad
            val bitmap = Bitmap.createBitmap(
                ceil((width + 2 * p) / QUARTER).toInt(),
                ceil((height + 2 * p) / QUARTER).toInt(),
                Bitmap.Config.ALPHA_8,
            )
            // CSS blur radius is two sigmas; BlurMaskFilter takes a radius where sigma = 0.57735 r + 0.5.
            val sigma = blur / 2f / QUARTER
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(max(0.5f, (sigma - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
            }
            val inset = -spread / QUARTER
            val r = radius / QUARTER
            Canvas(bitmap).drawRoundRect(
                p / QUARTER + inset, p / QUARTER + inset,
                (p + width) / QUARTER - inset, (p + height) / QUARTER - inset,
                r, r, paint,
            )
            bitmap
        }
    }
}
