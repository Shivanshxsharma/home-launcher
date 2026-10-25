package com.shivansh.mibox.minimal.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.shivansh.mibox.minimal.R
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

object Decode {
    /**
     * TMDB artwork by URL (downloaded once into [ImageFiles]), decoded no larger than needed to
     * cover [width] x [height] and never upscaled. Opaque JPEGs come back as RGB_565, half the
     * memory of ARGB_8888; [mutable] when shading is to be drawn into it.
     */
    fun image(url: String, width: Int, height: Int, mutable: Boolean = false): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ImageFiles.file(url))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
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
 * last asked for, so a reused view drops stale results.
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

/** Posters for the Favs row, decoded at their on-screen size. */
class Posters {
    private val loader = Loader<Bitmap>(10 * 1024 * 1024, { it.allocationByteCount }, Workers.images)

    fun load(view: ImageView, url: String, width: Int, height: Int) {
        view.setImageDrawable(null)
        loader.load(view, "$url@$width", {
            Decode.image(url, width, height).also { it.prepareToDraw() }
        }) { bitmap, cached ->
            view.setImageBitmap(bitmap)
            if (!cached) {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(200).setStartDelay(0).setInterpolator(Motion.EASE)
            }
        }
    }

    fun trim() = loader.clear()
}
