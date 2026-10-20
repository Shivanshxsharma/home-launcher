package com.shivansh.mibox.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.LayoutRes
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView

/** A card's size, lift and caption, in device px. */
class CardSpec(
    val width: Int,
    val height: Int,
    val radius: Float,
    val lift: Float,
    val captionHeight: Int,
    /** True when the caption only shows on the focused card, the way tvOS labels app icons. */
    val captionOnFocus: Boolean,
    val bandAlpha: Float,
) {
    companion object {
        /** Sizes in mockup px. */
        fun of(width: Int, height: Int, radius: Int, lift: Float, caption: Int, captionOnFocus: Boolean, bandAlpha: Float = 0.24f) =
            CardSpec(Ui.px(width), Ui.px(height), Ui.px(radius.toFloat()), lift, Ui.px(caption), captionOnFocus, bandAlpha)
    }
}

object Cards {
    /** tvOS-sized app icons, 5:3. */
    val APP by lazy { CardSpec.of(310, 186, 20, lift = 1.1f, caption = 58, captionOnFocus = true, bandAlpha = 0.3f) }
    val UP_NEXT by lazy { CardSpec.of(416, 234, 16, lift = 1.08f, caption = 80, captionOnFocus = false) }
    val POSTER by lazy { CardSpec.of(232, 348, 16, lift = 1.1f, caption = 44, captionOnFocus = true) }

    /** A little smaller on the Shows and Movies tabs, so two rows and their captions fit one screen. */
    val PAGE_POSTER by lazy { CardSpec.of(204, 306, 14, lift = 1.1f, caption = 44, captionOnFocus = true) }
    val FAVORITE by lazy { CardSpec.of(560, 315, 16, lift = 1.07f, caption = 44, captionOnFocus = true) }

    /** Scrims baked into the art, only where something is drawn over it. */
    val UP_NEXT_FINISH = CardFinish(cssAngle = 0f, from = 0.6f, end = 0.36f)
    val POSTER_FINISH = CardFinish(cssAngle = 0f, from = 0.85f, end = 0.48f)
    val FAVORITE_FINISH = CardFinish(cssAngle = 20f, from = 0.8f, end = 0.55f)

    /** No scrim: for posters whose own art carries the title. */
    val CLEAN_FINISH = CardFinish(cssAngle = 0f, from = 0f, end = 1f)
}

/**
 * A rounded card with tvOS-style focus: it springs up, tilts toward the direction focus came from
 * and settles, a soft glare drifts across it and a band of light sweeps over once. A deep shadow
 * and a faint glow in the show's tint sit under it, and a lit keyline traces its edge. The caption
 * underneath steps aside (or fades in) instead of being covered.
 *
 * Cheap on purpose: every effect is a transform or alpha on its own view, the glare and sweep are
 * shaders moved by a matrix inside the card's rounded shape (no clipping), and the 3D tilt only
 * runs for the half second after focus lands.
 */
