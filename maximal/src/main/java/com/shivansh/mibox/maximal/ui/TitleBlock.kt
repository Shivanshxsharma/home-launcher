package com.shivansh.mibox.maximal.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.shivansh.mibox.maximal.R

/**
 * The words over the art: genre tags, the big wide title and the runtime at the bottom left, and
 * the storyline at the bottom right. A new title dips out and rises back in as the art crossfades.
 */
class TitleBlock(private val block: View, private val story: View) {
    class Words(val tags: String, val title: String, val runtime: CharSequence, val storyline: String)

    private val tags: TextView = block.findViewById(R.id.title_tags)
    private val title: TextView = block.findViewById(R.id.title_text)
    private val runtime: TextView = block.findViewById(R.id.title_runtime)
    private val storyText: TextView = story.findViewById(R.id.storyline_text)
    private val main = Handler(Looper.getMainLooper())
    private var shown: Words? = null

    /** What [setShown] last asked for. */
    private var titleOn = true
    private var storyOn = true

    /**
     * The words change on a timer rather than at the end of the dip: a fade started elsewhere (the
     * panels coming back after a preview) cancels the dip, and a cancelled animation never runs its
     * end action, which would leave the old title on screen.
     */
    private val swap = Runnable {
        pending = false
        shown?.let(::bind)
        rise(block, titleOn, delay = 0)
        rise(story, storyWanted(), delay = 80)
    }

    /** A dip is under way; its swap will show the latest words and visibility. */
    private var pending = false

    /** [fresh] is false when the same title just gained details (runtime, storyline): no dip. */
    fun show(words: Words, fresh: Boolean) {
        val same = shown?.title == words.title
        shown = words
        if (same || !fresh) {
            if (pending) return
            bind(words)
            // The storyline may have just arrived (or gone).
            story.fadeTo(if (storyWanted()) 1f else 0f, 300)
            return
        }
        main.removeCallbacks(swap)
        for (view in arrayOf(block, story)) {
            view.animate().alpha(0f).translationY(0f).setDuration(DIP_MS).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction(null)
        }
        pending = true
        main.postDelayed(swap, DIP_MS)
    }

    /** Search hides the words while its keyboard has the middle; the storyline goes in ambient mode. */
    fun setShown(title: Boolean, storyline: Boolean) {
        titleOn = title
        storyOn = storyline
        if (pending) return
        block.fadeTo(if (title) 1f else 0f, 300)
        story.fadeTo(if (storyWanted()) 1f else 0f, 300)
    }

    private fun storyWanted() = titleOn && storyOn && storyText.text.isNotEmpty()

    private fun rise(view: View, on: Boolean, delay: Long) {
        view.animate().cancel()
        if (!on) {
            view.alpha = 0f
            view.translationY = 0f
            view.visibility = View.INVISIBLE
            return
        }
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = Ui.px(22f)
        view.animate().alpha(1f).translationY(0f).setDuration(520).setStartDelay(delay).setInterpolator(Motion.SETTLE).withEndAction(null)
    }

    /**
     * The largest size at which the title fits in two lines without breaking a word; long titles
     * step down rather than end in an ellipsis.
     */
    private fun fit(text: String) {
        val width = (title.layoutParams.width.takeIf { it > 0 } ?: block.layoutParams.width).toFloat()
        val words = text.uppercase().split(' ').filter { it.isNotEmpty() }
        val paint = title.paint
        for (size in SIZES) {
            title.textSize = size
            val space = paint.measureText(" ")
            if (words.any { paint.measureText(it) > width }) continue
            var lines = 1
            var used = 0f
            for (word in words) {
                val w = paint.measureText(word)
                used = if (used == 0f) w else if (used + space + w <= width) used + space + w else {
                    lines++
                    w
                }
            }
            if (lines <= 2) return
        }
    }

    private fun bind(words: Words) {
        tags.text = words.tags
        tags.visibility = if (words.tags.isEmpty()) View.GONE else View.VISIBLE
        title.text = words.title
        fit(words.title)
        runtime.text = words.runtime
        runtime.visibility = if (words.runtime.isEmpty()) View.GONE else View.VISIBLE
        storyText.text = words.storyline
    }

    private companion object {
        const val DIP_MS = 120L

        /** Title sizes (sp), largest first. */
        val SIZES = floatArrayOf(54f, 48f, 42f, 37f, 33f, 29f, 26f)
    }
}
