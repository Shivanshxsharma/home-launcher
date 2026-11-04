package com.shivansh.mytv.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.shivansh.mibox.data.MediaType
import com.shivansh.mibox.data.Show
import com.shivansh.mytv.R

/** The words over the picture: a small kicker, the title, a meta line and a few lines of story. */
class Hero(private val root: View) {
    val resources: Resources = root.resources
    private val kicker: TextView = root.findViewById(R.id.hero_kicker)
    private val title: TextView = root.findViewById(R.id.hero_title)
    private val meta: TextView = root.findViewById(R.id.hero_meta)
    private val overview: TextView = root.findViewById(R.id.hero_overview)
    private var shownTitle = ""

    fun show(kicker: String, title: String, meta: CharSequence, overview: String, @Suppress("UNUSED_PARAMETER") hint: String?) {
        this.kicker.text = kicker
        this.title.text = title
        this.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(if (title.length > 26) 60f else if (title.length > 14) 76f else 92f))
        this.meta.text = meta
        this.meta.setTextColor(Palette.INK)
        this.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        this.overview.text = overview
        this.overview.visibility = if (overview.isEmpty()) View.GONE else View.VISIBLE
        if (title != shownTitle) {
            // A new title rises into place, while the row keeps moving under it.
            shownTitle = title
            root.animate().cancel()
            root.translationY = Ui.px(14f)
            root.animate().translationY(0f).setDuration(Motion.ms(420)).setStartDelay(0).setInterpolator(Motion.SETTLE).withEndAction(null)
        }
    }

    /** A status in the meta line, e.g. while waiting for the phone. */
    fun status(text: String, color: Int) {
        meta.text = text
        meta.setTextColor(color)
        meta.visibility = View.VISIBLE
    }

    companion object {
        /** "★ 8.5 · 2022 · 2 seasons · Animation", the star in gold. */
        fun metaFor(show: Show): CharSequence {
            val parts = ArrayList<CharSequence>()
            show.scoreLabel.takeIf { it.isNotEmpty() }?.let { label ->
                parts += SpannableString("★ $label").apply {
                    setSpan(ForegroundColorSpan(Palette.STAR), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            parts += listOf(show.year, show.length, show.genres.substringBefore(" · ")).filter { it.isNotEmpty() }
            return SpannableStringBuilder().apply {
                parts.forEach { part ->
                    if (isNotEmpty()) append("  ·  ")
                    append(part)
                }
            }
        }

        fun kickerFor(show: Show, film: String, series: String): String {
            val kind = if (show.mediaType == MediaType.MOVIE) film else series
            return if (show.service.isEmpty()) kind else "$kind  ·  ${show.service}"
        }
    }
}

/** The QR code on white, for setting up TMDB from a phone, with a label under it. */
class QrCard(private val root: View) {
    private val code: ImageView = root.findViewById(R.id.qr_code)
    private val label: TextView = root.findViewById(R.id.qr_label)

    /** Null [bitmap] hides the card, e.g. while the code is being made. */
    fun show(bitmap: Bitmap?, text: CharSequence?) {
        code.setImageBitmap(bitmap)
        label.text = text
        root.visibility = if (bitmap == null) View.INVISIBLE else View.VISIBLE
    }
}

/** Posters decoded at their on-screen size, with an LRU in front. */
class Posters {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String, width: Int, height: Int) {
        val key = "$url@$width"
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        Worker.net.execute {
            val bitmap = runCatching { Decode.url(url, width, height).also { it.prepareToDraw() } }.getOrNull() ?: return@execute
            main.post {
                cache.put(key, bitmap)
                if (view.tag == key) {
                    view.setImageBitmap(bitmap)
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(Motion.ms(240)).setStartDelay(0).setInterpolator(Motion.EASE)
                }
            }
        }
    }

    fun trim() = cache.evictAll()
}
