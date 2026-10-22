package com.shivansh.mibox.maximal.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** One decoded background: the 720p picture with its shading baked in, and a tiny blurred copy for the glass. */
class ArtSet(val sharp: Bitmap, val glass: Bitmap) {
    companion object {
        fun decode(assets: AssetManager, path: String): ArtSet {
            val sharp = Decode.image(assets, path, 1280, 720, mutable = true)
            Shade.bake(sharp)
            val tiny = Decode.image(assets, path, Glass.WIDTH, Glass.HEIGHT, lowRam = false)
            val glass = Glass.frost(tiny)
            // Start the GPU uploads now, off the frame that shows them.
            sharp.prepareToDraw()
            glass.prepareToDraw()
            return ArtSet(sharp, glass)
        }
    }
}

/**
 * The shading behind Maximal's words, drawn into the picture once as it's decoded: the bottom for
 * the title, the right for the storyline, a little at the top. Live gradient overlays would cost
 * the Mali-450 several milliseconds a frame; baked in, they cost nothing.
 */
object Shade {
    fun bake(art: Bitmap) {
        val w = art.width.toFloat()
        val h = art.height.toFloat()
        val canvas = Canvas(art)
        val paint = Paint().apply { isDither = true }
        fun band(x0: Float, y0: Float, x1: Float, y1: Float, alphas: FloatArray, stops: FloatArray) {
            val colors = IntArray(alphas.size) { Palette.alpha(Palette.NIGHT, alphas[it]) }
            paint.shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        band(0f, h, 0f, h * 0.4f, floatArrayOf(0.92f, 0.6f, 0.2f, 0f), floatArrayOf(0f, 0.3f, 0.65f, 1f))
        band(0f, 0f, w * 0.5f, 0f, floatArrayOf(0.5f, 0.18f, 0f), floatArrayOf(0f, 0.55f, 1f))
        band(w, 0f, w * 0.6f, 0f, floatArrayOf(0.45f, 0.15f, 0f), floatArrayOf(0f, 0.5f, 1f))
        band(0f, 0f, 0f, h * 0.18f, floatArrayOf(0.35f, 0f), floatArrayOf(0f, 1f))
    }
}

/**
 * Fake frosted glass. Android 9 has no live blur, and the Mali-450 couldn't afford one, so each
 * background gets a 240x135 blurred copy that is stretched to full screen; a blur this wide hides
 * the 8x upscale.
 */
object Glass {
    const val WIDTH = 240
    const val HEIGHT = 135

    fun frost(tiny: Bitmap): Bitmap {
        val blurred = blur(tiny, radius = 7)
        val out = Bitmap.createBitmap(blurred.width, blurred.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.3f) }) }
        Canvas(out).drawBitmap(blurred, 0f, 0f, paint)
        return out
    }

    /** Three box-blur passes, close to a Gaussian. Only ever run on tiny bitmaps. */
    private fun blur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) {
            pass(a, b, w, h, radius, horizontal = true)
            pass(b, a, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, len - 1) * stride]
                sr += p shr 16 and 0xFF
                sg += p shr 8 and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * stride] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[base + (i - r).coerceAtLeast(0) * stride]
                val inn = src[base + (i + r + 1).coerceAtMost(len - 1) * stride]
                sr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                sg += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}

/**
 * One full-screen background: the picture, plus the glass under the sidebar and the rail. The
 * glass is the whole blurred picture stretched over the screen and clipped to each panel, so it
 * lines up with the art behind it and crossfades with it.
 */
class ArtSlot @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val sharp = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val leftGlass = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val rightGlass = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var leftWidth = 0
    private var rightWidth = 0

    init {
        for (view in arrayOf(sharp, leftGlass, rightGlass)) addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Fading the slot fades each layer on its own instead of through an offscreen buffer. */
    override fun hasOverlappingRendering() = false

    /** The glass panels' widths in px, at the left and right edges. */
    fun setPanels(left: Int, right: Int) {
        leftWidth = left
        rightWidth = right
        clipPanels()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipPanels()
    }

    private fun clipPanels() {
        leftGlass.clipBounds = Rect(0, 0, leftWidth, height)
        rightGlass.clipBounds = Rect(width - rightWidth, 0, width, height)
    }

    fun bind(art: ArtSet) {
        sharp.setImageBitmap(art.sharp)
        leftGlass.setImageBitmap(art.glass)
        rightGlass.setImageBitmap(art.glass)
    }

    /** Drops the bitmaps so only the visible slot holds a full-screen picture. */
    fun release() {
        animate().cancel()
        sharp.setImageDrawable(null)
        leftGlass.setImageDrawable(null)
        rightGlass.setImageDrawable(null)
        visibility = INVISIBLE
    }
}

/**
 * Crossfades between two [ArtSlot]s, slowly. At most two full-screen bitmaps exist at once: the
 * one on screen and the one fading in. A new decode starts only after the previous fade has
 * finished and the hidden slot has let go of its bitmap.
 */
class Backdrop(
    /** Holds both slots; fading it reveals the preview video underneath. */
    private val group: View,
    private val lower: ArtSlot,
    private val upper: ArtSlot,
    private val assets: AssetManager,
    private val onFirstShown: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var front: ArtSlot? = null
    private var shown: String? = null
    private var wanted: String? = null
    private var busy = false
    private val pump = Runnable { pump() }

    fun setPanels(left: Int, right: Int) {
        lower.setPanels(left, right)
        upper.setPanels(left, right)
    }

    /** Shows [path], after [delayMs] so quick moves along the rail don't decode every picture. */
    fun show(path: String, delayMs: Long = 0) {
        wanted = path
        main.removeCallbacks(pump)
        if (delayMs > 0) main.postDelayed(pump, delayMs) else pump()
    }

    /** Fades the art away to show the preview playing under it, or brings it back. */
    fun setArtShown(shown: Boolean) = group.fadeTo(if (shown) 1f else 0f, if (shown) 450 else 900)

    private fun pump() {
        val path = wanted ?: return
        if (busy || path == shown) return
        busy = true
        val slot = if (front === upper) lower else upper
        slot.release()
        Workers.backdrop.execute {
            val art = try {
                ArtSet.decode(assets, path)
            } catch (_: Exception) {
                null
            }
            main.post { present(slot, path, art) }
        }
    }

    private fun present(slot: ArtSlot, path: String, art: ArtSet?) {
        shown = path
        if (art == null) {
            busy = false
            pump()
            return
        }
        slot.bind(art)
        val first = front == null
        front = slot
        if (slot === upper) {
            upper.alpha = 0f
            upper.visibility = View.VISIBLE
            upper.animate().alpha(1f).setDuration(FADE_MS).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction {
                lower.release()
                settle(first)
            }
        } else {
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
        /** The reference's slow, cinematic crossfade. */
        const val FADE_MS = 900L
    }
}
