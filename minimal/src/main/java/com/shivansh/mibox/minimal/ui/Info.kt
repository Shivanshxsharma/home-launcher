package com.shivansh.mibox.minimal.ui

import android.content.res.Resources
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.minimal.R

/** The words over Favs' backdrop: a kicker on orange, the big title, a meta line, a few lines, a hint. */
class Info(root: View) {
    val resources: Resources = root.resources
    private val kicker: TextView = root.findViewById(R.id.kicker)
    private val title: TextView = root.findViewById(R.id.title)
    private val meta: TextView = root.findViewById(R.id.meta)
    private val overview: TextView = root.findViewById(R.id.overview)
    private val hint: View = root.findViewById(R.id.hint)
    private val hintText: TextView = root.findViewById(R.id.hint_text)
    private var shownTitle = ""

    fun show(kicker: String, title: String, meta: CharSequence, overview: String, hint: String?) {
        this.kicker.text = kicker
        if (title != shownTitle) {
            shownTitle = title
            this.title.fit(title, max = 76f, min = 44f, lines = 3)
        }
        this.meta.text = meta
        this.meta.setTextColor(Palette.INK)
        this.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        this.overview.text = overview
        this.overview.visibility = if (overview.isEmpty()) View.GONE else View.VISIBLE
        hintText.text = hint.orEmpty()
        this.hint.visibility = if (hint.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** A status in the meta line, e.g. while waiting for the phone. */
    fun status(text: String, color: Int) {
        meta.text = text.uppercase()
        meta.setTextColor(color)
        meta.visibility = View.VISIBLE
    }

    companion object {
        /** "★ 8.5  /  2022  /  2 SEASONS  /  ANIMATION": the first genre only, the star in orange. */
        fun metaFor(show: Show): CharSequence {
            val parts = ArrayList<CharSequence>()
            show.scoreLabel.takeIf { it.isNotEmpty() }?.let { label ->
                parts += SpannableString("★ $label").apply {
                    setSpan(ForegroundColorSpan(Palette.ORANGE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            parts += listOf(show.year, show.length.uppercase(), show.genres.substringBefore(" · ").uppercase()).filter { it.isNotEmpty() }
            return SpannableStringBuilder().apply {
                parts.forEach { part ->
                    if (isNotEmpty()) append("  /  ")
                    append(part)
                }
            }
        }
    }
}
