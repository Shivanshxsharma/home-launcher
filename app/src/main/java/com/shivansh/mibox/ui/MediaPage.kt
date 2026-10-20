package com.shivansh.mibox.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shivansh.mibox.R
import com.shivansh.mibox.data.MediaType
import com.shivansh.mibox.data.MediaShelf
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.ui.rows.PosterAdapter

/**
 * The Shows and Movies tabs, after the PS5 home: a strip of genres, the focused title large over
 * its art, and one row of posters. The focused poster holds its place near the left while the row
 * slides under it. Up from the posters is the genre strip; left and right there change genre,
 * and the row follows once the choice settles.
 */
class MediaPage(
    private val root: View,
    private val row: RecyclerView,
    private val adapter: PosterAdapter,
    private val logos: TitleLogos,
    /** A title came to the front: its art goes behind the page, and its full record is fetched. */
    private val onShow: (Show) -> Unit,
) {
    private val context = root.context
    private val genreScroll: HorizontalScrollView = root.findViewById(R.id.media_genres)
    private val genreList: LinearLayout = root.findViewById(R.id.media_genre_list)
    private val info: View = root.findViewById(R.id.media_info)
    private val dot: View = root.findViewById(R.id.media_dot)
    private val kind: TextView = root.findViewById(R.id.media_kind)
    private val title: TextView = root.findViewById(R.id.media_title)
    private val logo: ImageView = root.findViewById(R.id.media_logo)
    private val meta: TextView = root.findViewById(R.id.media_meta)
    private val overview: TextView = root.findViewById(R.id.media_overview)
    private val empty: TextView = root.findViewById(R.id.media_empty)
    private val hints: LinearLayout = root.findViewById(R.id.media_hints)
    private val main = Handler(Looper.getMainLooper())
    private val dark = context.getColor(R.color.ink_dark)

    private val nav = Navigator(
        arrayOf(Navigator.Row(row, keep = 1, step = POSTER_WIDTH + GAP)),
        focusHero = {},
        onMove = { _, col, _ -> adapter.items.getOrNull(col)?.let(::present) },
    )

    private var shelves: List<MediaShelf> = emptyList()
    private var selected = 0
    private var inGenres = false
    private var shown: Show? = null
    private var offline = ""

    /** The strip holds the user's lists (Library) rather than genres; only the hints differ. */
    private var lists = false

    /** A genre chosen on the strip but not yet shown in the row (it waits for the choice to settle). */
    private var pending = false
    private val settle = Runnable { showShelf() }

    /** The poster OK would act on; none while the genre strip has focus. */
    val focusedShow: Show? get() = if (inGenres) null else adapter.items.getOrNull(nav.col)

    val genresFocused: Boolean get() = inGenres

    /**
     * New shelves. [fresh] starts the page over (another tab); otherwise the chosen shelf and the
     * place in its row are kept. An empty shelf shows its own line, or [offline] if it has none.
     */
    fun bind(newShelves: List<MediaShelf>, offline: String, fresh: Boolean, lists: Boolean = false) {
        this.offline = offline
        this.lists = lists
        val keep = if (fresh) null else shelves.getOrNull(selected)?.key
        val relabel = newShelves.map { it.key to it.label } != shelves.map { it.key to it.label }
        shelves = newShelves
        selected = newShelves.indexOfFirst { it.key == keep }.coerceAtLeast(0)
        if (relabel) buildChips()
        if (fresh) {
            inGenres = false
            genreScroll.scrollTo(0, 0)
        }
        paintChips()
        setHints()
        val items = shelves.getOrNull(selected)?.items.orEmpty()
        if (fresh || pending) {
            showShelf()
        } else if (items != adapter.items) {
            adapter.items = items
            showEmpty()
        }
    }

    /** Swaps in a title's full record wherever it appears. */
    fun update(rich: Show) {
        shelves = shelves.map { shelf ->
            if (shelf.items.none { it.id == rich.id }) shelf else shelf.copy(items = shelf.items.map { if (it.id == rich.id) rich else it })
        }
        val position = adapter.items.indexOfFirst { it.id == rich.id }
        if (position >= 0) adapter.replace(position, rich)
        if (shown?.id == rich.id) {
            shown = rich
            bindInfo(rich)
        }
    }

    /** Focus in from the tab bar: the posters, or the genre strip when this genre has none. */
    fun enter() {
        if (adapter.itemCount > 0) toRow() else toGenres()
    }

    /** Focus back where it was, e.g. after returning from an app or new shelves. */
    fun refocus() {
        if (inGenres || adapter.itemCount == 0) toGenres() else nav.refocus()
    }

    /** A D-pad press while the page has focus. False means up past the genres: the tab bar's turn. */
    fun move(dRow: Int, dCol: Int, first: Boolean): Boolean {
        if (inGenres) {
            when {
                dCol != 0 -> {
                    val next = selected + dCol
                    if (next in shelves.indices) {
                        selected = next
                        paintChips()
                        scrollToChip()
                        pending = true
                        main.removeCallbacks(settle)
                        main.postDelayed(settle, SETTLE_MS)
                    } else if (first) {
                        bumpChip(dCol)
                    }
                }
                dRow > 0 -> if (shelves.getOrNull(selected)?.items.isNullOrEmpty()) {
                    if (first) bumpChip(0)
                } else {
                    toRow()
                }
                dRow < 0 -> return false
            }
            return true
        }
        when {
            dRow < 0 -> toGenres()
            dRow > 0 -> if (first) (row.focusedChild as? FocusCard)?.bump(0, 1)
            !nav.move(0, dCol) && first -> (row.focusedChild as? FocusCard)?.bump(dCol, 0)
        }
        return true
    }

    /** OK on the genre strip steps down into its posters. */
    fun okOnGenres() {
        if (inGenres) move(1, 0, first = true)
    }

    private fun toGenres() {
        inGenres = true
        genreScroll.requestFocus()
        paintChips()
        setHints()
    }

    private fun toRow() {
        if (pending) showShelf()
        inGenres = false
        paintChips()
        setHints()
        nav.enter(View.FOCUS_DOWN)
    }

    /** The chosen genre's posters, from the start, with its first title up front. */
    private fun showShelf() {
        main.removeCallbacks(settle)
        pending = false
        val shelf = shelves.getOrNull(selected) ?: return
        adapter.items = shelf.items
        nav.reset()
        showEmpty()
        shelf.items.firstOrNull()?.let(::present)
    }

    private fun showEmpty() {
        val shelf = shelves.getOrNull(selected)
        val none = shelf == null || shelf.items.isEmpty()
        row.visibility = if (none) View.INVISIBLE else View.VISIBLE
        info.visibility = if (none) View.INVISIBLE else View.VISIBLE
        empty.visibility = if (none) View.VISIBLE else View.GONE
        empty.text = shelf?.empty?.ifEmpty { null } ?: offline
    }

    /** Brings [show] to the front: its art behind the page and its words above the row. */
    private fun present(show: Show) {
        val same = shown?.id == show.id
        shown = show
        onShow(show)
        if (same) {
            bindInfo(show)
            return
        }
        // A quick dip and rise: the words change while they're out of sight.
        info.animate().alpha(0f).translationY(0f).setDuration(90).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction {
            shown?.let(::bindInfo)
            info.translationY = Ui.px(14f)
            info.animate().alpha(1f).translationY(0f).setDuration(420).setStartDelay(0).setInterpolator(Motion.SETTLE).withEndAction(null)
        }
    }

    private fun bindInfo(show: Show) {
        val what = context.getString(if (show.mediaType == MediaType.MOVIE) R.string.menu_kind_movie else R.string.menu_kind_show)
        kind.text = if (show.service.isEmpty()) what else "$what  ·  ${show.service}"
        dot.backgroundTintList = ColorStateList.valueOf(show.serviceColor)
        title.text = show.title
        title.showTitle(show.style, 108f)
        title.lineBox(0.9f, top = 12f, bottom = 0f)
        val url = show.logo
        logos.show(logo, title, url?.let { logos.get(it) }, LOGO_W, LOGO_H)
        if (url != null && logos.get(url) == null) {
            logos.fetch(url) { art -> if (art != null && shown?.logo == url) logos.show(logo, title, art, LOGO_W, LOGO_H) }
        }
        meta.text = listOf(show.year, show.genres, show.length).filter { it.isNotEmpty() }.joinToString("  ·  ")
        overview.text = show.overview
        overview.visibility = if (show.overview.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildChips() {
        genreList.removeAllViews()
        shelves.forEachIndexed { i, shelf ->
            val chip = TextView(context).apply {
                text = shelf.label
                typeface = Fonts.semibold
                includeFontPadding = false
                gravity = Gravity.CENTER
                textPx(22f)
                setShadowLayer(Ui.px(10f), 0f, 0f, SHADOW)
                setPadding(Ui.px(26), 0, Ui.px(26), 0)
            }
            genreList.addView(
                chip,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.px(52)).apply { if (i > 0) marginStart = Ui.px(6) },
            )
        }
    }

    /**
     * The chosen genre is lit cream while the strip has focus, and sits on glass while the posters
     * do, so it's always clear which genre the row is showing.
     */
    private fun paintChips() {
        val radius = Ui.px(26f)
        for (i in 0 until genreList.childCount) {
            val chip = genreList.getChildAt(i) as TextView
            val on = i == selected
            val lit = on && inGenres
            chip.background = when {
                lit -> GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Palette.INK)
                }
                on -> Glass.control(Ui.px(52f), Palette.alpha(0xFFFFFF, 0.16f))
                else -> null
            }
            chip.setTextColor(
                when {
                    lit -> dark
                    on -> Palette.INK
                    else -> QUIET
                },
            )
            val scale = if (lit) 1.06f else 1f
            chip.animate().scaleX(scale).scaleY(scale).setDuration(280).setStartDelay(0).setInterpolator(Motion.LIFT)
        }
    }

    private fun scrollToChip() {
        val chip = genreList.getChildAt(selected) ?: return
        val pad = Ui.px(120)
        val visible = genreScroll.width - genreScroll.paddingStart - genreScroll.paddingEnd
        val left = chip.left - genreScroll.scrollX
        val target = when {
            left + chip.width > visible - pad -> chip.left + chip.width - visible + pad
            left < pad -> chip.left - pad
            else -> return
        }
        genreScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
    }

    /** Nothing further that way: the chip leans and settles back. */
    private fun bumpChip(dx: Int) {
        val chip = genreList.getChildAt(selected) ?: return
        chip.animate().translationX(Ui.px(10f) * dx).translationY(if (dx == 0) Ui.px(6f) else 0f).setDuration(90).setStartDelay(0)
            .setInterpolator(Motion.EASE).withEndAction {
                chip.animate().translationX(0f).translationY(0f).setDuration(320).setInterpolator(Motion.SETTLE).withEndAction(null)
            }
    }

    /** The remote's buttons for where focus is, at the bottom right. */
    private fun setHints() {
        val pairs = if (inGenres) {
            listOf("◀ ▶" to if (lists) R.string.hint_list else R.string.hint_genre, "▼" to R.string.hint_browse, "▲" to R.string.hint_tabs)
        } else {
            listOf(
                "OK" to R.string.hint_open,
                context.getString(R.string.hint_hold_ok) to R.string.hint_options,
                "▲" to if (lists) R.string.hint_lists else R.string.hint_genres,
            )
        }
        Hints.show(hints, pairs)
    }

    companion object {
        /** Poster width (as [Cards.PAGE_POSTER]) and the space between posters, in mockup px. */
        const val POSTER_WIDTH = 204
        const val GAP = 32

        private const val SETTLE_MS = 260L
        private const val LOGO_W = 500f
        private const val LOGO_H = 150f
        private const val QUIET = 0xFFC9C5CF.toInt()
        private const val SHADOW = 0x99000000.toInt()
    }
}
