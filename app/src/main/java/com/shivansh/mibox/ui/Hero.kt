package com.shivansh.mibox.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.shivansh.mibox.R
import com.shivansh.mibox.data.HeroSlide

/**
 * The hero banner: the user's shows one at a time, over a full-bleed backdrop or the show's muted
 * preview. Left and right change slides; on its own it moves on every [SLIDE_MS] while focused.
 * In ambient mode the details step away and the title settles small at the bottom left, leaving
 * the preview to fill the screen.
 */
class Hero(private val root: View, private val slides: List<HeroSlide>, private val onSlide: (HeroSlide) -> Unit) {
    private val tag: View = root.findViewById(R.id.hero_tag)
    private val dot: View = root.findViewById(R.id.hero_dot)
    private val provider: TextView = root.findViewById(R.id.hero_provider)
    private val status: TextView = root.findViewById(R.id.hero_status)
    private val title: TextView = root.findViewById(R.id.hero_title)
    private val details: View = root.findViewById(R.id.hero_details)
    private val meta: View = root.findViewById(R.id.hero_meta)
    private val next: TextView = root.findViewById(R.id.hero_next)
    private val genre: TextView = root.findViewById(R.id.hero_genre)
    private val year: TextView = root.findViewById(R.id.hero_year)
    private val rating: TextView = root.findViewById(R.id.hero_rating)
    private val blurb: TextView = root.findViewById(R.id.hero_blurb)
    private val actions: View = root.findViewById(R.id.hero_actions)
    private val playWrap: View = root.findViewById(R.id.hero_play_wrap)
    private val playLabel: TextView = root.findViewById(R.id.hero_play_label)
    private val playGlow: ImageView = root.findViewById(R.id.hero_play_glow)
    private val dots: HeroDots = root.findViewById(R.id.hero_dots)
    val playButton: View = root.findViewById(R.id.hero_play)

    private val handler = Handler(Looper.getMainLooper())
    private val advance = Runnable { step(1) }
    private var running = false
    private var leaving = false
    private var ambientAnim: ObjectAnimator? = null

    var index = 0
        private set
    val current: HeroSlide get() = slides[index]

    init {
        dots.count = slides.size
        val circle = Ui.px(34f)
        for (id in intArrayOf(R.id.hero_mine, R.id.hero_info)) root.findViewById<View>(id).background = Glass.control(circle)
        playButton.setOnFocusChangeListener { _, focused ->
            val scale = if (focused) 1.06f else 1f
            playWrap.animate().scaleX(scale).scaleY(scale).setDuration(320).setStartDelay(0).setInterpolator(Motion.LIFT)
        }
        // The Play glow is baked for the button's size, which changes with its label.
        playButton.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Posted: resizing the glow is a layout request, not allowed mid-layout.
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) v.post { updateGlow(v.width, v.height) }
        }
    }

    fun bind(i: Int, entrance: Boolean) {
        index = i
        val slide = slides[i]
        val show = slide.show
        dot.backgroundTintList = ColorStateList.valueOf(show.provider.color)
        provider.text = show.provider.label
        status.text = slide.status
        title.text = show.title
        title.showTitle(show.style, 150f)
        title.lineBox(0.9f, top = 16f, bottom = 0f)
        next.text = slide.next
        genre.text = slide.genre
        year.text = slide.year
        rating.text = slide.rating
        blurb.text = slide.blurb
        playLabel.text = slide.play
        playGlow.drawable?.setTint(Palette.alpha(show.tint, 0.5f))
        dots.select(i)
        if (entrance) enter()
    }

    /** Next or previous slide: the current one slips away to the left, the next rises in. */
    fun step(delta: Int) {
        index = Math.floorMod(index + delta, slides.size)
        onSlide(current)
        restartTimer()
        if (leaving) return
        leaving = true
        val out = Ui.px(-28f) * delta
        for (view in arrayOf(tag, title, details)) {
            view.animate().alpha(0f).translationX(out).setDuration(180).setStartDelay(0).setInterpolator(Motion.EASE)
        }
        title.animate().withEndAction {
            leaving = false
            for (view in arrayOf(tag, title, details)) view.translationX = 0f
            bind(index, entrance = true)
        }
    }

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
        details.fadeTo(if (on) 0f else 1f, if (on) 500 else 400)
        ambientAnim?.cancel()
        ambientAnim = ObjectAnimator.ofPropertyValuesHolder(
            title,
            PropertyValuesHolder.ofFloat(View.SCALE_X, if (on) 0.72f else 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, if (on) 0.72f else 1f),
        ).apply {
            duration = 1000
            interpolator = Motion.SETTLE
            start()
        }
        root.glideY(if (on) Ui.px(560f) else 0f, 1000)
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

    /** Staggered entrance: tag and title first, then meta, blurb, buttons and dots. */
    private fun enter() {
        val rise = Ui.px(22f)
        for ((view, delay) in listOf(tag to 0L, title to 40L, meta to 120L, blurb to 180L, actions to 240L, dots to 300L)) {
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f).setDuration(800).setStartDelay(delay).setInterpolator(Motion.SETTLE)
        }
        details.alpha = 1f
        details.translationX = 0f
    }

    companion object {
        const val SLIDE_MS = 9_400L
    }
}
