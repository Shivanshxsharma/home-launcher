package com.shivansh.mibox.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.shivansh.mibox.R
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

object Decode {
    /**
     * Decodes an asset no larger than needed to cover [width] x [height] (never upscaled).
     * [lowRam] lets opaque JPEGs come back as RGB_565, half the memory of ARGB_8888.
     */
    fun asset(
        assets: AssetManager,
        path: String,
        width: Int,
        height: Int,
        lowRam: Boolean = true,
        mutable: Boolean = false,
    ): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = if (lowRam) ImageDecoder.MEMORY_POLICY_LOW_RAM else ImageDecoder.MEMORY_POLICY_DEFAULT
        decoder.isMutableRequired = mutable
        val size = info.size
        val scale = max(width.toFloat() / size.width, height.toFloat() / size.height)
        if (scale < 1f) {
            decoder.setTargetSize(
                max(1, (size.width * scale).roundToInt()),
                max(1, (size.height * scale).roundToInt()),
            )
        }
    }
}

/**
 * Loads values off the main thread into views, with an LRU in front.
 * A view remembers the key it last asked for, so a recycled view drops stale results.
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

/**
 * Card art from the bundled demo assets: decoded at card size as RGB_565, with the card's scrim
 * and hairline baked in ([CardFinish]) and drawn with rounded corners ([RoundedImage]), so each
 * picture costs one draw and needs no clip.
 */
class Images(private val assets: AssetManager) {
    private val loader = Loader<Bitmap>(10 * 1024 * 1024, { it.allocationByteCount }, Workers.images)

    fun card(view: ImageView, path: String, spec: CardSpec, finish: CardFinish) {
        // Decode a touch larger than the card so the focus lift stays sharp.
        val w = (spec.width * spec.lift).roundToInt()
        val h = (spec.height * spec.lift).roundToInt()
        // The picture is the card's face, so it also stands in for it while it loads.
        view.setImageDrawable(GradientDrawable().apply {
            cornerRadius = spec.radius
            setColor(Palette.CARD)
        })
        loader.load(view, "$path@$w:${finish.key}", {
            Decode.asset(assets, path, w, h, mutable = true).also {
                finish.apply(it)
                it.prepareToDraw()
            }
        }) { bitmap, cached ->
            view.setImageDrawable(RoundedImage(bitmap, spec.radius))
            if (!cached) {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(220).setStartDelay(0).setInterpolator(Motion.EASE)
            }
        }
    }

    fun trim() = loader.clear()
}
