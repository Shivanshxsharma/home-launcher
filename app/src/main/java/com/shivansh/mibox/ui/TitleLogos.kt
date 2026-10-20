package com.shivansh.mibox.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.min

/**
 * TMDB title logos for the hero and the Shows and Movies tabs: decoded off the main thread and
 * kept for reuse. Logos with dark lettering are refused, since they vanish against shaded art;
 * those titles keep their typeset names.
 */
class TitleLogos(private val assets: AssetManager) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val refused = HashSet<String>()
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val main = Handler(Looper.getMainLooper())

    fun get(url: String): Bitmap? = cache.get(url)

    /** Loads [url] if needed; [done] runs on the main thread with the logo, or null if there is none to use. */
    fun fetch(url: String, done: (Bitmap?) -> Unit = {}) {
        cache.get(url)?.let {
            done(it)
            return
        }
        if (url in refused) {
            done(null)
            return
        }
        waiting[url]?.let {
            it += done
            return
        }
        waiting[url] = mutableListOf(done)
        Workers.images.execute {
            val art = runCatching { Decode.asset(assets, url, Ui.px(MAX_W.toInt()), Ui.px(MAX_H.toInt()), lowRam = false) }
                .getOrNull()?.takeIf(::readable)
            art?.prepareToDraw()
            main.post {
                if (art == null) refused += url else cache.put(url, art)
                waiting.remove(url)?.forEach { it(art) }
            }
        }
    }

    /**
     * Shows [art] in [logo] fitted to a [boxW] x [boxH] box (mockup px), keeping its shape, and hides
     * the typeset [title]. With no art, the title comes back.
     */
    fun show(logo: ImageView, title: TextView, art: Bitmap?, boxW: Float, boxH: Float) {
        if (art == null) {
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            title.visibility = View.VISIBLE
            return
        }
        val scale = min(Ui.px(boxW) / art.width, Ui.px(boxH) / art.height)
        logo.layoutParams = logo.layoutParams.apply {
            width = (art.width * scale).toInt()
            height = (art.height * scale).toInt()
        }
        logo.setImageBitmap(art)
        logo.visibility = View.VISIBLE
        title.visibility = View.GONE
    }

    private fun readable(art: Bitmap): Boolean {
        val w = art.width
        val h = art.height
        val pixels = IntArray(w * h)
        art.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0f
        var count = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            if (Color.alpha(p) > 160) {
                sum += (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f
                count++
            }
            i += 7
        }
        return count > 0 && sum / count > MIN_LUMA
    }

    private companion object {
        /** Decoded no larger than the biggest box a logo is shown in, in mockup px. */
        const val MAX_W = 560f
        const val MAX_H = 176f
        const val MIN_LUMA = 0.3f
        const val CACHE_BYTES = 8 * 1024 * 1024
    }
}
