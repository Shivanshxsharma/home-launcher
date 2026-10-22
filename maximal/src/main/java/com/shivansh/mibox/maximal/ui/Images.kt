package com.shivansh.mibox.maximal.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.shivansh.mibox.maximal.R
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

object Decode {
    /** Bundled demo art by asset path; TMDB artwork by URL, downloaded once into [ImageFiles]. */
    private fun source(assets: AssetManager, path: String): ImageDecoder.Source =
        if (path.startsWith("https://")) ImageDecoder.createSource(ImageFiles.file(path)) else ImageDecoder.createSource(assets, path)

    /**
     * Decodes no larger than needed to cover [width] x [height] (never upscaled). [lowRam] lets
     * opaque JPEGs come back as RGB_565, half the memory of ARGB_8888.
     */
    fun image(
        assets: AssetManager,
        path: String,
        width: Int,
        height: Int,
        lowRam: Boolean = true,
        mutable: Boolean = false,
    ): Bitmap = ImageDecoder.decodeBitmap(source(assets, path)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = if (lowRam) ImageDecoder.MEMORY_POLICY_LOW_RAM else ImageDecoder.MEMORY_POLICY_DEFAULT
        decoder.isMutableRequired = mutable
        val size = info.size
        val scale = max(width.toFloat() / size.width, height.toFloat() / size.height)
        if (scale < 1f) {
            decoder.setTargetSize(max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
        }
    }
}

/**
 * Loads values off the main thread into views, with an LRU in front. A view remembers the key it
 * last asked for, so a recycled view drops stale results.
 */
class Loader<T : Any>(maxSize: Int, private val sizeOf: (T) -> Int, private val pool: Executor) {
    private val cache = object : LruCache<String, T>(maxSize) {
        override fun sizeOf(key: String, value: T) = this@Loader.sizeOf(value)
    }
    private val main = Handler(Looper.getMainLooper())

    /** Delivers on the main thread; `cached` is true when it came straight from memory. */
    fun load(target: View, key: String, produce: () -> T?, deliver: (value: T, cached: Boolean) -> Unit) {
        target.setTag(R.id.load_key, key)
        cache.get(key)?.let {
            deliver(it, true)
            return
        }
        pool.execute {
            val value = try {
                produce()
            } catch (_: Exception) {
                null
            } ?: return@execute
            main.post {
                cache.put(key, value)
                if (target.getTag(R.id.load_key) == key) deliver(value, false)
            }
        }
    }

    fun clear() = cache.evictAll()
}

/** A bitmap drawn with rounded corners through a shader: one draw call, no clipping. */
class RoundedImage(private val bitmap: Bitmap, private val radius: Float) : Drawable() {
    private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = this@RoundedImage.shader }
    private val rect = RectF()
    private val matrix = Matrix()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        // Centre-crop the picture into the bounds.
        val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(rect.left + (rect.width() - bitmap.width * scale) / 2f, rect.top + (rect.height() - bitmap.height * scale) / 2f)
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

/** Rail posters: decoded at their on-screen size (a little larger, for the focus lift) as RGB_565. */
class Posters(private val assets: AssetManager) {
    private val loader = Loader<Bitmap>(12 * 1024 * 1024, { it.allocationByteCount }, Workers.images)

    fun load(view: ImageView, path: String, width: Int, height: Int, radius: Float) {
        view.setImageDrawable(GradientDrawable().apply {
            cornerRadius = radius
            setColor(Palette.CARD)
        })
        val w = (width * LIFT).roundToInt()
        val h = (height * LIFT).roundToInt()
        loader.load(view, "$path@$w", {
            Decode.image(assets, path, w, h).also { it.prepareToDraw() }
        }) { bitmap, cached ->
            view.setImageDrawable(RoundedImage(bitmap, radius))
            if (!cached) {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(220).setStartDelay(0).setInterpolator(Motion.EASE)
            }
        }
    }

    fun trim() = loader.clear()

    private companion object {
        const val LIFT = 1.08f
    }
}
