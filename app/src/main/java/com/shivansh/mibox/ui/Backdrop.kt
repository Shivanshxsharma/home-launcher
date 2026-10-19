package com.shivansh.mibox.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** One decoded backdrop: the 720p picture plus its two pre-blurred glass layers. */
class BackdropArt(val sharp: Bitmap, val glass: Bitmap, val band: Bitmap) {
    companion object {
        fun decode(assets: AssetManager, path: String): BackdropArt {
            val sharp = Decode.asset(assets, path, 1280, 720)
            val tiny = Decode.asset(assets, path, Glass.WIDTH, Glass.HEIGHT, lowRam = false)
            val glass = Glass.full(tiny)
            val band = Glass.band(glass)
            // Start the GPU uploads now, off the frame that shows them.
            sharp.prepareToDraw()
            glass.prepareToDraw()
            band.prepareToDraw()
            return BackdropArt(sharp, glass, band)
        }
    }
}

/**
 * A full-screen backdrop: the picture, the full glass used on the lower rows, and the frosted band
 * behind the apps row. The slot zooms as a whole, so the glass stays lined up with the picture.
 */
class BackdropSlot @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val sharp = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val glass = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        alpha = 0f
        visibility = INVISIBLE
    }
    private val band = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var drift: ObjectAnimator? = null

    init {
        addView(sharp, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(band, LayoutParams(LayoutParams.MATCH_PARENT, Ui.px(1080 - Glass.BAND_TOP.toInt()), Gravity.BOTTOM))
    }

    /** Fading the slot fades each layer on its own instead of through an offscreen buffer. */
    override fun hasOverlappingRendering() = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w * 0.7f
        pivotY = h * 0.45f
    }

    fun bind(art: BackdropArt, fullGlass: Boolean, showBand: Boolean) {
        sharp.setImageBitmap(art.sharp)
        glass.setImageBitmap(art.glass)
        band.setImageBitmap(art.band)
        glass.animate().cancel()
        band.animate().cancel()
        glass.alpha = if (fullGlass) 1f else 0f
        glass.visibility = if (fullGlass) VISIBLE else INVISIBLE
        sharp.visibility = if (fullGlass) INVISIBLE else VISIBLE
        band.alpha = if (showBand) 1f else 0f
        band.visibility = if (showBand) VISIBLE else INVISIBLE
    }

    fun setGlass(fullGlass: Boolean, showBand: Boolean) {
        if (fullGlass) {
            glass.visibility = VISIBLE
            // Once the glass is opaque, the picture under it is pure overdraw.
            glass.animate().alpha(1f).setDuration(600).setStartDelay(0).setInterpolator(Motion.EASE)
                .withEndAction { sharp.visibility = INVISIBLE }
        } else {
            sharp.visibility = VISIBLE
            glass.fadeTo(0f, 600)
        }
        band.fadeTo(if (showBand) 1f else 0f, 700)
    }

    /**
     * The slow Ken Burns push: 7% over 14 s, mostly in the first few seconds. Its own animator, so it
     * never merges with the slot's crossfade (ViewPropertyAnimator batches calls made in one frame).
     */
    fun startDrift() {
        drift?.cancel()
        scaleX = 1f
        scaleY = 1f
        if (!DRIFT) return
        drift = ObjectAnimator.ofPropertyValuesHolder(
            this,
            PropertyValuesHolder.ofFloat(SCALE_X, 1.07f),
            PropertyValuesHolder.ofFloat(SCALE_Y, 1.07f),
        ).apply {
            duration = 14_000
            interpolator = Motion.DRIFT
            start()
        }
    }

    /** Holds the zoom while the art is hidden under a preview, so nothing redraws for it. */
    fun holdDrift(hold: Boolean) {
        val d = drift ?: return
        if (hold) d.pause() else d.resume()
    }

    /** Drops the bitmaps so only the visible slot holds a full-screen picture. */
    fun release() {
        animate().cancel()
        drift?.cancel()
        drift = null
        scaleX = 1f
        scaleY = 1f
        sharp.setImageDrawable(null)
        glass.setImageDrawable(null)
        band.setImageDrawable(null)
        visibility = INVISIBLE
    }

    companion object {
        /** Turn off if the box struggles: the zoom keeps the GPU drawing every frame. */
        const val DRIFT = true
    }
}

/**
 * Crossfades between two [BackdropSlot]s. At most two full-screen bitmaps exist at once: the one on
 * screen and the one fading in. A new decode starts only after the previous fade has finished and
 * the hidden slot has let go of its bitmap.
 */
class Backdrop(
    /** Holds both slots; fading it reveals the preview video underneath. */
    private val group: View,
    private val lower: BackdropSlot,
    private val upper: BackdropSlot,
    private val assets: AssetManager,
    private val onFirstShown: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var front: BackdropSlot? = null
    private var shown: String? = null
    private var wanted: String? = null
    private var busy = false
    private var fullGlass = false
    private var showBand = true
    private val pump = Runnable { pump() }

    /** Shows [path], after [delayMs] so quick focus moves across cards don't decode every one. */
    fun show(path: String, delayMs: Long = 0) {
        wanted = path
        main.removeCallbacks(pump)
        if (delayMs > 0) main.postDelayed(pump, delayMs) else pump()
    }

    /** Fades the art away to show the preview playing under it, or brings it back. */
    fun setArtShown(shown: Boolean) {
        group.fadeTo(if (shown) 1f else 0f, if (shown) 450 else 900)
        for (slot in arrayOf(lower, upper)) slot.holdDrift(!shown)
    }

    fun setGlass(fullGlass: Boolean, showBand: Boolean) {
        if (fullGlass == this.fullGlass && showBand == this.showBand) return
        this.fullGlass = fullGlass
        this.showBand = showBand
        for (slot in arrayOf(lower, upper)) if (slot.visibility == View.VISIBLE) slot.setGlass(fullGlass, showBand)
    }

    private fun pump() {
        val path = wanted ?: return
        if (busy || path == shown) return
        busy = true
        val slot = if (front === upper) lower else upper
        slot.release()
        Workers.backdrop.execute {
            val art = try {
                BackdropArt.decode(assets, path)
            } catch (_: Exception) {
                null
            }
            main.post { present(slot, path, art) }
        }
    }

    private fun present(slot: BackdropSlot, path: String, art: BackdropArt?) {
        shown = path
        if (art == null) {
            busy = false
            pump()
            return
        }
        slot.bind(art, fullGlass, showBand)
        slot.startDrift()
        val first = front == null
        front = slot
        if (slot === upper) {
            // New picture on top: fade it in, then let the lower one go.
            upper.alpha = 0f
            upper.visibility = View.VISIBLE
            upper.animate().alpha(1f).setDuration(FADE_MS).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction {
                lower.release()
                settle(first)
            }
        } else {
            // New picture underneath: fade the upper one away to reveal it.
            lower.alpha = 1f
            lower.visibility = View.VISIBLE
            upper.animate().alpha(0f).setDuration(FADE_MS).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction {
                upper.release()
                settle(first)
            }
        }
    }

    private fun settle(first: Boolean) {
        busy = false
        if (first) onFirstShown()
        pump()
    }

    private companion object {
        const val FADE_MS = 800L
    }
}
