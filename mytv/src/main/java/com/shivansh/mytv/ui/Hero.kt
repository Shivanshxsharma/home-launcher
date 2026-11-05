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

/**
 * The words over the picture: a kicker, the title's own logo (or its name), a meta line and a few
 * lines of story. Moving along a row doesn't touch them until focus rests; then they fade across,
 * so the text never flickers through every title on the way.
 */
class Hero(root: View, private val posters: Posters) {
    val resources: Resources = root.resources
    private val body: View = root.findViewById(R.id.hero_body)
    private val kicker: TextView = root.findViewById(R.id.hero_kicker)
    private val logo: ImageView = root.findViewById(R.id.hero_logo)
    private val title: TextView = root.findViewById(R.id.hero_title)
    private val meta: TextView = root.findViewById(R.id.hero_meta)
    private val overview: TextView = root.findViewById(R.id.hero_overview)
    private val main = Handler(Looper.getMainLooper())
    private var pending: (() -> Unit)? = null
    private var shownId: String? = null
    private val swap = Runnable {
        pending?.invoke()
        pending = null
        body.translationY = Ui.px(16f)
        body.animate().alpha(1f).translationY(0f).setDuration(Motion.ms(360)).setStartDelay(0).setInterpolator(Motion.SETTLE).withEndAction(null)
    }

    /** At once, for set-up and empty states. */
    fun show(kicker: String, title: String, meta: CharSequence, overview: String, @Suppress("UNUSED_PARAMETER") hint: String?) {
        main.removeCallbacks(swap)
        pending = null
        shownId = null
        body.animate().cancel()
        body.alpha = 1f
        body.translationY = 0f
        logo.visibility = View.GONE
        logo.tag = null
        words(kicker, title, meta, overview)
    }

    /** A title, once focus has rested on it for a moment. */
    fun title(show: Show, kicker: String) {
        if (show.id == shownId) {
            main.removeCallbacks(swap)
            if (pending != null) {
                pending = null
                body.animate().alpha(1f).setDuration(Motion.ms(200)).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction(null)
            }
            return
        }
        val first = shownId == null
        main.removeCallbacks(swap)
        pending = {
            shownId = show.id
            words(kicker, show.title, metaFor(show), show.overview)
            logo.visibility = View.GONE
            logo.tag = null
            val url = show.logo
            if (url != null) {
                posters.logo(logo, url) { bitmap ->
                    logo.setImageBitmap(bitmap)
                    logo.visibility = View.VISIBLE
                    title.visibility = View.GONE
                }
            }
        }
        if (first) {
            swap.run()
            return
        }
        body.animate().alpha(0f).setDuration(Motion.ms(110)).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction(null)
        main.postDelayed(swap, REST_MS)
    }

    private fun words(kicker: String, name: String, meta: CharSequence, overview: String) {
        this.kicker.text = kicker
        title.visibility = View.VISIBLE
        title.text = name
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(if (name.length > 26) 60f else if (name.length > 14) 76f else 92f))
        this.meta.text = meta
        this.meta.setTextColor(Palette.INK)
        this.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        this.overview.text = overview
        this.overview.visibility = if (overview.isEmpty()) View.GONE else View.VISIBLE
    }

    /** A status in the meta line, e.g. while waiting for the phone. */
    fun status(text: String, color: Int) {
        meta.text = text
        meta.setTextColor(color)
        meta.visibility = View.VISIBLE
    }

    companion object {
        private const val REST_MS = 190L

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

/** Posters and title logos decoded at their on-screen size, with an LRU in front. */
class Posters {
    private val cache = object : LruCache<String, Bitmap>(14 * 1024 * 1024) {
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
                    view.animate().alpha(1f).setDuration(Motion.ms(280)).setStartDelay(0).setInterpolator(Motion.EASE)
                }
            }
        }
    }

    /** A title logo, only if it is still wanted when it arrives. */
    fun logo(view: ImageView, url: String, deliver: (Bitmap) -> Unit) {
        view.tag = url
        cache.get(url)?.let {
            deliver(it)
            return
        }
        Worker.net.execute {
            val bitmap = runCatching { Decode.url(url, 640, 1).also { it.prepareToDraw() } }.getOrNull() ?: return@execute
            main.post {
                cache.put(url, bitmap)
                if (view.tag == url) deliver(bitmap)
            }
        }
    }

    fun trim() = cache.evictAll()
}
