package com.shivansh.mibox.ui

import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shivansh.mibox.R
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.ui.rows.PosterAdapter

/**
 * The Search tab: what's typed and the keyboard on the left, the results as a grid of posters on
 * the right (trending titles until something is typed). Typing searches TMDB once the keys pause.
 * Right from the keyboard's last column enters the results; left from their first column, back.
 */
class SearchPage(
    root: View,
    private val grid: RecyclerView,
    private val adapter: PosterAdapter,
    /** A result came into focus: its art behind the page, its full record fetched. */
    private val onShow: (Show) -> Unit,
    /** Runs a TMDB search off the main thread; null results mean TMDB couldn't be reached. */
    private val search: (query: String, done: (List<Show>?) -> Unit) -> Unit,
    /** The mic key: the activity starts the box's speech recognizer. */
    private val listen: () -> Unit,
) {
    private val context = root.context
    private val query: TextView = root.findViewById(R.id.search_query)
    private val heading: TextView = root.findViewById(R.id.search_heading)
    private val empty: TextView = root.findViewById(R.id.search_empty)
    private val hints: LinearLayout = root.findViewById(R.id.search_hints)
    val keyboard: Keyboard = root.findViewById(R.id.search_keys)
    private val main = Handler(Looper.getMainLooper())

    private var text = ""
    private var inResults = false
    private var position = 0
    private var trending: List<Show> = emptyList()

    /** The query whose results are wanted; answers to older ones are dropped. */
    private var asked = ""
    private val run = Runnable { runSearch() }

    val keysFocused: Boolean get() = !inResults

    val focusedShow: Show? get() = if (inResults) adapter.items.getOrNull(position) else null

    init {
        keyboard.onKey = ::onKey
        showText()
    }

    /** What to show before anything is typed. */
    fun setTrending(shows: List<Show>) {
        trending = shows
        if (text.isBlank()) showResults(trending, context.getString(R.string.search_trending))
    }

    fun enter() = toKeys()

    fun refocus() {
        if (inResults && adapter.itemCount > 0) focusResult(position.coerceAtMost(adapter.itemCount - 1)) else toKeys()
    }

    /** Replaces the query, e.g. with what was said to the mic. */
    fun setText(value: String) {
        text = value.take(MAX_LENGTH)
        showText()
        main.removeCallbacks(run)
        if (text.isBlank()) {
            asked = ""
            showResults(trending, context.getString(R.string.search_trending))
        } else {
            main.postDelayed(run, PAUSE_MS)
        }
    }

    fun type(chars: String) = setText(text + chars)

    fun backspace() {
        if (text.isNotEmpty()) setText(text.dropLast(1))
    }

    /** OK on the keyboard. Held on Delete, it keeps deleting. */
    fun press(repeat: Int) {
        if (repeat == 0 || keyboard.current.action == Keyboard.Action.DELETE) keyboard.press()
    }

    /** A D-pad press while the page has focus. False means up off the page: the tab bar's turn. */
    fun move(dRow: Int, dCol: Int, first: Boolean): Boolean {
        if (!inResults) {
            when {
                keyboard.move(dRow, dCol) -> Unit
                dCol > 0 && adapter.itemCount > 0 -> focusResult(0)
                dRow < 0 -> return false
            }
            return true
        }
        val count = adapter.itemCount
        val next = when {
            dCol > 0 -> if (position % COLUMNS < COLUMNS - 1 && position + 1 < count) position + 1 else -1
            dCol < 0 -> if (position % COLUMNS == 0) {
                toKeys()
                return true
            } else {
                position - 1
            }
            // Down into a shorter last row lands on its last card.
            dRow > 0 -> if (position / COLUMNS < (count - 1) / COLUMNS) minOf(position + COLUMNS, count - 1) else -1
            else -> if (position >= COLUMNS) position - COLUMNS else return false
        }
        if (next >= 0) focusResult(next) else if (first) focusedCard()?.bump(dCol, dRow)
        return true
    }

    /** Swaps in a result's full record (service, logo) once it arrives. */
    fun update(rich: Show) {
        val at = adapter.items.indexOfFirst { it.id == rich.id }
        if (at >= 0) adapter.replace(at, rich)
    }

    private fun onKey(key: Keyboard.Key) {
        when (key.action) {
            Keyboard.Action.CHAR -> type(key.label)
            Keyboard.Action.SPACE -> if (text.isNotEmpty() && !text.endsWith(" ")) type(" ")
            Keyboard.Action.DELETE -> backspace()
            Keyboard.Action.CLEAR -> setText("")
            Keyboard.Action.VOICE -> listen()
        }
    }

    private fun runSearch() {
        val q = text.trim()
        if (q.isEmpty()) return
        asked = q
        heading.text = context.getString(R.string.search_searching, q)
        search(q) { results ->
            if (q != asked) return@search
            when {
                results == null -> showResults(emptyList(), context.getString(R.string.search_results, q), context.getString(R.string.media_offline))
                results.isEmpty() -> showResults(emptyList(), context.getString(R.string.search_results, q), context.getString(R.string.search_none, q))
                else -> showResults(results, context.getString(R.string.search_results, q))
            }
        }
    }

    private fun showResults(items: List<Show>, title: String, none: String = "") {
        val focusWasHere = inResults
        adapter.items = items
        grid.scrollToPosition(0)
        position = 0
        heading.text = title
        empty.text = none
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        // New results under the remote: start them from the top, or go back to the keys if none.
        if (focusWasHere) {
            if (items.isEmpty()) toKeys() else focusResult(0)
        }
        items.firstOrNull()?.let(onShow)
    }

    /** The query in large type with a quiet caret, or a hint of what can be searched. */
    private fun showText() {
        query.text = if (text.isEmpty()) {
            SpannableStringBuilder(context.getString(R.string.search_placeholder)).apply {
                setSpan(ForegroundColorSpan(QUIET), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            SpannableStringBuilder(text).apply {
                append("|")
                setSpan(ForegroundColorSpan(QUIET), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun toKeys() {
        inResults = false
        keyboard.setLit(true)
        keyboard.requestFocus()
        Hints.show(hints, listOf("OK" to R.string.hint_type, "▶" to R.string.hint_results, "▲" to R.string.hint_tabs))
    }

    private fun focusResult(at: Int) {
        val arriving = !inResults
        inResults = true
        position = at
        keyboard.setLit(false)
        val card = grid.findViewHolderForAdapterPosition(at)?.itemView
        if (card != null) {
            card.requestFocus()
        } else {
            grid.scrollToPosition(at)
            grid.post { if (inResults && position == at) grid.findViewHolderForAdapterPosition(at)?.itemView?.requestFocus() }
        }
        adapter.items.getOrNull(at)?.let(onShow)
        if (arriving) {
            Hints.show(
                hints,
                listOf("OK" to R.string.hint_open, context.getString(R.string.hint_hold_ok) to R.string.hint_options, "◀" to R.string.hint_keyboard),
            )
        }
    }

    private fun focusedCard(): FocusCard? = grid.findViewHolderForAdapterPosition(position)?.itemView as? FocusCard

    companion object {
        /** Results per row of the grid. */
        const val COLUMNS = 5

        /** Typing pauses this long before TMDB is asked. */
        private const val PAUSE_MS = 380L
        private const val MAX_LENGTH = 40
        private const val QUIET = 0xFF8F8B97.toInt()
    }
}
