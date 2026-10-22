package com.shivansh.mibox.maximal.ui

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shivansh.mibox.data.AppEntry
import com.shivansh.mibox.data.Show
import kotlin.math.max

/** What the rail lists: titles as portrait posters, or apps as 16:9 tiles. */
sealed class RailItem {
    data class Title(val show: Show) : RailItem()
    data class App(val entry: AppEntry) : RailItem()
}

/**
 * The right-hand rail: one column of posters (or app tiles) with titles underneath. It fills from
 * the top; once focus passes the middle it scrolls so the focused item stays centred, and a thin
 * bar shows where you are. It never takes Android focus; the activity moves [focused].
 */
class Rail(
    private val list: RecyclerView,
    private val track: View,
    private val thumb: View,
    assets: AssetManager,
    private val appArt: AppArtSource,
) {
    private val posters = Posters(assets)
    private val adapter = Adapter()

    /** Sizes in layout px. */
    private val posterW = Ui.px(200)
    private val posterH = Ui.px(300)
    private val tileW = Ui.px(TILE_W)
    private val tileH = Ui.px(TILE_H)
    private val radius = Ui.px(14f)
    private val posterPitch = Ui.px(376)
    private val tilePitch = Ui.px(212)
    private val topPad = Ui.px(16)

    var items: List<RailItem> = emptyList()
        private set
    var focused = 0
        private set

    /** Whether the rail has the remote; the focused item is lit only then. */
    private var lit = false

    private val pitch: Int get() = if (items.firstOrNull() is RailItem.App) tilePitch else posterPitch

    init {
        list.layoutManager = object : LinearLayoutManager(list.context, VERTICAL, false) {
            // The rail scrolls only when told to: focus never drags it.
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean,
            ) = false
        }
        list.adapter = adapter
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = moveThumb()
        })
        list.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                pad()
                list.post { center(animate = false) }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun set(newItems: List<RailItem>, keep: Boolean) {
        val keepId = if (keep) items.getOrNull(focused)?.let(::idOf) else null
        items = newItems
        focused = keepId?.let { id -> newItems.indexOfFirst { idOf(it) == id } }?.takeIf { it >= 0 } ?: 0
        pad()
        adapter.notifyDataSetChanged()
        list.post { center(animate = false) }
        val show = newItems.size > 1
        track.visibility = if (show) View.VISIBLE else View.INVISIBLE
        thumb.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    /** Swaps one title in place (its full record arrived), keeping the rail where it is. */
    fun update(index: Int, item: RailItem) {
        if (index !in items.indices) return
        items = items.toMutableList().apply { set(index, item) }
        adapter.notifyItemChanged(index, Unit)
    }

    fun setLit(on: Boolean) {
        lit = on
        repaint()
    }

    /** Up or down one item; false at either end. */
    fun move(delta: Int): Boolean {
        val next = focused + delta
        if (next !in items.indices) return false
        focused = next
        repaint()
        center(animate = true)
        return true
    }

    fun trim() {
        posters.trim()
        appArt.trim()
    }

    /** A little air at the top; enough at the bottom that the last item can come up to the middle. */
    private fun pad() {
        val h = list.height
        if (h == 0) return
        val bottom = max(0, (h - pitch) / 2)
        if (list.paddingTop != topPad || list.paddingBottom != bottom) list.setPadding(0, topPad, 0, bottom)
    }

    /** The scroll that puts item [index] in the middle of the rail (none while it's still near the top). */
    private fun offsetFor(index: Int): Int = max(0, topPad + index * pitch + pitch / 2 - list.height / 2)

    private fun center(animate: Boolean) {
        val target = offsetFor(focused)
        val dy = target - list.computeVerticalScrollOffset()
        if (dy != 0) {
            if (animate) list.smoothScrollBy(0, dy, Motion.SETTLE, 420) else list.scrollBy(0, dy)
        }
        moveThumb()
    }

    private fun moveThumb() {
        val count = items.size
        if (count < 2) return
        val travel = track.height - thumb.height
        val end = offsetFor(count - 1)
        val progress = if (end == 0) 0f else list.computeVerticalScrollOffset().toFloat() / end
        thumb.translationY = travel * progress.coerceIn(0f, 1f)
    }

    private fun repaint() {
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val position = list.getChildAdapterPosition(child)
            if (position != RecyclerView.NO_POSITION) style(child as ViewGroup, position, animate = true)
        }
    }

    /** The focused item lifts and its frame and name light up; the rest sit back a little. */
    private fun style(item: ViewGroup, position: Int, animate: Boolean) {
        val on = position == focused
        val frame = item.getChildAt(0) as FrameLayout
        val label = item.getChildAt(1) as TextView
        val scale = if (on && lit) 1.07f else 1f
        val alpha = if (on || !lit) 1f else 0.62f
        frame.foreground = if (on) ring(lit) else null
        label.setTextColor(if (on) Palette.INK else Palette.QUIET)
        if (animate) {
            frame.animate().scaleX(scale).scaleY(scale).setDuration(320).setStartDelay(0).setInterpolator(Motion.LIFT)
            item.animate().alpha(alpha).setDuration(240).setStartDelay(0).setInterpolator(Motion.EASE)
        } else {
            frame.animate().cancel()
            frame.scaleX = scale
            frame.scaleY = scale
            item.alpha = alpha
        }
    }

    private fun ring(lit: Boolean) = GradientDrawable().apply {
        cornerRadius = radius
        setStroke(Ui.px(if (lit) 4 else 2), Palette.alpha(Palette.INK, if (lit) 1f else 0.5f))
    }

    private fun idOf(item: RailItem): String = when (item) {
        is RailItem.Title -> item.show.id
        is RailItem.App -> item.entry.packageName
    }

    private inner class Holder(val item: LinearLayout) : RecyclerView.ViewHolder(item) {
        val frame = item.getChildAt(0) as FrameLayout
        val image = frame.getChildAt(0) as ImageView
        val label = item.getChildAt(1) as TextView
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is RailItem.App) APP else TITLE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val app = viewType == APP
            val w = if (app) tileW else posterW
            val h = if (app) tileH else posterH
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (app) tilePitch else posterPitch)
            }
            val frame = FrameLayout(context).apply {
                pivotX = w / 2f
                pivotY = h / 2f
            }
            frame.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(w, h))
            item.addView(frame, LinearLayout.LayoutParams(w, h).apply { topMargin = Ui.px(10) })
            item.addView(
                TextView(context).apply {
                    typeface = Fonts.regular
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    letterSpacing = 0.02f
                    textPx(21f)
                },
                LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(16) },
            )
            return Holder(item)
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position) else bindLabel(holder, position)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val item = items[position]) {
                is RailItem.Title -> {
                    holder.label.text = item.show.title
                    holder.label.gravity = Gravity.START
                    posters.load(holder.image, item.show.poster ?: item.show.still, posterW, posterH, radius)
                }
                is RailItem.App -> {
                    holder.label.text = item.entry.label
                    holder.label.gravity = Gravity.START
                    holder.image.setImageDrawable(GradientDrawable().apply {
                        cornerRadius = radius
                        setColor(Palette.CARD)
                    })
                    appArt.load(holder.image, item.entry) { art, _ ->
                        holder.image.setImageDrawable(
                            if (art.isBanner) RoundedImage(art.bitmap, radius) else IconTile(art.bitmap, art.glow, radius),
                        )
                    }
                }
            }
            style(holder.item, position, animate = false)
        }

        private fun bindLabel(holder: Holder, position: Int) {
            val item = items[position]
            if (item is RailItem.Title) holder.label.text = item.show.title
            style(holder.item, position, animate = false)
        }
    }

    companion object {
        private const val TITLE = 0
        private const val APP = 1

        /** App tiles, 16:9, in layout px; the app art is rendered at this size. */
        const val TILE_W = 220
        const val TILE_H = 124
    }
}