class FocusCard(
    context: Context,
    private val spec: CardSpec,
    @LayoutRes bodyLayout: Int,
    @LayoutRes captionLayout: Int,
) : FrameLayout(context) {
    /** The card itself: scales, tilts and nudges; the caption stays put below it. */
    private val lift = FrameLayout(context)

    /** The card's face; the body layout is inflated into it. */
    val body = FrameLayout(context)
    val caption: View
    private val shadow = ImageView(context)
    private val glow = ImageView(context)
    private val sheen = Sheen(spec.radius, spec.bandAlpha)
    private val keyline = View(context)
    private var lit = false

    private val growX = spring(DynamicAnimation.SCALE_X, 1f, 380f, 0.6f)
    private val growY = spring(DynamicAnimation.SCALE_Y, 1f, 380f, 0.6f)
    private val tiltX = spring(DynamicAnimation.ROTATION_X, 0f, 140f, 0.4f)
    private val tiltY = spring(DynamicAnimation.ROTATION_Y, 0f, 140f, 0.4f).apply {
        // The glare slides the opposite way to the tilt, like light on a turning surface.
        addUpdateListener { _, value, _ -> sheen.glareX = GLARE_X - value * 0.035f }
    }
    private val nudgeX = spring(DynamicAnimation.TRANSLATION_X, 0f, 700f, 0.42f)
    private val nudgeY = spring(DynamicAnimation.TRANSLATION_Y, 0f, 700f, 0.42f)
    private var glareAnim: ValueAnimator? = null
    private var sweepAnim: ValueAnimator? = null

    init {
        layoutParams = RecyclerView.LayoutParams(spec.width, spec.height + spec.captionHeight)
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        lift.clipChildren = false
        lift.clipToPadding = false
        lift.cameraDistance = 9000f * resources.displayMetrics.density
        addView(lift, LayoutParams(spec.width, spec.height))

        val mask = Halo.mask(spec.width, spec.height, spec.radius)
        lift.addView(shadow.halo(mask, Palette.alpha(0, 0.62f)), outset(Halo.pad))
        shadow.translationY = Ui.px(34f)
        lift.addView(glow.halo(mask, Palette.INK), outset(Halo.pad))

        body.alpha = DIMMED
        // Dimming applies the alpha to each draw, rather than through an offscreen layer.
        body.forceHasOverlappingRendering(false)
        setFace(Palette.CARD)
        lift.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        LayoutInflater.from(context).inflate(bodyLayout, body, true)

        sheen.glareX = GLARE_X
        lift.addView(View(context).apply { background = sheen }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        keyline.background = Keyline(spec.radius, Ui.px(2f))
        keyline.alpha = KEYLINE_REST
        keyline.forceHasOverlappingRendering(false)
        lift.addView(keyline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        caption = LayoutInflater.from(context).inflate(captionLayout, this, false)
        addView(caption, LayoutParams(spec.width, spec.captionHeight).apply { topMargin = spec.height })
        caption.forceHasOverlappingRendering(false)
        if (spec.captionOnFocus) {
            caption.alpha = 0f
            caption.visibility = INVISIBLE
        }
    }

    private fun spring(property: DynamicAnimation.ViewProperty, rest: Float, stiffness: Float, damping: Float) =
        SpringAnimation(lift, property, rest).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
        }

    private fun ImageView.halo(mask: Bitmap, color: Int): ImageView {
        scaleType = ImageView.ScaleType.FIT_XY
        setImageDrawable(BitmapDrawable(resources, mask).apply { setTint(color) })
        alpha = 0f
        visibility = INVISIBLE
        return this
    }

    private fun outset(by: Int) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
        setMargins(-by, -by, -by, -by)
    }

    /** A solid rounded face, seen while the picture loads or on cards without one. */
    fun setFace(color: Int) {
        body.background = GradientDrawable().apply {
            cornerRadius = spec.radius
            setColor(color)
        }
    }

    /**
     * A face of any drawable; it must keep to the card's rounded corners itself. Null when a
     * full-bleed picture is the face, so the card isn't painted twice.
     */
    fun setFace(face: Drawable?) {
        body.background = face
    }

    /** The focus glow's color: a faint wash of the show's tint under the shadow. */
    fun setTint(color: Int) {
        glow.drawable.setTint(Palette.alpha(color, 0.38f))
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        setLit(gainFocus, animate = true, direction = direction)
        // Rows draw their focused card last, so its lift and shadow lie over the neighbours.
        (parent as? View)?.invalidate()
    }

    fun setLit(on: Boolean, animate: Boolean, direction: Int = 0) {
        if (!animate) {
            snap(on)
            return
        }
        if (on == lit) return
        lit = on
        val scale = if (on) spec.lift else 1f
        for (s in arrayOf(growX, growY)) {
            s.spring.dampingRatio = if (on) 0.6f else 0.9f
            s.animateToFinalPosition(scale)
        }
        val light = if (on) 1f else 0f
        glow.fadeTo(light, 420)
        shadow.fadeTo(light, 420)
        keyline.animate().alpha(if (on) 1f else KEYLINE_REST).setDuration(320).setStartDelay(0).setInterpolator(Motion.EASE)
        body.animate().alpha(if (on) 1f else DIMMED).setDuration(260).setStartDelay(0).setInterpolator(Motion.EASE)
        moveCaption(on)
        fadeGlare(on)
        if (on) {
            tilt(direction)
            sweep()
        } else {
            sweepAnim?.cancel()
            sheen.sweep = -1f
        }
    }

    /** OK pressed: the card dips and springs back. */
    fun press() {
        val scale = if (lit) spec.lift else 1f
        growX.animateToFinalPosition(scale * 0.94f)
        growY.animateToFinalPosition(scale * 0.94f)
        postDelayed({
            growX.animateToFinalPosition(scale)
            growY.animateToFinalPosition(scale)
        }, 110)
    }

    /** Focus hit the end of a row: a small push in that direction that springs back. */
    fun bump(dx: Int, dy: Int) {
        if (dx != 0) nudgeX.setStartVelocity(dx * Ui.px(900f)).animateToFinalPosition(0f)
        if (dy != 0) nudgeY.setStartVelocity(dy * Ui.px(900f)).animateToFinalPosition(0f)
    }

    private fun tilt(direction: Int) {
        val (rx, ry) = when (direction) {
            FOCUS_RIGHT -> 0f to -TILT
            FOCUS_LEFT -> 0f to TILT
            FOCUS_DOWN -> TILT * 0.8f to 0f
            FOCUS_UP -> -TILT * 0.8f to 0f
            else -> 0f to 0f
        }
        tiltX.cancel()
        tiltY.cancel()
        lift.rotationX = rx
        lift.rotationY = ry
        tiltX.animateToFinalPosition(0f)
        tiltY.animateToFinalPosition(0f)
    }

    private fun moveCaption(on: Boolean) {
        // Clear the lifted card: it grows by half its extra height at the bottom.
        val clear = spec.height * (spec.lift - 1f) / 2f + Ui.px(6f)
        caption.animate().translationY(if (on) clear else 0f).setDuration(380).setStartDelay(0).setInterpolator(Motion.SETTLE)
        if (spec.captionOnFocus) {
            if (on) caption.translationY = clear - Ui.px(10f)
            caption.fadeTo(if (on) 1f else 0f, if (on) 320 else 160)
        }
    }

    private fun fadeGlare(on: Boolean) {
        glareAnim?.cancel()
        glareAnim = ValueAnimator.ofFloat(sheen.glare, if (on) 1f else 0f).apply {
            duration = if (on) 500 else 200
            interpolator = Motion.EASE
            addUpdateListener { sheen.glare = it.animatedValue as Float }
            start()
        }
    }

    /** One band of light across the face, from off the left edge to off the right. */
    private fun sweep() {
        sweepAnim?.cancel()
        sweepAnim = ValueAnimator.ofFloat(-0.6f, 1.6f).apply {
            duration = 1000
            startDelay = 140
            interpolator = Motion.SETTLE
            addUpdateListener { sheen.sweep = it.animatedValue as Float }
            start()
        }
    }

    private fun snap(on: Boolean) {
        lit = on
        for (s in arrayOf(growX, growY, tiltX, tiltY, nudgeX, nudgeY)) s.cancel()
        glareAnim?.cancel()
        sweepAnim?.cancel()
        for (v in arrayOf(glow, shadow, keyline, body, caption)) v.animate().cancel()
        val scale = if (on) spec.lift else 1f
        lift.scaleX = scale
        lift.scaleY = scale
        lift.rotationX = 0f
        lift.rotationY = 0f
        lift.translationX = 0f
        lift.translationY = 0f
        for (v in arrayOf(glow, shadow)) {
            v.alpha = if (on) 1f else 0f
            v.visibility = if (on) VISIBLE else INVISIBLE
        }
        keyline.alpha = if (on) 1f else KEYLINE_REST
        body.alpha = if (on) 1f else DIMMED
        sheen.glare = if (on) 1f else 0f
        sheen.glareX = GLARE_X
        sheen.sweep = -1f
        caption.translationY = if (on) spec.height * (spec.lift - 1f) / 2f + Ui.px(6f) else 0f
        if (spec.captionOnFocus) {
            caption.alpha = if (on) 1f else 0f
            caption.visibility = if (on) VISIBLE else INVISIBLE
        }
    }

    companion object {
        /** Unfocused cards sit back a little: their face at 86%. */
        private const val DIMMED = 0.86f
        private const val KEYLINE_REST = 0.35f
        private const val GLARE_X = 0.3f

        /** Degrees of tilt as focus lands. */
        private const val TILT = 7f
    }
}
