package com.shivansh.mibox.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.shivansh.mibox.R
import com.shivansh.mibox.data.HeroSlide
import com.shivansh.mibox.data.MediaType
import com.shivansh.mibox.data.Show

/**
 * The hero banner: the user's shows one at a time, over a full-bleed backdrop or the show's muted
 * preview. Play, Add to List and Trailer sit side by side; left and right move between them, and
 * past either end change slides. On its own it moves on every [SLIDE_MS] while focused. In ambient mode
 * the details step away and the title settles small at the bottom left, leaving the preview to
 * fill the screen.
 */
class Hero(
    private val root: View,
    private var slides: List<HeroSlide>,
    private val logos: TitleLogos,
    private val onSlide: (HeroSlide) -> Unit,
) {
    private val tag: View = root.findViewById(R.id.hero_tag)
    private val dot: View = root.findViewById(R.id.hero_dot)
    private val provider: TextView = root.findViewById(R.id.hero_provider)
    private val status: TextView = root.findViewById(R.id.hero_status)
    private val titleBox: View = root.findViewById(R.id.hero_title_box)
    private val title: TextView = root.findViewById(R.id.hero_title)
    private val logo: ImageView = root.findViewById(R.id.hero_logo)
    private val details: View = root.findViewById(R.id.hero_details)
    private val meta: View = root.findViewById(R.id.hero_meta)
    private val next: TextView = root.findViewById(R.id.hero_next)
    private val genre: TextView = root.findViewById(R.id.hero_genre)
    private val year: TextView = root.findViewById(R.id.hero_year)
    private val rating: TextView = root.findViewById(R.id.hero_rating)
    private val score: TextView = root.findViewById(R.id.hero_score)
    private val scoreGap: View = root.findViewById(R.id.hero_score_gap)
    private val blurb: TextView = root.findViewById(R.id.hero_blurb)
    private val actions: View = root.findViewById(R.id.hero_actions)
    private val playWrap: View = root.findViewById(R.id.hero_play_wrap)
    private val playIcon: ImageView = root.findViewById(R.id.hero_play_icon)
    private val playLabel: TextView = root.findViewById(R.id.hero_play_label)
    private val playGlow: ImageView = root.findViewById(R.id.hero_play_glow)
    private val listIcon: ImageView = root.findViewById(R.id.hero_list_icon)
    private val listLabel: TextView = root.findViewById(R.id.hero_list_label)
    private val dots: HeroDots = root.findViewById(R.id.hero_dots)
    val playButton: View = root.findViewById(R.id.hero_play)
    val listButton: View = root.findViewById(R.id.hero_list)
    val trailerButton: View = root.findViewById(R.id.hero_trailer)

    /** Whether a show is on the user's watchlist; the activity knows, including changes in flight. */
    var inList: (Show) -> Boolean = { false }

    private val handler = Handler(Looper.getMainLooper())
    private val advance = Runnable { step(1) }
    private var running = false
    private var leaving = false
    private var ambient = false
    private var ambientAnim: ObjectAnimator? = null

    /** A hero button: the view that takes focus, the one that grows, and its lit and resting faces. */
    private class Button(val view: View, val grow: View, val icon: ImageView, val label: TextView, val lit: Drawable, val rest: Drawable)

    // Each view gets its own lit (cream) and resting (glass) drawables.
    private val buttons = listOf(
        button(playButton, playWrap, playIcon, playLabel),
        button(listButton, listButton, root.findViewById(R.id.hero_list_icon), root.findViewById(R.id.hero_list_label)),
        button(trailerButton, trailerButton, root.findViewById(R.id.hero_trailer_icon), root.findViewById(R.id.hero_trailer_label)),
    )

    private fun button(view: View, grow: View, icon: ImageView, label: TextView) = Button(
        view, grow, icon, label,
        root.resources.getDrawable(R.drawable.pill_cream, null),
        Glass.control(Ui.px(68f), Palette.alpha(0xFFFFFF, 0.16f)),
    )

    var index = 0
        private set
    val current: HeroSlide get() = slides[index]

    val listFocused: Boolean get() = listButton.isFocused

    /** One of the hero's buttons has focus (rather than the tab bar or a row). */
    val buttonFocused: Boolean get() = buttons.any { it.view.isFocused }

    init {
        dots.count = slides.size
        for (button in buttons) {
            button.view.setOnFocusChangeListener { _, focused ->
                val scale = if (focused) 1.06f else 1f
                button.grow.animate().scaleX(scale).scaleY(scale).setDuration(320).setStartDelay(0).setInterpolator(Motion.LIFT)
                paintButtons()
            }
        }
        paintButtons()
        // The Play glow is baked for the button's size, which changes with its label.
        playButton.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Posted: resizing the glow is a layout request, not allowed mid-layout.
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) v.post { updateGlow(v.width, v.height) }
        }
        prefetchLogos()
    }

    /** New content: keep the current slide if it's still there, else start over. */
    fun setSlides(newSlides: List<HeroSlide>) {
        if (newSlides.isEmpty() || newSlides == slides) return
        // A slide change in flight would otherwise land on the old list. Its exit has already faded
        // the words out, so the slide must make a full entrance even when it is kept.
        val interrupted = leaving
        handler.removeCallbacks(swap)
        leaving = false
        val keep = newSlides.indexOfFirst { it.show.id == current.show.id }
        slides = newSlides
        dots.count = slides.size
        prefetchLogos()
        bind(if (keep >= 0) keep else 0, entrance = keep < 0 || interrupted)
        if (keep < 0) onSlide(current)
        restartTimer()
    }

    fun bind(i: Int, entrance: Boolean) {
        index = i.coerceIn(0, slides.size - 1)
        val slide = slides[index]
        val show = slide.show
        dot.backgroundTintList = ColorStateList.valueOf(show.serviceColor)
        provider.text = show.service
        status.text = slide.status
        title.text = show.title
        title.showTitle(show.style, 150f)
        title.lineBox(0.9f, top = 16f, bottom = 0f)
        showLogo(show, fade = false)
        next.text = slide.next
        genre.text = slide.genre
        year.text = slide.year
        rating.text = slide.rating
        rating.visibility = if (slide.rating.isEmpty()) View.GONE else View.VISIBLE
        score.text = scoreText(show)
        score.visibility = if (score.text.isEmpty()) View.GONE else View.VISIBLE
        scoreGap.visibility = score.visibility
        blurb.text = slide.blurb
        playLabel.text = slide.play
        playGlow.drawable?.setTint(Palette.alpha(show.tint, 0.5f))
        // Demo shows aren't on TMDB, so there is no list to add them to; not every show has a trailer.
        reveal(listButton, show.tmdbId > 0)
        reveal(trailerButton, show.trailers.isNotEmpty())
        refreshList()
        dots.select(index)
        if (entrance) enter()
    }

    /** Re-reads whether the current show is on the watchlist. */
    fun refreshList() {
        val show = current.show
        val on = inList(show)
        listIcon.setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_plus)
        listLabel.setText(
            when {
                !on -> R.string.add_to_list
                show.mediaType == MediaType.MOVIE -> R.string.in_watchlist
                else -> R.string.in_my_shows
            },
        )
    }

    fun focusPlay() {
        playButton.requestFocus()
    }

    /** Left or right across the buttons this show has; false past either end (time to change slides). */
    fun moveButton(delta: Int): Boolean {
        val shown = buttons.filter { it.view.visibility == View.VISIBLE }
        val at = shown.indexOfFirst { it.view.isFocused }
        val next = if (at < 0) 0 else at + delta
        if (next !in shown.indices) return false
        shown[next].view.requestFocus()
        return true
    }

    private fun reveal(button: View, on: Boolean) {
        if (!on && button.isFocused) playButton.requestFocus()
        button.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Next or previous slide: the current one slips away to the left, the next rises in. */
    fun step(delta: Int) {
        index = Math.floorMod(index + delta, slides.size)
        onSlide(current)
        restartTimer()
        // Already on its way out: the pending swap will show whichever slide is current by then.
        if (leaving) return
        leaving = true
        val out = Ui.px(-28f) * delta
        for (view in arrayOf(tag, titleBox, details)) {
            view.animate().alpha(0f).translationX(out).setDuration(EXIT_MS).setStartDelay(0).setInterpolator(Motion.EASE)
        }
        // A timer rather than an animation end action: a cancelled animation never runs its end action.
        handler.postDelayed(swap, EXIT_MS)
    }

    private val swap = Runnable {
        leaving = false
        bind(index, entrance = true)
    }

    /** Moving between the buttons restarts the slide's clock, so it doesn't change under the remote. */
    fun holdSlide() = restartTimer()

    /** Auto-advance runs only while the hero has focus and the launcher is on screen. */
    fun setRunning(on: Boolean) {
        if (on == running) return
        running = on
        if (on) {
            restartTimer()
        } else {
            handler.removeCallbacks(advance)
            dots.hold()
        }
    }

    /** Ambient mode: details away, the title small at the bottom left, the preview in charge. */
    fun setAmbient(on: Boolean) {
        ambient = on
        details.fadeTo(if (on) 0f else 1f, if (on) 500 else 400)
        ambientAnim?.cancel()
        ambientAnim = ObjectAnimator.ofPropertyValuesHolder(
            titleBox,
            PropertyValuesHolder.ofFloat(View.SCALE_X, if (on) 0.72f else 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, if (on) 0.72f else 1f),
        ).apply {
            duration = 1000
            interpolator = Motion.SETTLE
            start()
        }
        root.glideY(if (on) Ui.px(500f) else 0f, 1000)
    }

    /**
     * Apple TV's buttons: the focused one is lit cream with dark words, the other is glass. With
     * neither focused (the tab bar has focus), Play stays lit as the main action.
     */
    private fun paintButtons() {
        val lit = buttons.firstOrNull { it.view.isFocused } ?: buttons.first()
        val dark = root.context.getColor(R.color.ink_dark)
        for (button in buttons) {
            val on = button === lit
            button.view.background = if (on) button.lit else button.rest
            button.label.setTextColor(if (on) dark else Palette.INK)
            button.icon.imageTintList = ColorStateList.valueOf(if (on) dark else Palette.INK)
        }
        playGlow.fadeTo(if (lit === buttons.first()) 1f else 0f, 300)
    }

    private fun restartTimer() {
        handler.removeCallbacks(advance)
        if (!running) return
        handler.postDelayed(advance, SLIDE_MS)
        dots.run(SLIDE_MS)
    }

    private fun updateGlow(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val mask = Halo.mask(width, height, height / 2f, blur = Ui.px(70f), spread = -Ui.px(4f))
        val tint = Palette.alpha(current.show.tint, 0.5f)
        playGlow.setImageDrawable(BitmapDrawable(playGlow.resources, mask).apply { setTint(tint) })
        // Sized by hand: a wrap_content FrameLayout won't stretch a lone match_parent child.
        val pad = Halo.pad
        playGlow.layoutParams = (playGlow.layoutParams as ViewGroup.MarginLayoutParams).apply {
            this.width = width + 2 * pad
            this.height = height + 2 * pad
            setMargins(-pad, -pad, -pad, -pad)
        }
    }

    /** The show's logo art in place of the typeset title, once it has loaded. */
    private fun showLogo(show: Show, fade: Boolean) {
        val url = show.logo
        val art = url?.let { logos.get(it) }
        logos.show(logo, title, art, LOGO_W, LOGO_H)
        if (art == null) {
            title.alpha = 1f
            if (url != null) fetchLogo(url)
            return
        }
        if (fade) {
            logo.alpha = 0f
            logo.animate().alpha(1f).setDuration(400).setStartDelay(0).setInterpolator(Motion.EASE)
        } else {
            logo.animate().cancel()
            logo.alpha = 1f
        }
    }

    /** Fetches every slide's logo ahead, so each one is ready when its slide comes round. */
    private fun prefetchLogos() {
        for (slide in slides) slide.show.logo?.let { fetchLogo(it) }
    }

    private fun fetchLogo(url: String) {
        logos.fetch(url) { art ->
            // Arrived while its slide is up: swap the typeset title for it, unless it's mid-exit.
            if (art != null && !leaving && current.show.logo == url && logo.visibility != View.VISIBLE) showLogo(current.show, fade = true)
        }
    }

    /** Staggered entrance: tag and title first, then meta, blurb, buttons and dots. */
    private fun enter() {
        val rise = Ui.px(22f)
        // Clear whatever the last exit left behind, or it keeps running over the new slide.
        for (view in arrayOf(tag, titleBox, details)) {
            view.animate().cancel()
            view.translationX = 0f
        }
        if (!ambient) {
            details.alpha = 1f
            details.visibility = View.VISIBLE
        }
        for ((view, delay) in listOf(tag to 0L, titleBox to 40L, meta to 120L, blurb to 180L, actions to 240L, dots to 300L)) {
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f).setDuration(800).setStartDelay(delay).setInterpolator(Motion.SETTLE)
        }
    }

    companion object {
        const val SLIDE_MS = 9_400L
        private const val EXIT_MS = 180L

        /** The box a title logo fits in, in mockup px. */
        private const val LOGO_W = 560f
        private const val LOGO_H = 176f
    }
}
